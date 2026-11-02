package io.github.makish003.aodnotify;

/** Keys and defaults shared by the settings screen and the SystemUI hook. */
final class Prefs {
    /** LSPosed remote-preferences group. */
    static final String GROUP = "settings";

    static final String MODE = "mode";
    static final String POPUP_SECONDS = "popup_seconds";
    static final String MAX_CARDS = "max_cards";
    static final String PLACEMENT = "placement";
    static final String SHOW_TEXT = "show_text";
    static final String ONLY_ALERTING = "only_alerting";
    static final String INCLUDE_OLD = "include_old";
    static final String POCKET_CHECK = "pocket_check";
    static final String PEEK_ON_TAP = "peek_on_tap";
    static final String ICON_STYLE = "icon_style";
    static final String ICON_COLOR = "icon_color";

    static final int MODE_OFF = 0;
    static final int MODE_BRIEF = 1;
    static final int MODE_DETAILED = 2;

    static final int PLACE_TOP = 0;
    static final int PLACE_CENTER = 1;
    static final int PLACE_BOTTOM = 2;
    /** Top edge of the overlay for each placement, in percent of the screen height. */
    static final int[] PLACEMENT_PERCENT = {6, 42, 70};

    static final int DEF_MODE = MODE_BRIEF;
    static final int DEF_POPUP_SECONDS = 6;
    static final int DEF_MAX_CARDS = 2;
    static final int DEF_PLACEMENT = PLACE_TOP;
    static final boolean DEF_SHOW_TEXT = true;
    static final boolean DEF_ONLY_ALERTING = true;
    static final boolean DEF_INCLUDE_OLD = false;
    static final boolean DEF_POCKET_CHECK = true;
    static final boolean DEF_PEEK_ON_TAP = false;

    static final int ICONS_COLOR = 0;
    static final int ICONS_MONO = 1;
    static final int DEF_ICON_STYLE = ICONS_COLOR;
    static final int DEF_ICON_COLOR = 0xFFFFFFFF;
    /** Named by R.array.icon_color_names, in the same order. */
    static final int[] ICON_COLORS = {
            0xFFFFFFFF, 0xFF9E9E9E, 0xFF1A8CFF, 0xFF4FC3F7, 0xFF4CD964, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF453A, 0xFFFF6FAE, 0xFFB57BFF};

    static final int[] POPUP_SECONDS_CHOICES = {3, 5, 6, 8, 10, 15};
    static final int[] MAX_CARDS_CHOICES = {1, 2, 3, 4};

    private Prefs() {
    }
}
