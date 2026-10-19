package com.gathin.tvhome;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * Listens for the TV turning on (screen on after standby, or a cold boot) for the power-on input and for
 * "change wallpaper at power on". SCREEN_ON can only be received by a registered receiver, so it lives
 * here, for as long as the launcher's process does.
 */
public class TvHomeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SleepTimer.restore(this);
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onPowerOn(context);
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    static void onPowerOn(Context ctx) {
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
