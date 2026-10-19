package com.gathin.tvhome;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextClock;

import java.util.Date;

/** The 12/24-hour choice (Settings → Home screen) applied to the clocks and to times the launcher shows. */
final class Clocks {

    private Clocks() {
    }

    /** Sets a big clock and its AM/PM label to the chosen format; the label hides in 24-hour time. */
    static void apply(Context ctx, TextClock clock, TextClock ampm) {
        String f = Prefs.timeFormat(ctx);
        if (Prefs.TIME_24.equals(f)) {
            clock.setFormat12Hour("HH:mm");
            clock.setFormat24Hour("HH:mm");
            if (ampm != null) ampm.setVisibility(View.GONE);
        } else if (Prefs.TIME_12.equals(f)) {
            clock.setFormat12Hour("h:mm");
            clock.setFormat24Hour("h:mm");
            if (ampm != null) {
                ampm.setFormat12Hour("a");
                ampm.setFormat24Hour("a");
                ampm.setVisibility(View.VISIBLE);
            }
        } else {
            clock.setFormat12Hour("h:mm");
            clock.setFormat24Hour("HH:mm");
            if (ampm != null) {
                ampm.setFormat12Hour("a");
                ampm.setFormat24Hour("");
                ampm.setVisibility(View.VISIBLE);
            }
        }
    }

    static boolean is24Hour(Context ctx) {
        String f = Prefs.timeFormat(ctx);
        if (Prefs.TIME_24.equals(f)) return true;
        if (Prefs.TIME_12.equals(f)) return false;
        return DateFormat.is24HourFormat(ctx);
    }

    /** A length of time as offered in the menus: "Off" (the given label), "10 minutes", "1 hour", "1 h 30 min". */
    static String duration(Context ctx, int minutes, int offLabel) {
        if (minutes <= 0) return ctx.getString(offLabel);
        if (minutes % 60 == 0) {
            return minutes == 60 ? ctx.getString(R.string.hour_option) : ctx.getString(R.string.hours_option, minutes / 60);
        }
        if (minutes > 60) return ctx.getString(R.string.hour_minutes_option, minutes / 60, minutes % 60);
        return ctx.getString(R.string.minutes_option, minutes);
    }

    /** A wall clock time as text, e.g. "10:45 p. m." or "22:45". */
    static String time(Context ctx, long millis) {
        String pattern = is24Hour(ctx) ? "HH:mm" : "h:mm a";
        return DateFormat.format(pattern, new Date(millis)).toString();
    }
}
