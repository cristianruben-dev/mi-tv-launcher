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
 * Clips the grid like a plain FrameLayout but fades tiles out over its top edge instead of cutting
 * them off under the top bar. The fade only costs an offscreen layer while the grid is scrolled.
 */
public class FadeFrame extends FrameLayout {

    private final Paint fade = new Paint();
    private final float fadeLength;
    private boolean active;

    public FadeFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        fadeLength = 72f * getResources().getDisplayMetrics().density;
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        fade.setShader(new LinearGradient(0, 0, 0, fadeLength, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        setWillNotDraw(false);
    }

    /** Turns the fade on while tiles can sit under the top edge. */
    void setFadeActive(boolean on) {
        if (active == on) return;
        active = on;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (!active) {
            super.dispatchDraw(canvas);
            return;
        }
        int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        super.dispatchDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), fadeLength, fade);
        canvas.restoreToCount(save);
    }
}
