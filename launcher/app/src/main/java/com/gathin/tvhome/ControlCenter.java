package com.gathin.tvhome;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.IntConsumer;

/**
 * Control Center, like tvOS: a glass panel hanging from its pill in the top bar, over the blurred home
 * screen. The time; switches for Wi-Fi, Bluetooth and mute, and a button to turn off; and tiles for the
 * screensaver, the sleep timer, freeing memory and the TV's settings. Holding OK on the screensaver or the
 * sleep timer grows its tile into a card with its durations. Focus is a white ring, so a switch's colour
 * only ever means on or off.
 */
final class ControlCenter {

    interface Host {
        /** Blurs the home screen behind the panel, keeping {@code sharp} (the pill) in focus. */
        void showBackdrop(View sharp);

        void hideBackdrop();

        /** The blurred home screen behind the panel, for its glass. */
        Frost.Source menuFrost();

        void onSleepTimerChanged();

        void openTvSettings();
    }

    /** A switch's state and how to change it; {@code set} is false when the TV refuses. */
    private interface Toggle {
        boolean isOn();

        boolean set(boolean on);
    }

    /** Screensaver delay when its tile turns it on. */
    private static final int SCREENSAVER_ON_MINUTES = 10;

    private static final int PANEL_WIDTH_DP = 360;

    private final Activity act;
    private final Host host;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PopupWindow popup;
    /** The first switch, where focus starts. */
    private View firstSwitch;
    /** The panel's glass, and the switches and tiles that fade while a card covers them. */
    private ViewGroup glass;
    private View switchesView, tilesView;
    /** The open choices card, the tile it grew from, and its growing. */
    private View card, cardTile;
    private ValueAnimator morph;
    private final Rect from = new Rect(), to = new Rect();

    ControlCenter(Activity act, Host host) {
        this.act = act;
        this.host = host;
        this.density = act.getResources().getDisplayMetrics().density;
    }

    void show(View anchor) {
        View root = act.findViewById(R.id.root);
        glass = (ViewGroup) act.getLayoutInflater().inflate(R.layout.popup_control_center, (ViewGroup) root, false);
        // Back folds an open card away before it closes the panel: the popup's own window takes Back first.
        FrameLayout panel = new FrameLayout(act) {
            private boolean foldedOnDown;

            @Override
            public boolean dispatchKeyEventPreIme(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN && card != null) {
                        if (e.getRepeatCount() == 0) closeChoices();
                        foldedOnDown = true;
                        return true;
                    }
                    if (e.getAction() == KeyEvent.ACTION_UP && foldedOnDown) {
                        foldedOnDown = false;
                        return true;
                    }
                }
                return super.dispatchKeyEventPreIme(e);
            }
        };
        panel.setClipChildren(false);
        panel.addView(glass);
        Clocks.apply(act, glass.findViewById(R.id.ccClock), (TextClock) glass.findViewById(R.id.ccAmpm));
        LinearLayout tiles = glass.findViewById(R.id.tiles);
        tilesView = tiles;

        LinearLayout switches = glass.findViewById(R.id.switches);
        switchesView = switches;
        final WifiManager wifi = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            addSwitch(switches, R.drawable.ic_wifi, act.getString(R.string.wifi), R.string.cc_cant_switch, new Toggle() {
                public boolean isOn() {
                    return wifi.isWifiEnabled();
                }

                public boolean set(boolean on) {
                    return wifi.setWifiEnabled(on);
                }
            });
        }
        final BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt != null) {
            addSwitch(switches, R.drawable.ic_bluetooth, act.getString(R.string.cc_bluetooth), R.string.cc_cant_switch, new Toggle() {
                public boolean isOn() {
                    return bt.isEnabled();
                }

                public boolean set(boolean on) {
                    return on ? bt.enable() : bt.disable();
                }
            });
        }
        final AudioManager audio = act.getSystemService(AudioManager.class);
        if (audio != null) {
            addSwitch(switches, R.drawable.ic_mute, act.getString(R.string.cc_mute), R.string.cc_cant_switch, new Toggle() {
                public boolean isOn() {
                    return audio.isStreamMute(AudioManager.STREAM_MUSIC);
                }

                public boolean set(boolean on) {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
                    return true;
                }
            });
        }
        View off = addButton(switches, R.drawable.ic_power, act.getString(R.string.cc_turn_off));
        off.setOnClickListener(v -> {
            popup.dismiss();
            if (!SleepTimer.sleepNow(act)) Toast.makeText(act, R.string.needs_grant, Toast.LENGTH_LONG).show();
        });

        LinearLayout row = addRow(tiles);
        final View saver = addTile(row, R.drawable.ic_screensaver, act.getString(R.string.section_screensaver),
                screensaverStatus());
        saver.setOnClickListener(v -> {
            // OK turns it on at the usual delay, or off.
            int m = SystemTweaks.screensaverMinutes(act) > 0 ? 0 : SCREENSAVER_ON_MINUTES;
            if (!SystemTweaks.setScreensaver(act, m)) {
                Toast.makeText(act, R.string.needs_grant, Toast.LENGTH_LONG).show();
                return;
            }
            setStatus(saver, screensaverStatus());
        });
        saver.setOnLongClickListener(v -> {
            showChoices(saver, R.drawable.ic_screensaver, act.getString(R.string.section_screensaver),
                    SystemTweaks.SCREENSAVER_MINUTES, R.string.screensaver_off, SystemTweaks.screensaverMinutes(act), m -> {
                        if (!SystemTweaks.setScreensaver(act, m)) {
                            Toast.makeText(act, R.string.needs_grant, Toast.LENGTH_LONG).show();
                            return;
                        }
                        setStatus(saver, screensaverStatus());
                    });
            return true;
        });
        final View sleep = addTile(row, R.drawable.ic_sleep, act.getString(R.string.section_sleep), sleepStatus());
        sleep.setOnClickListener(v -> nextSleepTimer(sleep));
        sleep.setOnLongClickListener(v -> {
            showChoices(sleep, R.drawable.ic_sleep, act.getString(R.string.section_sleep),
                    SleepTimer.MINUTES, R.string.sleep_off, sleepChoice(), m -> {
                if (m > 0 && !SleepTimer.canSleep(act)) {
                    Toast.makeText(act, R.string.needs_grant, Toast.LENGTH_LONG).show();
                    return;
                }
                SleepTimer.set(act, m);
                setStatus(sleep, sleepStatus());
                host.onSleepTimerChanged();
            });
            return true;
        });

        row = addRow(tiles);
        final View memory = addTile(row, R.drawable.ic_performance, act.getString(R.string.free_memory), memoryStatus());
        memory.setOnClickListener(v -> freeMemory(memory));
        View tv = addTile(row, R.drawable.ic_settings, act.getString(R.string.section_tv_settings), null);
        tv.setOnClickListener(v -> {
            popup.dismiss();
            host.openTvSettings();
        });
        ((ViewGroup.MarginLayoutParams) row.getLayoutParams()).bottomMargin = 0;

        popup = new PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            handler.removeCallbacksAndMessages(null);
            if (morph != null) morph.cancel();
            card = null;
            cardLeaving = null;
            host.hideBackdrop();
        });

        host.showBackdrop(anchor);
        Frost.apply(glass, host.menuFrost(), 20 * density, 0x40000000);
        // Under the pill, lined up with the right end of the top bar, unfolding from the pill.
        int width = (int) (PANEL_WIDTH_DP * density);
        popup.setWidth(width);
        View bar = (View) anchor.getParent();
        int[] barAt = new int[2], pillAt = new int[2];
        bar.getLocationInWindow(barAt);
        anchor.getLocationInWindow(pillAt);
        int x = barAt[0] + bar.getWidth() - width;
        int y = pillAt[1] + anchor.getHeight() + (int) (10 * density);
        panel.setPivotX(pillAt[0] + anchor.getWidth() / 2f - x);
        panel.setPivotY(0f);
        panel.setAlpha(0f);
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.ms(240))
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(panel::invalidate).start();   // the glass follows where the panel settled
        (firstSwitch != null ? firstSwitch : off).requestFocus();
    }

    // ---- actions ---------------------------------------------------------------------------------

    /** OK steps through the sleep timer's choices: off, 15 min, 30 min… and back to off. */
    private void nextSleepTimer(View tile) {
        // A running timer counts down between two choices: step to the next one up, or off after the last.
        int left = SleepTimer.minutesLeft(act);
        int next = left == 0 ? SleepTimer.MINUTES[1] : 0;
        if (left > 0) {
            for (int m : SleepTimer.MINUTES) {
                if (m > left) {
                    next = m;
                    break;
                }
            }
        }
        if (next > 0 && !SleepTimer.canSleep(act)) {
            Toast.makeText(act, R.string.needs_grant, Toast.LENGTH_LONG).show();
            return;
        }
        SleepTimer.set(act, next);
        setStatus(tile, sleepStatus());
        host.onSleepTimerChanged();
    }

    /** The sleep timer's choice that is running: the smallest one not below the minutes left. */
    private int sleepChoice() {
        int left = SleepTimer.minutesLeft(act);
        if (left == 0) return 0;
        for (int m : SleepTimer.MINUTES) if (m >= left) return m;
        return SleepTimer.MINUTES[SleepTimer.MINUTES.length - 1];
    }

    private String sleepStatus() {
        int left = SleepTimer.minutesLeft(act);
        return left > 0 ? act.getString(R.string.cc_minutes, left) : act.getString(R.string.sleep_off);
    }

    private boolean freeing;

    private void freeMemory(View tile) {
        if (freeing) return;
        freeing = true;
        final long before = SystemTweaks.memory(act).availMem;
        new Thread(() -> {
            SystemTweaks.killBackgroundApps(act);
            handler.postDelayed(() -> {
                long freed = Math.max(0, SystemTweaks.memory(act).availMem - before);
                Toast.makeText(act, act.getString(R.string.memory_freed,
                        Formatter.formatShortFileSize(act, freed)), Toast.LENGTH_SHORT).show();
                setStatus(tile, memoryStatus());
                freeing = false;
            }, 1200);
        }, "free-memory").start();
    }

    private String screensaverStatus() {
        return Clocks.duration(act, SystemTweaks.screensaverMinutes(act), R.string.screensaver_off);
    }

    private String memoryStatus() {
        ActivityManager.MemoryInfo info = SystemTweaks.memory(act);
        return act.getString(R.string.cc_free, Formatter.formatShortFileSize(act, info.availMem));
    }

    // ---- switches --------------------------------------------------------------------------------

    /** A round button with its name below, in the switches' row. */
    private View addButton(LinearLayout row, int icon, String label) {
        View item = act.getLayoutInflater().inflate(R.layout.item_cc_switch, row, false);
        final ImageView button = item.findViewById(R.id.button);
        button.setId(View.generateViewId());
        if (firstSwitch == null) firstSwitch = button;
        button.setImageResource(icon);
        button.setContentDescription(label);
        ((TextView) item.findViewById(R.id.label)).setText(label);
        button.setOnFocusChangeListener((v, focused) -> v.animate()
                .scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(Motion.ms(180)).start());
        row.addView(item);
        return button;
    }

    private void addSwitch(LinearLayout row, int icon, String label, int refusedMessage, Toggle toggle) {
        final View button = addButton(row, icon, label);
        button.setActivated(toggle.isOn());
        button.setOnClickListener(v -> {
            boolean want = !toggle.isOn();
            boolean ok;
            try {
                ok = toggle.set(want);
            } catch (SecurityException e) {
                ok = false;
            }
            if (!ok) {
                Toast.makeText(act, refusedMessage, Toast.LENGTH_LONG).show();
                return;
            }
            button.setActivated(want);
            // Wi-Fi and Bluetooth switch in the background: show what really happened.
            handler.postDelayed(() -> button.setActivated(toggle.isOn()), 2500);
        });
    }

    // ---- choices ---------------------------------------------------------------------------------

    /**
     * Hold OK on the screensaver or the sleep timer: its tile grows into a card over the switches and
     * tiles, with its durations and the one in use in blue. OK picks one and Back keeps the current one;
     * either way the card shrinks back into the tile.
     */
    private void showChoices(View tile, int icon, String title, int[] minutes, int offLabel, int current,
                             IntConsumer pick) {
        if (card != null || cardLeaving != null) return;   // still growing or shrinking
        FrameLayout panel = (FrameLayout) glass.getParent();
        // From the tile to everything under the clock, in the panel's coordinates.
        from.set(0, 0, tile.getWidth(), tile.getHeight());
        panel.offsetDescendantRectToMyCoords(tile, from);
        to.set(0, 0, switchesView.getWidth(), switchesView.getHeight());
        panel.offsetDescendantRectToMyCoords(switchesView, to);
        Rect tilesAt = new Rect(0, 0, tilesView.getWidth(), tilesView.getHeight());
        panel.offsetDescendantRectToMyCoords(tilesView, tilesAt);
        to.bottom = tilesAt.bottom;

        View content = act.getLayoutInflater().inflate(R.layout.cc_choices, panel, false);
        ((ImageView) content.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) content.findViewById(R.id.title)).setText(title);
        LinearLayout list = content.findViewById(R.id.choices);
        View focus = null;
        LinearLayout line = null;
        int gap = (int) (8 * density);
        for (int i = 0; i < minutes.length; i++) {
            final int m = minutes[i];
            if (i % 2 == 0) {
                line = new LinearLayout(act);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) lp.topMargin = gap;
                list.addView(line, lp);
            }
            TextView choice = new TextView(act);
            choice.setText(Clocks.duration(act, m, offLabel));
            choice.setTextColor(0xFFFFFFFF);
            choice.setTextSize(14);
            choice.setTypeface(content.<TextView>findViewById(R.id.title).getTypeface());
            choice.setGravity(Gravity.CENTER);
            choice.setSingleLine(true);
            choice.setBackgroundResource(R.drawable.cc_choice_bg);
            choice.setFocusable(true);
            choice.setClickable(true);
            choice.setActivated(m == current);
            choice.setOnClickListener(v -> {
                if (morph != null && morph.isRunning()) return;
                pick.accept(m);
                closeChoices();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (44 * density), 1f);
            if (i % 2 == 1) lp.setMarginStart(gap);
            line.addView(choice, lp);
            if (m == current || focus == null) focus = choice;
        }
        if (minutes.length % 2 == 1) line.addView(new View(act), new LinearLayout.LayoutParams(0, 0, 1f));

        // The card: the durations laid out at their final size, revealed as its rounded outline grows.
        FrameLayout c = new FrameLayout(act);
        c.addView(content, new FrameLayout.LayoutParams(to.width(), to.height()));
        GradientDrawable solid = new GradientDrawable();
        solid.setColor(0xFF242428);
        solid.setCornerRadius(20 * density);
        c.setBackground(solid);
        Frost.apply(c, host.menuFrost(), 20 * density, 0x59000000);
        c.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 20 * density);
            }
        });
        c.setClipToOutline(true);
        c.setElevation(glass.getElevation());   // over the glass: same height, added after it
        card = c;
        cardTile = tile;
        panel.addView(c, new FrameLayout.LayoutParams(from.width(), from.height()));
        layoutCard(0f);

        focus.requestFocus();
        glass.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        animateCard(1f, Motion.ms(300), null);
    }

    /** Shrinks the card back into its tile, which takes focus again. */
    private void closeChoices() {
        if (card == null) return;
        final View closing = card, tile = cardTile;
        card = null;
        glass.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        tile.requestFocus();
        cardLeaving = closing;
        animateCard(0f, Motion.ms(240), () -> {
            ((ViewGroup) closing.getParent()).removeView(closing);
            cardLeaving = null;
        });
    }

    /** The card being shrunk away, once {@link #card} already says there is none. */
    private View cardLeaving;

    private void animateCard(float target, long ms, Runnable end) {
        float start = morph != null ? (float) morph.getAnimatedValue() : 1f - target;
        if (morph != null) morph.cancel();
        morph = ValueAnimator.ofFloat(start, target);
        morph.setDuration(ms);
        morph.setInterpolator(new DecelerateInterpolator(1.8f));
        morph.addUpdateListener(a -> layoutCard((float) a.getAnimatedValue()));
        morph.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                morph = null;
                if (end != null && !cancelled) end.run();
            }
        });
        morph.start();
    }

    /** The card {@code t} of the way from its tile (0) to its full size (1). */
    private void layoutCard(float t) {
        View c = card != null ? card : cardLeaving;
        if (c == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) c.getLayoutParams();
        lp.leftMargin = lerp(from.left, to.left, t);
        lp.topMargin = lerp(from.top, to.top, t);
        lp.width = lerp(from.right, to.right, t) - lp.leftMargin;
        lp.height = lerp(from.bottom, to.bottom, t) - lp.topMargin;
        c.setLayoutParams(lp);
        c.invalidateOutline();
        c.invalidate();
        c.setAlpha(Math.min(1f, t / 0.15f));
        ((ViewGroup) c).getChildAt(0).setAlpha(Math.max(0f, (t - 0.4f) / 0.6f));
        float behind = 1f - Math.min(1f, t / 0.6f);
        switchesView.setAlpha(behind);
        tilesView.setAlpha(behind);
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    // ---- tiles -----------------------------------------------------------------------------------

    private LinearLayout addRow(LinearLayout tiles) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (10 * density);
        tiles.addView(row, lp);
        return row;
    }

    private View addTile(LinearLayout row, int icon, String title, String status) {
        View tile = act.getLayoutInflater().inflate(R.layout.item_cc_tile, row, false);
        tile.setId(View.generateViewId());
        ((ImageView) tile.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.title)).setText(title);
        setStatus(tile, status);
        if (row.getChildCount() > 0) {
            ((ViewGroup.MarginLayoutParams) tile.getLayoutParams()).setMarginStart((int) (10 * density));
        }
        row.addView(tile);
        return tile;
    }

    private static void setStatus(View tile, String status) {
        TextView s = tile.findViewById(R.id.status);
        s.setText(status);
        s.setVisibility(status == null ? View.GONE : View.VISIBLE);
    }
}
