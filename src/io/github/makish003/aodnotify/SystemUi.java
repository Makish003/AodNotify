package io.github.makish003.aodnotify;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * The settings screen built from the real COUI library of the system Settings app: its code is
 * loaded from com.android.settings at runtime (nothing of it is shipped in this APK), so rows,
 * cards, switches, the select menu with its glass and finger sliding are exactly the system ones.
 * Everything goes through reflection because those classes live in Settings' class loader.
 * Any failure throws, and SettingsActivity falls back to the hand-made CouiUi.
 */
final class SystemUi {
    private static final String SETTINGS = "com.android.settings";
    private static final int MATERIAL_FLAGS = 7;
    /** Views of darkmode_preview_preference: the two halves, pictures, labels, radio buttons. */
    private static final String[] PICKER_IDS = {"layout_light_theme", "layout_dark_theme",
            "iv_light_image", "iv_dark_image", "tv_light_text", "tv_dark_text", "rb_light_button", "rb_dark_button"};
    /** Index of the row itself in mPicker, after the views above. */
    private static final int PICKER_ROW = PICKER_IDS.length;

    /** Where the screen reads and writes values. */
    interface Store {
        int getInt(String key);

        boolean getBool(String key);

        void putInt(String key, int value);

        void putBool(String key, boolean value);
    }

    private final Activity mActivity;
    private final Store mStore;
    private final Context mCoui;
    private final ClassLoader mLoader;
    private final Object mManager;
    private final Object mScreen;
    private final ViewGroup mRoot;
    private final Object mMaterial;
    /** Rows that only make sense for one choice elsewhere on the screen. */
    private Object mCardsPref;
    private Object mIconColorPref;
    private boolean mFirstCategory;
    /** The brief/detailed picker: ids in Settings' layout, the views of the bound row, "Off" below it. */
    private final int[] mPickerIds;
    private View[] mPicker;
    private ColorStateList mPickerText;
    private final int mPickerSelectedText;
    private Object mOffPref;

    /**
     * The page is put together the way COUIPreferenceWithAppbarFragment (the base of Settings'
     * and AOD's sub-pages) does it: its own coui_preference_list_with_appbar_fragment layout
     * (CoordinatorLayout, COUIDividerAppBarLayout + COUIToolbar, AppBarBlurOverlayView), the list
     * scrolling under the bar, and the bar's gradient blur. The toolbar gives its navigation button
     * the material (glass) effect by itself on ColorOS 16+ (ToolbarMaterialEffectDelegate).
     */
    private SystemUi(Activity activity, Store store, CharSequence title, boolean dark) throws Exception {
        mActivity = activity;
        mStore = store;
        Context settings = activity.createPackageContext(SETTINGS,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        mLoader = settings.getClassLoader();
        Resources res = settings.getResources();
        // AOD's own pages are always dark: ThemeAppNoTitlePreference is Theme.COUI.Main.Dark with
        // PreferenceThemeOverlay.COUITheme.WithToolBar. The light page is Settings' AppNoTitleTheme;
        // Settings itself gets its dark look from the system's colour conversion, which does not
        // reach this app, so the dark page uses the real dark COUI theme instead.
        int theme = res.getIdentifier(dark ? "Theme.COUI.Main.Dark" : "AppNoTitleTheme", "style", SETTINGS);
        int overlay = dark ? res.getIdentifier("PreferenceThemeOverlay.COUITheme.WithToolBar", "style", SETTINGS) : 0;
        if (theme == 0 || dark && overlay == 0) {
            throw new IllegalStateException("Settings theme not found");
        }
        mCoui = new CouiContext(activity, res, mLoader, theme, overlay);

        // SettingsApplicationEx.initMaterialStateManager: blur, stroke and caustic shadow (flags 7)
        // follow the system switches only once the app registers; without it the bar has no blur
        // and the back button no glass. Unregistered in release(), as Settings does on onStop.
        mMaterial = mLoader.loadClass("com.coui.appcompat.COUIMaterialStateManager").getField("INSTANCE").get(null);
        call(mMaterial, "registerMaterialStateObserver", mCoui, MATERIAL_FLAGS);

        mManager = make("androidx.preference.PreferenceManager", mCoui);
        mScreen = call(mManager, "createPreferenceScreen", mCoui);

        LayoutInflater inflater = LayoutInflater.from(mCoui);
        ViewGroup page = (ViewGroup) inflater.inflate(id("coui_preference_list_with_appbar_fragment", "layout"), null, false);
        ViewGroup container = page.findViewById(android.R.id.list_container);
        // Settings maps @string/coui_floating_scrolling_view_behavior to its own
        // SettingsCouiFloatingScrollingViewBehavior, which AppBarBlurHelper does not recognise, so
        // the bar would stay opaque; AOD's string names COUI's own behaviour, used here as well.
        call(container.getLayoutParams(), "setBehavior",
                make("com.google.android.material.appbar.COUIFloatingScrollingViewBehavior"));

        // COUIPreferenceWithAppbarFragment.onCreateRecyclerView
        View list = inflater.inflate(id("coui_preference_percent_recyclerview", "layout"), container, false);
        call(list, "setEnablePointerDownAction", false);
        call(list, "setLayoutManager", make("androidx.recyclerview.widget.LinearLayoutManager", mCoui));
        mLoader.loadClass("com.coui.appcompat.darkmode.COUIDarkModeUtil")
                .getMethod("setForceDarkAllow", View.class, boolean.class).invoke(null, list, false);
        call(list, "setAdapter", make("androidx.preference.PreferenceGroupAdapter", mScreen));
        // COUIPreferenceFragment.setPreferenceScreen: the card dividers between rows
        call(list, "addItemDecoration", make("com.coui.appcompat.preference.COUIPreferenceItemDecoration", mCoui, mScreen));
        list.setVerticalScrollBarEnabled(false);
        container.addView(list);

        // The mode picker row is Settings' own darkmode_preview_preference; its pictures, labels
        // and radio buttons are filled in each time the list attaches that row.
        mPickerIds = new int[PICKER_IDS.length];
        for (int i = 0; i < PICKER_IDS.length; i++) {
            mPickerIds[i] = id(PICKER_IDS[i], "id");
        }
        // SettingsDarkModePreviewPreference colours the chosen label couiColorContainerTheme
        TypedArray colors = mCoui.obtainStyledAttributes(new int[]{id("couiColorContainerTheme", "attr")});
        mPickerSelectedText = colors.getColor(0, dark ? 0xFF1A8CFF : 0xFF0080FF);
        colors.recycle();
        call(list, "addOnChildAttachStateChangeListener",
                proxy("androidx.recyclerview.widget.RecyclerView$OnChildAttachStateChangeListener", (method, args) -> {
                    View child = (View) args[0];
                    if (method.equals("onChildViewAttachedToWindow")) {
                        try {
                            bindPicker(child);
                        } catch (RuntimeException e) {
                            Log.w("AodNotify", "mode picker", e);
                        }
                    } else if (mPicker != null && child == mPicker[PICKER_ROW]) {
                        mPicker = null;
                    }
                    return null;
                }));

        // COUIPreferenceWithAppbarFragment.onViewCreated
        Object toolbar = page.findViewById(id("toolbar", "id"));
        call(toolbar, "setNavigationIcon", id("coui_back_arrow", "drawable"));
        call(toolbar, "setNavigationOnClickListener", (View.OnClickListener) v -> activity.finish());
        call(toolbar, "setTitle", title);
        ViewGroup appBar = page.findViewById(id("appbar_layout", "id"));
        appBar.setTouchscreenBlocksFocus(false);
        call(appBar, "setGradientBlurEnabled", true);
        list.setNestedScrollingEnabled(true);
        call(appBar, "bindRecyclerView", list);
        activity.getWindow().setBackgroundDrawable(
                res.getDrawable(id("coui_window_background_with_card_selector", "drawable"), mCoui.getTheme()));

        // The bar sits under the status bar (needStatusBarViewHolder); the list's top padding is
        // COUIFloatingScrollingViewBehavior's, only its bottom clears the navigation bar.
        page.setOnApplyWindowInsetsListener((v, in) -> {
            Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            appBar.setPadding(bars.left, bars.top, bars.right, appBar.getPaddingBottom());
            list.setPadding(bars.left, list.getPaddingTop(), bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        mRoot = page;
    }

    static SystemUi create(Activity activity, Store store, CharSequence title, boolean dark) throws Exception {
        return new SystemUi(activity, store, title, dark);
    }

    private int id(String name, String type) {
        int id = mCoui.getResources().getIdentifier(name, type, SETTINGS);
        if (id == 0) {
            throw new IllegalStateException("Settings resource not found: " + type + "/" + name);
        }
        return id;
    }

    View root() {
        return mRoot;
    }

    void release() {
        try {
            call(mMaterial, "unregisterMaterialStateObserver", MATERIAL_FLAGS);
        } catch (Exception ignored) {
            // the observers die with the process anyway
        }
    }

    /** Fills the screen; called again whenever the activity rebuilds. */
    void build(boolean showWarning) throws Exception {
        call(mScreen, "removeAll");
        mFirstCategory = true;

        if (showWarning) {
            Object warn = category(null);
            Object row = pref("com.coui.appcompat.preference.COUIPreference", str(R.string.not_active_title),
                    str(R.string.not_active_summary));
            call(row, "setSelectable", false);
            add(warn, row);
        }

        Object style = category(str(R.string.category_style));
        modePicker(style);
        offMark(style);

        Object look = category(str(R.string.category_look));
        menu(look, str(R.string.placement), null, strs(R.array.placements), new int[]{0, 1, 2}, Prefs.PLACEMENT);
        int[] secs = Prefs.POPUP_SECONDS_CHOICES;
        String[] secLabels = new String[secs.length];
        for (int i = 0; i < secs.length; i++) {
            secLabels[i] = mActivity.getResources().getQuantityString(R.plurals.seconds, secs[i], secs[i]);
        }
        menu(look, str(R.string.popup_duration), str(R.string.popup_duration_summary), secLabels, secs, Prefs.POPUP_SECONDS);
        int[] cards = Prefs.MAX_CARDS_CHOICES;
        String[] cardLabels = new String[cards.length];
        for (int i = 0; i < cards.length; i++) {
            cardLabels[i] = String.valueOf(cards[i]);
        }
        mCardsPref = menu(look, str(R.string.max_cards), str(R.string.max_cards_summary), cardLabels, cards, Prefs.MAX_CARDS);
        menu(look, str(R.string.icons), str(R.string.icons_summary), strs(R.array.icon_styles), new int[]{Prefs.ICONS_COLOR, Prefs.ICONS_MONO}, Prefs.ICON_STYLE);
        mIconColorPref = menu(look, str(R.string.icon_color), str(R.string.icon_color_summary),
                strs(R.array.icon_color_names), Prefs.ICON_COLORS, Prefs.ICON_COLOR);
        updateDependents();

        Object tap = category(str(R.string.category_clock));
        toggle(tap, str(R.string.peek_on_tap), str(R.string.peek_on_tap_summary), Prefs.PEEK_ON_TAP);

        Object content = category(str(R.string.category_content));
        toggle(content, str(R.string.show_text), str(R.string.show_text_summary), Prefs.SHOW_TEXT);
        toggle(content, str(R.string.only_alerting), str(R.string.only_alerting_summary), Prefs.ONLY_ALERTING);
        toggle(content, str(R.string.include_old), str(R.string.include_old_summary), Prefs.INCLUDE_OLD);

        Object safety = category(str(R.string.category_safety));
        toggle(safety, str(R.string.pocket_check), str(R.string.pocket_check_summary), Prefs.POCKET_CHECK);

        // the grey tip under the last card, as AOD's key_setting_hint_tips
        Object footer = make("com.coui.appcompat.preference.COUIPagerFooterPreference", mCoui, (AttributeSet) null);
        common(footer);
        call(footer, "setSummary", (CharSequence) str(R.string.footer));
        call(footer, "setSelectable", false);
        add(mScreen, footer);

        call(mScreen, "onAttached");
        refreshPicker();
    }

    /** Our own strings: mCoui carries the Settings app's resources, the activity carries ours. */
    private String str(int id) {
        return mActivity.getString(id);
    }

    private String[] strs(int id) {
        return mActivity.getResources().getStringArray(id);
    }

    // ---- preferences ----

    private Object category(String title) throws Exception {
        Object category = make("com.coui.appcompat.preference.COUIPreferenceCategory", mCoui, (AttributeSet) null);
        common(category);
        // as AOD's preference_settings.xml: title_margin_start_type="large" (the default),
        // and top_margin_type="small" on the first category only
        if (mFirstCategory) {
            call(category, "setMarginTopType", 1);
            mFirstCategory = false;
        }
        if (title != null) {
            call(category, "setTitle", (CharSequence) title);
        }
        add(mScreen, category);
        return category;
    }

    private Object pref(String cls, String title, String summary) throws Exception {
        Object pref = make(cls, mCoui);
        common(pref);
        call(pref, "setTitle", (CharSequence) title);
        if (summary != null) {
            call(pref, "setSummary", (CharSequence) summary);
        }
        return pref;
    }

    private void common(Object pref) throws Exception {
        call(pref, "setPersistent", false);
        call(pref, "setIconSpaceReserved", false);
    }

    private void add(Object group, Object pref) throws Exception {
        call(group, "addPreference", pref);
    }

    /**
     * Brief or detailed, picked the way Settings' "Display & brightness" picks light or dark
     * (SettingsDarkModePreviewPreference): its darkmode_preview_preference layout, here on a
     * COUIPreference, which gives the row its card background and the divider below it.
     */
    private void modePicker(Object group) throws Exception {
        Object pref = make("com.coui.appcompat.preference.COUIPreference", mCoui);
        common(pref);
        call(pref, "setLayoutResource", id("darkmode_preview_preference", "layout"));
        // the two halves take the taps, the row itself has no press state
        call(pref, "setSelectable", false);
        add(group, pref);
    }

    /** Fills the picker row when the list attaches it; other rows are left alone. */
    private void bindPicker(View row) {
        View[] views = new View[PICKER_IDS.length + 1];
        for (int i = 0; i < PICKER_IDS.length; i++) {
            views[i] = row.findViewById(mPickerIds[i]);
            if (views[i] == null) {
                return;
            }
        }
        views[PICKER_ROW] = row;
        mPicker = views;
        ((ImageView) views[2]).setImageDrawable(new ModePreview(mActivity, false));
        ((ImageView) views[3]).setImageDrawable(new ModePreview(mActivity, true));
        ((TextView) views[4]).setText(R.string.mode_brief);
        ((TextView) views[5]).setText(R.string.mode_detailed);
        views[0].setOnClickListener(v -> pickMode(Prefs.MODE_BRIEF));
        views[1].setOnClickListener(v -> pickMode(Prefs.MODE_DETAILED));
        // One UI's proportions: the pictures are a short screen excerpt, not a whole phone, so
        // the row keeps less air around them than ColorOS's 34dp/24dp/10dp/25dp
        setCardPadding(row, dp(24), dp(20));
        for (int i = 4; i <= 5; i++) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) views[i].getLayoutParams();
            lp.topMargin = dp(14);
            lp.bottomMargin = dp(6);
            views[i].setLayoutParams(lp);
        }
        if (mPickerText == null) {
            mPickerText = ((TextView) views[4]).getTextColors();
        }
        refreshPicker();
    }

    /**
     * COUICardListSelectedItemLayout re-applies the padding it was inflated with (plus the
     * first/last row extra) on every bind in setPositionInGroup, so that remembered value is
     * what changes; the extra already applied is kept.
     */
    private static void setCardPadding(View row, int top, int bottom) {
        for (Class<?> c = row.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field initTop = c.getDeclaredField("mInitPaddingTop");
                Field initBottom = c.getDeclaredField("mInitPaddingBottom");
                initTop.setAccessible(true);
                initBottom.setAccessible(true);
                int extraTop = row.getPaddingTop() - initTop.getInt(row);
                int extraBottom = row.getPaddingBottom() - initBottom.getInt(row);
                initTop.setInt(row, top);
                initBottom.setInt(row, bottom);
                top += extraTop;
                bottom += extraBottom;
                break;
            } catch (NoSuchFieldException e) {
                // look in the superclass
            } catch (IllegalAccessException e) {
                break;
            }
        }
        // without the fields (renamed in another COUI) this holds until the row is bound again
        row.setPaddingRelative(row.getPaddingStart(), top, row.getPaddingEnd(), bottom);
    }

    /** Radio buttons and label colours follow the stored mode; "Off" checks neither. */
    private void refreshPicker() {
        if (mPicker == null) {
            return;
        }
        int mode = mStore.getInt(Prefs.MODE);
        for (int i = 0; i < 2; i++) {
            boolean on = mode == (i == 0 ? Prefs.MODE_BRIEF : Prefs.MODE_DETAILED);
            ((RadioButton) mPicker[6 + i]).setChecked(on);
            TextView label = (TextView) mPicker[4 + i];
            if (on) {
                label.setTextColor(mPickerSelectedText);
            } else {
                label.setTextColor(mPickerText);
            }
        }
    }

    private void pickMode(int mode) {
        mStore.putInt(Prefs.MODE, mode);
        refreshPicker();
        try {
            call(mOffPref, "setChecked", false);
            updateDependents();
        } catch (Exception e) {
            Log.w("AodNotify", "mode picker", e);
        }
    }

    /** COUIMarkPreference "Off" under the picker; checking it clears both radio buttons. */
    private void offMark(Object group) throws Exception {
        Object pref = pref("com.coui.appcompat.preference.COUIMarkPreference", str(R.string.mode_off), str(R.string.mode_off_summary));
        call(pref, "setChecked", mStore.getInt(Prefs.MODE) == Prefs.MODE_OFF);
        call(pref, "setOnPreferenceChangeListener", changeListener(value -> {
            if (!(Boolean) value) {
                return false; // it stays checked until a picture is picked
            }
            mStore.putInt(Prefs.MODE, Prefs.MODE_OFF);
            refreshPicker();
            updateDependents();
            return true;
        }));
        mOffPref = pref;
        add(group, pref);
    }

    private int dp(int v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }

    /** COUIMenuPreference: the system select menu that opens at the finger. */
    /** "Cards at once" only for the detailed mode, "icon colour" only for monochrome icons. */
    private void updateDependents() throws Exception {
        if (mCardsPref != null) {
            call(mCardsPref, "setVisible", mStore.getInt(Prefs.MODE) == Prefs.MODE_DETAILED);
        }
        if (mIconColorPref != null) {
            call(mIconColorPref, "setVisible", mStore.getInt(Prefs.ICON_STYLE) == Prefs.ICONS_MONO);
        }
    }

    private Object menu(Object group, String title, String summary, String[] labels, int[] values, String key) throws Exception {
        Object pref = pref("com.coui.appcompat.preference.COUIMenuPreference", title, summary);
        String[] ids = new String[values.length];
        int current = mStore.getInt(key);
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            ids[i] = String.valueOf(values[i]);
            if (values[i] == current) {
                selected = i;
            }
        }
        call(pref, "setEntries", (Object) labels);
        call(pref, "setEntryValues", (Object) ids);
        call(pref, "setValue", ids[selected]);
        // the grey value next to the arrows, as Settings' own menu rows set it
        call(pref, "setAssignment", (CharSequence) labels[selected]);
        try {
            call(pref, "setUseBackgroundBlur", true);
        } catch (NoSuchMethodException ignored) {
            // older COUI: the menu decides itself
        }
        call(pref, "setOnPreferenceChangeListener", changeListener(value -> {
            int picked = Integer.parseInt(value.toString());
            mStore.putInt(key, picked);
            for (int i = 0; i < values.length; i++) {
                if (values[i] == picked) {
                    call(pref, "setAssignment", (CharSequence) labels[i]);
                }
            }
            updateDependents();
            return true;
        }));
        add(group, pref);
        return pref;
    }

    /** COUISwitchPreferenceCompat. */
    private void toggle(Object group, String title, String summary, String key) throws Exception {
        Object pref = pref("com.coui.appcompat.preference.COUISwitchPreferenceCompat", title, summary);
        call(pref, "setChecked", mStore.getBool(key));
        call(pref, "setOnPreferenceChangeListener", changeListener(value -> {
            mStore.putBool(key, (Boolean) value);
            return true;
        }));
        add(group, pref);
    }

    private interface Change {
        boolean apply(Object value) throws Exception;
    }

    /** androidx.preference.Preference.OnPreferenceChangeListener from Settings' class loader. */
    private Object changeListener(Change change) throws Exception {
        return proxy("androidx.preference.Preference$OnPreferenceChangeListener",
                (method, args) -> method.equals("onPreferenceChange") ? change.apply(args[1]) : null);
    }

    private interface Callback {
        Object on(String method, Object[] args) throws Exception;
    }

    /** An implementation of a listener interface from Settings' class loader. */
    private Object proxy(String iface, Callback callback) throws Exception {
        Class<?> type = mLoader.loadClass(iface);
        return Proxy.newProxyInstance(mLoader, new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "AodNotify" + type.getSimpleName();
                default:
                    return callback.on(method.getName(), args);
            }
        });
    }

    // ---- reflection ----

    private Object make(String cls, Object... args) throws Exception {
        for (Constructor<?> c : mLoader.loadClass(cls).getConstructors()) {
            if (fits(c.getParameterTypes(), args)) {
                return unwrap(() -> c.newInstance(args));
            }
        }
        throw new NoSuchMethodException(cls + " constructor with " + args.length + " args");
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && fits(m.getParameterTypes(), args)) {
                return unwrap(() -> m.invoke(target, args));
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private interface Invocation {
        Object run() throws Exception;
    }

    private static Object unwrap(Invocation invocation) throws Exception {
        try {
            return invocation.run();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
        }
    }

    private static boolean fits(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            Object a = args[i];
            if (a == null) {
                if (t.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (t.isPrimitive()) {
                if (!(t == boolean.class && a instanceof Boolean || t == int.class && a instanceof Integer
                        || t == float.class && a instanceof Float || t == long.class && a instanceof Long)) {
                    return false;
                }
            } else if (!t.isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Our activity (window, system services) with Settings' resources, theme and class loader, the
     * way a COUI view expects to see its host. The theme plus its preference theme overlay (the
     * given one, or the theme's own preferenceTheme), as PreferenceFragmentCompat would apply it.
     */
    private static final class CouiContext extends ContextThemeWrapper {
        private final Resources mRes;
        private final ClassLoader mClassLoader;
        private final Resources.Theme mTheme;

        CouiContext(Context base, Resources res, ClassLoader loader, int theme, int overlay) {
            super(base, 0);
            mRes = res;
            mClassLoader = loader;
            mTheme = res.newTheme();
            mTheme.applyStyle(theme, true);
            if (overlay == 0) {
                TypedValue value = new TypedValue();
                int attr = res.getIdentifier("preferenceTheme", "attr", SETTINGS);
                if (attr != 0 && mTheme.resolveAttribute(attr, value, true)) {
                    overlay = value.resourceId;
                }
            }
            if (overlay != 0) {
                mTheme.applyStyle(overlay, true);
            }
        }

        @Override
        public Resources getResources() {
            return mRes;
        }

        @Override
        public AssetManager getAssets() {
            return mRes.getAssets();
        }

        @Override
        public Resources.Theme getTheme() {
            return mTheme;
        }

        @Override
        public void setTheme(int resid) {
            mTheme.applyStyle(resid, true);
        }

        @Override
        public ClassLoader getClassLoader() {
            return mClassLoader;
        }
    }
}
