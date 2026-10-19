package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * The menu for a long press on a tile: open, dock, arrange, hide, app info, uninstall. A compact glass
 * dropdown like the Inputs menu, hanging below the tile (or above it when there is no room), over the
 * blurred home screen with the pressed tile kept sharp.
 */
final class AppMenu {

    interface Host {
        void openApp(AppAdapter.Entry e);

        void toggleDock(AppAdapter.Entry e, boolean inDock);

        void hideApp(AppAdapter.Entry e);

        /** Opens Settings → Apps with this app picked up, ready to move. */
        void arrangeApps(AppAdapter.Entry e);

        void appInfo(AppAdapter.Entry e);

        void uninstall(AppAdapter.Entry e);

        /** Blurs the home screen behind the menu, keeping {@code sharp} (the pressed tile) in focus. */
        void showBackdrop(View sharp);

        void hideBackdrop();

        /** The blurred home screen behind the menu, for its glass. */
        Frost.Source menuFrost();
    }

    private final Activity act;
    private final Host host;
    private final AppAdapter.Entry entry;
    private final boolean inDock;
    private final View card;
    private PopupWindow popup;

    AppMenu(Activity act, Host host, AppAdapter.Entry entry, boolean inDock, View card) {
        this.act = act;
        this.host = host;
        this.entry = entry;
        this.inDock = inDock;
        this.card = card;
    }

    void show() {
        float density = act.getResources().getDisplayMetrics().density;
        View root = act.findViewById(R.id.root);
        View panel = act.getLayoutInflater().inflate(R.layout.popup_app_menu, (ViewGroup) root, false);
        ((TextView) panel.findViewById(R.id.appName)).setText(entry.label);

        LinearLayout rows = panel.findViewById(R.id.rows);
        addRow(rows, R.drawable.ic_open, R.string.menu_open, () -> host.openApp(entry));
        addRow(rows, R.drawable.ic_dock, inDock ? R.string.menu_remove_from_dock : R.string.menu_add_to_dock,
                () -> host.toggleDock(entry, inDock));
        if (!inDock) addRow(rows, R.drawable.ic_arrange, R.string.menu_arrange, () -> host.arrangeApps(entry));
        addRow(rows, R.drawable.ic_hide, R.string.menu_hide, () -> host.hideApp(entry));
        addRow(rows, R.drawable.ic_info, R.string.menu_info, () -> host.appInfo(entry));
        if (!entry.system) addRow(rows, R.drawable.ic_delete, R.string.menu_uninstall, () -> host.uninstall(entry));
        View first = rows.getChildAt(0), last = rows.getChildAt(rows.getChildCount() - 1);
        first.setNextFocusUpId(first.getId());
        last.setNextFocusDownId(last.getId());

        int width = (int) (280 * density);
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        int height = panel.getMeasuredHeight();

        popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(host::hideBackdrop);

        // Below the tile as it looks now (scaled up by focus), or above it when it would run off screen.
        int[] at = new int[2];
        card.getLocationInWindow(at);
        float scale = card.getScaleX();
        int tileW = (int) (card.getWidth() * scale), tileH = (int) (card.getHeight() * scale);
        int gap = (int) (14 * density), margin = (int) (24 * density);
        int screenW = root.getWidth(), screenH = root.getHeight();
        int x = at[0] + tileW / 2 - width / 2;
        x = Math.max(margin, Math.min(x, screenW - width - margin));
        int y = at[1] + tileH + gap;
        boolean below = y + height <= screenH - margin;
        if (!below) y = Math.max(margin, at[1] - gap - height);

        host.showBackdrop(card);
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y);

        // Drop in like the Inputs menu: fade and unfold from the edge next to the tile.
        panel.setAlpha(0f);
        panel.setPivotY(below ? 0f : height);
        panel.setScaleY(0.92f);
        panel.setTranslationY((below ? -8 : 8) * density);
        Frost.apply(panel, host.menuFrost(), 20 * density, 0x40000000);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(Motion.ms(220))
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(panel::invalidate).start();   // the glass follows where the panel settled
        first.requestFocus();
    }

    private void addRow(LinearLayout rows, int icon, int title, Runnable action) {
        View row = act.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setId(View.generateViewId());
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
