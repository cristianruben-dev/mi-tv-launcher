package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Launcher settings chosen in the settings dialog, with the defaults from res/values/config.xml. */
final class Prefs {

    /** Grid columns offered in settings; the dock has one slot per column. */
    static final int[] COLUMN_CHOICES = {3, 4, 5, 6};

    private static final String FILE = "tvhome";
    private static final String KEY_DOCK_APPS = "dock_apps";
    private static final String KEY_HIDDEN = "hidden_apps";
    private static final String KEY_ORDER = "app_order";
    private static final String KEY_COLUMNS = "columns";
    private static final String KEY_WALLPAPER = "wallpaper";
    private static final String KEY_ANIMATIONS = "animations";
    private static final String KEY_LIGHT_MODE = "light_mode";
    private static final String KEY_GLASS_OPACITY = "glass_opacity";
    private static final String KEY_APPEARANCE_AT = "appearance_at";
    private static final String KEY_TIME_FORMAT = "time_format";
    private static final String KEY_POWER_ON_INPUT = "power_on_input";
    private static final String KEY_SLEEP_AT = "sleep_at";
    private static final String KEY_ROTATION = "wallpaper_rotation";
    private static final String KEY_ROTATED_AT = "wallpaper_rotated_at";
    private static final String KEY_ROTATE_PENDING = "wallpaper_rotate_pending";

    static final String TIME_AUTO = "";
    static final String TIME_12 = "12";
    static final String TIME_24 = "24";
    /** Power-on choices besides a TV input id: stay on the home screen, or Live TV. */
    static final String POWER_ON_HOME = "";
    static final String POWER_ON_LIVE_TV = "live_tv";
    static final String ROTATE_NEVER = "";
    static final String ROTATE_ON_POWER = "power_on";
    static final String ROTATE_HOURLY = "hourly";
    static final String ROTATE_DAILY = "daily";

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Glass opacity choices (Settings → Home screen), in percent: how much the glass tints what is behind. */
    static final int GLASS_MIN = 0, GLASS_MAX = 100, GLASS_STEP = 5, GLASS_DEFAULT = 15;
    /** Light mode puts dark text on the glass: below this much white it would not read over a dark picture. */
    static final int GLASS_MIN_LIGHT = 40;

    /** The lowest opacity the slider offers in the current mode. */
    static int glassMin(Context ctx) {
        return lightMode(ctx) ? GLASS_MIN_LIGHT : GLASS_MIN;
    }

    /** Light mode: white glass and light menus. Off (the default): dark glass and dark menus. */
    static boolean lightMode(Context ctx) {
        return sp(ctx).getBoolean(KEY_LIGHT_MODE, false);
    }

    static void setLightMode(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_LIGHT_MODE, on).putLong(KEY_APPEARANCE_AT, System.currentTimeMillis()).apply();
    }

    static int glassOpacity(Context ctx) {
        int v = sp(ctx).getInt(KEY_GLASS_OPACITY, GLASS_DEFAULT);
        return Math.max(glassMin(ctx), Math.min(GLASS_MAX, v));
    }

    static void setGlassOpacity(Context ctx, int percent) {
        sp(ctx).edit().putInt(KEY_GLASS_OPACITY, percent).putLong(KEY_APPEARANCE_AT, System.currentTimeMillis()).apply();
    }

    /** When the mode or the opacity was last chosen here, so House Control keeps whichever choice is newer. */
    static long appearanceAt(Context ctx) {
        return sp(ctx).getLong(KEY_APPEARANCE_AT, 0L);
    }

    /**
     * Wraps a base context in the chosen mode: night resources (values/) for dark mode, values-notnight/ for
     * light mode, whatever the TV itself is set to.
     */
    static Context wrapMode(Context base) {
        boolean light = base.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_LIGHT_MODE, false);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (light ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES);
        return base.createConfigurationContext(config);
    }

    /** The app only speaks Spanish: resources, TextClock and date formats use it whatever the TV is set to. */
    static Context wrapLocale(Context base) {
        Locale locale = new Locale("es");
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }

    // ---- dock and app list -----------------------------------------------------------------------

    /** Dock packages in order: the user's choice, or config.xml's dock_apps until they pick some. */
    static List<String> dockApps(Context ctx) {
        String saved = sp(ctx).getString(KEY_DOCK_APPS, null);
        return saved == null
                ? new ArrayList<>(Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps)))
                : split(saved);
    }

    /**
     * The dock apps that fit {@code slots} slots. The saved list is kept whole, so going from 6 columns to
     * 4 and back brings the last two apps back.
     */
    static List<String> dockApps(Context ctx, int slots) {
        List<String> all = dockApps(ctx);
        return all.size() > slots ? new ArrayList<>(all.subList(0, slots)) : all;
    }

    static void setDockApps(Context ctx, List<String> pkgs) {
        sp(ctx).edit().putString(KEY_DOCK_APPS, TextUtils.join(",", pkgs)).apply();
    }

    static void resetDockApps(Context ctx) {
        sp(ctx).edit().remove(KEY_DOCK_APPS).apply();
    }

    /** Packages never shown as tiles: chosen in settings, or config.xml's hidden_apps by default. */
    static Set<String> hiddenApps(Context ctx) {
        String saved = sp(ctx).getString(KEY_HIDDEN, null);
        return new LinkedHashSet<>(saved == null
                ? Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps))
                : split(saved));
    }

    static void setHiddenApps(Context ctx, Set<String> pkgs) {
        sp(ctx).edit().putString(KEY_HIDDEN, TextUtils.join(",", pkgs)).apply();
    }

    /** Grid order picked in settings; apps not in it follow alphabetically. */
    static List<String> appOrder(Context ctx) {
        return split(sp(ctx).getString(KEY_ORDER, ""));
    }

    static void setAppOrder(Context ctx, List<String> pkgs) {
        sp(ctx).edit().putString(KEY_ORDER, TextUtils.join(",", pkgs)).apply();
    }

    static void resetAppOrder(Context ctx) {
        sp(ctx).edit().remove(KEY_ORDER).apply();
    }

    // ---- look ------------------------------------------------------------------------------------

    static int columns(Context ctx) {
        int def = Math.max(1, ctx.getResources().getInteger(R.integer.columns));
        return sp(ctx).getInt(KEY_COLUMNS, def);
    }

    static void setColumns(Context ctx, int columns) {
        sp(ctx).edit().putInt(KEY_COLUMNS, columns).apply();
    }

    /**
     * File name in assets/wallpapers. Always one of them: a choice that is missing (or the old "none") falls
     * back to the first. "" only when no wallpaper is bundled.
     */
    static String wallpaper(Context ctx) {
        String chosen = sp(ctx).getString(KEY_WALLPAPER, "");
        List<String> all = Wallpapers.list(ctx);
        if (all.contains(chosen)) return chosen;
        return all.isEmpty() ? "" : all.get(0);
    }

    static void setWallpaper(Context ctx, String name) {
        sp(ctx).edit().putString(KEY_WALLPAPER, name).apply();
    }

    // ---- performance -----------------------------------------------------------------------------

    /** The launcher's own motion: springs, scaling, fades. */
    static boolean animations(Context ctx) {
        return sp(ctx).getBoolean(KEY_ANIMATIONS, true);
    }

    static void setAnimations(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_ANIMATIONS, on).apply();
    }

    // ---- clock, power on, sleep timer ------------------------------------------------------------

    /** {@link #TIME_AUTO} (as the TV), {@link #TIME_12} or {@link #TIME_24}. */
    static String timeFormat(Context ctx) {
        return sp(ctx).getString(KEY_TIME_FORMAT, TIME_AUTO);
    }

    static void setTimeFormat(Context ctx, String format) {
        sp(ctx).edit().putString(KEY_TIME_FORMAT, format).apply();
    }

    /** What to open when the TV is turned on: {@link #POWER_ON_HOME}, {@link #POWER_ON_LIVE_TV} or an input id. */
    static String powerOnInput(Context ctx) {
        return sp(ctx).getString(KEY_POWER_ON_INPUT, POWER_ON_HOME);
    }

    static void setPowerOnInput(Context ctx, String input) {
        sp(ctx).edit().putString(KEY_POWER_ON_INPUT, input).apply();
    }

    /** When the sleep timer turns the TV off (wall clock millis), or 0 when it is off. */
    static long sleepAt(Context ctx) {
        return sp(ctx).getLong(KEY_SLEEP_AT, 0L);
    }

    static void setSleepAt(Context ctx, long at) {
        sp(ctx).edit().putLong(KEY_SLEEP_AT, at).apply();
    }

    // ---- wallpaper rotation ----------------------------------------------------------------------

    static String rotation(Context ctx) {
        return sp(ctx).getString(KEY_ROTATION, ROTATE_NEVER);
    }

    static void setRotation(Context ctx, String rotation) {
        sp(ctx).edit().putString(KEY_ROTATION, rotation).putLong(KEY_ROTATED_AT, System.currentTimeMillis()).apply();
    }

    static long rotatedAt(Context ctx) {
        return sp(ctx).getLong(KEY_ROTATED_AT, 0L);
    }

    static void setRotatedAt(Context ctx, long at) {
        sp(ctx).edit().putLong(KEY_ROTATED_AT, at).apply();
    }

    /** Set when the TV turns on with "change on power on": the home screen rotates on its next resume. */
    static boolean rotatePending(Context ctx) {
        return sp(ctx).getBoolean(KEY_ROTATE_PENDING, false);
    }

    static void setRotatePending(Context ctx, boolean pending) {
        sp(ctx).edit().putBoolean(KEY_ROTATE_PENDING, pending).apply();
    }

    private static List<String> split(String joined) {
        List<String> out = new ArrayList<>();
        for (String p : joined.split(",")) {
            String t = p.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }
}
