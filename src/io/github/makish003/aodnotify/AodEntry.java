package io.github.makish003.aodnotify;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Adds a "Notifications" row to AOD's "Display settings" page (Settings > Home screen, lock screen
 * and style > Always-On Display > Display settings, com.oplus.aod SettingsFragment), under
 * "New notifications". It is a COUIJumpPreference built by the same constructor that inflates the
 * page's preference_settings.xml, and opens the module's screen in AOD's task, so it reads as
 * one more sub-page of the system settings.
 */
final class AodEntry {
    private static final String TAG = "AodNotify";

    static final String EXTRA_FROM_AOD = "io.github.makish003.aodnotify.FROM_AOD_SETTINGS";

    private static final String FRAGMENT = "com.oplus.aod.fragment.SettingsFragment";
    private static final String CATEGORY_KEY = "key_aod_preference_category_content";
    // androidx.preference / COUI members as R8 named them in com.oplus.aod 17.001.0021
    private static final String FIND_PREFERENCE = "g"; // PreferenceFragmentCompat.findPreference(CharSequence)
    private static final String ADD_PREFERENCE = "T0"; // PreferenceGroup.addPreference(Preference)
    private static final String SET_ASSIGNMENT = "W0"; // COUIPreference.setAssignment(CharSequence)

    private final XposedModule mModule;
    private final Method mFind;
    private final Method mAdd;
    private final Method mAssign;
    private final Constructor<?> mJump;
    private final Field mContext;
    private final Field mIntent;
    private final Map<Object, Object> mRows = new WeakHashMap<>();
    private SharedPreferences mPrefs;

    private AodEntry(XposedModule module, ClassLoader cl) throws Throwable {
        mModule = module;
        Class<?> preference = Reflect.find("androidx.preference.Preference", cl);
        Class<?> group = Reflect.find("androidx.preference.PreferenceGroup", cl);
        mFind = method(Reflect.find(FRAGMENT, cl), FIND_PREFERENCE, preference, CharSequence.class);
        mAdd = method(group, ADD_PREFERENCE, boolean.class, preference);
        mAssign = method(Reflect.find("com.coui.appcompat.preference.COUIPreference", cl), SET_ASSIGNMENT,
                void.class, CharSequence.class);
        mJump = Reflect.find("com.coui.appcompat.preference.COUIJumpPreference", cl)
                .getConstructor(Context.class, android.util.AttributeSet.class);
        mContext = onlyField(preference, Context.class);
        mIntent = onlyField(preference, Intent.class);
    }

    static void install(XposedModule module, ClassLoader cl) {
        try {
            AodEntry entry = new AodEntry(module, cl);
            Class<?> fragment = Reflect.find(FRAGMENT, cl);
            // onCreate builds the page from preference_settings.xml and looks its rows up
            module.hook(fragment.getDeclaredMethod("onCreate", Bundle.class)).intercept(chain -> {
                Object result = chain.proceed();
                entry.guard(() -> entry.addRow(chain.getThisObject()));
                return result;
            });
            module.hook(fragment.getDeclaredMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed();
                entry.guard(() -> entry.refresh(chain.getThisObject()));
                return result;
            });
            module.log(Log.INFO, TAG, "AOD settings entry installed");
        } catch (Throwable t) {
            // another AOD build: leave its page as it is
            module.log(Log.WARN, TAG, "AOD settings entry not installed", t);
        }
    }

    private void addRow(Object fragment) throws Throwable {
        Object category = mFind.invoke(fragment, CATEGORY_KEY);
        if (category == null) {
            return;
        }
        Context context = (Context) mContext.get(category);
        Object row;
        try (XmlResourceParser parser = moduleRes(context).getXml(R.xml.aod_entry)) {
            int type;
            do {
                type = parser.next();
            } while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT);
            row = mJump.newInstance(context, Xml.asAttributeSet(parser));
        }
        // Preference.performClick starts its intent from the page's context, i.e. in AOD's task
        mIntent.set(row, new Intent()
                .setComponent(new ComponentName("io.github.makish003.aodnotify", SettingsActivity.class.getName()))
                .putExtra(EXTRA_FROM_AOD, true));
        mAdd.invoke(category, row);
        mRows.put(fragment, row);
        refresh(fragment);
    }

    /** The current mode as the grey value on the right, like the "Information" row shows its own. */
    private void refresh(Object fragment) throws Throwable {
        Object row = mRows.get(fragment);
        if (row == null) {
            return;
        }
        if (mPrefs == null) {
            mPrefs = mModule.getRemotePreferences(Prefs.GROUP);
        }
        int mode = mPrefs.getInt(Prefs.MODE, Prefs.DEF_MODE);
        String[] names = moduleRes((Context) mContext.get(row)).getStringArray(R.array.mode_names);
        mAssign.invoke(row, mode >= 0 && mode < names.length ? names[mode] : null);
    }

    /** The module's own resources, in the language AOD runs in, which is the system one. */
    private Resources moduleRes(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getResourcesForApplication(mModule.getModuleApplicationInfo());
    }

    private interface Action {
        void run() throws Throwable;
    }

    /** A failure here must never break the system settings page. */
    private void guard(Action action) {
        try {
            action.run();
        } catch (Throwable t) {
            mModule.log(Log.ERROR, TAG, "AOD settings entry failed", t);
        }
    }

    /** The obfuscated member, checked against the signature the decompiled build has. */
    private static Method method(Class<?> c, String name, Class<?> returns, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                if (returns.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchMethodException(c.getName() + '#' + name);
    }

    /** Preference keeps exactly one Context and one Intent, whatever R8 named the fields. */
    private static Field onlyField(Class<?> c, Class<?> type) throws NoSuchFieldException {
        Field found = null;
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == type) {
                if (found != null) {
                    throw new NoSuchFieldException(c.getName() + " has several " + type.getSimpleName());
                }
                found = f;
            }
        }
        if (found == null) {
            throw new NoSuchFieldException(c.getName() + " has no " + type.getSimpleName());
        }
        found.setAccessible(true);
        return found;
    }
}
