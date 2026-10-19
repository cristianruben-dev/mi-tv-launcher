package com.gathin.tvhome;

/** The launcher's animations switch (Settings → Performance). Durations collapse to 0 when off. */
final class Motion {

    static boolean enabled = true;

    private Motion() {
    }

    static long ms(long duration) {
        return enabled ? duration : 0L;
    }
}
