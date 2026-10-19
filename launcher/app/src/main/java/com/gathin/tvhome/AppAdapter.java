package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * App tiles. The first row is the pinned dock (favourites, fixed order), the rest follow the order
 * picked in settings, then alphabetical. Dock and grid share the column count chosen in settings, so the
 * dock holds one app per column and every tile is the same size, grown or shrunk to fill the row.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Long press (or long OK) on a tile: show the app's menu. */
        void onTileLongPressed(int position, View card);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        final boolean system;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon, boolean system) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.system = system;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Tile width as a share of its cell; the rest is the gap between tiles. */
    private static final float TILE_FILL = 0.85f;
    private static final float TILE_ASPECT = 101f / 180f;

    private final Context ctx;
    private final TileListener listener;
    private final float density;
    private List<String> favourites;
    private Set<String> hidden;
    private List<String> order;
    private int columns;
    private int gridWidth;
    /** Shown tiles: the dock, then the grid. */
    private final List<Entry> items = new ArrayList<>();
    /** Every launchable app, hidden ones included, alphabetical; for the settings lists. */
    private final List<Entry> all = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int columns) {
        this.ctx = ctx;
        this.listener = listener;
        this.columns = columns;
        this.density = ctx.getResources().getDisplayMetrics().density;
        refreshPrefs();
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /** Re-reads dock, hidden apps and order from the settings; call reload() afterwards. */
    void refreshPrefs() {
        favourites = Prefs.dockApps(ctx, columns);
        hidden = Prefs.hiddenApps(ctx);
        order = Prefs.appOrder(ctx);
    }

    // ---- layout ----------------------------------------------------------------------------------

    int columns() {
        return columns;
    }

    void setColumns(int columns) {
        if (this.columns == columns) return;
        this.columns = columns;
        notifyDataSetChanged();
    }

    /** Dock slots: one per column. */
    int dockSize() {
        return columns;
    }

    int spanCount() {
        return columns;
    }

    int spanAt(int position) {
        return 1;
    }

    int gridWidth() {
        return gridWidth;
    }

    /** The grid's inner width; tile sizes follow it. */
    void setGridWidth(int px) {
        if (px <= 0 || px == gridWidth) return;
        gridWidth = px;
        notifyDataSetChanged();
    }

    int cardWidth(boolean dock) {
        int width = gridWidth > 0 ? gridWidth : (int) (848 * density);
        return (int) (width / (float) columns * TILE_FILL);
    }

    int cardHeight(boolean dock) {
        return (int) (cardWidth(dock) * TILE_ASPECT);
    }

    /** Height of the dock row including its padding and the name label slot. */
    int dockRowHeight() {
        return cardHeight(true) + (int) (45 * density);
    }

    // ---- data ------------------------------------------------------------------------------------

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            boolean system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon, system));
        }
        Collections.sort(fresh, (a, b) -> a.label.compareToIgnoreCase(b.label));
        all.clear();
        all.addAll(fresh);

        List<Entry> shown = new ArrayList<>();
        for (Entry e : fresh) if (!hidden.contains(e.pkg)) shown.add(e);
        Collections.sort(shown, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            int oa = order.indexOf(a.pkg), ob = order.indexOf(b.pkg);
            if (oa >= 0 || ob >= 0) {
                if (oa < 0) return 1;
                if (ob < 0) return -1;
                return Integer.compare(oa, ob);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        items.addAll(shown);
        notifyDataSetChanged();
    }

    /** Shown apps, alphabetical, for the dock picker. */
    List<Entry> entriesAlphabetical() {
        List<Entry> out = new ArrayList<>(items);
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** Every app including hidden ones, in grid order (dock apps first), for the Apps settings. */
    List<Entry> entriesInGridOrder() {
        List<Entry> out = new ArrayList<>(all);
        Collections.sort(out, (a, b) -> {
            int ia = items.indexOf(a), ib = items.indexOf(b);
            if (ia >= 0 && ib >= 0) return Integer.compare(ia, ib);
            if (ia >= 0) return -1;
            if (ib >= 0) return 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        return out;
    }

    Entry entryAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        boolean dock = position < dockSize();
        sizeTile(h, dock);
        h.name.setText(e.label);
        h.name.setVisibility(dock ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION) listener.onTileLongPressed(pos, v);
            return true;
        });
    }

    private void sizeTile(Holder h, boolean dock) {
        int w = cardWidth(dock), hgt = cardHeight(dock);
        FrameLayout.LayoutParams card = (FrameLayout.LayoutParams) h.card.getLayoutParams();
        if (card.width != w || card.height != hgt) {
            card.width = w;
            card.height = hgt;
            h.card.setLayoutParams(card);
        }
        FrameLayout.LayoutParams name = (FrameLayout.LayoutParams) h.name.getLayoutParams();
        int top = hgt + (int) (7 * density);
        if (name.width != w || name.topMargin != top) {
            name.width = w;
            name.topMargin = top;
            h.name.setLayoutParams(name);
        }
    }

    void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(Motion.ms(focused ? 260 : 180))
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(Motion.ms(220)).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
