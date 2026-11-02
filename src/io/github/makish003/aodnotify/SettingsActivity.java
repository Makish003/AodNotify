package io.github.makish003.aodnotify;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Settings in the ColorOS look. Values go to LSPosed remote preferences, which SystemUI
 * observes, so changes apply immediately. A private copy keeps the screen usable while
 * LSPosed is connecting.
 */
public final class SettingsActivity extends Activity implements SystemUi.Store {
    private static final String LOCAL = "local";

    private static SharedPreferences sRemote;
    private static boolean sBound;
    private static boolean sScopeAsked;
    private static final String AOD_PACKAGE = "com.oplus.aod";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private SharedPreferences mLocal;
    private CouiUi mUi;
    private SystemUi mSystem;
    private LinearLayout mList;
    private boolean mGaveUpWaiting;
    private CharSequence mTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mLocal = getSharedPreferences(LOCAL, MODE_PRIVATE);
        // opened from AOD's "Display settings" it is that page's "Notifications" sub-page, dark like it
        boolean fromAod = getIntent().getBooleanExtra(AodEntry.EXTRA_FROM_AOD, false);
        mTitle = getString(fromAod ? R.string.aod_entry_title : R.string.app_name);
        mUi = new CouiUi(this, fromAod);

        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setBackgroundColor(mUi.bg);
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            insets.setSystemBarsAppearance(mUi.dark ? 0 : light, light);
        }

        // The real COUI screen from the system Settings app; the hand-made one if that cannot load.
        try {
            mSystem = SystemUi.create(this, this, mTitle, mUi.dark);
            setContentView(mSystem.root());
            build();
        } catch (Throwable t) {
            Log.w("AodNotify", "system COUI unavailable, using built-in look", t);
            mSystem = null;
            buildOwnScreen();
        }

        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                requestAodScope(service);
                SharedPreferences remote = service.getRemotePreferences(Prefs.GROUP);
                mMain.post(() -> onBound(remote));
            }

            @Override
            public void onServiceDied(XposedService service) {
                mMain.post(() -> {
                    sBound = false;
                    sRemote = null;
                    build();
                });
            }
        });
        if (!sBound) {
            mMain.postDelayed(() -> {
                mGaveUpWaiting = true;
                if (!sBound) {
                    build();
                }
            }, 1500);
        }
    }

    @Override
    protected void onDestroy() {
        if (mSystem != null) {
            mSystem.release();
        }
        super.onDestroy();
    }

    /**
     * The "Notifications" row in AOD's display settings needs the AOD app in the module's scope.
     * LSPosed asks the user to approve it in its own notification; asked once per process, and
     * LSPosed itself stops asking if the user blocks the request.
     */
    private static void requestAodScope(XposedService service) {
        if (sScopeAsked) {
            return;
        }
        sScopeAsked = true;
        try {
            if (!service.getScope().contains(AOD_PACKAGE)) {
                service.requestScope(java.util.Collections.singletonList(AOD_PACKAGE), new XposedService.OnScopeEventListener() {
                    @Override
                    public void onScopeRequestFailed(String message) {
                        Log.w("AodNotify", "AOD scope request failed: " + message);
                    }
                });
            }
        } catch (Throwable t) {
            Log.w("AodNotify", "AOD scope request failed", t);
        }
    }

    private void buildOwnScreen() {
        // The toolbar stays put and the list scrolls under it, as in COUI pages.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(mUi.bg);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mList);
        root.addView(scroll);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(mUi.bg);
        bar.addView(mUi.header(mTitle.toString(), v -> finish()));
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            bar.setPadding(bars.left, bars.top, bars.right, 0);
            scroll.setPadding(bars.left, bars.top + mUi.dp(52) + mUi.dp(8), bars.right, bars.bottom + mUi.dp(24));
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        build();
    }

    private void onBound(SharedPreferences remote) {
        sRemote = remote;
        sBound = true;
        // LSPosed is the source of truth; mirror it locally, seeding defaults on first run.
        SharedPreferences.Editor local = mLocal.edit();
        SharedPreferences.Editor seed = remote.edit();
        boolean seeded = false;
        for (String key : new String[]{Prefs.MODE, Prefs.POPUP_SECONDS, Prefs.MAX_CARDS, Prefs.PLACEMENT,
                Prefs.ICON_STYLE, Prefs.ICON_COLOR}) {
            if (remote.contains(key)) {
                local.putInt(key, remote.getInt(key, 0));
            } else {
                seed.putInt(key, mLocal.getInt(key, defInt(key)));
                seeded = true;
            }
        }
        for (String key : new String[]{Prefs.SHOW_TEXT, Prefs.ONLY_ALERTING, Prefs.INCLUDE_OLD, Prefs.POCKET_CHECK,
                Prefs.PEEK_ON_TAP}) {
            if (remote.contains(key)) {
                local.putBoolean(key, remote.getBoolean(key, false));
            } else {
                seed.putBoolean(key, mLocal.getBoolean(key, defBool(key)));
                seeded = true;
            }
        }
        local.apply();
        if (seeded) {
            seed.apply();
        }
        build();
    }

    // ---- screen ----

    private void build() {
        if (mSystem != null) {
            try {
                mSystem.build(!sBound && mGaveUpWaiting);
                return;
            } catch (Throwable t) {
                Log.w("AodNotify", "system COUI failed, using built-in look", t);
                mSystem = null;
                buildOwnScreen();
                return;
            }
        }
        mList.removeAllViews();

        if (!sBound && mGaveUpWaiting) {
            LinearLayout warn = mUi.group();
            warn.addView(mUi.row(getString(R.string.not_active_title), getString(R.string.not_active_summary), null));
            addGroup(null, warn);
        }

        int mode = getInt(Prefs.MODE);
        LinearLayout style = mUi.group();
        int[] modes = {Prefs.MODE_BRIEF, Prefs.MODE_DETAILED};
        style.addView(mUi.picker(new String[]{getString(R.string.mode_brief), getString(R.string.mode_detailed)},
                new ModePreview[]{new ModePreview(this, false), new ModePreview(this, true)},
                indexOf(modes, mode, -1), picked -> {
                    putInt(Prefs.MODE, modes[picked]);
                    build();
                }));
        style.addView(choiceRow(getString(R.string.mode_off), getString(R.string.mode_off_summary), mode == Prefs.MODE_OFF, Prefs.MODE_OFF));
        addGroup(getString(R.string.category_style), style);

        LinearLayout look = mUi.group();
        String[] places = getResources().getStringArray(R.array.placements);
        look.addView(valueRow(getString(R.string.placement), null, places, getInt(Prefs.PLACEMENT), Prefs.PLACEMENT, null));
        int[] secs = Prefs.POPUP_SECONDS_CHOICES;
        String[] secLabels = new String[secs.length];
        for (int i = 0; i < secs.length; i++) {
            secLabels[i] = getResources().getQuantityString(R.plurals.seconds, secs[i], secs[i]);
        }
        look.addView(valueRow(getString(R.string.popup_duration), getString(R.string.popup_duration_summary),
                secLabels, indexOf(secs, getInt(Prefs.POPUP_SECONDS)), Prefs.POPUP_SECONDS, secs));
        int[] cards = Prefs.MAX_CARDS_CHOICES;
        String[] cardLabels = new String[cards.length];
        for (int i = 0; i < cards.length; i++) {
            cardLabels[i] = String.valueOf(cards[i]);
        }
        if (mode == Prefs.MODE_DETAILED) {
            look.addView(valueRow(getString(R.string.max_cards), getString(R.string.max_cards_summary),
                    cardLabels, indexOf(cards, getInt(Prefs.MAX_CARDS)), Prefs.MAX_CARDS, cards));
        }
        look.addView(valueRow(getString(R.string.icons), getString(R.string.icons_summary),
                getResources().getStringArray(R.array.icon_styles), getInt(Prefs.ICON_STYLE), Prefs.ICON_STYLE, null));
        if (getInt(Prefs.ICON_STYLE) == Prefs.ICONS_MONO) {
            look.addView(valueRow(getString(R.string.icon_color), getString(R.string.icon_color_summary),
                    getResources().getStringArray(R.array.icon_color_names),
                    indexOf(Prefs.ICON_COLORS, getInt(Prefs.ICON_COLOR)), Prefs.ICON_COLOR, Prefs.ICON_COLORS));
        }
        addGroup(getString(R.string.category_look), look);

        LinearLayout tap = mUi.group();
        tap.addView(switchRow(getString(R.string.peek_on_tap), getString(R.string.peek_on_tap_summary), Prefs.PEEK_ON_TAP));
        addGroup(getString(R.string.category_clock), tap);

        LinearLayout content = mUi.group();
        content.addView(switchRow(getString(R.string.show_text), getString(R.string.show_text_summary), Prefs.SHOW_TEXT));
        content.addView(switchRow(getString(R.string.only_alerting), getString(R.string.only_alerting_summary), Prefs.ONLY_ALERTING));
        content.addView(switchRow(getString(R.string.include_old), getString(R.string.include_old_summary), Prefs.INCLUDE_OLD));
        addGroup(getString(R.string.category_content), content);

        LinearLayout safety = mUi.group();
        safety.addView(switchRow(getString(R.string.pocket_check), getString(R.string.pocket_check_summary), Prefs.POCKET_CHECK));
        addGroup(getString(R.string.category_safety), safety);

        mList.addView(mUi.footer(getString(R.string.footer)));
    }

    private void addGroup(String title, LinearLayout group) {
        boolean first = mList.getChildCount() == 0;
        if (title != null) {
            mList.addView(mUi.section(title, first));
        } else if (!first) {
            View gap = new View(this);
            mList.addView(gap, new LinearLayout.LayoutParams(1, mUi.dp(16)));
        }
        mList.addView(group, mUi.groupParams());
    }

    private View choiceRow(String title, String summary, boolean selected, int mode) {
        View check = mUi.new CheckView(this, mUi.accent);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        LinearLayout row = mUi.row(title, summary, check);
        row.setOnClickListener(v -> {
            putInt(Prefs.MODE, mode);
            build();
        });
        return row;
    }

    private View valueRow(String title, String summary, String[] labels, int selected, String key, int[] values) {
        int index = Math.max(0, Math.min(labels.length - 1, selected));
        LinearLayout row = mUi.row(title, summary, mUi.valueEnd(labels[index], true));
        // The list opens at the finger, as COUIClickSelectMenu does (PreciseClickHelper).
        float[] touch = new float[2];
        row.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touch[0] = e.getRawX();
                touch[1] = e.getRawY();
            }
            return false;
        });
        row.setOnClickListener(v -> {
            if (touch[0] == 0 && touch[1] == 0) {
                int[] at = new int[2];
                row.getLocationOnScreen(at);
                touch[0] = at[0] + row.getWidth() / 2f;
                touch[1] = at[1] + row.getHeight() / 2f;
            }
            mUi.choose(row, touch[0], touch[1], labels, index, picked -> {
                putInt(key, values != null ? values[picked] : picked);
                build();
            });
            touch[0] = 0;
            touch[1] = 0;
        });
        return row;
    }

    private View switchRow(String title, String summary, String key) {
        CouiUi.CouiSwitch sw = mUi.switchView(getBool(key));
        LinearLayout row = mUi.row(title, summary, sw);
        View.OnClickListener toggle = v -> {
            boolean on = !sw.isChecked();
            sw.setChecked(on);
            putBool(key, on);
        };
        row.setOnClickListener(toggle);
        sw.setOnClickListener(toggle);
        return row;
    }

    // ---- storage ----

    @Override
    public int getInt(String key) {
        return mLocal.getInt(key, defInt(key));
    }

    @Override
    public boolean getBool(String key) {
        return mLocal.getBoolean(key, defBool(key));
    }

    @Override
    public void putInt(String key, int value) {
        mLocal.edit().putInt(key, value).apply();
        if (sRemote != null) {
            sRemote.edit().putInt(key, value).apply();
        }
    }

    @Override
    public void putBool(String key, boolean value) {
        mLocal.edit().putBoolean(key, value).apply();
        if (sRemote != null) {
            sRemote.edit().putBoolean(key, value).apply();
        }
    }

    private static int defInt(String key) {
        switch (key) {
            case Prefs.MODE:
                return Prefs.DEF_MODE;
            case Prefs.POPUP_SECONDS:
                return Prefs.DEF_POPUP_SECONDS;
            case Prefs.MAX_CARDS:
                return Prefs.DEF_MAX_CARDS;
            case Prefs.PLACEMENT:
                return Prefs.DEF_PLACEMENT;
            case Prefs.ICON_STYLE:
                return Prefs.DEF_ICON_STYLE;
            case Prefs.ICON_COLOR:
                return Prefs.DEF_ICON_COLOR;
            default:
                return 0;
        }
    }

    private static boolean defBool(String key) {
        switch (key) {
            case Prefs.SHOW_TEXT:
                return Prefs.DEF_SHOW_TEXT;
            case Prefs.ONLY_ALERTING:
                return Prefs.DEF_ONLY_ALERTING;
            case Prefs.INCLUDE_OLD:
                return Prefs.DEF_INCLUDE_OLD;
            case Prefs.POCKET_CHECK:
                return Prefs.DEF_POCKET_CHECK;
            case Prefs.PEEK_ON_TAP:
                return Prefs.DEF_PEEK_ON_TAP;
            default:
                return false;
        }
    }

    private static int indexOf(int[] values, int value) {
        return indexOf(values, value, 0);
    }

    private static int indexOf(int[] values, int value, int missing) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return missing;
    }
}
