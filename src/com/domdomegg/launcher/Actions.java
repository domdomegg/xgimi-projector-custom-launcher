package com.domdomegg.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/** Shared actions used by the launcher and the deep-link trampoline. */
final class Actions {
    static final String TAG = "ProjectorLauncher";

    private Actions() {}

    /** Switch to an HDMI input via XGIMI's player. input is "HDMI1" or "HDMI2". */
    static boolean switchInput(Context ctx, String input) {
        Intent i = new Intent()
                .setComponent(new ComponentName("com.xgimi.xhplayer", "com.xgimi.xhplayer.InputActivity"))
                .putExtra("sw", input)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return start(ctx, i, "Couldn't switch input");
    }

    /** Open XGIMI's quick menu overlay. */
    static boolean quickMenu(Context ctx) {
        Intent i = new Intent("com.xgimi.fast.menu")
                .setComponent(new ComponentName("com.xgimi.misckey", "com.xgimi.misckey.component.MiscKeyService"));
        try {
            if (ctx.startService(i) == null) {
                Log.w(TAG, "quick menu service not found");
                toast(ctx, "Quick menu not available");
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "startService for quick menu failed", e);
            toast(ctx, "Quick menu not available");
            return false;
        }
    }

    static boolean settings(Context ctx) {
        return start(ctx, new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "Couldn't open settings");
    }

    static boolean home(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return start(ctx, i, "Couldn't open home");
    }

    static boolean start(Context ctx, Intent i, String failMsg) {
        try {
            ctx.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "startActivity failed: " + i, e);
            toast(ctx, failMsg);
            return false;
        }
    }

    static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) {
        }
    }
}
