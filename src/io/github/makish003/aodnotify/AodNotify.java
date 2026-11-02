package io.github.makish003.aodnotify;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Shows notification content on the ColorOS AOD screen.
 *
 * Power model: nothing runs on a timer. Work happens only when the stock AOD
 * notification pipeline delivers an update. The overlay lives inside the stock AOD
 * window, whose invalidation hooks already ask the display for DOZE frames, so the panel
 * goes back to DOZE_SUSPEND / OFF right after the pop-up. The only wakelock is a short
 * one that lets a pop-up finish its animation and disappear on time.
 */
final class AodNotify {
    private static final String TAG = "AodNotify";

    private static final String C_BASE_CONTROLLER = "com.oplus.systemui.aod.controller.BaseAodController";
    private static final String C_CLOCK_CONTROLLER = "com.oplus.systemui.aod.controller.BaseAodClockLayoutController";
    private static final String C_BASE_ROOT = "com.oplus.systemui.aod.aodclock.off.BaseRootLayout";
    private static final String C_ROOT = "com.oplus.systemui.aod.aodclock.off.AodRootLayout";
    private static final String C_CLOCK = "com.oplus.systemui.aod.aodclock.off.AodClockLayout";
    private static final String C_NOTIF_MGR = "com.oplus.systemui.aod.common.NotificationManager";
    private static final String C_LISTENER = C_NOTIF_MGR + "$NotificationListener";
    private static final String C_LISTENER_SERVICE = "com.oplus.systemui.aod.common.AodNotificationListenerService";
    private static final String C_AOD_DATA = "com.oplus.systemui.aod.aodclock.constant.AodData";
    private static final String C_DISPLAY_UTIL = "com.oplus.systemui.aod.display.AODDisplayUtil";
    private static final String C_DEPENDENCY = "com.android.systemui.DependencyEx";
    private static final String C_PRIVACY = "com.android.systemui.statusbar.notification.helper.PrivacyHelper";
    private static final String C_KEYGUARD_HELPER = "com.oplus.systemui.statusbar.notification.helper.NotificationKeyguardHelper";

    private static final String HOST_TAG = "aodnotify_host";
    private static final int REDRAW_MS = 1200;
    private static final int KEEP_CHUNK_MS = 5000;
    private static final int KEEP_RENEW_MS = 4500;
    private static final int MAX_ITEMS = 20;
    private static final int PRUNE_DELAY_MS = 700;
    private static final int MAX_ERROR_LOGS = 20;

    private static AodNotify sInstance;

    private final XposedModule mModule;
    private final ClassLoader mCl;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Random mRandom = new Random();

    private Class<?> mRootClass;
    private Class<?> mAodDataClass;
    private Method mNmGetInstance;
    private Method mNmInit;
    private Method mNmAdd;
    private Method mNmRemove;
    private Method mNmRegister;
    private Method mNmGetActive;
    private Method mDuGetInstance;
    private Method mDuRequest;
    private Object mListenerProxy;

    private SharedPreferences mPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener;
    private int mMode = Prefs.DEF_MODE;
    private int mPopupSeconds = Prefs.DEF_POPUP_SECONDS;
    private int mMaxCards = Prefs.DEF_MAX_CARDS;
    private int mPlacement = Prefs.DEF_PLACEMENT;
    private boolean mShowText = Prefs.DEF_SHOW_TEXT;
    private boolean mOnlyAlerting = Prefs.DEF_ONLY_ALERTING;
    private boolean mIncludeOld = Prefs.DEF_INCLUDE_OLD;
    private boolean mPocketCheck = Prefs.DEF_POCKET_CHECK;
    private boolean mPeekOnTap = Prefs.DEF_PEEK_ON_TAP;

    private Context mContext;
    private ViewGroup mRoot;
    private int mBlackLayerId;
    private FrameLayout mHost;
    private LinearLayout mColumn;
    private PowerManager.WakeLock mWakeLock;
    private PocketCheck mPocket;
    private ViewGroup mPendingRoot;
    private boolean mScreenOnRegistered;

    /** Session = one screen-off period. Cleared when the screen turns on. */
    private long mSessionStart;
    private boolean mSeeded;
    private final LinkedHashMap<String, AodItem> mItems = new LinkedHashMap<>();
    private final Set<String> mAlerted = new HashSet<>();
    private AodItem mPopup;
    private AodViews.Capsule mPopupView;
    private String mAnimateKey;
    private long mKeepUntil;
    /** Set once energy saving hid the clock; the next showClock() is then a user tap. */
    private boolean mClockWasHidden;
    private AodItem mPeek;
    private AodViews.Capsule mPeekView;
    private final Map<String, String> mLabels = new HashMap<>();
    private int mErrorLogs;

    private final Runnable mHidePopup = () -> guard("hidePopup", () -> hidePopup(true));
    private final Runnable mKeepScreen = () -> guard("keepScreen", this::keepScreenTick);
    private final Runnable mPrune = () -> guard("prune", this::pruneNow);
    private final Runnable mShowRunnable = () -> {
        ViewGroup root = mPendingRoot;
        mPendingRoot = null;
        if (root != null) {
            guard("aodShow", () -> onAodShow(root));
        }
    };

    private interface Action {
        void run() throws Throwable;
    }

    private interface ChainAction {
        void run(XposedInterface.Chain chain) throws Throwable;
    }

    private AodNotify(XposedModule module, ClassLoader cl) {
        mModule = module;
        mCl = cl;
    }

    static void install(XposedModule module, ClassLoader cl) {
        if (sInstance != null) {
            return;
        }
        try {
            AodNotify instance = new AodNotify(module, cl);
            instance.resolve();
            instance.hook();
            instance.watchPrefs();
            sInstance = instance;
            module.log(Log.INFO, TAG, "hooks installed");
        } catch (Throwable t) {
            // Missing classes mean an unsupported SystemUI build: stay out of the way.
            module.log(Log.WARN, TAG, "not installed", t);
        }
    }

    private void resolve() throws Throwable {
        mRootClass = Reflect.find(C_ROOT, mCl);
        mAodDataClass = Reflect.find(C_AOD_DATA, mCl);
        Class<?> nm = Reflect.find(C_NOTIF_MGR, mCl);
        Class<?> listener = Reflect.find(C_LISTENER, mCl);
        mNmGetInstance = nm.getMethod("getInstance", Context.class);
        mNmInit = nm.getMethod("init");
        mNmAdd = nm.getMethod("addNotificationListener", listener);
        mNmRemove = nm.getMethod("removeNotificationListener", listener);
        mNmRegister = nm.getMethod("registerNotification");
        mNmGetActive = nm.getMethod("getActiveNotifications");
        Class<?> du = Reflect.find(C_DISPLAY_UTIL, mCl);
        mDuGetInstance = du.getMethod("getInstance", Context.class);
        mDuRequest = du.getMethod("requestScreenState", int.class, int.class, String.class);
        mListenerProxy = Proxy.newProxyInstance(mCl, new Class<?>[]{listener}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "onReceiveNotification":
                    guard("onReceive", () -> onReceive((StatusBarNotification[]) args[0],
                            (StatusBarNotification) args[1], (Boolean) args[2]));
                    return null;
                case "onRemoveNotification":
                    guard("onRemove", () -> onReceive((StatusBarNotification[]) args[0], null, false));
                    return null;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "AodNotifyListener";
                default:
                    return null;
            }
        });
    }

    private void hook() throws Throwable {
        // Primary trigger: the stock code always makes the AOD root visible when AOD starts.
        // Hooking the View itself survives ART inlining of controller super-calls.
        hookAfter(Reflect.find(C_BASE_ROOT, mCl).getDeclaredMethod("setVisibility", int.class), "rootVisibility",
                chain -> {
                    Object root = chain.getThisObject();
                    if (mRootClass.isInstance(root) && (Integer) chain.getArg(0) == View.VISIBLE) {
                        scheduleShow((ViewGroup) root);
                    }
                });
        // Secondary trigger: controllers re-show AOD (energy saving, re-init) without toggling the root.
        for (String name : new String[]{C_BASE_CONTROLLER, C_CLOCK_CONTROLLER}) {
            Class<?> c = Reflect.findOrNull(name, mCl);
            if (c == null) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("startShow")) {
                    hookAfter(m, "startShow", chain -> {
                        Object root = Reflect.call(chain.getThisObject(), "getRootLayout");
                        if (mRootClass.isInstance(root)) {
                            scheduleShow((ViewGroup) root);
                        }
                    });
                } else if (m.getName().equals("onDreamingStopped")) {
                    hookBefore(m, "onDreamingStopped", chain -> onMain(() -> guard("endSession", this::endSession)));
                }
            }
        }
        Class<?> clock = Reflect.findOrNull(C_CLOCK, mCl);
        if (clock != null) {
            for (Method m : clock.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) {
                    continue;
                }
                switch (m.getName()) {
                    case "updateNextPosition":
                        // Ride on the stock burn-in shift instead of running our own timer.
                        hookAfter(m, "burnIn", chain -> onMain(() -> guard("burnIn", this::shiftForBurnIn)));
                        break;
                    case "hideClock":
                        hookAfter(m, "hideClock", chain -> onMain(() -> guard("clockHidden", this::onClockHidden)));
                        break;
                    case "showClock":
                        hookAfter(m, "showClock", chain -> onMain(() -> guard("clockShown", this::onClockShown)));
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private void hookAfter(Method m, String where, ChainAction action) {
        mModule.hook(m).intercept(chain -> {
            Object result = chain.proceed();
            guard(where, () -> action.run(chain));
            return result;
        });
    }

    private void hookBefore(Method m, String where, ChainAction action) {
        mModule.hook(m).intercept(chain -> {
            guard(where, () -> action.run(chain));
            return chain.proceed();
        });
    }

    // ---- config (LSPosed remote preferences, pushed live from the settings app) ----

    private void watchPrefs() {
        mPrefs = mModule.getRemotePreferences(Prefs.GROUP);
        readConfig();
        mPrefsListener = (p, key) -> onMain(() -> guard("prefs", this::readConfig));
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefsListener);
    }

    private void readConfig() {
        int oldMode = mMode;
        int oldPlacement = mPlacement;
        mMode = mPrefs.getInt(Prefs.MODE, Prefs.DEF_MODE);
        mPopupSeconds = mPrefs.getInt(Prefs.POPUP_SECONDS, Prefs.DEF_POPUP_SECONDS);
        mMaxCards = mPrefs.getInt(Prefs.MAX_CARDS, Prefs.DEF_MAX_CARDS);
        mPlacement = mPrefs.getInt(Prefs.PLACEMENT, Prefs.DEF_PLACEMENT);
        mShowText = mPrefs.getBoolean(Prefs.SHOW_TEXT, Prefs.DEF_SHOW_TEXT);
        mOnlyAlerting = mPrefs.getBoolean(Prefs.ONLY_ALERTING, Prefs.DEF_ONLY_ALERTING);
        mIncludeOld = mPrefs.getBoolean(Prefs.INCLUDE_OLD, Prefs.DEF_INCLUDE_OLD);
        mPocketCheck = mPrefs.getBoolean(Prefs.POCKET_CHECK, Prefs.DEF_POCKET_CHECK);
        mPeekOnTap = mPrefs.getBoolean(Prefs.PEEK_ON_TAP, Prefs.DEF_PEEK_ON_TAP);
        if (!mPeekOnTap) {
            removePeek(false);
        }
        AodViews.setIconStyle(mPrefs.getInt(Prefs.ICON_STYLE, Prefs.DEF_ICON_STYLE) == Prefs.ICONS_MONO,
                mPrefs.getInt(Prefs.ICON_COLOR, Prefs.DEF_ICON_COLOR));
        mPopupSeconds = Math.max(2, Math.min(30, mPopupSeconds));
        mMaxCards = Math.max(1, Math.min(4, mMaxCards));
        if (mPlacement < 0 || mPlacement >= Prefs.PLACEMENT_PERCENT.length) {
            mPlacement = Prefs.DEF_PLACEMENT;
        }
        if (oldMode != mMode || oldPlacement != mPlacement) {
            hidePopup(false);
            detachHost(); // rebuilt with the new layout on the next AOD start
        }
        Log.i(TAG, "config mode=" + mMode + " popup=" + mPopupSeconds + "s cards=" + mMaxCards
                + " placement=" + mPlacement + " text=" + mShowText + " alerting=" + mOnlyAlerting
                + " old=" + mIncludeOld + " pocket=" + mPocketCheck + " peek=" + mPeekOnTap);
    }

    // ---- lifecycle ----

    /** Several hooks can fire for one AOD start; coalesce them into one pass on the main thread. */
    private void scheduleShow(ViewGroup root) {
        mPendingRoot = root;
        mMain.removeCallbacks(mShowRunnable);
        mMain.post(mShowRunnable);
    }

    private void onAodShow(ViewGroup rootView) throws Throwable {
        mContext = rootView.getContext();
        registerScreenOnReceiver();
        Object nm = mNmGetInstance.invoke(null, mContext);
        if (mMode == Prefs.MODE_OFF) {
            mNmRemove.invoke(nm, mListenerProxy);
            if (!mPeekOnTap) {
                detachHost();
                return;
            }
        }
        if (mSessionStart == 0) {
            mSessionStart = System.currentTimeMillis();
        }
        attachHost(rootView);
        if (mMode != Prefs.MODE_OFF) {
            mNmInit.invoke(nm);
            mNmAdd.invoke(nm, mListenerProxy);
            mNmRegister.invoke(nm);
        }
        if (mMode == Prefs.MODE_DETAILED) {
            if (mIncludeOld && !mSeeded) {
                seedFromActive(nm);
            }
            mSeeded = true;
            renderDetailed(true);
        }
    }

    private void endSession() {
        mMain.removeCallbacks(mPrune);
        hidePopup(false);
        removePeek(false);
        mClockWasHidden = false;
        stopKeepingScreen();
        releaseWakeLock();
        if (mPocket != null) {
            mPocket.cancel();
        }
        mItems.clear();
        mAlerted.clear();
        mSessionStart = 0;
        mSeeded = false;
        detachHost();
    }

    /** The stock code clears AOD state on screen-on; mirror that without a hook. */
    private void registerScreenOnReceiver() {
        if (mScreenOnRegistered) {
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                guard("screenOn", AodNotify.this::endSession);
            }
        };
        mContext.getApplicationContext().registerReceiver(receiver,
                new IntentFilter(Intent.ACTION_SCREEN_ON), null, mMain, Context.RECEIVER_NOT_EXPORTED);
        mScreenOnRegistered = true;
    }

    private void attachHost(ViewGroup root) {
        if (mRoot != root) {
            detachHost();
            mRoot = root;
        }
        ensureHost();
    }

    /**
     * Workshop AOD hides its clock by fading a black mask in over the AOD root, in the
     * black layer above it. While that layer is up our overlay must sit in it, on top of
     * the mask; otherwise it lives in the AOD root next to the clock.
     */
    private ViewGroup pickContainer() {
        if (mRoot == null) {
            return null;
        }
        if (mBlackLayerId == 0) {
            mBlackLayerId = mRoot.getResources().getIdentifier(
                    "workshop_aod_black_container", "id", "com.android.systemui");
        }
        if (mBlackLayerId != 0) {
            View black = mRoot.getRootView().findViewById(mBlackLayerId);
            if (black instanceof ViewGroup && black.getVisibility() == View.VISIBLE && black.isAttachedToWindow()) {
                return (ViewGroup) black;
            }
        }
        return mRoot;
    }

    /** Puts the overlay in the right layer and on top of its siblings (the mask may be re-added). */
    private void ensureHost() {
        ViewGroup container = pickContainer();
        if (container == null) {
            return;
        }
        if (mHost != null && mHost.getParent() == container) {
            if (container.getChildAt(container.getChildCount() - 1) != mHost) {
                mHost.bringToFront();
            }
            return;
        }
        if (mHost == null) {
            Context ctx = container.getContext();
            mHost = new FrameLayout(ctx);
            mHost.setTag(HOST_TAG);
            mHost.setClipChildren(false);
            mColumn = new LinearLayout(ctx);
            mColumn.setOrientation(LinearLayout.VERTICAL);
            mColumn.setGravity(Gravity.CENTER_HORIZONTAL);
            mColumn.setClipChildren(false);
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(columnWidth(ctx),
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dm.heightPixels * Prefs.PLACEMENT_PERCENT[mPlacement] / 100;
            mHost.addView(mColumn, lp);
        } else if (mHost.getParent() instanceof ViewGroup) {
            ((ViewGroup) mHost.getParent()).removeView(mHost);
        }
        container.addView(mHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Views are cheap to rebuild; state (cards, pop-up) survives in mItems / mPopup. */
    private void detachHost() {
        if (mPopupView != null) {
            AodViews.cancel(mPopupView);
            mPopupView = null;
        }
        if (mPeekView != null) {
            AodViews.cancel(mPeekView);
            mPeekView = null;
            mPeek = null;
        }
        if (mHost != null) {
            ViewParent parent = mHost.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(mHost);
            }
        }
        mHost = null;
        mColumn = null;
        mRoot = null;
    }

    private static int columnWidth(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return Math.min((int) (dm.widthPixels * 0.9f), AodViews.dp(ctx, 420));
    }

    // ---- notifications ----

    private void onReceive(StatusBarNotification[] active, StatusBarNotification sbn, boolean isNewest) {
        if (mColumn == null || mMode == Prefs.MODE_OFF) {
            return;
        }
        schedulePrune();
        boolean changed = false;
        boolean candidate = sbn != null && isNewest && isCandidate(sbn, true);
        if (sbn != null && isNewest) {
            Log.i(TAG, "new notification " + sbn.getPackageName() + " candidate=" + candidate);
        }
        AodItem item = candidate ? buildItem(sbn) : null;
        if (item != null && !isDuplicate(item)) {
            mAlerted.add(item.key);
            if (mMode == Prefs.MODE_DETAILED) {
                mItems.remove(item.key);
                mItems.put(item.key, item);
                trimItems();
                changed = false; // rendered below once we know whether to wake the screen
            }
            final AodItem shown = item;
            whenOutOfPocket(inPocket -> {
                if (inPocket) {
                    Log.i(TAG, "in pocket, not showing " + shown.pkg);
                    if (mMode == Prefs.MODE_DETAILED) {
                        renderDetailed(false);
                    }
                } else if (mMode == Prefs.MODE_BRIEF) {
                    showPopup(shown);
                } else {
                    mAnimateKey = shown.key;
                    renderDetailed(true);
                    if (mHost != null && mHost.isShown()) {
                        announce(mPopupSeconds * 1000L);
                    }
                }
            });
        }
        if (changed && mMode == Prefs.MODE_DETAILED) {
            renderDetailed(true);
        }
    }

    private void whenOutOfPocket(PocketCheck.Callback callback) {
        if (!mPocketCheck) {
            callback.onResult(false);
            return;
        }
        if (mPocket == null) {
            mPocket = new PocketCheck(mContext, mMain);
        }
        acquireWakeLock(1500); // keep the CPU up for the sensor round trip
        mPocket.check(inPocket -> guard("pocket", () -> callback.onResult(inPocket)));
    }

    /**
     * ColorOS clears and rebuilds its AOD list on every update, so a single dispatch can
     * briefly miss notifications that still exist (Telegram's grouped updates hit this).
     * Removals are therefore confirmed against a fresh list once the burst has settled.
     */
    private void schedulePrune() {
        mMain.removeCallbacks(mPrune);
        mMain.postDelayed(mPrune, PRUNE_DELAY_MS);
    }

    private void pruneNow() throws Throwable {
        if (mContext == null) {
            return;
        }
        Object nm = mNmGetInstance.invoke(null, mContext);
        if (prune((StatusBarNotification[]) mNmGetActive.invoke(nm)) && mMode == Prefs.MODE_DETAILED) {
            renderDetailed(true);
        }
    }

    /** Drops cards whose notification is gone from the lock screen list. */
    private boolean prune(StatusBarNotification[] active) {
        Set<String> keys = new HashSet<>();
        Set<String> groups = new HashSet<>();
        if (active != null) {
            for (StatusBarNotification s : active) {
                if (s != null) {
                    keys.add(s.getKey());
                    groups.add(s.getGroupKey());
                }
            }
        }
        boolean changed = false;
        Iterator<AodItem> it = mItems.values().iterator();
        while (it.hasNext()) {
            AodItem i = it.next();
            if (!keys.contains(i.key) && !groups.contains(i.groupKey)) {
                it.remove();
                changed = true;
            }
        }
        if (mPopup != null && !keys.contains(mPopup.key) && !groups.contains(mPopup.groupKey)) {
            hidePopup(true);
        }
        if (mPeek != null && !keys.contains(mPeek.key) && !groups.contains(mPeek.groupKey)) {
            removePeek(true);
        }
        return changed;
    }

    private boolean isCandidate(StatusBarNotification sbn, boolean newOnly) {
        if (newOnly && sbn.getPostTime() < mSessionStart - 2000) {
            return false;
        }
        Notification n = sbn.getNotification();
        if (n == null || "com.oplus.olc".equals(sbn.getPackageName())) {
            return false;
        }
        int flags = n.flags;
        if ((flags & (Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_ONGOING_EVENT)) != 0) {
            return false;
        }
        // Group summaries ("3 new messages") would replace the real message in the pop-up.
        if ((flags & Notification.FLAG_GROUP_SUMMARY) != 0 && newOnly) {
            return false;
        }
        if (n.visibility == Notification.VISIBILITY_SECRET || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            return false;
        }
        if ((flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0 && mAlerted.contains(sbn.getKey())) {
            return false;
        }
        if (mOnlyAlerting) {
            int importance = importanceOf(sbn.getKey());
            if (importance >= 0 && importance < android.app.NotificationManager.IMPORTANCE_DEFAULT) {
                return false;
            }
        }
        return true;
    }

    /** Returns the ranking importance from the stock AOD entry list, or -1 if unknown. */
    private int importanceOf(String key) {
        try {
            Object list = Reflect.getStatic(Reflect.find(C_LISTENER_SERVICE, mCl), "sAodNotificationEntries");
            if (!(list instanceof Iterable)) {
                return -1;
            }
            for (Object entry : (Iterable<?>) list) {
                if (entry != null && key.equals(Reflect.call(entry, "getKey"))) {
                    return (Integer) Reflect.call(entry, "getImportance");
                }
            }
        } catch (Throwable ignored) {
            // unknown importance: let the notification through
        }
        return -1;
    }

    private boolean isDuplicate(AodItem item) {
        AodItem last = mMode == Prefs.MODE_BRIEF ? mPopup : mItems.get(item.key);
        return last != null && last.key.equals(item.key)
                && TextUtils.equals(last.title, item.title) && TextUtils.equals(last.text, item.text);
    }

    private void seedFromActive(Object nm) throws Throwable {
        StatusBarNotification[] active = (StatusBarNotification[]) mNmGetActive.invoke(nm);
        if (active == null) {
            return;
        }
        List<StatusBarNotification> list = new ArrayList<>();
        for (StatusBarNotification s : active) {
            if (s != null && isCandidate(s, false)) {
                list.add(s);
            }
        }
        list.sort((a, b) -> Long.compare(a.getPostTime(), b.getPostTime()));
        for (StatusBarNotification s : list) {
            AodItem item = buildItem(s);
            if (item != null) {
                mItems.put(item.key, item);
            }
        }
        trimItems();
    }

    private void trimItems() {
        Iterator<String> it = mItems.keySet().iterator();
        while (mItems.size() > MAX_ITEMS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private AodItem buildItem(StatusBarNotification sbn) {
        int lockState = lockscreenState();
        if (lockState == 0) {
            return null; // notifications are hidden on the lock screen entirely
        }
        Notification n = sbn.getNotification();
        AodItem item = new AodItem();
        item.key = sbn.getKey();
        item.groupKey = sbn.getGroupKey();
        item.pkg = sbn.getPackageName();
        item.when = n.when != 0 ? n.when : sbn.getPostTime();
        item.appName = appLabel(item.pkg);
        item.smallIcon = n.getSmallIcon();
        Object oplusIcon = n.extras.get("oplus_small_icon");
        if (oplusIcon instanceof android.graphics.drawable.Icon) {
            item.oplusIcon = (android.graphics.drawable.Icon) oplusIcon;
        }

        boolean hidden = lockState == 2 || isPrivacyEncrypted(sbn)
                || (n.visibility == Notification.VISIBILITY_PRIVATE && !allowPrivateOnLockscreen());
        Notification source = n;
        if (hidden && n.publicVersion != null) {
            source = n.publicVersion;
            hidden = false;
        }
        if (hidden) {
            item.title = item.appName;
            item.text = str(R.string.content_hidden);
        } else {
            CharSequence[] content = extract(source);
            item.title = content[0];
            item.text = mShowText ? content[1] : null;
        }
        if (TextUtils.isEmpty(item.title) && TextUtils.isEmpty(item.text)) {
            item.title = item.appName;
            item.text = str(R.string.new_notification);
        }
        return item;
    }

    private static CharSequence[] extract(Notification n) {
        Bundle e = n.extras;
        CharSequence conversation = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        CharSequence title = !TextUtils.isEmpty(conversation) ? conversation : e.getCharSequence(Notification.EXTRA_TITLE);
        if (TextUtils.isEmpty(title)) {
            title = e.getCharSequence(Notification.EXTRA_TITLE_BIG);
        }
        CharSequence text = null;
        Parcelable[] messages = e.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0 && messages[messages.length - 1] instanceof Bundle) {
            Bundle last = (Bundle) messages[messages.length - 1];
            text = last.getCharSequence("text");
            CharSequence sender = last.getCharSequence("sender");
            if (sender == null) {
                Object person = last.getParcelable("sender_person");
                if (person instanceof android.app.Person) {
                    sender = ((android.app.Person) person).getName();
                }
            }
            if (!TextUtils.isEmpty(conversation) && !TextUtils.isEmpty(sender) && !TextUtils.isEmpty(text)) {
                text = sender + ": " + text;
            }
        }
        if (TextUtils.isEmpty(text)) {
            CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) {
                text = lines[lines.length - 1];
            }
        }
        if (TextUtils.isEmpty(text)) {
            text = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
        }
        if (TextUtils.isEmpty(text)) {
            text = e.getCharSequence(Notification.EXTRA_TEXT);
        }
        return new CharSequence[]{plain(title), plain(text)};
    }

    private static CharSequence plain(CharSequence s) {
        if (s == null) {
            return null;
        }
        String out = s.toString().replace('\n', ' ').trim();
        return out.isEmpty() ? null : out;
    }

    // ---- privacy (mirrors what the stock lock screen does) ----

    /** OPlus lock screen setting: 0 = hide notifications, 1 = show, 2 = hide content. */
    private int lockscreenState() {
        Object helper = dependency(C_KEYGUARD_HELPER);
        if (helper != null) {
            try {
                return (Integer) Reflect.get(helper, "mLockScreenNotificationState");
            } catch (Throwable ignored) {
                // fall back to the raw setting
            }
        }
        return Settings.Secure.getInt(resolver(), "keyguard_notification_visibility", 1);
    }

    private boolean allowPrivateOnLockscreen() {
        return Settings.Secure.getInt(resolver(), "lock_screen_allow_private_notifications", 1) != 0;
    }

    private boolean isPrivacyEncrypted(StatusBarNotification sbn) {
        Object helper = dependency(C_PRIVACY);
        if (helper == null) {
            return false;
        }
        try {
            Method m = helper.getClass().getMethod("needEncrypted", String.class, int.class, String.class);
            return Boolean.TRUE.equals(m.invoke(helper, sbn.getPackageName(), sbn.getUid(), sbn.getKey()));
        } catch (Throwable t) {
            return false;
        }
    }

    private Object dependency(String className) {
        try {
            Object holder = Reflect.getStatic(Reflect.find(C_DEPENDENCY, mCl), "sDependency");
            Class<?> target = Reflect.find(className, mCl);
            return holder.getClass().getMethod("getDependency", Class.class).invoke(holder, target);
        } catch (Throwable t) {
            return null;
        }
    }

    private ContentResolver resolver() {
        return mContext.getContentResolver();
    }

    // ---- brief pop-up ----

    private void showPopup(AodItem item) {
        ensureHost();
        if (mHost == null || !mHost.isShown()) {
            Log.i(TAG, "popup skipped: AOD content not shown");
            return; // AOD content is hidden right now; don't wake anything for it
        }
        Log.i(TAG, "popup " + item.pkg + " for " + mPopupSeconds + "s in "
                + mHost.getParent().getClass().getSimpleName() + ", clock shown=" + isStockAodShowing());
        removePopupView();
        removePeek(false);
        mPopup = item;
        mPopupView = AodViews.popup(mColumn.getContext(), item, columnWidth(mColumn.getContext()));
        mColumn.addView(mPopupView.frame);
        AodViews.expand(mPopupView);
        shiftForBurnIn();
        long showMs = mPopupSeconds * 1000L;
        mMain.removeCallbacks(mHidePopup);
        mMain.postDelayed(mHidePopup, showMs);
        announce(showMs + AodViews.COLLAPSE_MS + 150);
    }

    private void hidePopup(boolean animate) {
        mMain.removeCallbacks(mHidePopup);
        if (mPopup == null) {
            return;
        }
        mPopup = null;
        AodViews.Capsule view = mPopupView;
        if (animate && view != null && mHost != null && mHost.isShown()) {
            // Keep the panel awake through the collapse animation, then let it sleep.
            mKeepUntil = Math.max(mKeepUntil, SystemClock.elapsedRealtime() + AodViews.COLLAPSE_MS + 150);
            AodViews.collapse(view, () -> guard("popupGone", () -> {
                if (mPopupView == view) {
                    removePopupView();
                }
                afterPopupGone();
            }));
        } else {
            removePopupView();
            afterPopupGone();
        }
    }

    private void afterPopupGone() {
        stopKeepingScreen();
        // When the AOD clock is hidden the panel goes OFF anyway; no need to wake it to erase.
        if (mPopup == null && isStockAodShowing()) {
            requestRedraw();
        }
        releaseWakeLock();
    }

    private void removePopupView() {
        if (mPopupView != null) {
            AodViews.cancel(mPopupView);
            ViewParent parent = mPopupView.frame.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(mPopupView.frame);
            }
            mPopupView = null;
        }
    }

    // ---- last notification on tap (leaves together with the clock) ----

    private void onClockHidden() {
        if (mSessionStart != 0 && !isStockAodShowing()) {
            mClockWasHidden = true;
            removePeek(false); // the panel is going dark anyway
        }
    }

    private void onClockShown() throws Throwable {
        if (!mClockWasHidden || !isStockAodShowing()) {
            return;
        }
        mClockWasHidden = false;
        if (mPeekOnTap && mMode != Prefs.MODE_DETAILED && mPopup == null) {
            showPeek();
        }
    }

    private void showPeek() throws Throwable {
        Object nm = mNmGetInstance.invoke(null, mContext);
        StatusBarNotification[] active = (StatusBarNotification[]) mNmGetActive.invoke(nm);
        StatusBarNotification latest = null;
        if (active != null) {
            for (StatusBarNotification s : active) {
                if (s != null && isCandidate(s, false)
                        && (latest == null || s.getPostTime() > latest.getPostTime())) {
                    latest = s;
                }
            }
        }
        AodItem item = latest != null ? buildItem(latest) : null;
        ensureHost();
        if (item == null || mColumn == null || !mHost.isShown()) {
            return;
        }
        removePeek(false);
        Log.i(TAG, "peek " + item.pkg);
        mPeek = item;
        AodViews.Capsule view = AodViews.popup(mColumn.getContext(), item, columnWidth(mColumn.getContext()));
        mPeekView = view;
        mColumn.addView(view.frame, 0);
        AodViews.expand(view);
        shiftForBurnIn();
        requestDoze(AodViews.EXPAND_MS + 400);
    }

    private void removePeek(boolean animate) {
        AodViews.Capsule view = mPeekView;
        mPeek = null;
        mPeekView = null;
        if (view == null) {
            return;
        }
        Runnable detach = () -> {
            ViewParent parent = view.frame.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(view.frame);
            }
        };
        if (animate && view.frame.isShown()) {
            requestDoze(AodViews.COLLAPSE_MS + 300);
            AodViews.collapse(view, detach);
        } else {
            AodViews.cancel(view);
            detach.run();
        }
    }

    // ---- detailed cards ----

    private void renderDetailed(boolean redraw) {
        ensureHost();
        if (mColumn == null) {
            return;
        }
        Context ctx = mColumn.getContext();
        mColumn.removeAllViews();
        List<AodItem> items = new ArrayList<>(mItems.values());
        int count = items.size();
        int shown = Math.min(count, mMaxCards);
        for (int i = count - 1; i >= count - shown; i--) {
            AodItem item = items.get(i);
            View card = AodViews.card(ctx, item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i != count - 1) {
                lp.topMargin = AodViews.dp(ctx, 10);
            }
            mColumn.addView(card, lp);
            if (item.key.equals(mAnimateKey)) {
                AodViews.appear(card);
            }
        }
        mAnimateKey = null;
        if (count > shown) {
            int extra = count - shown;
            View more = AodViews.more(ctx, str(R.string.more_count, extra));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = AodViews.dp(ctx, 10);
            mColumn.addView(more, lp);
        }
        shiftForBurnIn();
        if (redraw) {
            requestRedraw();
        }
    }

    private String appLabel(String pkg) {
        String label = mLabels.get(pkg);
        if (label == null) {
            PackageManager pm = mContext.getPackageManager();
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                label = pkg;
            }
            mLabels.put(pkg, label);
        }
        return label;
    }

    /**
     * Vertical only, like the stock clock (BaseAodClockLayoutController.updateNextPosition
     * moves it by setTranslationY): a sideways shift put the capsule visibly off the centre
     * line of the clock and the screen.
     */
    private void shiftForBurnIn() {
        if (mColumn == null || mColumn.getChildCount() == 0) {
            return;
        }
        int r = AodViews.dp(mColumn.getContext(), 8);
        mColumn.setTranslationX(0);
        mColumn.setTranslationY(mRandom.nextInt(2 * r + 1) - r);
    }

    // ---- display / power ----

    /**
     * Keeps the panel in DOZE long enough to read a new notification. Needed because the
     * energy-saving AOD mode turns the display fully OFF once its clock times out.
     */
    private void announce(long ms) {
        acquireWakeLock(ms + 1500);
        mKeepUntil = SystemClock.elapsedRealtime() + ms;
        mMain.removeCallbacks(mKeepScreen);
        keepScreenTick();
    }

    private void keepScreenTick() {
        long left = mKeepUntil - SystemClock.elapsedRealtime();
        if (left <= 0) {
            return;
        }
        // The stock API caps a single request at 6 s, so long pop-ups renew it.
        requestDoze((int) Math.min(left, KEEP_CHUNK_MS));
        mMain.postDelayed(mKeepScreen, Math.min(left, KEEP_RENEW_MS));
    }

    private void stopKeepingScreen() {
        mMain.removeCallbacks(mKeepScreen);
        mKeepUntil = 0;
    }

    /** Stock flag: true while the AOD clock is on screen, false once energy saving hid it. */
    private boolean isStockAodShowing() {
        try {
            Object data = Reflect.callStatic(mAodDataClass, "getInstance", mContext);
            return (Boolean) Reflect.get(data, "mAodIsInShow");
        } catch (Throwable t) {
            return true; // unknown: erase to be safe
        }
    }

    /** Ask for one short DOZE window so the panel shows the new content. */
    private void requestRedraw() {
        requestDoze(REDRAW_MS);
    }

    private void requestDoze(int ms) {
        if (mContext == null) {
            return;
        }
        try {
            Object du = mDuGetInstance.invoke(null, mContext);
            mDuRequest.invoke(du, 1, ms, "AodNotify");
        } catch (Throwable t) {
            logError("requestDoze", t);
        }
    }

    private void acquireWakeLock(long ms) {
        if (mWakeLock == null) {
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AodNotify:popup");
            mWakeLock.setReferenceCounted(false);
        }
        mWakeLock.acquire(ms);
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    // ---- helpers ----

    /**
     * The module's own strings in the system language. Looked up on each use: SystemUI lives on
     * through a language change, and the package manager keeps the resources cached.
     */
    private String str(int id, Object... args) {
        try {
            Resources res = mContext.getPackageManager().getResourcesForApplication(mModule.getModuleApplicationInfo());
            return res.getString(id, args);
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mMain.post(r);
        }
    }

    /** Every hook body runs through here so a failure can never crash SystemUI. */
    private void guard(String where, Action action) {
        try {
            action.run();
        } catch (Throwable t) {
            logError(where, t);
        }
    }

    private void logError(String where, Throwable t) {
        if (mErrorLogs++ < MAX_ERROR_LOGS) {
            mModule.log(Log.ERROR, TAG, where + " failed", t);
        }
    }
}
