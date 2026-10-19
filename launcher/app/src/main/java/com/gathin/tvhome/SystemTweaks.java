package com.gathin.tvhome;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * TV-wide settings the launcher can change once scripts/deploy.sh has granted it WRITE_SECURE_SETTINGS
 * and WRITE_SETTINGS over adb: the screensaver and the system animation speed. Also memory stats.
 */
final class SystemTweaks {

    /** Screensaver delays offered in settings, in minutes; 0 turns our screensaver off. */
    static final int[] SCREENSAVER_MINUTES = {0, 5, 10, 15, 30, 60};
    /** System animation speeds offered in settings: normal, fast, off. */
    static final float[] ANIMATION_SCALES = {1f, 0.5f, 0f};

    private static final String SCREENSAVER_ENABLED = "screensaver_enabled";
    private static final String SCREENSAVER_COMPONENTS = "screensaver_components";
    private static final String SCREENSAVER_ON_SLEEP = "screensaver_activate_on_sleep";
    private static final String[] ANIMATION_KEYS = {Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE};

    private SystemTweaks() {
    }

    /** True once the adb grants are in place. */
    static boolean canWrite(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                && Settings.System.canWrite(ctx);
    }

    private static ComponentName dream(Context ctx) {
        return new ComponentName(ctx, ClockDream.class);
    }

    /** Minutes before our screensaver starts, or 0 when it is off or another screensaver is chosen. */
    static int screensaverMinutes(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        if (Settings.Secure.getInt(cr, SCREENSAVER_ENABLED, 0) == 0) return 0;
        String comps = Settings.Secure.getString(cr, SCREENSAVER_COMPONENTS);
        ComponentName chosen = comps == null ? null : ComponentName.unflattenFromString(comps.split(",")[0]);
        if (!dream(ctx).equals(chosen)) return 0;
        int ms = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 0);
        return Math.max(1, Math.round(ms / 60000f));
    }

    /**
     * Turns our screensaver on after {@code minutes} of inactivity, or off for 0. Off only disables the
     * screensaver; the TV's own screen-off timer is left as it was. Returns false without the grants.
     */
    static boolean setScreensaver(Context ctx, int minutes) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            if (minutes <= 0) {
                Settings.Secure.putInt(cr, SCREENSAVER_ENABLED, 0);
                return true;
            }
            Settings.Secure.putString(cr, SCREENSAVER_COMPONENTS, dream(ctx).flattenToString());
            Settings.Secure.putInt(cr, SCREENSAVER_ENABLED, 1);
            Settings.Secure.putInt(cr, SCREENSAVER_ON_SLEEP, 1);
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, minutes * 60_000);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Current system animation speed (the animator scale stands for all three). */
    static float animationScale(Context ctx) {
        return Settings.Global.getFloat(ctx.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    static boolean setAnimationScale(Context ctx, float scale) {
        try {
            for (String key : ANIMATION_KEYS) Settings.Global.putFloat(ctx.getContentResolver(), key, scale);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    // ---- memory ----------------------------------------------------------------------------------

    static ActivityManager.MemoryInfo memory(Context ctx) {
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(info);
        return info;
    }

    /** Asks Android to end the background processes of every other app. Run off the main thread. */
    static void killBackgroundApps(Context ctx) {
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        if (am == null) return;
        for (ApplicationInfo ai : ctx.getPackageManager().getInstalledApplications(0)) {
            // Persistent system processes (the TV service, Bluetooth…) are not ours to stop.
            if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) continue;
            if (!ai.packageName.equals(ctx.getPackageName())) am.killBackgroundProcesses(ai.packageName);
        }
    }
}
