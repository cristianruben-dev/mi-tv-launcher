package com.gathin.tvhome;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Software blur for Android 11, which has no RenderEffect: work on a small copy, box blur it a few
 * times (close to a gaussian), and let the GPU scale it back up smoothly.
 */
final class Blur {

    /**
     * How soft every frosted surface is (dock, top bar, menus, settings, House Control's panel): a gaussian
     * spread of 4dp, the same as House Control's CSS {@code blur(4px)}.
     */
    static final float RADIUS_DP = 4f;
    /** Box radius 2 in three passes is close to a gaussian with a spread of √6 pixels of the small copy. */
    private static final int BOX = 2, PASSES = 3;
    private static final float BOX_SPREAD = 2.449f;

    private Blur() {
    }

    /** The spread in screen pixels. */
    private static float spreadPx() {
        return RADIUS_DP * Resources.getSystem().getDisplayMetrics().density;
    }

    /** How much smaller the copy is drawn so the box passes give exactly {@link #RADIUS_DP} once scaled back up. */
    private static float shrink() {
        return Math.min(1f, BOX_SPREAD / spreadPx());
    }

    /** A blurred, downscaled copy of what a view shows right now, for the backdrop of a menu. */
    static Bitmap snapshot(View view) {
        float k = shrink();
        int w = Math.max(1, Math.round(view.getWidth() * k)), h = Math.max(1, Math.round(view.getHeight() * k));
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.scale(w / (float) view.getWidth(), h / (float) view.getHeight());
            view.draw(c);
            blurInPlace(bmp, BOX, PASSES);
            return bmp;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** A full-resolution copy of what a view shows, clipped to rounded corners like a tile. */
    static Bitmap copyOf(View view, float radius) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        try {
            Bitmap bmp = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, view.getWidth(), view.getHeight()), radius, radius, Path.Direction.CW);
            c.clipPath(clip);
            view.draw(c);
            return bmp;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** A soft, low-resolution copy of a screen-sized bitmap, blurred by {@link #RADIUS_DP}. */
    static Bitmap scaledBlur(Bitmap src) {
        if (src == null) return null;
        float k = shrink();
        int w = Math.max(8, Math.round(src.getWidth() * k)), h = Math.max(8, Math.round(src.getHeight() * k));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
        blurInPlace(small, BOX, PASSES);
        return small;
    }

    static void blurInPlace(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < passes; i++) {
            boxBlur(px, w, h, radius, true);
            boxBlur(px, w, h, radius, false);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
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
