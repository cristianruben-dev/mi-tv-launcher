package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * tvOS style home: a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener, SettingsDialog.Host, AppMenu.Host {

    private static final long MINUTE_MS = 60_000L;
    /** The menu backdrop is blurred from a copy this many times smaller than the screen. */
    private static final int BACKDROP_DOWNSCALE = 6;
    private String liveTvPkg;

    private AppAdapter adapter;
    private GridLayoutManager layoutManager;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean ambientShown;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnWifi;
    private ImageView wallpaper;
    private View wallpaperDim;
    private View topScrim;
    private ImageView backdrop;
    private FadeFrame gridFrame;
    private DockDecoration dock;
    /** Asset name of the wallpaper on screen (or being loaded); "" for none, null before the first load. */
    private String loadedWallpaper;
    /** Section to reopen after the activity is recreated for a language change, or -1. */
    private static int reopenSettings = -1;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Prefs.wrapLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        Motion.enabled = Prefs.animations(this);
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        wallpaper = findViewById(R.id.wallpaper);
        wallpaperDim = findViewById(R.id.wallpaperDim);
        topScrim = findViewById(R.id.topScrim);
        backdrop = findViewById(R.id.backdrop);
        gridFrame = findViewById(R.id.gridFrame);

        grid = findViewById(R.id.grid);
        adapter = new AppAdapter(this, this, Prefs.columns(this));
        layoutManager = new GridLayoutManager(this, adapter.spanCount());
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.spanAt(position);
            }
        });
        grid.setLayoutManager(layoutManager);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dock = new DockDecoration(AppAdapter.DOCK, density, root);
        grid.addItemDecoration(dock);
        applyBlur();
        applyWallpaper();
        applyDim();
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press
        // Down. Tiles are sized from the grid's width, so the chosen column count fills the row.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            if (inner > 0) grid.post(() -> adapter.setGridWidth(inner));
            int dockRow = adapter.dockRowHeight() + (int) ((6 + 12) * density);   // plus DockDecoration offsets
            int want = Math.max(0, (b - t) - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // Tiles reach the top edge only once the grid scrolls past the dock.
                gridFrame.setFadeActive(rv.computeVerticalScrollOffset() > 0);
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        animatePill(btnInputs, this::showInputs);
        animatePill(btnWifi, () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        animatePill(btnSettings, () -> showSettings(SettingsDialog.SECTION_LANGUAGE));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambientShown = false;
        ambient.setAlpha(0f);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        adapter.reload();
        if (Motion.enabled) grid.scheduleLayoutAnimation();
        updateGreeting();
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
        if (reopenSettings >= 0) {
            final int section = reopenSettings;
            reopenSettings = -1;
            grid.post(() -> showSettings(section));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown || !Motion.enabled) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (!glassShown) glass.animate().alpha(1f).setDuration(Motion.ms(180)).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (position < AppAdapter.DOCK) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) {
                if (Motion.enabled) grid.smoothScrollBy(0, dy);
                else grid.scrollBy(0, dy);
            }
            showHero(position, r.top - dy);
        } else {
            // Grid apps get the ambient glow in their colour too; only the dock shows the big name.
            hideHero();
            showAmbient(adapter.ambientColorAt(position));
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, !Motion.enabled);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(Motion.ms(260)).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(Motion.ms(120)).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(Motion.ms(200)).start();
            }).start();
        } else {
            heroName.setY(y);
        }
        showAmbient(color);
    }

    /** Fades the ambient glow in, or over to a new colour, for whichever tile has focus. */
    private void showAmbient(int color) {
        if (!ambientShown) {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(Motion.ms(600)).withEndAction(null).start();
            ambientShown = true;
        } else if (color != ambientColor) {
            ambientColor = color;   // a quick second move retargets instead of queueing another fade
            if (!Motion.enabled) {
                setAmbient(color);
                return;
            }
            ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                setAmbient(ambientColor);
                ambient.animate().alpha(1f).setDuration(500).withEndAction(null).start();
            }).start();
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    /** Hides the dock's big app name; the ambient glow stays and follows the focused tile. */
    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(Motion.ms(160)).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(Motion.ms(160)).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int dockSize = AppAdapter.DOCK, cols = adapter.columns();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < dockSize) return false;          // dock row: let focus move to the top bar
            int index = position - dockSize;
            if (index < cols) {
                // First grid row: up to the dock slot above, by position across the row.
                target = cols == 1 ? 0 : Math.round(index * (dockSize - 1) / (float) (cols - 1));
                target = Math.min(target, Math.min(dockSize, count) - 1);
            } else {
                target = position - cols;
            }
        } else {
            if (position < dockSize) {
                if (count <= dockSize) return true;
                int col = Math.round(position * (cols - 1) / (float) (dockSize - 1));
                target = Math.min(dockSize + col, count - 1);
            } else {
                int lastRowStart = dockSize + ((count - 1 - dockSize) / cols) * cols;
                if (position >= lastRowStart) return true;     // already on the last row: stay put
                target = Math.min(position + cols, count - 1);
            }
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- long press menu -------------------------------------------------------------------------

    @Override
    public void onTileLongPressed(int position, View card) {
        AppAdapter.Entry e = adapter.entryAt(position);
        if (e == null) return;
        new AppMenu(this, this, e, position < AppAdapter.DOCK).show();
    }

    @Override
    public void openApp(AppAdapter.Entry e) {
        adapter.launch(e);
    }

    @Override
    public void toggleDock(AppAdapter.Entry e, boolean inDock) {
        // Start from the dock as shown (including apps that only fill empty slots), then change it.
        List<String> dockApps = new ArrayList<>();
        for (int i = 0; i < Math.min(AppAdapter.DOCK, adapter.getItemCount()); i++) {
            AppAdapter.Entry d = adapter.entryAt(i);
            if (d != null) dockApps.add(d.pkg);
        }
        if (inDock) {
            dockApps.remove(e.pkg);
        } else {
            if (dockApps.size() >= AppAdapter.DOCK) {
                // Full: the new app takes the last slot.
                AppAdapter.Entry out = adapter.entryAt(AppAdapter.DOCK - 1);
                dockApps.remove(AppAdapter.DOCK - 1);
                if (out != null) Toast.makeText(this, getString(R.string.dock_replaced, e.label, out.label),
                        Toast.LENGTH_SHORT).show();
            }
            dockApps.add(e.pkg);
        }
        Prefs.setDockApps(this, dockApps);
        lastFocusedKey = e.component.flattenToShortString();
        onAppsChanged();
    }

    @Override
    public void hideApp(AppAdapter.Entry e) {
        Set<String> hidden = Prefs.hiddenApps(this);
        hidden.add(e.pkg);
        Prefs.setHiddenApps(this, hidden);
        List<String> dockApps = Prefs.dockApps(this);
        if (dockApps.remove(e.pkg)) Prefs.setDockApps(this, dockApps);
        Toast.makeText(this, getString(R.string.app_hidden, e.label), Toast.LENGTH_SHORT).show();
        onAppsChanged();
    }

    @Override
    public void arrangeApps(AppAdapter.Entry e) {
        showSettings(SettingsDialog.SECTION_APPS, e.pkg);
    }

    @Override
    public void appInfo(AppAdapter.Entry e) {
        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)));
    }

    @Override
    public void uninstall(AppAdapter.Entry e) {
        open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg)));
    }

    // ---- menu backdrop ---------------------------------------------------------------------------

    /** Blurs the home screen behind a menu or the settings, when blur is on. */
    @Override
    public void showBackdrop() {
        if (!Prefs.blur(this)) return;
        Bitmap shot = Blur.snapshot(root, BACKDROP_DOWNSCALE);
        if (shot == null) return;
        backdrop.setImageBitmap(shot);
        backdrop.setVisibility(View.VISIBLE);
        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(Motion.ms(220)).withEndAction(null).start();
    }

    @Override
    public void hideBackdrop() {
        if (backdrop.getVisibility() != View.VISIBLE) return;
        backdrop.animate().alpha(0f).setDuration(Motion.ms(180)).withEndAction(() -> {
            backdrop.setVisibility(View.GONE);
            backdrop.setImageDrawable(null);
        }).start();
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        btnWifi.setText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Premium feel for the top pills: focus lifts and springs the pill up a little, a click presses it
     * in and bounces it back before the action runs.
     */
    private void animatePill(View pill, Runnable action) {
        final float focusScale = 1.09f;
        pill.setOnFocusChangeListener((v, focused) -> v.animate()
                .scaleX(focused ? focusScale : 1f).scaleY(focused ? focusScale : 1f)
                .translationY(focused ? -2f * density : 0f)
                .setDuration(Motion.ms(focused ? 340 : 220))
                .setInterpolator(focused ? new OvershootInterpolator(2.4f) : new DecelerateInterpolator())
                .start());
        pill.setOnClickListener(v -> {
            if (!Motion.enabled) {
                action.run();
                return;
            }
            v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        float s = v.isFocused() ? focusScale : 1f;
                        v.animate().scaleX(s).scaleY(s).setDuration(280)
                                .setInterpolator(new OvershootInterpolator(3f)).start();
                        action.run();
                    }).start();
        });
    }

    // ---- settings --------------------------------------------------------------------------------

    private void showSettings(int section) {
        showSettings(section, null);
    }

    private void showSettings(int section, String arrangePkg) {
        if (isFinishing() || isDestroyed()) return;
        new SettingsDialog(this, this).show(section, arrangePkg);
    }

    @Override
    public List<AppAdapter.Entry> allApps() {
        return adapter.entriesAlphabetical();
    }

    @Override
    public List<AppAdapter.Entry> appsInGridOrder() {
        return adapter.entriesInGridOrder();
    }

    @Override
    public void onAppsChanged() {
        adapter.refreshPrefs();
        adapter.reload();
        refocusAfterChange();
    }

    @Override
    public void onColumnsChanged() {
        adapter.setColumns(Prefs.columns(this));
        layoutManager.setSpanCount(adapter.spanCount());
        grid.requestLayout();
        refocusAfterChange();
    }

    /** After the tiles move, put focus and the glass back on the same app (or its old slot if it is gone). */
    private void refocusAfterChange() {
        int pos = adapter.positionOf(lastFocusedKey);
        final int target = pos >= 0 ? pos : Math.min(lastFocused, adapter.getItemCount() - 1);
        grid.post(() -> focusTile(target));
    }

    @Override
    public void onDimChanged() {
        applyDim();
    }

    @Override
    public void onWallpaperChanged() {
        applyWallpaper();
    }

    @Override
    public void onBlurChanged() {
        applyBlur();
    }

    @Override
    public void onAnimationsChanged() {
        Motion.enabled = Prefs.animations(this);
    }

    @Override
    public void onLanguageChanged() {
        reopenSettings = SettingsDialog.SECTION_LANGUAGE;
        recreate();
    }

    @Override
    public void openTvSettings() {
        open(new Intent(Settings.ACTION_SETTINGS));
    }

    @Override
    public void previewScreensaver() {
        open(new Intent(this, ScreensaverActivity.class));
    }

    private void applyBlur() {
        dock.setBlur(Prefs.blur(this));
        grid.invalidate();
    }

    private void applyDim() {
        wallpaperDim.setAlpha(Prefs.wallpaperDim(this) / 100f);
    }

    /** Shows the chosen wallpaper (decoded off the main thread), or the plain black background for none. */
    private void applyWallpaper() {
        final String name = Prefs.wallpaper(this);
        if (name.equals(loadedWallpaper)) return;
        loadedWallpaper = name;
        if (name.isEmpty()) {
            setWallpaperBitmap(null, null);
            return;
        }
        final int w = getResources().getDisplayMetrics().widthPixels;
        final int h = getResources().getDisplayMetrics().heightPixels;
        final Context app = getApplicationContext();
        new Thread(() -> {
            Bitmap full = Wallpapers.load(app, name, w, h);
            Bitmap soft = Wallpapers.blurred(full);
            runOnUiThread(() -> {
                if (isDestroyed() || !name.equals(loadedWallpaper)) return;   // another choice came in meanwhile
                if (full == null) {
                    loadedWallpaper = "";
                    setWallpaperBitmap(null, null);
                } else {
                    setWallpaperBitmap(full, soft);
                }
            });
        }, "wallpaper").start();
    }

    private void setWallpaperBitmap(Bitmap full, Bitmap soft) {
        boolean on = full != null;
        wallpaper.setImageBitmap(full);
        wallpaper.setVisibility(on ? View.VISIBLE : View.GONE);
        wallpaperDim.setVisibility(on ? View.VISIBLE : View.GONE);
        topScrim.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            wallpaper.setAlpha(0f);
            wallpaper.animate().alpha(1f).setDuration(Motion.ms(350)).start();
        }
        dock.setBlurSource(soft, on ? full.getWidth() : 0, on ? full.getHeight() : 0);
        grid.invalidate();
    }

    // ---- inputs ----------------------------------------------------------------------------------

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(Motion.ms(220))
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
