package com.domdomegg.launcher;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.util.List;
import java.util.Locale;

/**
 * Invisible trampoline for deep links, e.g. from Home Assistant:
 *   projector://input/HDMI1 (any port number), projector://menu, projector://settings, projector://home
 */
public class DeepLinkActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            handle(getIntent() == null ? null : getIntent().getData());
        } finally {
            finish();
        }
    }

    private void handle(Uri uri) {
        Log.i(Actions.TAG, "deep link: " + uri);
        if (uri == null) return;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        List<String> seg = uri.getPathSegments();
        switch (host) {
            case "input": {
                // projector://input/HDMI1 (or just /1)
                String in = seg.isEmpty() ? "" : seg.get(0).toUpperCase(Locale.ROOT).replace("HDMI", "");
                try {
                    Inputs.open(this, Integer.parseInt(in));
                } catch (NumberFormatException e) {
                    Log.w(Actions.TAG, "unknown input: " + seg);
                }
                break;
            }
            case "menu":
                Actions.quickMenu(this);
                break;
            case "home":
                Actions.home(this);
                break;
            case "settings":
                Actions.settings(this);
                break;
            default:
                Log.w(Actions.TAG, "unknown deep link: " + uri);
        }
    }
}
