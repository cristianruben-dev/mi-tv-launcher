package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

/** "Try it now" in Settings → Screensaver: the screensaver scene full screen until any key is pressed. */
public class ScreensaverActivity extends Activity {

    private ClockScene scene;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Prefs.wrapLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dream_clock);
        View root = findViewById(android.R.id.content);
        scene = new ClockScene(this, root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        scene.start();
    }

    @Override
    protected void onPause() {
        scene.stop();
        super.onPause();
        finish();   // like the real screensaver: leaving it ends it
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        finish();
        return true;
    }
}
