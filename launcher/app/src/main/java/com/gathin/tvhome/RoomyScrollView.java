package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * A ScrollView that keeps its fading edge length clear around the focused row when it scrolls to it, so
 * the last rows never sit against the edge, without drawing the fade itself.
 */
public class RoomyScrollView extends ScrollView {

    public RoomyScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        return 0f;
    }

    @Override
    protected float getBottomFadingEdgeStrength() {
        return 0f;
    }
}
