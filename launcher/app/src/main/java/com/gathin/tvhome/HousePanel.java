package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * House Control on the home screen: up to eight scene buttons in a 4 × 2 grid beside the live 3D model of the home.
 *
 * <p>The launcher binds House Control's HomeService (which only answers launchers whose signing certificate it
 * pins) and reads the home through the binder: code 1 reads plan and state, 2 runs a scene, 3 tells it whether
 * the widget is on screen, 4 runs a scene of the demo home. Binding also starts House Control's local server on
 * 127.0.0.1:8788, whose widget.html draws the model in the WebView and asks this panel for the home through the
 * {@code HouseWidget} JavaScript interface. No credential ever reaches the launcher.
 */
@SuppressLint("ViewConstructor")
final class HousePanel extends FrameLayout {

    static final String PACKAGE = "dev.housecontrol.tv";
    private static final String SERVICE = PACKAGE + ".HomeService";
    private static final String WIDGET_URL = "http://127.0.0.1:8788/widget.html";
    private static final int READ_HOME = 1, RUN_SCENE = 2, SET_ACTIVE = 3, RUN_DEMO_SCENE = 4;
    private static final int MAX_SCENES = 8, GRID_COLUMNS = 4, GRID_ROWS = 2;
    private static final long REFRESH_MS = 2000;

    interface Host {
        /** Up from the panel: the top bar, nearest to {@code x} (screen coordinates). */
        void panelUp(float x);

        /** Down from the panel: the dock tile nearest to {@code x}. */
        void panelDown(float x);

        /** Focus entered the panel. */
        void panelFocused();

        void openHouseControl();

        /** The blurred wallpaper the model's glass card shows through. */
        Frost.Source wallpaperFrost();
    }

    static boolean installed(Context context) {
        try {
            return context.getPackageManager().getApplicationInfo(PACKAGE, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private final Activity activity;
    private final Host host;
    private final float density;
    private final Typeface font;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final FrameLayout grid;
    private final View dot;
    private final TextView status;
    private final FrameLayout card;
    private final WebView model;
    private final TextView title;
    private final TextView openHint;
    private final View cover;
    private final List<SceneTile> scenes = new ArrayList<>();

    private IBinder binder;
    private boolean bound, attached, active = true, loaded, fetching, busy;
    private volatile String snapshot = "{}";
    private JSONObject current;
    private String revision = "";
    private long warningUntil;
    private int tileW, tileH, cardLeft, gridLeft;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!attached || !active) return;
            fetch();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            sendActive(active);
            fetch();
            // Binding is what starts House Control's local server: only now is the model there to load.
            if (!loaded) {
                loaded = true;
                model.loadUrl(WIDGET_URL);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            setStatus(R.string.house_reconnect, false);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            binder = null;
            setStatus(R.string.house_reconnect, false);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    HousePanel(Activity activity, Host host) {
        super(activity);
        this.activity = activity;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;
        font = activity.getResources().getFont(R.font.outfit);
        setClipChildren(false);
        setClipToPadding(false);

        grid = new FrameLayout(activity);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        addView(grid, new LayoutParams(-2, -2));

        card = new FrameLayout(activity);
        // Rounded clip of its own: the frosted glass background does not describe an outline.
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(20));
            }
        });
        card.setClipToOutline(true);
        addView(card, new LayoutParams(px(100), px(100)));

        model = new WebView(activity);
        // Transparent: the model floats on the card's frosted glass (widget.js draws no background).
        model.setBackgroundColor(Color.TRANSPARENT);
        model.setFocusable(false);
        model.setFocusableInTouchMode(false);
        WebSettings settings = model.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        model.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public String home() {
                return snapshot;
            }

            /** 0: the model follows the card's own shape. */
            @JavascriptInterface
            public double renderAspect() {
                return 0;
            }

            /** Fewer pixels than the card's size: a sharp enough model that is light on 1–2 GB TVs. */
            @JavascriptInterface
            public double resolution() {
                return 0.7;
            }

            @JavascriptInterface
            public String background() {
                return "transparent";
            }
        }, "HouseWidget");
        model.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            // Only House Control's own server on this TV: nothing else is fetched.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.getUrl().toString().startsWith("http://127.0.0.1:8788/")) return null;
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
        });
        card.addView(model, new LayoutParams(-1, -1));

        title = text(activity.getString(R.string.house_your_home), 15, Color.WHITE, 500);
        LayoutParams titleLp = new LayoutParams(-2, -2);
        titleLp.leftMargin = px(16);
        titleLp.topMargin = px(12);
        // The card shows only the model: no home name or lights count over it.
        title.setVisibility(GONE);
        card.addView(title, titleLp);
        dot = new View(activity);
        dot.setBackground(round(0xFFE0A84E, 4));
        LayoutParams dotLp = new LayoutParams(px(7), px(7), Gravity.BOTTOM | Gravity.START);
        dotLp.leftMargin = px(17);
        dotLp.bottomMargin = px(19);
        dot.setVisibility(GONE);
        card.addView(dot, dotLp);
        status = text(activity.getString(R.string.house_connecting), 13, 0xD9FFFFFF, 500);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams statusLp = new LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
        statusLp.leftMargin = px(32);
        statusLp.rightMargin = px(16);
        statusLp.bottomMargin = px(15);
        status.setVisibility(GONE);
        card.addView(status, statusLp);
        openHint = text(activity.getString(R.string.house_open), 11, 0xFF0B111D, 600);
        openHint.setGravity(Gravity.CENTER);
        openHint.setPadding(px(10), 0, px(10), 0);
        openHint.setBackground(round(0xF5FFFFFF, 11));
        openHint.setAlpha(0f);
        LayoutParams hintLp = new LayoutParams(-2, px(22), Gravity.END | Gravity.TOP);
        hintLp.rightMargin = px(12);
        hintLp.topMargin = px(10);
        card.addView(openHint, hintLp);

        cover = new View(activity);
        cover.setFocusable(true);
        cover.setFocusableInTouchMode(true);
        cover.setContentDescription(activity.getString(R.string.house_your_home));
        card.addView(cover, new LayoutParams(-1, -1));
        cover.setOnClickListener(v -> host.openHouseControl());
        cover.setOnFocusChangeListener((v, focus) -> {
            if (focus) host.panelFocused();
            // No zoom: the card already fills the panel, so it shows focus with a white edge.
            card.setForeground(ring(focus));
            openHint.animate().alpha(focus ? 1f : 0f).setDuration(Motion.ms(200)).start();
        });
        card.setForeground(ring(false));
        applyBlur();
        cover.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    host.panelUp(centerX(card));
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    host.panelDown(centerX(card));
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // The scene at the grid's right edge in the card's top row.
                    if (!scenes.isEmpty()) scenes.get(Math.min(GRID_COLUMNS, scenes.size()) - 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return false;
            }
        });
    }

    // ---- layout ----------------------------------------------------------------------------------

    /**
     * Places the panel's parts in a {@code width} × {@code height} box: the model's card at the right, about
     * half the width, and the scene buttons in a 4 × 2 grid against its left side. The rest stays empty.
     */
    void arrange(int width, int height) {
        int gap = px(12), inset = px(6);
        int cardW = Math.max(px(220), Math.round(width * 0.46f));
        cardLeft = width - cardW;
        tileH = Math.max(px(56), (height - (GRID_ROWS - 1) * gap - 2 * inset) / GRID_ROWS);
        int room = (cardLeft - px(18) - (GRID_COLUMNS - 1) * gap) / GRID_COLUMNS;
        tileW = Math.max(px(64), Math.min(Math.min(Math.round(tileH * 1.12f), px(132)), room));
        int gridW = GRID_COLUMNS * tileW + (GRID_COLUMNS - 1) * gap;
        gridLeft = Math.max(0, cardLeft - px(18) - gridW);

        LayoutParams lp = (LayoutParams) grid.getLayoutParams();
        lp.width = gridW;
        lp.height = height;
        lp.leftMargin = gridLeft;
        grid.setLayoutParams(lp);
        placeTiles();

        lp = (LayoutParams) card.getLayoutParams();
        lp.width = cardW;
        lp.height = height;
        lp.leftMargin = cardLeft;
        card.setLayoutParams(lp);
    }

    /** Scene buttons fill the grid row by row from the top; both rows always keep their place. */
    private void placeTiles() {
        int gap = px(12);
        int gridH = grid.getLayoutParams().height;
        int top = Math.max(0, (gridH - (GRID_ROWS * tileH + (GRID_ROWS - 1) * gap)) / 2);
        for (int i = 0; i < scenes.size(); i++) {
            SceneTile t = scenes.get(i);
            LayoutParams lp = (LayoutParams) t.getLayoutParams();
            lp.width = tileW;
            lp.height = tileH;
            lp.leftMargin = (i % GRID_COLUMNS) * (tileW + gap);
            lp.topMargin = top + (i / GRID_COLUMNS) * (tileH + gap);
            t.setLayoutParams(lp);
            t.size(tileW, tileH);
        }
    }

    /** Left edge of the scene grid, in the panel (the card's when there are no scenes). */
    int contentLeft() {
        return scenes.isEmpty() ? cardLeft : gridLeft;
    }

    /** Left edge of the model's card, in the panel. */
    int cardLeft() {
        return cardLeft;
    }

    /** The card is frosted glass over the wallpaper with blur on (Settings → Performance), a solid card otherwise. */
    void applyBlur() {
        card.setBackground(cardBackground());
        Frost.apply(card, host.wallpaperFrost(), px(20), 0x33000000);
        card.invalidate();
        for (SceneTile t : scenes) t.applyBlur();
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        bind();
        handler.post(refresh);
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        handler.removeCallbacksAndMessages(null);
        if (bound) {
            try {
                activity.unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        binder = null;
        model.removeJavascriptInterface("HouseWidget");
        model.destroy();
        worker.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void bind() {
        if (bound) return;
        Intent intent = new Intent().setComponent(new ComponentName(PACKAGE, SERVICE));
        try {
            bound = activity.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception ignored) {
            bound = false;
        }
        if (!bound) setStatus(R.string.house_reconnect, false);
    }

    /** On screen and in front: read the home and draw the model. Off: stop both, and let House Control rest. */
    void setActive(boolean value) {
        if (active == value) return;
        active = value;
        sendActive(value);
        handler.removeCallbacks(refresh);
        if (value && attached) {
            bind();
            model.onResume();
            model.evaluateJavascript("window.widgetRunning && window.widgetRunning(true)", null);
            handler.post(refresh);
        } else {
            model.evaluateJavascript("window.widgetRunning && window.widgetRunning(false)", null);
            model.onPause();
        }
    }

    private void sendActive(boolean value) {
        if (binder == null || worker.isShutdown()) return;
        worker.execute(() -> {
            IBinder target = binder;
            if (target == null) return;
            Parcel in = Parcel.obtain(), out = Parcel.obtain();
            try {
                in.writeInt(value ? 1 : 0);
                target.transact(SET_ACTIVE, in, out, 0);
                out.readException();
            } catch (Exception ignored) {
            } finally {
                in.recycle();
                out.recycle();
            }
        });
    }

    private JSONObject transact(int code, String scene) throws Exception {
        IBinder target = binder;
        if (target == null) throw new IllegalStateException("House Control is not connected.");
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            if (scene != null) in.writeString(scene);
            if (!target.transact(code, in, out, 0)) throw new IllegalStateException("Update House Control.");
            out.readException();
            return new JSONObject(out.readString());
        } finally {
            in.recycle();
            out.recycle();
        }
    }

    // ---- reading the home ------------------------------------------------------------------------

    private void fetch() {
        if (fetching || binder == null || busy || worker.isShutdown()) return;
        fetching = true;
        worker.execute(() -> {
            try {
                JSONObject home = transact(READ_HOME, null);
                String raw = home.toString();
                handler.post(() -> {
                    if (!attached) return;
                    boolean changed = !raw.equals(snapshot);
                    snapshot = raw;
                    if (changed) update(home);
                });
            } catch (Exception error) {
                handler.post(() -> {
                    if (attached) setStatus(R.string.house_reconnect, false);
                });
            } finally {
                fetching = false;
            }
        });
    }

    private void update(JSONObject home) {
        if (home.has("error")) {
            // No plan yet (or House Control refused this launcher): the model shows its own hint.
            current = null;
            setStatus(R.string.house_no_plan, false);
            if (!revision.isEmpty()) {
                revision = "";
                renderScenes(null);
            }
            model.evaluateJavascript("window.widgetRefresh && window.widgetRefresh()", null);
            return;
        }
        current = home;
        boolean demo = home.optBoolean("demo");
        JSONObject plan = home.optJSONObject("plan");
        String name = plan == null ? "" : plan.optString("name");
        title.setText(demo ? (name.isEmpty() ? activity.getString(R.string.house_demo) : name)
                : activity.getString(R.string.house_your_home));

        JSONObject state = home.optJSONObject("state");
        JSONObject lights = state == null ? null : state.optJSONObject("lights");
        int on = 0, total = lights == null ? 0 : lights.length();
        if (lights != null) {
            Iterator<String> keys = lights.keys();
            while (keys.hasNext()) {
                JSONObject light = lights.optJSONObject(keys.next());
                if (light != null && light.optBoolean("on")) on++;
            }
        }
        String lit = on == 0 ? activity.getString(R.string.house_lights_off)
                : activity.getString(R.string.house_lights_on, on, total);
        if (System.currentTimeMillis() < warningUntil) setStatus(R.string.house_some_failed, false);
        else if (!home.optBoolean("controlReady")) setStatus(R.string.house_connect_devices, false);
        else if (!home.optString("cloudError").isEmpty())
            setStatus(lit + " · " + activity.getString(R.string.house_reconnecting), false);
        else setStatus(demo ? lit + " · " + activity.getString(R.string.house_demo_short) : lit, true);

        String rev = home.optString("revision");
        if (!revision.equals(rev)) {
            revision = rev;
            renderScenes(plan);
        }
        String selected = state == null ? "" : state.optString("scene");
        for (SceneTile t : scenes) t.markActive(t.id.equals(selected));
        model.evaluateJavascript("window.widgetRefresh && window.widgetRefresh()", null);
    }

    private void setStatus(int text, boolean live) {
        setStatus(activity.getString(text), live);
    }

    private void setStatus(String text, boolean live) {
        if (!text.contentEquals(status.getText())) status.setText(text);
        dot.setBackground(round(live ? 0xFF34D17A : 0xFFE0A84E, 4));
    }

    // ---- scenes ----------------------------------------------------------------------------------

    private void renderScenes(JSONObject plan) {
        boolean hadFocus = false;
        for (SceneTile t : scenes) hadFocus |= t.hasFocus();
        grid.removeAllViews();
        scenes.clear();
        JSONArray list = plan == null ? null : plan.optJSONArray("scenes");
        int count = list == null ? 0 : Math.min(MAX_SCENES, list.length());
        for (int i = 0; i < count; i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            SceneTile t = new SceneTile(s.optString("id"), s.optString("name", s.optString("id")), s.optString("icon", "sun"));
            grid.addView(t, new LayoutParams(tileW, tileH));
            scenes.add(t);
        }
        placeTiles();
        int n = scenes.size();
        for (int i = 0; i < n; i++) {
            final int index = i, col = i % GRID_COLUMNS;
            final SceneTile t = scenes.get(i);
            t.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (key) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (index >= GRID_COLUMNS) scenes.get(index - GRID_COLUMNS).requestFocus();
                        else host.panelUp(centerX(t));
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (index + GRID_COLUMNS < n) scenes.get(index + GRID_COLUMNS).requestFocus();
                        else host.panelDown(centerX(t));
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (col > 0) scenes.get(index - 1).requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (col < GRID_COLUMNS - 1 && index + 1 < n) scenes.get(index + 1).requestFocus();
                        else cover.requestFocus();
                        return true;
                    default:
                        return false;
                }
            });
        }
        if (hadFocus) focusNearest(0);
    }

    private void runScene(String id, String name) {
        if (busy) return;
        if (current == null || !current.optBoolean("controlReady")) {
            host.openHouseControl();
            return;
        }
        busy = true;
        for (SceneTile t : scenes) t.paint();
        setStatus(activity.getString(R.string.house_setting_scene, name), true);
        final int code = current.optBoolean("demo") ? RUN_DEMO_SCENE : RUN_SCENE;
        worker.execute(() -> {
            JSONObject result;
            try {
                result = transact(code, id);
            } catch (Exception error) {
                result = null;
            }
            final JSONObject done = result;
            handler.post(() -> {
                busy = false;
                if (!attached) return;
                if (done == null || done.has("error")) {
                    warningUntil = System.currentTimeMillis() + 15_000;
                    setStatus(R.string.house_some_failed, false);
                    Toast.makeText(activity, done == null ? activity.getString(R.string.house_scene_failed)
                            : done.optString("error"), Toast.LENGTH_LONG).show();
                } else {
                    warningUntil = 0;
                    JSONObject home = done.optJSONObject("home");
                    if (home != null) {
                        snapshot = home.toString();
                        update(home);
                    }
                }
                for (SceneTile t : scenes) t.paint();
                fetch();
            });
        });
    }

    /** One scene: a glass tile with a vector glyph; white when focused, warm when it is the active scene. */
    private final class SceneTile extends FrameLayout {
        final String id, name;
        final SceneIcon glyph;
        final TextView label;
        final View mark;
        boolean selected, focused;

        SceneTile(String id, String name, String icon) {
            super(activity);
            this.id = id;
            this.name = name;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClipChildren(false);
            setContentDescription(name);
            glyph = new SceneIcon(activity);
            glyph.setKind(icon);
            addView(glyph, new LayoutParams(px(28), px(28), Gravity.CENTER_HORIZONTAL));
            label = text(name, 12, 0xF2FFFFFF, 500);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addView(label, new LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
            mark = new View(activity);
            mark.setBackground(round(0xFFFFC46B, 2.5f));
            mark.setAlpha(0f);
            addView(mark, new LayoutParams(px(5), px(5), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
            applyBlur();
            paint();
            setOnFocusChangeListener((v, f) -> {
                focused = f;
                paint();
                if (f) host.panelFocused();
                float s = f ? 1.05f : 1f;
                animate().scaleX(s).scaleY(s).setDuration(Motion.ms(f ? 260 : 200)).start();
            });
            setOnClickListener(v -> runScene(id, name));
        }

        /** Glyph, name and active mark scale with the tile, which follows the room between the top bar and the dock. */
        void size(int w, int h) {
            int icon = Math.round(h * 0.28f);
            LayoutParams lp = (LayoutParams) glyph.getLayoutParams();
            lp.width = icon;
            lp.height = icon;
            lp.topMargin = Math.round(h * 0.2f);
            glyph.setLayoutParams(lp);
            lp = (LayoutParams) label.getLayoutParams();
            lp.topMargin = Math.round(h * 0.6f);
            lp.leftMargin = px(4);
            lp.rightMargin = px(4);
            label.setLayoutParams(lp);
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(px(11), Math.min(px(15), h * 0.15f)));
            lp = (LayoutParams) mark.getLayoutParams();
            lp.bottomMargin = Math.round(h * 0.07f);
            mark.setLayoutParams(lp);
        }

        void markActive(boolean value) {
            if (value == selected) return;
            selected = value;
            paint();
        }

        /** Frosted like the top bar's buttons with blur on; their solid pill otherwise. */
        void applyBlur() {
            setBackgroundResource(R.drawable.scene_bg);
            Frost.apply(this, host.wallpaperFrost(), px(22), 0x33000000, false);
        }

        void paint() {
            // The background (frosted glass or pill) turns white on focus and brighter while activated.
            setActivated(selected);
            // The active scene glows warm like a lit light; focus is a white edge, like the 3D card's.
            android.graphics.drawable.Drawable glow = selected ? activeGlow(22) : null, edge = focused ? focusEdge(22) : null;
            setForeground(glow != null && edge != null ? new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{glow, edge}) : glow != null ? glow : edge);
            glyph.setColor(selected ? 0xFFFFD08A : 0xFFFFFFFF);
            label.setTextColor(0xFFFFFFFF);
            mark.animate().alpha(selected ? 1f : 0f).setDuration(Motion.ms(240)).start();
            setAlpha(busy && !focused ? 0.55f : 1f);
        }
    }

    // ---- focus -----------------------------------------------------------------------------------

    /** Up from the dock (or down from the top bar): the bottom-row scene or the model nearest to {@code x}. */
    void focusNearest(float x) {
        View best = cover;
        float distance = Math.abs(centerX(card) - x);
        int lastRow = scenes.isEmpty() ? 0 : (scenes.size() - 1) / GRID_COLUMNS * GRID_COLUMNS;
        for (int i = lastRow; i < scenes.size(); i++) {
            SceneTile t = scenes.get(i);
            float d = Math.abs(centerX(t) - x);
            if (d < distance) {
                distance = d;
                best = t;
            }
        }
        best.requestFocus();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private int px(float dp) {
        return Math.round(dp * density);
    }

    private float centerX(View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return l[0] + v.getWidth() / 2f;
    }

    private TextView text(String value, float sp, int color, int weight) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radiusDp));
        return d;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xE6101A2B, 0xE60B111D});
        d.setCornerRadius(px(20));
        return d;
    }

    /** The active scene's warm glow: an amber wash over its glass, brighter at the top, with a warm rim. */
    private GradientDrawable activeGlow(float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x55FFC46B, 0x1FFFC46B});
        d.setCornerRadius(px(radiusDp));
        d.setStroke(Math.max(1, px(1f)), 0x99FFC46B);
        return d;
    }

    /** The white edge a focused scene button or card shows. */
    private GradientDrawable focusEdge(float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(px(radiusDp));
        d.setStroke(Math.max(1, px(1f)), 0xF2FFFFFF);
        return d;
    }

    /** The card's edge: a faint rim, white when focused. */
    private GradientDrawable ring(boolean focus) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(px(20));
        d.setStroke(Math.max(1, px(focus ? 1f : 0.8f)), focus ? 0xF2FFFFFF : 0x26FFFFFF);
        return d;
    }
}
