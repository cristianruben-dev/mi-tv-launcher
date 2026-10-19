package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the tvOS style band behind the first (dock) row and adds a gap below it. The band is either
 * solid or frosted glass: a blurred slice of the wallpaper under a translucent sheen.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final ViewGroup root;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final RectF wallRect = new RectF();
    private final Rect offset = new Rect();
    private final Path clip = new Path();
    private boolean blur;
    private Bitmap blurred;
    /** Size of the full wallpaper the blurred copy was made from, for the same centre-crop. */
    private int wallW, wallH;

    DockDecoration(int dockSize, float density, ViewGroup root) {
        this.dockSize = dockSize;
        this.density = density;
        this.root = root;
        band.setColor(0xFF141416);
        frost.setColor(0x24FFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x1FFFFFFF);
    }

    void setBlur(boolean blur) {
        this.blur = blur;
        edge.setColor(blur ? 0x40FFFFFF : 0x1FFFFFFF);
    }

    /** The blurred wallpaper and the size of the original it was made from, or null for no wallpaper. */
    void setBlurSource(Bitmap blurred, int wallW, int wallH) {
        this.blurred = blurred;
        this.wallW = wallW;
        this.wallH = wallH;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        rect.inset(-pad, -pad);
        float radius = 26 * density;

        if (!blur) {
            c.drawRoundRect(rect, radius, radius, band);
        } else {
            if (blurred != null && wallW > 0 && wallH > 0) drawBlurredWallpaper(c, parent, radius);
            c.drawRoundRect(rect, radius, radius, frost);
        }
        c.drawRoundRect(rect, radius, radius, edge);
    }

    /** Draws the part of the blurred wallpaper that sits behind the band, centre-cropped like the wallpaper. */
    private void drawBlurredWallpaper(Canvas c, RecyclerView parent, float radius) {
        int rw = root.getWidth(), rh = root.getHeight();
        if (rw == 0 || rh == 0) return;
        offset.set(0, 0, 0, 0);
        root.offsetDescendantRectToMyCoords(parent, offset);   // grid origin in root coordinates
        float scale = Math.max(rw / (float) wallW, rh / (float) wallH);
        float w = wallW * scale, h = wallH * scale;
        float left = (rw - w) / 2f - offset.left, top = (rh - h) / 2f - offset.top;
        wallRect.set(left, top, left + w, top + h);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawBitmap(blurred, null, wallRect, blurPaint);
        c.restore();
    }
}
