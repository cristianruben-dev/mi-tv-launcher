package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Intent;
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
import android.widget.HorizontalScrollView;
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
 * The launcher's settings, full screen over the wallpaper: the sections on the left, the focused section's
 * options on the right. Moving through the sections switches the options; Right enters them, Left and Back
 * come back. In Wallpaper, moving along the strip of wallpapers shows each one behind the settings; OK keeps it.
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

        void onTimeFormatChanged();

        void onWallpaperChanged();

        /** Dark or light mode was chosen: the host recreates itself and may reopen this section. */
        void onModeChanged(int reopenSection);

        /** The glass opacity changed: the host redraws its glass. */
        void onGlassChanged();

        void onAnimationsChanged();

        /** House Control's widgets were shown or hidden. */
        void onHouseWidgetsChanged();

        void onSleepTimerChanged();

        void previewScreensaver();

        /** The wallpaper on the home screen right now, or null for none. */
        Bitmap wallpaperBitmap();
    }

    static final int SECTION_GENERAL = 0;
    static final int SECTION_HOME = 1;
    static final int SECTION_APPS = 2;
    static final int SECTION_WALLPAPER = 3;
    static final int SECTION_SCREENSAVER = 4;
    static final int SECTION_SLEEP = 5;
    static final int SECTION_PERFORMANCE = 6;

    private static final int[] SECTION_TITLES = {R.string.section_general, R.string.section_home,
            R.string.section_apps, R.string.section_wallpaper, R.string.section_screensaver,
            R.string.section_sleep, R.string.section_performance};
    private static final int[] SECTION_ICONS = {R.drawable.ic_power, R.drawable.ic_appearance,
            R.drawable.ic_apps, R.drawable.ic_wallpaper, R.drawable.ic_screensaver,
            R.drawable.ic_sleep, R.drawable.ic_performance};


    /** Apps section modes. */
    private static final int APPS_DOCK = 0, APPS_VISIBILITY = 1, APPS_ORDER = 2;
    /** The full wallpaper is decoded once focus has rested this long on its thumbnail. */
    private static final long PREVIEW_DELAY_MS = 180;

    private final Activity act;
    private final Host host;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> sectionRows = new ArrayList<>();
    private Dialog dialog;
    private ImageView backdrop;
    private LinearLayout rows;
    private ScrollView scroll;
    private TextView sectionHint;
    /** Wallpaper section: the strip of wallpaper tiles, or null in other sections. */
    private LinearLayout strip;
    /** Where Right from the sections lands, when not the first option (the wallpaper in use). */
    private View entry;
    /** The wallpaper behind the settings: the home screen's, or the one just chosen. */
    private Bitmap homeBitmap;
    /** The section whose options are on the right. */
    private int current = -1;
    /** Bumped on every section switch so late loads land only in the section that asked. */
    private int generation;
    private int appsMode = APPS_DOCK;
    /** Apps section, order mode: the row picked up for moving, or null. */
    private View carried;
    /** App to pick up when the Apps section opens (from the long press menu's "Move in the grid"). */
    private String carryOnOpen;
    /**
     * True while a section's rows are replaced. Removing the focused row sends focus to the first section,
     * and that focus change must not switch sections.
     */
    private boolean rebuilding;

    // Wallpaper preview state.
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private int previewGen;
    /** The wallpaper whose tile has focus. */
    private String focusedKey;
    private String previewKey;
    private Bitmap previewFull;
    private Runnable pendingPreview;

    SettingsDialog(Activity act, Host host) {
        this.act = act;
        this.host = host;
        this.density = act.getResources().getDisplayMetrics().density;
    }

    /** Opens on a section; with {@code arrangePkg}, opens Apps in order mode with that app picked up. */
    void show(int startSection, String arrangePkg) {
        carryOnOpen = arrangePkg;
        if (arrangePkg != null) appsMode = APPS_ORDER;
        dialog = new Dialog(act, R.style.SettingsDialog) {
            @Override
            public void onBackPressed() {
                goBack();
            }
        };
        View root = act.getLayoutInflater().inflate(R.layout.dialog_settings, null, false);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        backdrop = root.findViewById(R.id.backdrop);
        rows = root.findViewById(R.id.rows);
        scroll = root.findViewById(R.id.scroll);
        sectionHint = root.findViewById(R.id.sectionHint);
        // Captions sit straight over the wallpaper: a soft shadow of the opposite tone keeps them readable.
        sectionHint.setShadowLayer(6 * density, 0, density, act.getColor(R.color.on_wall_shadow));
        fadeWhenScrolled(root.findViewById(R.id.sectionsScroll), root.findViewById(R.id.sectionsFrame));
        fadeWhenScrolled(scroll, root.findViewById(R.id.rowsFrame));

        homeBitmap = host.wallpaperBitmap();
        backdrop.setImageBitmap(homeBitmap);
        if (homeBitmap == null) loadHomeWallpaper();   // the home screen is still decoding it (e.g. just recreated)

        LinearLayout sections = root.findViewById(R.id.sections);
        for (int i = 0; i < SECTION_TITLES.length; i++) {
            final int section = i;
            View row = newRow();
            row.setMinimumHeight((int) (44 * density));   // all the sections fit without scrolling
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(SECTION_ICONS[i]);
            ((TextView) row.findViewById(R.id.title)).setText(SECTION_TITLES[i]);
            row.setOnFocusChangeListener((v, focused) -> {
                if (focused && !rebuilding) select(section);
            });
            row.setOnClickListener(v -> {
                View first = firstItem();
                if (first != null) first.requestFocus();
            });
            sections.addView(row);
            sectionRows.add(row);
        }
        View first = sectionRows.get(0), last = sectionRows.get(sectionRows.size() - 1);
        first.setNextFocusUpId(first.getId());
        last.setNextFocusDownId(last.getId());
        for (View s : sectionRows) s.setNextFocusLeftId(s.getId());

        dialog.setOnDismissListener(d -> {
            handler.removeCallbacksAndMessages(null);
            generation++;
            previewGen++;
        });
        dialog.show();

        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(Motion.ms(220)).start();

        int start = arrangePkg != null ? SECTION_APPS : Math.max(0, Math.min(startSection, sectionRows.size() - 1));
        select(start);
        if (carried != null) carried.requestFocus();
        else sectionRows.get(start).requestFocus();
    }

    /** Fades the list out at its top and bottom edges, like the home grid, wherever there is more of it that way. */
    private static void fadeWhenScrolled(ScrollView list, FadeFrame frame) {
        Runnable update = () -> frame.setFades(list.canScrollVertically(-1), list.canScrollVertically(1));
        list.setOnScrollChangeListener((v, x, y, oldX, oldY) -> update.run());
        // The content changes with every section: check again once it is laid out.
        list.getChildAt(0).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> list.post(update));
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> list.post(update));
    }

    // ---- sections ------------------------------------------------------------------------------

    private void select(int section) {
        if (section == current) return;
        current = section;
        carried = null;
        for (int i = 0; i < sectionRows.size(); i++) sectionRows.get(i).setActivated(i == section);
        rebuild();
        rows.setAlpha(0f);
        rows.setTranslationX(16 * density);
        rows.animate().alpha(1f).translationX(0f).setDuration(Motion.ms(200))
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    /** Back: leaves the options for the sections, then closes the settings. */
    private void goBack() {
        View focus = rows.findFocus();
        if (focus != null) sectionRows.get(current).requestFocus();
        else dialog.dismiss();
    }

    /** Redraws the open section, e.g. after switching the Apps mode. */
    private void rebuild() {
        rebuilding = true;
        generation++;
        rows.removeAllViews();
        strip = null;
        entry = null;
        thumbs.clear();
        scroll.scrollTo(0, 0);
        switch (current) {
            case SECTION_GENERAL:
                buildGeneral();
                break;
            case SECTION_HOME:
                buildHome();
                break;
            case SECTION_APPS:
                buildApps();
                break;
            case SECTION_WALLPAPER:
                buildWallpaper();
                break;
            case SECTION_SCREENSAVER:
                buildScreensaver();
                break;
            case SECTION_SLEEP:
                buildSleep();
                break;
            default:
                buildPerformance();
                break;
        }
        wireFocus();
        rebuilding = false;
    }

    /**
     * Focusable options line by line: a row is a line of one; the wallpaper strip (tagged "strip") is a line
     * of its tiles.
     */
    private List<List<View>> focusLines() {
        List<List<View>> lines = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            List<View> line = new ArrayList<>();
            if ("strip".equals(child.getTag(R.id.rows))) {
                ViewGroup g = (ViewGroup) ((ViewGroup) child).getChildAt(0);
                for (int j = 0; j < g.getChildCount(); j++) if (g.getChildAt(j).isFocusable()) line.add(g.getChildAt(j));
            } else if (child.isFocusable()) {
                line.add(child);
            }
            if (!line.isEmpty()) lines.add(line);
        }
        return lines;
    }

    /** Where focus goes when entering the options. */
    private View firstItem() {
        if (entry != null) return entry;
        List<List<View>> lines = focusLines();
        return lines.isEmpty() ? null : lines.get(0).get(0);
    }

    /**
     * Right from the sections enters the options; Left from the first item of a line returns to its section;
     * the options' top and bottom edges hold focus.
     */
    private void wireFocus() {
        List<List<View>> lines = focusLines();
        if (lines.isEmpty()) return;
        View first = firstItem();
        for (View s : sectionRows) s.setNextFocusRightId(first.getId());
        int back = sectionRows.get(current).getId();
        for (int l = 0; l < lines.size(); l++) {
            List<View> line = lines.get(l);
            for (int i = 0; i < line.size(); i++) {
                View v = line.get(i);
                v.setNextFocusLeftId(i == 0 ? back : View.NO_ID);
                v.setNextFocusRightId(i == line.size() - 1 ? v.getId() : View.NO_ID);
                v.setNextFocusUpId(l == 0 ? v.getId() : View.NO_ID);
                v.setNextFocusDownId(l == lines.size() - 1 ? v.getId() : View.NO_ID);
            }
        }
    }

    // ---- general -------------------------------------------------------------------------------

    private void buildGeneral() {
        sectionHint.setText(R.string.general_hint);
        addHeader(act.getString(R.string.power_on_header));
        List<Inputs.Item> inputs = Inputs.list(act);
        final List<String> keys = new ArrayList<>();
        final List<View> options = new ArrayList<>();
        keys.add(Prefs.POWER_ON_HOME);
        options.add(addOption(act.getString(R.string.power_on_home), Prefs.powerOnInput(act).isEmpty()));
        for (Inputs.Item in : inputs) {
            keys.add(in.key);
            options.add(addOption(in.label, in.key.equals(Prefs.powerOnInput(act))));
        }
        for (int i = 0; i < options.size(); i++) {
            final String key = keys.get(i);
            options.get(i).setOnClickListener(v -> {
                Prefs.setPowerOnInput(act, key);
                for (int j = 0; j < options.size(); j++) setChecked(options.get(j), keys.get(j).equals(key));
            });
        }
        View test = addAction(R.drawable.ic_open, act.getString(R.string.power_on_test));
        test.setOnClickListener(v -> {
            Intent i = Inputs.intentFor(act, Prefs.powerOnInput(act));
            if (i == null) {
                Toast.makeText(act, R.string.power_on_nothing, Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            try {
                act.startActivity(i);
            } catch (RuntimeException e) {
                Toast.makeText(act, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- home screen -----------------------------------------------------------------------------

    private void buildHome() {
        sectionHint.setText(R.string.home_hint);
        addHeader(act.getString(R.string.appearance_header));
        final boolean light = Prefs.lightMode(act);
        View dark = addOption(act.getString(R.string.mode_dark), !light);
        View bright = addOption(act.getString(R.string.mode_light), light);
        dark.setOnClickListener(v -> setMode(false));
        bright.setOnClickListener(v -> setMode(true));
        addGlassSlider();
        if (HousePanel.installed(act)) {
            addHeader(act.getString(R.string.house_widgets_header));
            View widgets = addToggle(act.getString(R.string.house_widgets), Prefs.houseWidgets(act));
            widgets.setOnClickListener(v -> {
                boolean on = !Prefs.houseWidgets(act);
                Prefs.setHouseWidgets(act, on);
                setToggle(widgets, on);
                host.onHouseWidgetsChanged();
            });
        }
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
        addHeader(act.getString(R.string.time_format_header));
        final String[] formats = {Prefs.TIME_AUTO, Prefs.TIME_12, Prefs.TIME_24};
        int[] names = {R.string.time_format_auto, R.string.time_format_12, R.string.time_format_24};
        final List<View> timeRows = new ArrayList<>();
        for (int i = 0; i < formats.length; i++) {
            final String f = formats[i];
            View row = addOption(act.getString(names[i]), f.equals(Prefs.timeFormat(act)));
            timeRows.add(row);
            row.setOnClickListener(v -> {
                Prefs.setTimeFormat(act, f);
                for (int j = 0; j < timeRows.size(); j++) setChecked(timeRows.get(j), formats[j].equals(f));
                host.onTimeFormatChanged();
            });
        }
    }

    private void setMode(boolean light) {
        if (light == Prefs.lightMode(act)) return;
        Prefs.setLightMode(act, light);
        dialog.dismiss();
        host.onModeChanged(SECTION_HOME);
    }

    /** Glass opacity: left and right step it, and the home screen and these rows follow at once. */
    private void addGlassSlider() {
        View row = act.getLayoutInflater().inflate(R.layout.item_slider, rows, false);
        row.setId(View.generateViewId());
        Glass.apply(row, 14 * density);
        ((TextView) row.findViewById(R.id.title)).setText(R.string.glass_opacity);
        Runnable show = () -> {
            int p = Prefs.glassOpacity(act);
            ((TextView) row.findViewById(R.id.value)).setText(act.getString(R.string.glass_opacity_value, p));
            ((ProgressBar) row.findViewById(R.id.bar)).setProgress(p * 100 / Prefs.GLASS_MAX);
        };
        show.run();
        row.setOnKeyListener((v, keyCode, event) -> {
            int step = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -Prefs.GLASS_STEP
                    : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? Prefs.GLASS_STEP : 0;
            if (step == 0) return false;
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            int now = Prefs.glassOpacity(act);
            int next = Math.max(Prefs.glassMin(act), Math.min(Prefs.GLASS_MAX, now + step));
            if (next != now) {
                Prefs.setGlassOpacity(act, next);
                show.run();
                host.onGlassChanged();
                Glass.invalidateAll(dialog.getWindow().getDecorView());
            }
            return true;
        });
        rows.addView(row);
    }

    // ---- apps: dock, show/hide, order ------------------------------------------------------------

    private void buildApps() {
        int[] hints = {R.string.dock_hint_short, R.string.apps_visibility_hint, R.string.apps_arrange_hint};
        sectionHint.setText(hints[appsMode]);
        int[] modes = {R.string.apps_mode_dock, R.string.apps_mode_visibility, R.string.apps_mode_arrange};
        for (int m = 0; m < modes.length; m++) {
            final int mode = m;
            View row = addOption(act.getString(modes[m]), m == appsMode);
            row.setOnClickListener(v -> switchAppsMode(mode));
        }
        if (appsMode == APPS_DOCK) buildDockList();
        else buildVisibilityOrOrder();
    }

    private void switchAppsMode(int mode) {
        if (appsMode == mode) return;
        appsMode = mode;
        carried = null;
        rebuild();
        View target = rows.getChildAt(mode);   // stay on the mode row just chosen
        if (target != null) target.requestFocus();
    }

    private void buildDockList() {
        List<AppAdapter.Entry> apps = host.allApps();
        final int slots = Prefs.columns(act);
        Set<String> installed = new HashSet<>();
        for (AppAdapter.Entry e : apps) installed.add(e.pkg);
        final Map<String, TextView> badges = new HashMap<>();
        final TextView header = addHeader("");

        Runnable refresh = () -> {
            List<String> dock = installedDock(installed, slots);
            header.setText(act.getString(R.string.dock_count, dock.size(), slots));
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

    private void buildVisibilityOrOrder() {
        boolean ordering = appsMode == APPS_ORDER;
        addHeader(act.getString(ordering ? R.string.apps_header_order : R.string.apps_header_visibility));
        // Shown apps come first in grid order, so the first row of them is the dock, which is managed
        // in the Dock mode; hidden apps follow.
        List<AppAdapter.Entry> apps = host.appsInGridOrder();
        Set<String> hidden = Prefs.hiddenApps(act);
        int seen = 0;
        for (AppAdapter.Entry e : apps) {
            boolean isHidden = hidden.contains(e.pkg);
            if (!isHidden && seen++ < Prefs.columns(act)) continue;   // dock apps
            if (ordering && isHidden) continue;                     // hidden apps have no place in the grid
            View row = appRow(e);
            row.setTag(e.pkg);
            if (ordering) {
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
        if (ordering) {
            View reset = addAction(R.drawable.ic_arrange, act.getString(R.string.apps_reset_order));
            reset.setOnClickListener(v -> {
                Prefs.resetAppOrder(act);
                host.onAppsChanged();
                rebuild();
                View f = rows.getChildAt(APPS_ORDER);
                if (f != null) f.requestFocus();
            });
        }
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

    // ---- wallpaper: the one in use, rotation -----------------------------------------------------

    private void buildWallpaper() {
        List<String> files = Wallpapers.list(act);
        sectionHint.setText(files.isEmpty()
                ? act.getString(R.string.wallpaper_hint) + " " + act.getString(R.string.wallpaper_empty)
                : act.getString(R.string.wallpaper_hint));

        // The wallpapers in a strip: moving along it previews each one behind the settings, OK keeps it.
        final String chosen = Prefs.wallpaper(act);
        HorizontalScrollView scroller = new HorizontalScrollView(act);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // The sides fade out progressively while there are more wallpapers that way, and scrolling keeps the
        // focused tile clear of that fade.
        scroller.setHorizontalFadingEdgeEnabled(true);
        scroller.setFadingEdgeLength((int) (64 * density));
        scroller.setClipChildren(false);
        scroller.setTag(R.id.rows, "strip");
        strip = new LinearLayout(act);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setClipChildren(false);
        // Room inside the strip for the focused tile to grow without its ring being cut. It is the strip's
        // padding, not the scroller's, so the side fades reach the very edge.
        strip.setPadding((int) (8 * density), (int) (10 * density), (int) (8 * density), (int) (12 * density));
        strip.setClipToPadding(false);
        scroller.addView(strip);
        for (final String key : files) {
            View tile = act.getLayoutInflater().inflate(R.layout.item_wallpaper, strip, false);
            tile.setId(View.generateViewId());
            ImageView image = tile.findViewById(R.id.image);
            roundCorners(image, 10);
            loadThumbnail(image, key);
            tile.findViewById(R.id.selected).setVisibility(key.equals(chosen) ? View.VISIBLE : View.GONE);
            tile.setTag(key);
            tile.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.07f : 1f).scaleY(focused ? 1.07f : 1f)
                        .setDuration(Motion.ms(180)).start();
                if (focused) preview(key);
                else handler.post(this::restoreIfLeftStrip);
            });
            tile.setOnClickListener(v -> choose(key));
            strip.addView(tile);
            if (key.equals(chosen)) entry = tile;
        }
        rows.addView(scroller);

        addHeader(act.getString(R.string.rotation_header));
        final String[] rotations = {Prefs.ROTATE_NEVER, Prefs.ROTATE_ON_POWER, Prefs.ROTATE_HOURLY, Prefs.ROTATE_DAILY};
        int[] names = {R.string.rotation_never, R.string.rotation_power_on, R.string.rotation_hourly, R.string.rotation_daily};
        final List<View> rotRows = new ArrayList<>();
        for (int i = 0; i < rotations.length; i++) {
            final String r = rotations[i];
            View row = addOption(act.getString(names[i]), r.equals(Prefs.rotation(act)));
            rotRows.add(row);
            row.setOnClickListener(v -> {
                Prefs.setRotation(act, r);
                for (int j = 0; j < rotRows.size(); j++) setChecked(rotRows.get(j), rotations[j].equals(r));
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
        int now = SystemTweaks.screensaverMinutes(act);
        final List<View> options = new ArrayList<>();
        for (int m : SystemTweaks.SCREENSAVER_MINUTES) {
            View row = addOption(Clocks.duration(act, m, R.string.screensaver_off), m == now || (m != 0 && isClosest(m, now)));
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

    // ---- sleep timer -----------------------------------------------------------------------------

    private void buildSleep() {
        Runnable hint = () -> {
            int left = SleepTimer.minutesLeft(act);
            String text = left > 0
                    ? act.getString(R.string.sleep_active, Clocks.time(act, Prefs.sleepAt(act)), left)
                    : act.getString(R.string.sleep_hint);
            if (!SleepTimer.canSleep(act)) text += " " + act.getString(R.string.needs_grant);
            sectionHint.setText(text);
        };
        hint.run();
        addHeader(act.getString(R.string.sleep_header));
        final List<View> options = new ArrayList<>();
        boolean active = SleepTimer.minutesLeft(act) > 0;
        for (int m : SleepTimer.MINUTES) {
            View row = addOption(Clocks.duration(act, m, R.string.sleep_off), m == 0 && !active);
            options.add(row);
            row.setOnClickListener(v -> {
                if (m > 0 && !SleepTimer.canSleep(act)) {
                    Toast.makeText(act, R.string.needs_grant, Toast.LENGTH_LONG).show();
                    return;
                }
                SleepTimer.set(act, m);
                for (int j = 0; j < options.size(); j++) setChecked(options.get(j), SleepTimer.MINUTES[j] == m);
                hint.run();
                host.onSleepTimerChanged();
            });
        }
    }

    // ---- performance -----------------------------------------------------------------------------

    private void buildPerformance() {
        sectionHint.setText(R.string.performance_hint);
        View memory = act.getLayoutInflater().inflate(R.layout.item_memory, rows, false);
        Glass.apply(memory, 14 * density);
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
        View anim = addToggle(act.getString(R.string.launcher_animations), Prefs.animations(act));
        anim.setOnClickListener(v -> {
            boolean on = !Prefs.animations(act);
            Prefs.setAnimations(act, on);
            setToggle(anim, on);
            host.onAnimationsChanged();
        });
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

    /** Decodes the wallpaper in use for the backdrop when the home screen has not shown it yet. */
    private void loadHomeWallpaper() {
        final String key = Prefs.wallpaper(act);
        if (key.isEmpty()) return;
        final int w = act.getResources().getDisplayMetrics().widthPixels;
        final int h = act.getResources().getDisplayMetrics().heightPixels;
        new Thread(() -> {
            Bitmap full = Wallpapers.load(act, key, w, h);
            handler.post(() -> {
                if (full == null || homeBitmap != null || !key.equals(Prefs.wallpaper(act))) return;
                homeBitmap = full;
                if (focusedKey == null) setBackdrop(full);
            });
        }, "wallpaper-home").start();
    }

    /** Puts a picture behind the settings. */
    private void setBackdrop(Bitmap picture) {
        backdrop.setImageBitmap(picture);
    }

    // ---- wallpaper preview -----------------------------------------------------------------------

    /**
     * Shows a wallpaper behind the settings: its thumbnail at once, then the full picture once focus rests on
     * it, so moving quickly along the strip never waits for a decode.
     */
    private void preview(String key) {
        focusedKey = key;
        final int gen = ++previewGen;
        if (pendingPreview != null) handler.removeCallbacks(pendingPreview);
        if (key.equals(previewKey) && previewFull != null) {
            setBackdrop(previewFull);
            return;
        }
        setBackdrop(thumbs.get(key));
        final int w = act.getResources().getDisplayMetrics().widthPixels;
        final int h = act.getResources().getDisplayMetrics().heightPixels;
        pendingPreview = () -> new Thread(() -> {
            Bitmap full = Wallpapers.load(act, key, w, h);
            handler.post(() -> {
                if (gen != previewGen || full == null) return;
                previewKey = key;
                previewFull = full;
                setBackdrop(full);
            });
        }, "wallpaper-preview").start();
        handler.postDelayed(pendingPreview, PREVIEW_DELAY_MS);
    }

    /** Focus left the strip without OK: the wallpaper in use comes back. */
    private void restoreIfLeftStrip() {
        if (strip != null && strip.findFocus() != null) return;
        if (focusedKey == null) return;
        previewGen++;
        if (pendingPreview != null) handler.removeCallbacks(pendingPreview);
        focusedKey = null;
        previewKey = null;
        previewFull = null;
        setBackdrop(homeBitmap);
    }

    private void choose(String key) {
        Prefs.setWallpaper(act, key);
        // Each wallpaper brings the look that suits it; a new mode recreates the home screen and these settings.
        if (Wallpapers.applyPreset(act, key)) {
            dialog.dismiss();
            host.onModeChanged(SECTION_WALLPAPER);
            return;
        }
        host.onGlassChanged();
        Glass.invalidateAll(dialog.getWindow().getDecorView());
        host.onWallpaperChanged();
        for (int i = 0; strip != null && i < strip.getChildCount(); i++) {
            View tile = strip.getChildAt(i);
            tile.findViewById(R.id.selected).setVisibility(key.equals(tile.getTag()) ? View.VISIBLE : View.GONE);
        }
        if (key.equals(previewKey) && previewFull != null) {
            homeBitmap = previewFull;
        } else {
            // Chosen before the full picture came in: keep the thumbnail for now and swap in the full one.
            homeBitmap = thumbs.get(key);
            final int w = act.getResources().getDisplayMetrics().widthPixels;
            final int h = act.getResources().getDisplayMetrics().heightPixels;
            new Thread(() -> {
                Bitmap full = Wallpapers.load(act, key, w, h);
                handler.post(() -> {
                    if (full == null || !key.equals(Prefs.wallpaper(act))) return;
                    homeBitmap = full;
                    if (focusedKey == null || key.equals(focusedKey)) setBackdrop(full);
                });
            }, "wallpaper-chosen").start();
        }
    }

    // ---- rows ------------------------------------------------------------------------------------

    private View newRow() {
        View row = act.getLayoutInflater().inflate(R.layout.item_setting, rows, false);
        row.setId(View.generateViewId());
        Glass.apply(row, 14 * density);
        return row;
    }

    /** A choice row with a check mark when selected; no leading icon. */
    private View addOption(String title, boolean checked) {
        View row = newRow();
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        setChecked(row, checked);
        rows.addView(row);
        return row;
    }

    /** A row that does something, with a leading icon. */
    private View addAction(int icon, String title) {
        View row = newRow();
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        rows.addView(row);
        return row;
    }

    /** An on/off row with a switch at the end. */
    private View addToggle(String title, boolean on) {
        View row = newRow();
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.toggle).setVisibility(View.VISIBLE);
        setToggle(row, on);
        rows.addView(row);
        return row;
    }

    /** An app row: its icon and name. */
    private View appRow(AppAdapter.Entry e) {
        View row = newRow();
        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageTintList(null);
        icon.setImageDrawable(e.icon);
        sizeIcon(icon, 30, 30);
        ((TextView) row.findViewById(R.id.title)).setText(e.label);
        rows.addView(row);
        return row;
    }

    /** A small caption between groups of rows; not focusable. */
    private TextView addHeader(String text) {
        TextView h = new TextView(act);
        h.setText(text);
        h.setTextSize(15f);
        h.setTextColor(act.getColor(R.color.on_wall_soft));
        h.setShadowLayer(6 * density, 0, density, act.getColor(R.color.on_wall_shadow));
        h.setTypeface(act.getResources().getFont(R.font.outfit));
        h.setPadding((int) (6 * density), (int) (16 * density), (int) (6 * density), (int) (8 * density));
        h.setFocusable(false);
        rows.addView(h);
        return h;
    }

    private static void setChecked(View row, boolean checked) {
        row.findViewById(R.id.check).setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
    }

    private static void setToggle(View row, boolean on) {
        row.findViewById(R.id.toggle).setActivated(on);
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

    /** Decodes a wallpaper thumbnail off the main thread; it is also the first preview behind the settings. */
    private void loadThumbnail(ImageView target, String file) {
        final int gen = generation;
        new Thread(() -> {
            Bitmap bmp = Wallpapers.thumbnail(act, file, 480, 270);
            handler.post(() -> {
                if (bmp == null || gen != generation) return;
                thumbs.put(file, bmp);
                target.setImageBitmap(bmp);
                // The focused wallpaper's preview was still empty: show its thumbnail now.
                if (file.equals(focusedKey) && backdrop.getDrawable() == null) backdrop.setImageBitmap(bmp);
            });
        }, "wallpaper-thumb").start();
    }
}
