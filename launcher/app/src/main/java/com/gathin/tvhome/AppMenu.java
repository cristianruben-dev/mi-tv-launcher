package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The menu for a long press on a tile: open, dock, hide, arrange, app info, uninstall. A glass panel like
 * the Inputs dropdown over the blurred home screen.
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

        void showBackdrop();

        void hideBackdrop();
    }

    private final Activity act;
    private final Host host;
    private final AppAdapter.Entry entry;
    private final boolean inDock;
    private Dialog dialog;

    AppMenu(Activity act, Host host, AppAdapter.Entry entry, boolean inDock) {
        this.act = act;
        this.host = host;
        this.entry = entry;
        this.inDock = inDock;
    }

    void show() {
        float density = act.getResources().getDisplayMetrics().density;
        dialog = new Dialog(act, R.style.SettingsDialog);
        View panel = act.getLayoutInflater().inflate(R.layout.dialog_app_menu, null, false);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) w.setLayout((int) (360 * density), ViewGroup.LayoutParams.WRAP_CONTENT);

        ImageView art = panel.findViewById(R.id.art);
        art.setClipToOutline(true);   // round corners from the tile_bg background (the XML attribute needs API 31)
        if (entry.banner != null) art.setImageDrawable(entry.banner);
        else art.setImageDrawable(entry.icon);
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

        host.showBackdrop();
        dialog.setOnDismissListener(d -> host.hideBackdrop());
        dialog.show();

        panel.setAlpha(0f);
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.ms(220))
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        first.requestFocus();
    }

    private void addRow(LinearLayout rows, int icon, int title, Runnable action) {
        View row = act.getLayoutInflater().inflate(R.layout.item_setting, rows, false);
        row.setId(View.generateViewId());
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
