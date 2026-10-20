package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the tvOS style band behind the first (dock) row and adds a gap below it. The band is the
 * launcher's glass (see {@link Glass}): a translucent white sheen under a thin rim.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** Where the band's sheen was last laid out, so it is rebuilt only when the band or the glass changes. */
    private float sheenTop = Float.NaN, sheenBottom = Float.NaN;
    private int sheenStamp = -1;

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        band.setColor(0xFFFFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
    }

    /** The dock follows the grid's column count. */
    void setDockSize(int dockSize) {
        this.dockSize = dockSize;
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
        float firstLeft = -1, cell = 0, cardW = 0;
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
            if (pos == 0) {
                firstLeft = l;
                cell = child.getWidth();
                cardW = card.getWidth();
            }
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        // Fewer apps than slots: the band still spans every slot, the last ones left empty.
        if (firstLeft >= 0) rect.right = Math.max(rect.right, firstLeft + (dockSize - 1) * cell + cardW);
        float pad = 14 * density;
        rect.inset(-pad, -pad);
        float radius = 26 * density;

        if (rect.top != sheenTop || rect.bottom != sheenBottom || sheenStamp != Glass.stamp()) {
            sheenTop = rect.top;
            sheenBottom = rect.bottom;
            sheenStamp = Glass.stamp();
            band.setShader(Glass.sheen(rect));
        }
        edge.setColor(Glass.rim());
        c.drawRoundRect(rect, radius, radius, band);
        rect.inset(density / 2f, density / 2f);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
