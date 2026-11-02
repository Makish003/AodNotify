package io.github.makish003.aodnotify;

import android.graphics.drawable.Icon;

/** What the overlay needs to draw one notification; built once, never mutated. */
final class AodItem {
    String key;
    String groupKey;
    String pkg;
    long when;
    String appName;
    CharSequence title;
    CharSequence text;
    Icon smallIcon;
    /** ColorOS adds its own status-bar glyph for some apps under the "oplus_small_icon" extra. */
    Icon oplusIcon;
}
