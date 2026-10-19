package com.gathin.tvhome;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;

/**
 * Listens for the TV turning on (screen on after standby, or a cold boot) for the power-on input and for
 * "change wallpaper at power on", and for it turning off, which cancels the sleep timer. SCREEN_ON and
 * SCREEN_OFF can only be received by a registered receiver, so it lives here, for as long as the
 * launcher's process does.
 */
public class TvHomeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SleepTimer.restore(this);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    // Turned off with the remote: a sleep timer set for this session must not turn the TV
                    // off again later, after it has been turned back on.
                    if (Prefs.sleepAt(context) != 0L) SleepTimer.set(context, 0);
                } else {
                    onPowerOn(context);
                }
            }
        }, filter);
    }

    /** A cold boot sends both BOOT_COMPLETED and SCREEN_ON: handle the pair once. */
    private static final long POWER_ON_DEBOUNCE_MS = 30_000L;
    private static long lastPowerOn;

    static void onPowerOn(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (lastPowerOn != 0L && now - lastPowerOn < POWER_ON_DEBOUNCE_MS) return;
        lastPowerOn = now;
        if (Prefs.ROTATE_ON_POWER.equals(Prefs.rotation(ctx))) Prefs.setRotatePending(ctx, true);
        Inputs.openPowerOnInput(ctx);
    }

    /** Cold boot: the same as turning on from standby. */
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) onPowerOn(context);
        }
    }
}
