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

    static final String LANG_SYSTEM = "";
    /** Grid columns offered in settings; the dock has one slot per column. */
    static final int[] COLUMN_CHOICES = {3, 4, 5, 6};
    static final int[] DIM_CHOICES = {0, 20, 40, 60};

    private static final String FILE = "tvhome";
    private static final String KEY_LANG = "lang";
    private static final String KEY_DOCK_APPS = "dock_apps";
    private static final String KEY_HIDDEN = "hidden_apps";
    private static final String KEY_ORDER = "app_order";
    private static final String KEY_COLUMNS = "columns";
    private static final String KEY_DIM = "wallpaper_dim";
    private static final String KEY_WALLPAPER = "wallpaper";
    private static final String KEY_BLUR = "blur";
    private static final String KEY_ANIMATIONS = "animations";
    /** Older builds stored the dock look as "solid" or "blur"; read once as the default for KEY_BLUR. */
    private static final String KEY_LEGACY_DOCK_STYLE = "dock_style";

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- language --------------------------------------------------------------------------------

    /** "en", "es", or {@link #LANG_SYSTEM} to follow the TV's language. */
    static String language(Context ctx) {
        return sp(ctx).getString(KEY_LANG, LANG_SYSTEM);
    }

    static void setLanguage(Context ctx, String lang) {
        sp(ctx).edit().putString(KEY_LANG, lang).apply();
    }

    /** Wraps a base context so resources, TextClock and date formats use the chosen language. */
    static Context wrapLocale(Context base) {
        String lang = base.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LANG, LANG_SYSTEM);
        if (TextUtils.isEmpty(lang)) {
            // Back to the TV's language: undo a default left over from an earlier choice in this process.
            Locale.setDefault(base.getResources().getConfiguration().getLocales().get(0));
            return base;
        }
        Locale locale = new Locale(lang);
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

    /** How much to darken the wallpaper, 0–100. */
    static int wallpaperDim(Context ctx) {
        return sp(ctx).getInt(KEY_DIM, 0);
    }

    static void setWallpaperDim(Context ctx, int percent) {
        sp(ctx).edit().putInt(KEY_DIM, percent).apply();
    }

    /** File name in assets/wallpapers, or "" for none (the black background with the ambient glow). */
    static String wallpaper(Context ctx) {
        return sp(ctx).getString(KEY_WALLPAPER, "");
    }

    static void setWallpaper(Context ctx, String name) {
        sp(ctx).edit().putString(KEY_WALLPAPER, name).apply();
    }

    // ---- performance -----------------------------------------------------------------------------

    /** Frosted dock and blurred background behind menus. Off: solid dock, plain dimming. */
    static boolean blur(Context ctx) {
        SharedPreferences sp = sp(ctx);
        if (sp.contains(KEY_BLUR)) return sp.getBoolean(KEY_BLUR, true);
        return !"solid".equals(sp.getString(KEY_LEGACY_DOCK_STYLE, "blur"));
    }

    static void setBlur(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_BLUR, on).remove(KEY_LEGACY_DOCK_STYLE).apply();
    }

    /** The launcher's own motion: springs, scaling, fades. */
    static boolean animations(Context ctx) {
        return sp(ctx).getBoolean(KEY_ANIMATIONS, true);
    }

    static void setAnimations(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_ANIMATIONS, on).apply();
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
