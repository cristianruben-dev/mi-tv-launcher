package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

/**
 * Wallpapers bundled in assets/wallpapers. Drop images there and rebuild; they show up in the
 * settings dialog by file name.
 */
final class Wallpapers {

    private static final String DIR = "wallpapers";

    private Wallpapers() {
    }

    /** Image file names in assets/wallpapers, sorted. */
    static List<String> list(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            String[] names = ctx.getAssets().list(DIR);
            if (names == null) return out;
            Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
            for (String n : names) {
                String l = n.toLowerCase(Locale.ROOT);
                if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp")) out.add(n);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /**
     * The look each bundled wallpaper reads best with, picked by eye on the TV: light mode (white glass, dark text)
     * for bright pictures, dark mode (dark glass, white text) for dark ones, and the glass opacity in percent.
     */
    private static final Map<String, int[]> PRESETS = new HashMap<>();

    static {
        // {light mode ? 1 : 0, opacity}
        PRESETS.put("acantilados_estelares.jpg", new int[]{0, 45});
        PRESETS.put("bajo_el_mar.jpg", new int[]{1, 55});
        PRESETS.put("bosque_al_atardecer.jpg", new int[]{0, 30});
        PRESETS.put("cometa_sobre_las_nubes.jpg", new int[]{0, 30});
        PRESETS.put("icebergs_al_atardecer.jpg", new int[]{1, 50});
        PRESETS.put("mirando_los_planetas.jpg", new int[]{0, 30});
        PRESETS.put("nebulosa_roja.jpg", new int[]{0, 35});
        PRESETS.put("portal_en_ruinas.jpg", new int[]{0, 30});
    }

    /**
     * Applies the look that suits {@code file}, if it has one. Returns true when the mode changed, which needs the
     * activity recreated (the menus' colours are resources of the mode); the opacity alone only needs a redraw.
     */
    static boolean applyPreset(Context ctx, String file) {
        int[] p = PRESETS.get(file);
        if (p == null) return false;
        boolean light = p[0] == 1, changed = light != Prefs.lightMode(ctx);
        Prefs.setLightMode(ctx, light);
        Prefs.setGlassOpacity(ctx, p[1]);
        return changed;
    }

    /** Switches to the next bundled wallpaper (from "none", the first), for automatic rotation. */
    static void rotateNext(Context ctx) {
        List<String> all = list(ctx);
        Prefs.setRotatedAt(ctx, System.currentTimeMillis());
        if (all.isEmpty()) return;
        int i = all.indexOf(Prefs.wallpaper(ctx));
        Prefs.setWallpaper(ctx, all.get((i + 1) % all.size()));
    }

    /**
     * Decodes a wallpaper no smaller than the screen, as RGB_565 to halve its memory on 1 GB TVs.
     * Returns null when the file is missing or unreadable.
     */
    static Bitmap load(Context ctx, String file, int screenW, int screenH) {
        return decode(ctx, file, Math.max(1, screenW), Math.max(1, screenH), Bitmap.Config.RGB_565);
    }

    /** Small preview for the settings list. */
    static Bitmap thumbnail(Context ctx, String file, int w, int h) {
        return decode(ctx, file, w, h, Bitmap.Config.RGB_565);
    }

    private static Bitmap decode(Context ctx, String file, int reqW, int reqH, Bitmap.Config config) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (!read(ctx, file, bounds)) return null;
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = config;
        return readBitmap(ctx, file, opts);
    }

    private static boolean read(Context ctx, String file, BitmapFactory.Options opts) {
        try (InputStream in = ctx.getAssets().open(DIR + "/" + file)) {
            BitmapFactory.decodeStream(in, null, opts);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Bitmap readBitmap(Context ctx, String file, BitmapFactory.Options opts) {
        try (InputStream in = ctx.getAssets().open(DIR + "/" + file)) {
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }
}
