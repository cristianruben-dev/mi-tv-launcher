package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextClock;

import java.util.Random;

/**
 * The screensaver scene shared by {@link ClockDream} and {@link ScreensaverActivity}: the launcher's thin
 * clock and date over the chosen wallpaper (darkened) or a slow ambient glow. Every minute the clock drifts
 * to a new spot and the glow shifts colour, so nothing burns in.
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** How dark the wallpaper sits behind the clock, so it stays readable and easy on the panel. */
    private static final float WALLPAPER_ALPHA = 0.45f;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Context ctx;
    private final View content;
    private final ImageView wallpaper;
    private final GradientDrawable glow = new GradientDrawable();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private int colorIndex;
    private int glowColor;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** {@code root} is an inflated R.layout.dream_clock. */
    ClockScene(Context ctx, View root) {
        this.ctx = ctx;
        content = root.findViewById(R.id.dreamContent);
        wallpaper = root.findViewById(R.id.dreamWallpaper);
        Clocks.apply(ctx, root.findViewById(R.id.dreamClock), (TextClock) root.findViewById(R.id.dreamAmpm));
        View ambient = root.findViewById(R.id.dreamAmbient);
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    void start() {
        running = true;
        loadWallpaper();
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(tick, MOVE_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void loadWallpaper() {
        final String name = Prefs.wallpaper(ctx);
        if (name.isEmpty()) return;
        final int w = ctx.getResources().getDisplayMetrics().widthPixels;
        final int h = ctx.getResources().getDisplayMetrics().heightPixels;
        new Thread(() -> {
            Bitmap bmp = Wallpapers.load(ctx, name, w, h);
            handler.post(() -> {
                if (!running || bmp == null) return;
                wallpaper.setImageBitmap(bmp);
                wallpaper.setVisibility(View.VISIBLE);
                wallpaper.setAlpha(0f);
                wallpaper.animate().alpha(WALLPAPER_ALPHA).setDuration(1500).start();
            });
        }, "dream-wallpaper").start();
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
