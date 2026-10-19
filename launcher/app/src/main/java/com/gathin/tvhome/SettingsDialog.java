package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
        /** Shown apps, alphabetical. */
        List<AppAdapter.Entry> allApps();

        /** Every app including hidden ones, dock first, then in grid order. */
        List<AppAdapter.Entry> appsInGridOrder();

        /** Dock, hidden apps or order changed. */
        void onAppsChanged();

        void onColumnsChanged();

        void onDimChanged();

        void onWallpaperChanged();

        void onBlurChanged();

        void onAnimationsChanged();

        /** The language changed: the host recreates itself and may reopen this section. */
        void onLanguageChanged();

        void openTvSettings();

        void previewScreensaver();
    }

    static final int SECTION_LANGUAGE = 0;
    static final int SECTION_DOCK = 1;
    static final int SECTION_APPS = 2;
    static final int SECTION_PERSONALIZE = 3;
    static final int SECTION_WALLPAPER = 4;
    static final int SECTION_SCREENSAVER = 5;
    static final int SECTION_PERFORMANCE = 6;
    static final int SECTION_TV = 7;

    private static final int[] SECTION_TITLES = {R.string.section_language, R.string.section_dock,
            R.string.section_apps, R.string.section_personalize, R.string.section_wallpaper,
            R.string.section_screensaver, R.string.section_performance, R.string.section_tv_settings};
    private static final int[] SECTION_ICONS = {R.drawable.ic_language, R.drawable.ic_dock,
            R.drawable.ic_apps, R.drawable.ic_appearance, R.drawable.ic_wallpaper,
            R.drawable.ic_screensaver, R.drawable.ic_performance, R.drawable.ic_settings};

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
    /** Apps section: false shows and hides apps, true arranges them. */
    private boolean arranging;
    /** Apps section: the row picked up for moving, or null. */
    private View carried;
    /** App to pick up when the Apps section opens (from the long press menu's "Arrange"). */
    private String carryOnOpen;
    /**
     * True while a section's rows are replaced. Removing the focused row sends focus to the first sidebar
     * entry, and that focus change must not switch sections.
     */
    private boolean rebuilding;

    SettingsDialog(Activity act, Host host) {
        this.act = act;
        this.host = host;
        this.density = act.getResources().getDisplayMetrics().density;
    }

    /** Opens on a section; with {@code arrangePkg}, opens Apps in arrange mode with that app picked up. */
    void show(int startSection, String arrangePkg) {
        carryOnOpen = arrangePkg;
        arranging = arrangePkg != null;
        dialog = new Dialog(act, R.style.SettingsDialog);
        View panel = act.getLayoutInflater().inflate(R.layout.dialog_settings, null, false);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        // Inflated without a parent the layout's own size is lost, so size the window to it.
        if (w != null) w.setLayout((int) (780 * density), (int) (480 * density));

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
                if (focused && !rebuilding) select(section);
            });
            row.setOnClickListener(v -> {
                if (section == SECTION_TV) {
                    dialog.dismiss();
                    host.openTvSettings();
                    return;
                }
                View first = firstFocusableRow();
                if (first != null) first.requestFocus();
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
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.ms(240))
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();

        int start = Math.max(0, Math.min(startSection, sectionRows.size() - 1));
        select(start);
        if (carried != null) carried.requestFocus();
        else sectionRows.get(start).requestFocus();
    }

    // ---- sections ------------------------------------------------------------------------------

    private void select(int section) {
        if (section == current) return;
        current = section;
        generation++;
        carried = null;
        for (int i = 0; i < sectionRows.size(); i++) sectionRows.get(i).setActivated(i == section);
        rebuild();
    }

    /** Redraws the open section, e.g. after switching the Apps mode. */
    private void rebuild() {
        rebuilding = true;
        sectionTitle.setText(SECTION_TITLES[current]);
        rows.removeAllViews();
        scroll.scrollTo(0, 0);
        switch (current) {
            case SECTION_LANGUAGE:
                buildLanguage();
                break;
            case SECTION_DOCK:
                buildDock();
                break;
            case SECTION_APPS:
                buildApps();
                break;
            case SECTION_PERSONALIZE:
                buildPersonalize();
                break;
            case SECTION_WALLPAPER:
                buildWallpaper();
                break;
            case SECTION_SCREENSAVER:
                buildScreensaver();
                break;
            case SECTION_PERFORMANCE:
                buildPerformance();
                break;
            default:
                buildTv();
                break;
        }
        wireFocus();
        rebuilding = false;
    }

    private View firstFocusableRow() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i).isFocusable()) return rows.getChildAt(i);
        }
        return null;
    }

    /** Right from the sidebar enters the section; Left from a row returns to its sidebar entry. */
    private void wireFocus() {
        List<View> focusable = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i).isFocusable()) focusable.add(rows.getChildAt(i));
        }
        if (focusable.isEmpty()) return;
        View firstRow = focusable.get(0), lastRow = focusable.get(focusable.size() - 1);
        for (View s : sectionRows) s.setNextFocusRightId(firstRow.getId());
        int back = sectionRows.get(current).getId();
        for (View r : focusable) {
            r.setNextFocusLeftId(back);
            r.setNextFocusRightId(r.getId());
            r.setNextFocusUpId(View.NO_ID);
            r.setNextFocusDownId(View.NO_ID);
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
        final int slots = Prefs.columns(act);
        Set<String> installed = new HashSet<>();
        for (AppAdapter.Entry e : apps) installed.add(e.pkg);
        final Map<String, TextView> badges = new HashMap<>();

        Runnable refresh = () -> {
            List<String> dock = installedDock(installed, slots);
            sectionHint.setText(act.getString(R.string.dock_hint, dock.size(), slots));
            for (Map.Entry<String, TextView> b : badges.entrySet()) {
                int idx = dock.indexOf(b.getKey());
                b.getValue().setVisibility(idx >= 0 ? View.VISIBLE : View.GONE);
                b.getValue().setText(String.valueOf(idx + 1));
            }
        };

        for (AppAdapter.Entry e : apps) {
            View row = appRow(e);
            badges.put(e.pkg, row.findViewById(R.id.badge));
            row.setOnClickListener(v -> {
                List<String> dock = installedDock(installed, slots);
                if (dock.contains(e.pkg)) {
                    dock.remove(e.pkg);
                } else if (dock.size() >= slots) {
                    Toast.makeText(act, act.getString(R.string.dock_full, slots), Toast.LENGTH_SHORT).show();
                    return;
                } else {
                    dock.add(e.pkg);
                }
                Prefs.setDockApps(act, dock);
                refresh.run();
                host.onAppsChanged();
            });
        }

        View reset = addAction(R.drawable.ic_dock, act.getString(R.string.dock_reset));
        reset.setOnClickListener(v -> {
            Prefs.resetDockApps(act);
            refresh.run();
            host.onAppsChanged();
        });
        refresh.run();
    }

    /** The dock as saved, minus apps that are no longer installed or shown, up to one per column. */
    private List<String> installedDock(Set<String> installed, int slots) {
        List<String> dock = new ArrayList<>();
        for (String p : Prefs.dockApps(act)) {
            if (installed.contains(p) && !dock.contains(p) && dock.size() < slots) dock.add(p);
        }
        return dock;
    }

    // ---- apps: show/hide and arrange ---------------------------------------------------------

    private void buildApps() {
        sectionHint.setText(arranging ? R.string.apps_arrange_hint : R.string.apps_visibility_hint);
        View showHide = addOption(act.getString(R.string.apps_mode_visibility), !arranging);
        View arrange = addOption(act.getString(R.string.apps_mode_arrange), arranging);
        showHide.setOnClickListener(v -> switchAppsMode(false));
        arrange.setOnClickListener(v -> switchAppsMode(true));
        addHeader(act.getString(arranging ? R.string.apps_header_order : R.string.apps_header_visibility));

        // Shown apps come first in grid order, so the first row of them is the dock, which is managed
        // in "Dock apps"; hidden apps follow.
        List<AppAdapter.Entry> apps = host.appsInGridOrder();
        Set<String> hidden = Prefs.hiddenApps(act);
        int seen = 0;
        for (AppAdapter.Entry e : apps) {
            boolean isHidden = hidden.contains(e.pkg);
            if (!isHidden && seen++ < Prefs.columns(act)) continue;   // dock apps
            if (arranging && isHidden) continue;                 // hidden apps have no place in the grid
            View row = appRow(e);
            row.setTag(e.pkg);
            if (arranging) {
                row.setOnClickListener(v -> toggleCarry(row));
                row.setOnKeyListener((v, keyCode, event) -> carryKeys(row, keyCode, event));
                if (e.pkg.equals(carryOnOpen)) {
                    carryOnOpen = null;
                    pickUp(row);
                }
            } else {
                setChecked(row, !isHidden);
                row.setAlpha(isHidden ? 0.55f : 1f);
                row.setOnClickListener(v -> {
                    Set<String> h = Prefs.hiddenApps(act);
                    boolean nowHidden = !h.contains(e.pkg);
                    if (nowHidden) h.add(e.pkg);
                    else h.remove(e.pkg);
                    Prefs.setHiddenApps(act, h);
                    setChecked(row, !nowHidden);
                    row.setAlpha(nowHidden ? 0.55f : 1f);
                    host.onAppsChanged();
                });
            }
        }
        if (arranging) {
            View reset = addAction(R.drawable.ic_arrange, act.getString(R.string.apps_reset_order));
            reset.setOnClickListener(v -> {
                Prefs.resetAppOrder(act);
                host.onAppsChanged();
                rebuild();
                View f = firstFocusableRow();
                if (f != null) f.requestFocus();
            });
        }
    }

    private void switchAppsMode(boolean arrange) {
        if (arranging == arrange) return;
        arranging = arrange;
        carried = null;
        generation++;
        rebuild();
        // Stay on the mode row just chosen.
        View target = rows.getChildAt(arrange ? 1 : 0);
        if (target != null) target.requestFocus();
    }

    private void toggleCarry(View row) {
        if (carried == row) drop();
        else {
            if (carried != null) drop();
            pickUp(row);
        }
    }

    private void pickUp(View row) {
        carried = row;
        row.setBackgroundResource(R.drawable.sidebar_row_bg);
        row.setActivated(true);
        TextView badge = row.findViewById(R.id.badge);
        badge.setText("↕");
        badge.setVisibility(View.VISIBLE);
        row.animate().scaleX(1.02f).scaleY(1.02f).setDuration(Motion.ms(160)).start();
        sectionHint.setText(R.string.apps_carry_hint);
    }

    private void drop() {
        if (carried == null) return;
        View row = carried;
        carried = null;
        row.setActivated(false);
        row.setBackgroundResource(R.drawable.input_row_bg);
        row.findViewById(R.id.badge).setVisibility(View.GONE);
        row.animate().scaleX(1f).scaleY(1f).setDuration(Motion.ms(160)).start();
        sectionHint.setText(R.string.apps_arrange_hint);
    }

    /** While an app is carried: Up/Down move it, Back or OK drops it. */
    private boolean carryKeys(View row, int keyCode, KeyEvent event) {
        if (carried != row) return false;
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) drop();
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        int idx = rows.indexOfChild(row);
        int to = keyCode == KeyEvent.KEYCODE_DPAD_UP ? idx - 1 : idx + 1;
        View neighbour = to >= 0 && to < rows.getChildCount() ? rows.getChildAt(to) : null;
        if (neighbour == null || !(neighbour.getTag() instanceof String)) return true;   // ends of the app list
        // Move the neighbour to the other side rather than the carried row: the carried row keeps focus.
        rows.removeView(neighbour);
        rows.addView(neighbour, idx);
        row.getParent().requestChildFocus(row, row);   // keep the moved row in view
        wireFocus();
        saveOrder();
        return true;
    }

    private void saveOrder() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            Object tag = rows.getChildAt(i).getTag();
            if (tag instanceof String) order.add((String) tag);
        }
        Prefs.setAppOrder(act, order);
        host.onAppsChanged();
    }

    // ---- look ------------------------------------------------------------------------------------

    private void buildPersonalize() {
        sectionHint.setText(R.string.personalize_hint);
        addHeader(act.getString(R.string.columns_header));
        final List<View> colRows = new ArrayList<>();
        for (int c : Prefs.COLUMN_CHOICES) {
            View row = addOption(act.getString(R.string.columns_option, c), c == Prefs.columns(act));
            colRows.add(row);
            row.setOnClickListener(v -> {
                Prefs.setColumns(act, c);
                for (int j = 0; j < colRows.size(); j++) setChecked(colRows.get(j), Prefs.COLUMN_CHOICES[j] == c);
                host.onColumnsChanged();
            });
        }
        addHeader(act.getString(R.string.dim_header));
        final List<View> dimRows = new ArrayList<>();
        for (int d : Prefs.DIM_CHOICES) {
            String title = d == 0 ? act.getString(R.string.dim_none) : act.getString(R.string.dim_option, d);
            View row = addOption(title, d == Prefs.wallpaperDim(act));
            dimRows.add(row);
            row.setOnClickListener(v -> {
                Prefs.setWallpaperDim(act, d);
                for (int j = 0; j < dimRows.size(); j++) setChecked(dimRows.get(j), Prefs.DIM_CHOICES[j] == d);
                host.onDimChanged();
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

    // ---- screensaver -----------------------------------------------------------------------------

    private void buildScreensaver() {
        boolean granted = SystemTweaks.canWrite(act);
        sectionHint.setText(granted ? act.getString(R.string.screensaver_hint)
                : act.getString(R.string.screensaver_hint) + " " + act.getString(R.string.needs_grant));
        View preview = addAction(R.drawable.ic_open, act.getString(R.string.screensaver_preview));
        preview.setOnClickListener(v -> {
            dialog.dismiss();
            host.previewScreensaver();
        });
        addHeader(act.getString(R.string.screensaver_header));
        int current = SystemTweaks.screensaverMinutes(act);
        final List<View> options = new ArrayList<>();
        for (int m : SystemTweaks.SCREENSAVER_MINUTES) {
            String title = m == 0 ? act.getString(R.string.screensaver_off)
                    : m < 60 ? act.getString(R.string.minutes_option, m) : act.getString(R.string.hour_option);
            View row = addOption(title, m == current || (m != 0 && isClosest(m, current)));
            options.add(row);
            row.setOnClickListener(v -> {
                if (!SystemTweaks.setScreensaver(act, m)) {
                    Toast.makeText(act, R.string.needs_grant, Toast.LENGTH_LONG).show();
                    return;
                }
                for (int j = 0; j < options.size(); j++) setChecked(options.get(j), SystemTweaks.SCREENSAVER_MINUTES[j] == m);
            });
        }
    }

    /** The offered delay closest to a value set elsewhere (e.g. 20 minutes in the TV's settings). */
    private static boolean isClosest(int option, int current) {
        if (current <= 0) return false;
        int best = -1, bestDiff = Integer.MAX_VALUE;
        for (int m : SystemTweaks.SCREENSAVER_MINUTES) {
            if (m == 0) continue;
            int d = Math.abs(m - current);
            if (d < bestDiff) {
                bestDiff = d;
                best = m;
            }
        }
        return best == option && best != current;
    }

    // ---- performance -----------------------------------------------------------------------------

    private void buildPerformance() {
        sectionHint.setText(R.string.performance_hint);
        View memory = act.getLayoutInflater().inflate(R.layout.item_memory, rows, false);
        memory.setId(View.generateViewId());
        rows.addView(memory);
        Runnable refresh = () -> showMemory(memory);
        refresh.run();
        memory.setOnClickListener(v -> refresh.run());

        View free = addAction(R.drawable.ic_performance, act.getString(R.string.free_memory));
        // A flag rather than setEnabled(false): a disabled row drops focus, which would jump to the sidebar.
        final boolean[] busy = {false};
        free.setOnClickListener(v -> {
            if (busy[0]) return;
            busy[0] = true;
            final long before = SystemTweaks.memory(act).availMem;
            new Thread(() -> {
                SystemTweaks.killBackgroundApps(act);
                handler.postDelayed(() -> {
                    long after = SystemTweaks.memory(act).availMem;
                    long freed = Math.max(0, after - before);
                    Toast.makeText(act, act.getString(R.string.memory_freed,
                            Formatter.formatShortFileSize(act, freed)), Toast.LENGTH_SHORT).show();
                    refresh.run();
                    busy[0] = false;
                }, 1200);
            }, "free-memory").start();
        });

        addHeader(act.getString(R.string.launcher_header));
        View anim = addOption(act.getString(R.string.launcher_animations), Prefs.animations(act));
        anim.setOnClickListener(v -> {
            boolean on = !Prefs.animations(act);
            Prefs.setAnimations(act, on);
            setChecked(anim, on);
            host.onAnimationsChanged();
        });
        View blur = addOption(act.getString(R.string.blur_effects), Prefs.blur(act));
        blur.setOnClickListener(v -> {
            boolean on = !Prefs.blur(act);
            Prefs.setBlur(act, on);
            setChecked(blur, on);
            host.onBlurChanged();
        });

        addHeader(act.getString(R.string.system_animations_header));
        float scale = SystemTweaks.animationScale(act);
        int[] names = {R.string.system_animations_normal, R.string.system_animations_fast, R.string.system_animations_off};
        final List<View> options = new ArrayList<>();
        for (int i = 0; i < SystemTweaks.ANIMATION_SCALES.length; i++) {
            final float s = SystemTweaks.ANIMATION_SCALES[i];
            View row = addOption(act.getString(names[i]), Math.abs(scale - s) < 0.01f);
            options.add(row);
            row.setOnClickListener(v -> {
                if (!SystemTweaks.setAnimationScale(act, s)) {
                    Toast.makeText(act, R.string.needs_grant, Toast.LENGTH_LONG).show();
                    return;
                }
                for (int j = 0; j < options.size(); j++) setChecked(options.get(j), SystemTweaks.ANIMATION_SCALES[j] == s);
            });
        }
    }

    private void showMemory(View row) {
        ActivityManager.MemoryInfo info = SystemTweaks.memory(act);
        long used = info.totalMem - info.availMem;
        TextView text = row.findViewById(R.id.memoryText);
        text.setText(act.getString(R.string.memory_usage,
                Formatter.formatShortFileSize(act, used),
                Formatter.formatShortFileSize(act, info.totalMem),
                Formatter.formatShortFileSize(act, info.availMem)));
        ProgressBar bar = row.findViewById(R.id.memoryBar);
        bar.setProgress(info.totalMem > 0 ? (int) (used * 100 / info.totalMem) : 0);
    }

    private void buildTv() {
        sectionHint.setText(R.string.tv_settings_hint);
        View row = addAction(R.drawable.ic_settings, act.getString(R.string.open_tv_settings));
        row.setOnClickListener(v -> {
            dialog.dismiss();
            host.openTvSettings();
        });
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

    /** A row that does something, with a leading icon. */
    private View addAction(int icon, String title) {
        View row = newRow(rows, R.drawable.input_row_bg);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        rows.addView(row);
        return row;
    }

    /** An app row: its icon and name. */
    private View appRow(AppAdapter.Entry e) {
        View row = newRow(rows, R.drawable.input_row_bg);
        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageTintList(null);
        icon.setImageDrawable(e.icon);
        sizeIcon(icon, 28, 28);
        ((TextView) row.findViewById(R.id.title)).setText(e.label);
        rows.addView(row);
        return row;
    }

    /** A small caption between groups of rows; not focusable. */
    private void addHeader(String text) {
        TextView h = new TextView(act);
        h.setText(text);
        h.setAllCaps(true);
        h.setLetterSpacing(0.14f);
        h.setTextSize(11f);
        h.setTextColor(0x8CFFFFFF);
        h.setTypeface(act.getResources().getFont(R.font.outfit));
        h.setPadding((int) (12 * density), (int) (14 * density), (int) (12 * density), (int) (6 * density));
        h.setFocusable(false);
        rows.addView(h);
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
