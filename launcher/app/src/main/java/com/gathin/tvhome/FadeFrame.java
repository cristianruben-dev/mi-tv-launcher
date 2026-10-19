package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Clips a scrolling list like a plain FrameLayout but fades it out over its top edge (and, for the
 * settings lists, its bottom edge) instead of cutting rows off. Each fade only shows while there is more
 * of the list that way, and only then costs an offscreen layer.
 */
public class FadeFrame extends FrameLayout {

    private final Paint topFade = new Paint();
    private final Paint bottomFade = new Paint();
    private final float fadeLength;
    private boolean top, bottom;

    public FadeFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        fadeLength = 72f * getResources().getDisplayMetrics().density;
        topFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        topFade.setShader(new LinearGradient(0, 0, 0, fadeLength, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        bottomFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        setWillNotDraw(false);
    }

    /** Turns the top fade on while rows can sit under the top edge. */
    void setFadeActive(boolean on) {
        setFades(on, bottom);
    }

    /** Turns each edge's fade on while there is more of the list beyond it. */
    void setFades(boolean top, boolean bottom) {
        if (this.top == top && this.bottom == bottom) return;
        this.top = top;
        this.bottom = bottom;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bottomFade.setShader(new LinearGradient(0, h - fadeLength, 0, h, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (!top && !bottom) {
            super.dispatchDraw(canvas);
            return;
        }
        int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        super.dispatchDraw(canvas);
        if (top) canvas.drawRect(0, 0, getWidth(), fadeLength, topFade);
        if (bottom) canvas.drawRect(0, getHeight() - fadeLength, getWidth(), getHeight(), bottomFade);
        canvas.restoreToCount(save);
    }
}
