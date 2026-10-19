package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Launcher settings chosen in the settings dialog, with the defaults from res/values/config.xml. */
final class Prefs {

    static final String LANG_SYSTEM = "";
    static final String DOCK_SOLID = "solid";
    static final String DOCK_BLUR = "blur";
    /** Dock slots; the dock always shows this many apps. */
    static final int DOCK_MAX = 4;

    private static final String FILE = "tvhome";
    private static final String KEY_LANG = "lang";
    private static final String KEY_DOCK_APPS = "dock_apps";
    private static final String KEY_DOCK_STYLE = "dock_style";
    private static final String KEY_WALLPAPER = "wallpaper";

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

    // ---- dock ------------------------------------------------------------------------------------

    /** Dock packages in order: the user's choice, or config.xml's dock_apps until they pick some. */
    static List<String> dockApps(Context ctx) {
        String saved = sp(ctx).getString(KEY_DOCK_APPS, null);
        if (saved == null) {
            return new ArrayList<>(Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps)));
        }
        List<String> out = new ArrayList<>();
        for (String p : saved.split(",")) {
            if (!p.trim().isEmpty() && out.size() < DOCK_MAX) out.add(p.trim());
        }
        return out;
    }

    static void setDockApps(Context ctx, List<String> pkgs) {
        sp(ctx).edit().putString(KEY_DOCK_APPS, TextUtils.join(",", pkgs)).apply();
    }

    static void resetDockApps(Context ctx) {
        sp(ctx).edit().remove(KEY_DOCK_APPS).apply();
    }

    static String dockStyle(Context ctx) {
        return sp(ctx).getString(KEY_DOCK_STYLE, DOCK_SOLID);
    }

    static void setDockStyle(Context ctx, String style) {
        sp(ctx).edit().putString(KEY_DOCK_STYLE, style).apply();
    }

    // ---- wallpaper -------------------------------------------------------------------------------

    /** File name in assets/wallpapers, or "" for none (the black background with the ambient glow). */
    static String wallpaper(Context ctx) {
        return sp(ctx).getString(KEY_WALLPAPER, "");
    }

    static void setWallpaper(Context ctx, String name) {
        sp(ctx).edit().putString(KEY_WALLPAPER, name).apply();
    }
}
