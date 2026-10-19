package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Control Center, like tvOS: a glass panel hanging from its pill in the top bar, over the blurred home
 * screen. The time; switches for Wi-Fi, Bluetooth, mute and the screensaver; and tiles to turn off, set the
 * sleep timer, free memory and open the TV's settings.
 */
final class ControlCenter {

    interface Host {
        /** Blurs the home screen behind the panel, keeping {@code sharp} (the pill) in focus. */
        void showBackdrop(View sharp);

        void hideBackdrop();

        /** The blurred home screen behind the panel, for its glass. */
        Frost.Source menuFrost();

        void openTvSettings();

        void onSleepTimerChanged();
    }

    /** A switch's state and how to change it; {@code set} is false when the TV refuses. */
    private interface Toggle {
        boolean isOn();

        boolean set(boolean on);
    }

    /** Screensaver delay when the switch turns it on. */
    private static final int SCREENSAVER_ON_MINUTES = 10;

    private static final int PANEL_WIDTH_DP = 360;

    private final Activity act;
    private final Host host;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PopupWindow popup;
    /** The first switch, where focus starts. */
    private View firstSwitch;

    ControlCenter(Activity act, Host host) {
        this.act = act;
        this.host = host;
        this.density = act.getResources().getDisplayMetrics().density;
    }

    void show(View anchor) {
        View root = act.findViewById(R.id.root);
        View panel = act.getLayoutInflater().inflate(R.layout.popup_control_center, (ViewGroup) root, false);
        Clocks.apply(act, panel.findViewById(R.id.ccClock), (TextClock) panel.findViewById(R.id.ccAmpm));
        LinearLayout tiles = panel.findViewById(R.id.tiles);

        LinearLayout switches = panel.findViewById(R.id.switches);
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
        addSwitch(switches, R.drawable.ic_screensaver, act.getString(R.string.cc_screensaver), R.string.needs_grant, new Toggle() {
            public boolean isOn() {
                return SystemTweaks.screensaverMinutes(act) > 0;
            }

            public boolean set(boolean on) {
                return SystemTweaks.setScreensaver(act, on ? SCREENSAVER_ON_MINUTES : 0);
            }
        });

        LinearLayout row = addRow(tiles);
        View off = addTile(row, R.drawable.ic_power, act.getString(R.string.cc_turn_off),
                act.getString(R.string.cc_turn_off_hint));
        off.setOnClickListener(v -> {
            popup.dismiss();
            if (!SleepTimer.sleepNow(act)) Toast.makeText(act, R.string.needs_grant, Toast.LENGTH_LONG).show();
        });
        final View sleep = addTile(row, R.drawable.ic_sleep, act.getString(R.string.section_sleep), sleepStatus());
        sleep.setOnClickListener(v -> nextSleepTimer(sleep));

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
            host.hideBackdrop();
        });

        host.showBackdrop(anchor);
        Frost.apply(panel, host.menuFrost(), 20 * density, 0x40000000);
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

    private String memoryStatus() {
        ActivityManager.MemoryInfo info = SystemTweaks.memory(act);
        return act.getString(R.string.cc_free, Formatter.formatShortFileSize(act, info.availMem));
    }

    // ---- switches --------------------------------------------------------------------------------

    private void addSwitch(LinearLayout row, int icon, String label, int refusedMessage, Toggle toggle) {
        View item = act.getLayoutInflater().inflate(R.layout.item_cc_switch, row, false);
        final ImageView button = item.findViewById(R.id.button);
        button.setId(View.generateViewId());
        if (firstSwitch == null) firstSwitch = button;
        button.setImageResource(icon);
        button.setContentDescription(label);
        ((TextView) item.findViewById(R.id.label)).setText(label);
        button.setActivated(toggle.isOn());
        button.setOnFocusChangeListener((v, focused) -> v.animate()
                .scaleX(focused ? 1.1f : 1f).scaleY(focused ? 1.1f : 1f).setDuration(Motion.ms(180)).start());
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
        row.addView(item);
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
