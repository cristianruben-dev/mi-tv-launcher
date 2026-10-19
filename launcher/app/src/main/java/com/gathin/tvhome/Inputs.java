package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The TV's own inputs (HDMI, AV) and Live TV, for "Input at power on" and opening them. */
final class Inputs {

    static final class Item {
        final String key;
        final String label;

        Item(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private Inputs() {
    }

    /** HDMI ports in order, then AV and other passthrough inputs, then Live TV when the TV has it. */
    static List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(ctx)).compareToIgnoreCase(String.valueOf(b.loadLabel(ctx)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(ctx);
                String label = l == null ? info.getId() : l.toString();
                if (info.getType() == TvInputInfo.TYPE_HDMI && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                out.add(new Item(info.getId(), label));
            }
        }
        if (liveTvIntent(ctx) != null) out.add(new Item(Prefs.POWER_ON_LIVE_TV, ctx.getString(R.string.live_tv)));
        return out;
    }

    /** The intent that opens an input (or Live TV), or null when it is gone. */
    static Intent intentFor(Context ctx, String key) {
        if (key == null || key.isEmpty()) return null;
        Intent i = Prefs.POWER_ON_LIVE_TV.equals(key) ? liveTvIntent(ctx)
                : new Intent(Intent.ACTION_VIEW).setDataAndType(
                TvContract.buildChannelUriForPassthroughInput(key), "vnd.android.cursor.item/channel");
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static Intent liveTvIntent(Context ctx) {
        String pkg = ctx.getString(R.string.live_tv_package).trim();
        return pkg.isEmpty() ? null : ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
    }

    /** Opens the input chosen for power on, if any. Returns false when there is nothing to open. */
    static boolean openPowerOnInput(Context ctx) {
        Intent i = intentFor(ctx, Prefs.powerOnInput(ctx));
        if (i == null) return false;
        try {
            ctx.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
