package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Wallpapers bundled in assets/wallpapers. Drop images there and rebuild; they show up in the
 * settings dialog by file name.
 */
final class Wallpapers {

    private static final String DIR = "wallpapers";
    /** The dock's frosted glass blurs a copy this many times smaller than the wallpaper. */
    private static final int BLUR_DOWNSCALE = 12;

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

    /** "blue_hills-2.jpg" → "Blue hills 2". */
    static String title(String file) {
        int dot = file.lastIndexOf('.');
        String s = (dot > 0 ? file.substring(0, dot) : file).replace('_', ' ').replace('-', ' ').trim();
        if (s.isEmpty()) return file;
        return s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
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

    /** A soft, low-resolution copy of the wallpaper for the dock's frosted glass. */
    static Bitmap blurred(Bitmap src) {
        return Blur.scaledBlur(src, BLUR_DOWNSCALE);
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
