package com.domdomegg.launcher;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HDMI inputs: discovered with TvInputManager, switched either the XGIMI way (when XGIMI's player
 * is installed) or the standard Android TV way (a passthrough-channel VIEW intent, handled by the
 * device's Live TV app).
 */
final class Inputs {
    private static final String TAG = Actions.TAG;
    private static final ComponentName XGIMI_PLAYER =
            new ComponentName("com.xgimi.xhplayer", "com.xgimi.xhplayer.InputActivity");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)\\D*$");

    private Inputs() {}

    static final class Input {
        final String id;     // TvInputInfo id, or null if unknown (XGIMI fallback)
        final int number;    // 1-based HDMI port number, used for the card numeral
        final String label;  // the user's custom name for the input, or "HDMI"

        Input(String id, int number, String label) {
            this.id = id;
            this.number = number;
            this.label = label;
        }
    }

    static boolean isXgimi(Context ctx) {
        try {
            ctx.getPackageManager().getActivityInfo(XGIMI_PLAYER, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** HDMI inputs, sorted by port number. */
    static List<Input> hdmi(Context ctx) {
        List<Input> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> infos = Collections.emptyList();
        try {
            if (tim != null) infos = tim.getTvInputList();
        } catch (RuntimeException e) {
            Log.w(TAG, "getTvInputList failed", e);
        }
        List<TvInputInfo> unnumbered = new ArrayList<>();
        for (TvInputInfo info : infos) {
            // Skip non-HDMI inputs, devices behind an HDMI port (HDMI-CEC children) and inputs the
            // user has hidden in Settings.
            if (info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null || info.isHidden(ctx)) continue;
            int n = number(String.valueOf(info.loadLabel(ctx)));
            if (n < 0) n = number(info.getId());
            if (n < 0) {
                unnumbered.add(info);
                continue;
            }
            out.add(new Input(info.getId(), n, customLabel(ctx, info)));
        }
        Collections.sort(out, (a, b) -> Integer.compare(a.number, b.number));
        for (TvInputInfo info : unnumbered) {
            out.add(new Input(info.getId(), out.size() + 1, customLabel(ctx, info)));
        }
        if (out.isEmpty() && isXgimi(ctx)) { // shouldn't happen, but XGIMI switching doesn't need ids
            out.add(new Input(null, 1, "HDMI"));
            out.add(new Input(null, 2, "HDMI"));
        }
        for (Input in : out) Log.i(TAG, "input: HDMI " + in.number + " id=" + in.id + " label=" + in.label);
        return out;
    }

    private static String customLabel(Context ctx, TvInputInfo info) {
        if (Build.VERSION.SDK_INT >= 24) {
            CharSequence c = info.loadCustomLabel(ctx);
            if (c != null && c.length() > 0) return c.toString();
        }
        return "HDMI";
    }

    private static int number(String s) {
        if (s == null) return -1;
        Matcher m = NUMBER.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Switch to the given HDMI input. */
    static boolean open(Context ctx, Input in) {
        if (isXgimi(ctx)) {
            // XGIMI's own source picker does this; the standard passthrough intent below has no
            // handler on XGIMI projectors.
            Intent i = new Intent().setComponent(XGIMI_PLAYER)
                    .putExtra("sw", "HDMI" + in.number)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return Actions.start(ctx, i, "Couldn't switch input");
        }
        if (in.id == null) return false;
        Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no app handles passthrough input " + in.id);
            Actions.toast(ctx, "No app on this device can show HDMI inputs");
            return false;
        } catch (RuntimeException e) {
            Log.e(TAG, "switching to " + in.id + " failed", e);
            Actions.toast(ctx, "Couldn't switch input");
            return false;
        }
    }

    /** Switch to HDMI port {@code number} (for deep links). */
    static boolean open(Context ctx, int number) {
        for (Input in : hdmi(ctx)) if (in.number == number) return open(ctx, in);
        Log.w(TAG, "no HDMI input " + number);
        Actions.toast(ctx, "No HDMI " + number + " input");
        return false;
    }
}
