package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * The launcher's glass for any view: a translucent sheen, a little stronger at the top, under a thin rim.
 * The top-bar pills, the dock, settings rows and House Control's panel all share it; nothing is blurred.
 * Dark mode tints what is behind darker, light mode lighter, by the opacity chosen in settings. Focused
 * views turn solid: white in dark mode, near black in light mode (R.color.glass_focus).
 */
final class Glass {

    private static boolean dark = true;
    private static int opacity = Prefs.GLASS_DEFAULT;
    /** Bumped on every change, so cached sheens know to rebuild. */
    private static int stamp;

    private Glass() {
    }

    /** Sets the mode and the opacity in percent; views already drawn update on their next invalidate. */
    static void configure(boolean darkMode, int opacityPercent) {
        dark = darkMode;
        opacity = opacityPercent;
        stamp++;
    }

    static int stamp() {
        return stamp;
    }

    /** The sheen at its top and bottom: the chosen opacity a third stronger at the top, a third weaker below. */
    static int top() {
        return tint(opacity * 4 / 3f, dark ? 0x000000 : 0xFFFFFF);
    }

    static int bottom() {
        return tint(opacity * 2 / 3f, dark ? 0x000000 : 0xFFFFFF);
    }

    static int rim() {
        return dark ? 0x33FFFFFF : 0x4DFFFFFF;
    }

    /** An activated view (the open settings section, an app being moved) gets a white sheen over the glass. */
    private static int topActive() {
        return tint(opacity * 4 / 3f + 14, 0xFFFFFF);
    }

    private static int bottomActive() {
        return tint(opacity * 2 / 3f + 10, 0xFFFFFF);
    }

    private static int tint(float percent, int rgb) {
        int alpha = Math.round(Math.max(0f, Math.min(100f, percent)) * 2.55f);
        return alpha << 24 | rgb;
    }

    /** Gives {@code v} the glass, with corners of {@code radiusPx}. */
    static void apply(View v, float radiusPx) {
        apply(v, radiusPx, true);
    }

    /** As above; {@code whiteOnFocus} false keeps the glass when focused (the view draws its own focus edge). */
    static void apply(View v, float radiusPx, boolean whiteOnFocus) {
        v.setBackground(new Sheen(radiusPx, v.getResources().getDisplayMetrics().density, whiteOnFocus,
                v.getContext().getColor(R.color.glass_focus)));
    }

    /** A vertical sheen for {@code rect}, from {@link #top} to {@link #bottom}. */
    static Shader sheen(RectF rect) {
        return new LinearGradient(0, rect.top, 0, rect.bottom, top(), bottom(), Shader.TileMode.CLAMP);
    }

    /** Redraws every glass view under {@code v}, after the mode or the opacity changed. */
    static void invalidateAll(View v) {
        v.invalidate();
        if (v.getBackground() != null) v.getBackground().invalidateSelf();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateAll(g.getChildAt(i));
        }
    }

    private static final class Sheen extends Drawable {
        private final float radius;
        private final boolean whiteOnFocus;
        /** The solid fill of a focused view: white in dark mode, near black in light mode. */
        private final int focusFill;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean focused, activated;
        /** The sheen for the current bounds and state, rebuilt only when either (or the glass) changes. */
        private Shader shader;
        private int shaderStamp;

        Sheen(float radius, float density, boolean whiteOnFocus, int focusFill) {
            this.radius = radius;
            this.whiteOnFocus = whiteOnFocus;
            this.focusFill = focusFill;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(density);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            rect.set(b);
            float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
            if (focused && whiteOnFocus) {
                fill.setShader(null);
                fill.setColor(focusFill);
                c.drawRoundRect(rect, r, r, fill);
                return;
            }
            if (shader == null || shaderStamp != stamp) shade();
            fill.setColor(0xFFFFFFFF);
            fill.setShader(shader);
            c.drawRoundRect(rect, r, r, fill);
            rim.setColor(activated ? 0x6BFFFFFF : rim());
            rect.inset(rim.getStrokeWidth() / 2f, rim.getStrokeWidth() / 2f);
            c.drawRoundRect(rect, r, r, rim);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            shade();
        }

        private void shade() {
            Rect b = getBounds();
            shader = new LinearGradient(0, b.top, 0, b.bottom,
                    activated ? topActive() : top(), activated ? bottomActive() : bottom(), Shader.TileMode.CLAMP);
            shaderStamp = stamp;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean f = false, a = false;
            for (int s : state) {
                if (s == android.R.attr.state_focused) f = true;
                if (s == android.R.attr.state_activated) a = true;
            }
            if (f == focused && a == activated) return false;
            focused = f;
            if (a != activated) {
                activated = a;
                shade();
            }
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
