package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The launcher's settings modal: a glass panel in the style of the Inputs dropdown, with a sidebar of
 * sections on the left. Moving through the sidebar switches the section; Right enters it, Left comes back.
 */
final class SettingsDialog {

    interface Host {
        /** Every app on the home screen, alphabetical. */
        List<AppAdapter.Entry> allApps();

        void onDockAppsChanged();

        void onDockStyleChanged();

        void onWallpaperChanged();

        /** The language changed: the host recreates itself and may reopen this section. */
        void onLanguageChanged();

        void openTvSettings();
    }

    static final int SECTION_LANGUAGE = 0;
    static final int SECTION_DOCK = 1;
    static final int SECTION_APPEARANCE = 2;
    static final int SECTION_WALLPAPER = 3;
    static final int SECTION_TV = 4;

    private static final int[] SECTION_TITLES = {R.string.section_language, R.string.section_dock,
            R.string.section_appearance, R.string.section_wallpaper, R.string.section_tv_settings};
    private static final int[] SECTION_ICONS = {R.drawable.ic_language, R.drawable.ic_dock,
            R.drawable.ic_appearance, R.drawable.ic_wallpaper, R.drawable.ic_settings};

    private final Activity act;
    private final Host host;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> sectionRows = new ArrayList<>();
    private Dialog dialog;
    private LinearLayout rows;
    private TextView sectionTitle;
    private TextView sectionHint;
    private ScrollView scroll;
    private int current = -1;
    /** Bumped on every section switch so late thumbnail loads land only in the section that asked. */
    private int generation;

    SettingsDialog(Activity act, Host host) {
        this.act = act;
        this.host = host;
        this.density = act.getResources().getDisplayMetrics().density;
    }

    void show(int startSection) {
        dialog = new Dialog(act, R.style.SettingsDialog);
        View panel = act.getLayoutInflater().inflate(R.layout.dialog_settings, null, false);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        // Inflated without a parent the layout's own size is lost, so size the window to it.
        if (w != null) w.setLayout((int) (760 * density), (int) (440 * density));

        rows = panel.findViewById(R.id.rows);
        sectionTitle = panel.findViewById(R.id.sectionTitle);
        sectionHint = panel.findViewById(R.id.sectionHint);
        scroll = panel.findViewById(R.id.scroll);
        LinearLayout sections = panel.findViewById(R.id.sections);

        for (int i = 0; i < SECTION_TITLES.length; i++) {
            final int section = i;
            View row = newRow(sections, R.drawable.sidebar_row_bg);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(SECTION_ICONS[i]);
            ((TextView) row.findViewById(R.id.title)).setText(SECTION_TITLES[i]);
            row.setOnFocusChangeListener((v, focused) -> {
                if (focused) select(section);
            });
            row.setOnClickListener(v -> {
                if (section == SECTION_TV) {
                    dialog.dismiss();
                    host.openTvSettings();
                    return;
                }
                if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
            });
            sections.addView(row);
            sectionRows.add(row);
        }
        View first = sectionRows.get(0), last = sectionRows.get(sectionRows.size() - 1);
        first.setNextFocusUpId(first.getId());
        last.setNextFocusDownId(last.getId());

        dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));
        dialog.show();

        // Open like the Inputs dropdown: fade in and settle from a slightly smaller size.
        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();

        int start = Math.max(0, Math.min(startSection, sectionRows.size() - 1));
        select(start);
        sectionRows.get(start).requestFocus();
    }

    // ---- sections ------------------------------------------------------------------------------

    private void select(int section) {
        if (section == current) return;
        current = section;
        generation++;
        for (int i = 0; i < sectionRows.size(); i++) sectionRows.get(i).setActivated(i == section);
        sectionTitle.setText(SECTION_TITLES[section]);
        rows.removeAllViews();
        scroll.scrollTo(0, 0);
        switch (section) {
            case SECTION_LANGUAGE:
                buildLanguage();
                break;
            case SECTION_DOCK:
                buildDock();
                break;
            case SECTION_APPEARANCE:
                buildAppearance();
                break;
            case SECTION_WALLPAPER:
                buildWallpaper();
                break;
            default:
                buildTv();
                break;
        }
        wireFocus();
    }

    /** Right from the sidebar enters the section; Left from a row returns to its sidebar entry. */
    private void wireFocus() {
        int n = rows.getChildCount();
        if (n == 0) return;
        View firstRow = rows.getChildAt(0), lastRow = rows.getChildAt(n - 1);
        for (View s : sectionRows) s.setNextFocusRightId(firstRow.getId());
        int back = sectionRows.get(current).getId();
        for (int i = 0; i < n; i++) {
            View r = rows.getChildAt(i);
            r.setNextFocusLeftId(back);
            r.setNextFocusRightId(r.getId());
        }
        firstRow.setNextFocusUpId(firstRow.getId());
        lastRow.setNextFocusDownId(lastRow.getId());
    }

    private void buildLanguage() {
        sectionHint.setText(R.string.language_hint);
        String chosen = Prefs.language(act);
        String[] codes = {Prefs.LANG_SYSTEM, "en", "es"};
        int[] names = {R.string.language_system, R.string.language_en, R.string.language_es};
        for (int i = 0; i < codes.length; i++) {
            final String code = codes[i];
            View row = addOption(act.getString(names[i]), code.equals(chosen));
            row.setOnClickListener(v -> {
                if (code.equals(Prefs.language(act))) return;
                Prefs.setLanguage(act, code);
                dialog.dismiss();
                host.onLanguageChanged();
            });
        }
    }

    private void buildDock() {
        List<AppAdapter.Entry> apps = host.allApps();
        Set<String> installed = new HashSet<>();
        for (AppAdapter.Entry e : apps) installed.add(e.pkg);
        final Map<String, TextView> badges = new HashMap<>();

        Runnable refresh = () -> {
            List<String> dock = installedDock(installed);
            sectionHint.setText(act.getString(R.string.dock_hint, dock.size()));
            for (Map.Entry<String, TextView> b : badges.entrySet()) {
                int idx = dock.indexOf(b.getKey());
                b.getValue().setVisibility(idx >= 0 ? View.VISIBLE : View.GONE);
                b.getValue().setText(String.valueOf(idx + 1));
            }
        };

        for (AppAdapter.Entry e : apps) {
            View row = newRow(rows, R.drawable.input_row_bg);
            ImageView icon = row.findViewById(R.id.icon);
            icon.setImageTintList(null);
            icon.setImageDrawable(e.icon);
            sizeIcon(icon, 28, 28);
            ((TextView) row.findViewById(R.id.title)).setText(e.label);
            badges.put(e.pkg, row.findViewById(R.id.badge));
            row.setOnClickListener(v -> {
                List<String> dock = installedDock(installed);
                if (dock.contains(e.pkg)) {
                    dock.remove(e.pkg);
                } else if (dock.size() >= Prefs.DOCK_MAX) {
                    Toast.makeText(act, R.string.dock_full, Toast.LENGTH_SHORT).show();
                    return;
                } else {
                    dock.add(e.pkg);
                }
                Prefs.setDockApps(act, dock);
                refresh.run();
                host.onDockAppsChanged();
            });
            rows.addView(row);
        }

        View reset = newRow(rows, R.drawable.input_row_bg);
        ((ImageView) reset.findViewById(R.id.icon)).setImageResource(R.drawable.ic_dock);
        ((TextView) reset.findViewById(R.id.title)).setText(R.string.dock_reset);
        reset.setOnClickListener(v -> {
            Prefs.resetDockApps(act);
            refresh.run();
            host.onDockAppsChanged();
        });
        rows.addView(reset);
        refresh.run();
    }

    /** The dock as saved, minus apps that are no longer installed. */
    private List<String> installedDock(Set<String> installed) {
        List<String> dock = new ArrayList<>();
        for (String p : Prefs.dockApps(act)) if (installed.contains(p) && !dock.contains(p)) dock.add(p);
        return dock;
    }

    private void buildAppearance() {
        sectionHint.setText(R.string.appearance_hint);
        String[] styles = {Prefs.DOCK_SOLID, Prefs.DOCK_BLUR};
        int[] names = {R.string.dock_style_solid, R.string.dock_style_blur};
        final List<View> options = new ArrayList<>();
        for (int i = 0; i < styles.length; i++) {
            final String style = styles[i];
            View row = addOption(act.getString(names[i]), style.equals(Prefs.dockStyle(act)));
            options.add(row);
            row.setOnClickListener(v -> {
                Prefs.setDockStyle(act, style);
                for (int j = 0; j < options.size(); j++) setChecked(options.get(j), styles[j].equals(style));
                host.onDockStyleChanged();
            });
        }
    }

    private void buildWallpaper() {
        List<String> files = Wallpapers.list(act);
        sectionHint.setText(files.isEmpty()
                ? act.getString(R.string.wallpaper_hint) + " " + act.getString(R.string.wallpaper_empty)
                : act.getString(R.string.wallpaper_hint));
        final List<String> keys = new ArrayList<>();
        final List<View> options = new ArrayList<>();
        keys.add("");
        options.add(addOption(act.getString(R.string.wallpaper_none), Prefs.wallpaper(act).isEmpty()));
        for (String f : files) {
            keys.add(f);
            View row = addOption(Wallpapers.title(f), f.equals(Prefs.wallpaper(act)));
            ImageView thumb = row.findViewById(R.id.icon);
            thumb.setImageTintList(null);
            thumb.setImageDrawable(null);
            thumb.setBackgroundColor(0x33FFFFFF);
            sizeIcon(thumb, 48, 27);
            roundCorners(thumb, 5);
            loadThumbnail(thumb, f);
            options.add(row);
        }
        for (int i = 0; i < options.size(); i++) {
            final String key = keys.get(i);
            options.get(i).setOnClickListener(v -> {
                Prefs.setWallpaper(act, key);
                for (int j = 0; j < options.size(); j++) setChecked(options.get(j), keys.get(j).equals(key));
                host.onWallpaperChanged();
            });
        }
    }

    private void buildTv() {
        sectionHint.setText(R.string.tv_settings_hint);
        View row = newRow(rows, R.drawable.input_row_bg);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(R.drawable.ic_settings);
        ((TextView) row.findViewById(R.id.title)).setText(R.string.open_tv_settings);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            host.openTvSettings();
        });
        rows.addView(row);
    }

    // ---- rows ------------------------------------------------------------------------------------

    private View newRow(ViewGroup parent, int background) {
        View row = act.getLayoutInflater().inflate(R.layout.item_setting, parent, false);
        row.setBackgroundResource(background);
        row.setId(View.generateViewId());
        return row;
    }

    /** A choice row with a check mark when selected; no leading icon. */
    private View addOption(String title, boolean checked) {
        View row = newRow(rows, R.drawable.input_row_bg);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        setChecked(row, checked);
        rows.addView(row);
        return row;
    }

    private static void setChecked(View row, boolean checked) {
        row.findViewById(R.id.check).setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
    }

    private void sizeIcon(ImageView icon, int wDp, int hDp) {
        ViewGroup.LayoutParams lp = icon.getLayoutParams();
        lp.width = (int) (wDp * density);
        lp.height = (int) (hDp * density);
        icon.setLayoutParams(lp);
        icon.setVisibility(View.VISIBLE);
    }

    private void roundCorners(View v, int radiusDp) {
        final float r = radiusDp * density;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    /** Decodes a wallpaper preview off the main thread. */
    private void loadThumbnail(ImageView target, String file) {
        final int gen = generation;
        new Thread(() -> {
            Bitmap bmp = Wallpapers.thumbnail(act, file, 160, 90);
            handler.post(() -> {
                if (gen == generation && bmp != null) target.setImageBitmap(bmp);
            });
        }, "wallpaper-thumb").start();
    }
}
