package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Sleep timer: turns the TV off after a while, even in the middle of a film. Android lets an app turn the
 * screen off only as a device admin ({@link DevicePolicyManager#lockNow()}, which puts a TV in standby),
 * so scripts/deploy.sh makes the launcher one over adb.
 */
final class SleepTimer {

    static final int[] MINUTES = {0, 15, 30, 45, 60, 90, 120};
    private static final long WARN_BEFORE_MS = 60_000L;
    private static final String ACTION_WARN = "com.gathin.tvhome.SLEEP_WARN";
    private static final String ACTION_SLEEP = "com.gathin.tvhome.SLEEP";

    private SleepTimer() {
    }

    static ComponentName admin(Context ctx) {
        return new ComponentName(ctx, Admin.class);
    }

    /** True once deploy.sh has made the launcher a device admin. */
    static boolean canSleep(Context ctx) {
        DevicePolicyManager dpm = ctx.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(admin(ctx));
    }

    /** Minutes left, rounded up, or 0 when the timer is off or already past. */
    static int minutesLeft(Context ctx) {
        long left = Prefs.sleepAt(ctx) - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 59_999L) / 60_000L);
    }

    /** Starts the timer for {@code minutes}, or cancels it for 0. */
    static void set(Context ctx, int minutes) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent warn = pending(ctx, ACTION_WARN), sleep = pending(ctx, ACTION_SLEEP);
        if (am != null) {
            am.cancel(warn);
            am.cancel(sleep);
        }
        if (minutes <= 0 || am == null) {
            Prefs.setSleepAt(ctx, 0L);
            return;
        }
        long at = System.currentTimeMillis() + minutes * 60_000L;
        Prefs.setSleepAt(ctx, at);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - WARN_BEFORE_MS, warn);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, sleep);
    }

    /**
     * Re-arms a running timer when the launcher's process starts again (after an update, or if Android
     * stopped it), and clears one that is already past.
     */
    static void restore(Context ctx) {
        long at = Prefs.sleepAt(ctx), now = System.currentTimeMillis();
        if (at == 0L) return;
        if (at <= now) {
            Prefs.setSleepAt(ctx, 0L);
            return;
        }
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        if (at - WARN_BEFORE_MS > now) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - WARN_BEFORE_MS, pending(ctx, ACTION_WARN));
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, ACTION_SLEEP));
    }

    private static PendingIntent pending(Context ctx, String action) {
        Intent i = new Intent(ctx, Receiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The alarms: a warning a minute before, then standby. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Context ctx = Prefs.wrapLocale(context);
            if (ACTION_WARN.equals(intent.getAction())) {
                if (Prefs.sleepAt(ctx) > 0) Toast.makeText(ctx, R.string.sleep_warning, Toast.LENGTH_LONG).show();
                return;
            }
            if (!ACTION_SLEEP.equals(intent.getAction()) || Prefs.sleepAt(ctx) == 0L) return;
            Prefs.setSleepAt(ctx, 0L);
            DevicePolicyManager dpm = ctx.getSystemService(DevicePolicyManager.class);
            if (dpm != null && dpm.isAdminActive(admin(ctx))) dpm.lockNow();
        }
    }

    /** The device admin that may turn the screen off. It asks for nothing else. */
    public static class Admin extends DeviceAdminReceiver {
    }
}
