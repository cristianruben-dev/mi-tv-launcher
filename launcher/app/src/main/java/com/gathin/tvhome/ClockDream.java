package com.gathin.tvhome;

import android.content.Context;
import android.service.dreams.DreamService;

/**
 * Screensaver: the launcher's clock over the wallpaper or an ambient glow (see {@link ClockScene}).
 * Chosen and timed in Settings → Screensaver. No network: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private ClockScene scene;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Prefs.wrapLocale(base));
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        scene = new ClockScene(this, getWindow().getDecorView());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
