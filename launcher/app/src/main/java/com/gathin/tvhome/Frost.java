package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

/**
 * Frosted glass in the dock's style for any view: the blurred slice of the wallpaper that sits behind it,
 * under a translucent sheen and a thin rim. Focused views turn solid white like the rest of the launcher.
 * Used only with Settings → Performance → Blur on; otherwise views keep their solid backgrounds.
 */
final class Frost {

    /** Sheen and rim of the dock. */
    static final int SHEEN = 0x24FFFFFF, RIM = 0x40FFFFFF;

    private Frost() {
    }

    /**
     * What the glass blurs: a soft, small copy of a picture that covers the screen centre-cropped (the
     * wallpaper), or of the screen itself (a menu backdrop).
     */
    static final class Source {
        Bitmap soft;
        int fullW, fullH;

        void set(Bitmap soft, int fullW, int fullH) {
            this.soft = soft;
            this.fullW = fullW;
            this.fullH = fullH;
        }

        /** Same picture as another source. */
        void copy(Source other) {
            set(other.soft, other.fullW, other.fullH);
        }
    }

    /** A blurred copy for glass (see Blur.RADIUS_DP). */
    static Bitmap soften(Bitmap full) {
        if (full == null) return null;
        return Blur.scaledBlur(full);
    }

    /** Gives {@code v} a frosted background, or leaves its own when blur is off. */
    static void apply(View v, Source src, float radiusPx, int idleTint) {
        apply(v, src, radiusPx, idleTint, true);
    }

    /** As above; {@code whiteOnFocus} false keeps the glass when focused (the view draws its own focus edge). */
    static void apply(View v, Source src, float radiusPx, int idleTint, boolean whiteOnFocus) {
        if (!Prefs.blur(v.getContext())) return;
        v.setBackground(new Glass(v, src, radiusPx, idleTint, whiteOnFocus));
    }

    /** Redraws every frosted view under {@code v}, after its source changed or it moved. */
    static void invalidateAll(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateAll(g.getChildAt(i));
        }
    }

    /**
     * The glass itself. Focused: solid white. Activated (the open section, an app being moved): a brighter
     * sheen. Otherwise the blurred picture under the sheen.
     */
    private static final class Glass extends Drawable {
        private final View host;
        private final Source src;
        private final float radius;
        private final int idleTint;
        private final boolean whiteOnFocus;
        private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF pictureRect = new RectF();
        private final Path clip = new Path();
        private final int[] at = new int[2];
        private boolean focused, activated;

        Glass(View host, Source src, float radius, int idleTint, boolean whiteOnFocus) {
            this.host = host;
            this.src = src;
            this.radius = radius;
            this.idleTint = idleTint;
            this.whiteOnFocus = whiteOnFocus;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(host.getResources().getDisplayMetrics().density);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            rect.set(b);
            float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
            if (focused && whiteOnFocus) {
                fill.setColor(0xF5FFFFFF);
                c.drawRoundRect(rect, r, r, fill);
                return;
            }
            if (src.soft != null && src.fullW > 0 && src.fullH > 0) {
                DisplayMetrics dm = host.getResources().getDisplayMetrics();
                int sw = dm.widthPixels, sh = dm.heightPixels;
                host.getLocationOnScreen(at);
                float scale = Math.max(sw / (float) src.fullW, sh / (float) src.fullH);
                float w = src.fullW * scale, h = src.fullH * scale;
                float left = (sw - w) / 2f - at[0], top = (sh - h) / 2f - at[1];
                pictureRect.set(left, top, left + w, top + h);
                clip.reset();
                clip.addRoundRect(rect, r, r, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                c.drawBitmap(src.soft, null, pictureRect, picture);
                c.restore();
            }
            if (idleTint != 0) {
                fill.setColor(idleTint);
                c.drawRoundRect(rect, r, r, fill);
            }
            fill.setColor(activated ? 0x38FFFFFF : SHEEN);
            c.drawRoundRect(rect, r, r, fill);
            rim.setColor(activated ? 0x66FFFFFF : RIM);
            rect.inset(rim.getStrokeWidth() / 2f, rim.getStrokeWidth() / 2f);
            c.drawRoundRect(rect, r, r, rim);
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
            activated = a;
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
