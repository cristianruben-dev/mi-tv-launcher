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
        if (src == null) return null;
        int w = Math.max(8, src.getWidth() / BLUR_DOWNSCALE);
        int h = Math.max(8, src.getHeight() / BLUR_DOWNSCALE);
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        // Three box blur passes approximate a gaussian.
        for (int i = 0; i < 3; i++) {
            boxBlur(px, w, h, 3, true);
            boxBlur(px, w, h, 3, false);
        }
        small.setPixels(px, 0, w, 0, 0, w, h);
        return small;
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

    /** One horizontal or vertical box blur pass over ARGB pixels, edges clamped. */
    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] out = new int[len];
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = px[index(clamp(i, len), line, w, horizontal)];
                sa += p >>> 24;
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[i] = (sa / div) << 24 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int pOut = px[index(clamp(i - r, len), line, w, horizontal)];
                int pIn = px[index(clamp(i + r + 1, len), line, w, horizontal)];
                sa += (pIn >>> 24) - (pOut >>> 24);
                sr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                sg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                sb += (pIn & 0xFF) - (pOut & 0xFF);
            }
            for (int i = 0; i < len; i++) px[index(i, line, w, horizontal)] = out[i];
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }

    private static int index(int i, int line, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
