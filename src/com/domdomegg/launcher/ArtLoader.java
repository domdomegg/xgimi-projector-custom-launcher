package com.domdomegg.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picks a random print from the art folders and decodes it off the UI thread at screen size
 * (ARGB_8888; RGB_565 banded visibly). Files from all folders are merged:
 *   /sdcard/Pictures/LauncherArt/                     (needs READ_EXTERNAL_STORAGE; build.sh grants it)
 *   /sdcard/Android/data/com.domdomegg.launcher/files/art/   (app-specific, no permission needed)
 */
final class ArtLoader {
    interface Callback {
        void onArt(Bitmap bitmap);
    }

    private static final String TAG = Actions.TAG;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Context ctx;
    private final int targetW, targetH;
    private String lastPath;
    private int generation;

    ArtLoader(Context ctx, int targetW, int targetH) {
        this.ctx = ctx.getApplicationContext();
        this.targetW = targetW;
        this.targetH = targetH;
    }

    List<File> folders() {
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(Environment.getExternalStorageDirectory(), "Pictures/LauncherArt"));
        File own = ctx.getExternalFilesDir(null);
        if (own != null) dirs.add(new File(own, "art"));
        return dirs;
    }

    private List<File> listArt() {
        List<File> out = new ArrayList<>();
        for (File dir : folders()) {
            File[] files = dir.listFiles();
            if (files == null) {
                Log.d(TAG, "art: can't list " + dir);
                continue;
            }
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** Load a new random print (never the previous one, if there's a choice). */
    void next(Callback cb) {
        final int gen = ++generation;
        final String previous = lastPath;
        executor.execute(() -> {
            long t0 = SystemClock.uptimeMillis();
            List<File> files = listArt();
            if (files.isEmpty()) {
                Log.w(TAG, "art: no images found in " + folders());
                return;
            }
            File pick = null;
            for (int tries = 0; tries < 8; tries++) {
                pick = files.get(random.nextInt(files.size()));
                if (files.size() == 1 || !pick.getAbsolutePath().equals(previous)) break;
            }
            Bitmap bmp = decode(pick);
            if (bmp == null) {
                Log.w(TAG, "art: failed to decode " + pick);
                return;
            }
            final File chosen = pick;
            final Bitmap result = bmp;
            Log.i(TAG, "art: chose " + chosen.getName() + " (" + result.getWidth() + "x" + result.getHeight()
                    + ", " + files.size() + " available, " + (SystemClock.uptimeMillis() - t0) + "ms)");
            main.post(() -> {
                if (gen != generation) { // superseded by a newer request
                    result.recycle();
                    return;
                }
                lastPath = chosen.getAbsolutePath();
                cb.onArt(result);
            });
        });
    }

    private Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetW && o.outHeight / (sample * 2) >= targetH) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "art: OOM decoding " + f, e);
            return null;
        }
    }

    void shutdown() {
        generation++;
        executor.shutdownNow();
    }
}
