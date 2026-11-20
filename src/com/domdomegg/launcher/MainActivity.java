package com.domdomegg.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String TAG = Actions.TAG;
    private static final String PREF_ORDER = "order";
    private static final String PREF_HIDDEN = "hidden";
    /** Hidden until "Show hidden apps" is used: XGIMI's ambient-light app isn't useful as a tile. */
    private static final Set<String> DEFAULT_HIDDEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.xgimi.atmosphere")));

    private static final long IDLE_TIMEOUT_MS = 10_000;

    private float s; // design px -> screen px
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ArtLoader art;
    private ImageView artBottom, artTop;
    private Bitmap bottomBitmap, topBitmap;
    private long lastArtRequest;
    private View shade, dim, peek, sink;
    private LinearLayout timeBar, icons;
    private boolean awake = true;
    private int swallowKey;
    private Grid row; // the app grid (HDMI cards first)
    private int inputCount; // the first inputCount cards in the grid are HDMI inputs
    private IconButton menuButton, settingsButton;
    private View lastRowFocus;
    private AlertDialog dialog;
    private String signature = "";
    private List<String> visibleKeys = new ArrayList<>();
    private final Map<String, ComponentName> components = new HashMap<>();

    private final BroadcastReceiver packageReceiver = new PackageReceiver(this);

    // Static nested classes only: JDK 21 javac writes a MethodParameters entry with no name for the
    // implicit outer-instance parameter of inner/anonymous class constructors, which crashes d8 34.0.0.
    private static final class PackageReceiver extends BroadcastReceiver {
        private final MainActivity activity;

        PackageReceiver(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "package change: " + intent.getAction() + " " + intent.getData());
            activity.refreshApps();
        }
    }

    private int px(float designPx) {
        return Math.round(designPx * s);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFormat(PixelFormat.RGBA_8888); // avoid 565 banding
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
        s = w / 1920f;
        art = new ArtLoader(this, w, h);
        buildUi();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);

        refreshApps();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(packageReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        handler.removeCallbacksAndMessages(null);
        dismissDialog();
        art.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Launch, or coming back from an app: new print, awake, HDMI 1 focused.
        newArt();
        wake(true, false);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(sleepRunnable);
        finishArtFade(); // never leave a half-finished cross-fade behind
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Home pressed (possibly while already on the home screen).
        dismissDialog();
        refreshApps();
        newArt();
        wake(true, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshApps();
    }

    // ---------------------------------------------------------------- art

    /** Pick a new random print. Debounced so onStart + onNewIntent for one Home press change it once. */
    private void newArt() {
        long now = SystemClock.uptimeMillis();
        if (now - lastArtRequest < 800) return;
        lastArtRequest = now;
        art.next(this::showArt);
    }

    /** Fade the new print in (200ms) over the current one, then release the old bitmap. */
    private void showArt(Bitmap bmp) {
        if (isDestroyed()) {
            bmp.recycle();
            return;
        }
        finishArtFade(); // if a previous fade is still running, commit it first
        artTop.animate().cancel();
        artTop.setImageBitmap(bmp);
        artTop.setAlpha(0f);
        topBitmap = bmp;
        artTop.animate().alpha(1f).setDuration(200).setInterpolator(new DecelerateInterpolator())
                .withEndAction(this::finishArtFade).start();
        // withEndAction doesn't run if the animation is interrupted (e.g. the window
        // stops drawing mid-fade), which left two prints blended. Always commit.
        artTop.removeCallbacks(fadeWatchdog);
        artTop.postDelayed(fadeWatchdog, 450);
    }

    private final Runnable fadeWatchdog = this::finishArtFade;

    private void finishArtFade() {
        if (topBitmap == null) return;
        Bitmap old = bottomBitmap;
        bottomBitmap = topBitmap;
        topBitmap = null;
        artBottom.setImageBitmap(bottomBitmap);
        artTop.animate().cancel();
        artTop.setImageDrawable(null);
        artTop.setAlpha(0f);
        if (old != null && old != bottomBitmap) old.recycle();
    }

    // ---------------------------------------------------------------- awake / idle

    private final Runnable sleepRunnable = this::sleep;

    private void scheduleSleep() {
        handler.removeCallbacks(sleepRunnable);
        handler.postDelayed(sleepRunnable, IDLE_TIMEOUT_MS);
    }

    /** AWAKE: grid fully up, clock/date/icons visible, dim + edge shade on. */
    private void wake(boolean focusHdmi1, boolean animate) {
        boolean was = awake;
        awake = true;
        View cur = getCurrentFocus();
        boolean inUi = cur != null && cur != sink && (cur.getParent() == row || cur == menuButton || cur == settingsButton);
        if (focusHdmi1 || !inUi) {
            View first = firstCard();
            if (first != null) first.requestFocus();
            lastRowFocus = first;
        }
        applyState(animate);
        scheduleSleep();
        if (!was) Log.i(TAG, "waking");
    }

    /** IDLE: just the art and the top 2/3 of the first row peeking above the screen bottom. */
    private void sleep() {
        handler.removeCallbacks(sleepRunnable);
        if (dialog != null && dialog.isShowing()) { // don't doze under the long-press menu
            scheduleSleep();
            return;
        }
        awake = false;
        sink.requestFocus(); // no card shows a focus ring while idle
        lastRowFocus = null;
        applyState(true);
    }

    private float idleGridOffset() {
        // Translate down so only the top 113px of the first row stays above the screen bottom.
        return row.getHeight() - px(113) + px(80);
    }

    private void applyState(boolean animate) {
        final boolean a = awake;
        float gridY = a ? 0 : idleGridOffset();
        float barY = a ? 0 : -px(10);
        if (!animate) {
            shade.setAlpha(a ? 1 : 0);
            dim.setAlpha(a ? 1 : 0);
            peek.setAlpha(a ? 0 : 1);
            timeBar.setAlpha(a ? 1 : 0);
            timeBar.setTranslationY(barY);
            icons.setAlpha(a ? 1 : 0);
            icons.setTranslationY(barY);
            row.setTranslationY(gridY);
            if (row.getHeight() == 0) row.post(() -> applyState(false)); // not laid out yet
            return;
        }
        DecelerateInterpolator ease = new DecelerateInterpolator();
        shade.animate().alpha(a ? 1 : 0).setDuration(200).setInterpolator(ease).start();
        dim.animate().alpha(a ? 1 : 0).setDuration(200).setInterpolator(ease).start();
        peek.animate().alpha(a ? 0 : 1).setDuration(200).setInterpolator(ease).start();
        timeBar.animate().alpha(a ? 1 : 0).translationY(barY).setDuration(180).setInterpolator(ease).start();
        icons.animate().alpha(a ? 1 : 0).translationY(barY).setDuration(160).setInterpolator(ease).start();
        row.animate().translationY(gridY).setDuration(200).setInterpolator(ease)
                .withEndAction(() -> Log.d(TAG, "state " + (a ? "AWAKE" : "IDLE") + ", focus=" + describe(getCurrentFocus())))
                .start();
    }

    private static String describe(View v) {
        if (v == null) return "none";
        CharSequence d = v.getContentDescription();
        return d != null ? d.toString() : v.getClass().getSimpleName();
    }

    private static boolean isNavKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;
            default:
                return false;
        }
    }

    /**
     * Idle: any D-pad key or OK only wakes (consumed, including its key-up).
     * Awake: every key resets the idle timer. D-pad navigation is managed by index:
     * Left/Right are linear through the grid (wrapping between rows, stopping at the ends, never
     * reaching the icons); Up/Down move by column; Up from the top row reaches the icons, and Up
     * from the icons goes straight to idle.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();

        if (swallowKey != 0 && code == swallowKey) { // rest of the key press that woke us
            if (action == KeyEvent.ACTION_UP) swallowKey = 0;
            return true;
        }
        if (!awake) {
            if (!isNavKey(code)) return super.dispatchKeyEvent(event);
            if (action == KeyEvent.ACTION_DOWN) {
                swallowKey = code;
                wake(true, true);
            }
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN) scheduleSleep();

        boolean dpad = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        View focused = getCurrentFocus();
        if (focused == sink && isNavKey(code)) { // shouldn't happen while awake, but be safe
            if (action == KeyEvent.ACTION_DOWN && firstCard() != null) firstCard().requestFocus();
            return true;
        }
        if (!dpad || focused == null) return super.dispatchKeyEvent(event);
        boolean onCard = focused.getParent() == row;
        boolean onIcon = focused == menuButton || focused == settingsButton;
        if (!onCard && !onIcon) return super.dispatchKeyEvent(event);
        if (action != KeyEvent.ACTION_DOWN) return true;

        if (onIcon) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN: focusRow(); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: if (focused == menuButton) settingsButton.requestFocus(); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (focused == settingsButton) menuButton.requestFocus(); break;
                case KeyEvent.KEYCODE_DPAD_UP: sleep(); break;
                default: break;
            }
            return true;
        }

        int i = row.indexOfChild(focused), n = row.getChildCount(), cols = row.columns();
        int target = -1;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (i + 1 < n) target = i + 1; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (i > 0) target = i - 1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (i + cols < n) target = i + cols;
                else if (i / cols < (n - 1) / cols) target = n - 1; // no card below: last card
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (i >= cols) {
                    target = i - cols;
                } else {
                    lastRowFocus = focused;
                    // Nearest icon: the right-hand columns go to the quick menu, the rest to settings.
                    (i % cols >= 3 ? menuButton : settingsButton).requestFocus();
                    return true;
                }
                break;
        }
        if (target >= 0) row.getChildAt(target).requestFocus();
        return true;
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back does nothing.
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setBackgroundColor(0xFF111111);
        int match = ViewGroup.LayoutParams.MATCH_PARENT;

        // Art: two stacked full-screen images for the cross-fade.
        artBottom = new ImageView(this);
        artTop = new ImageView(this);
        for (ImageView iv : new ImageView[]{artBottom, artTop}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(iv, new FrameLayout.LayoutParams(match, match));
        }
        artTop.setAlpha(0f);

        // Edge shade (awake): darkens the top for the clock and the bottom for the grid.
        shade = new View(this);
        shade.setBackground(new GradientLayer(
                new int[]{GradientLayer.black(.55f), GradientLayer.black(.25f), 0, 0,
                        GradientLayer.black(.3f), GradientLayer.black(.62f)},
                new float[]{0f, .16f, .34f, .52f, .72f, 1f}));
        root.addView(shade, new FrameLayout.LayoutParams(match, match));
        // Dim (awake): rgba(5,6,10,.32).
        dim = new View(this);
        dim.setBackgroundColor(0x5205060A);
        root.addView(dim, new FrameLayout.LayoutParams(match, match));
        // Peek shadow (idle): a subtle darkening behind the peeking row.
        peek = new View(this);
        peek.setBackground(new GradientLayer(
                new int[]{0, 0, GradientLayer.black(.18f), GradientLayer.black(.42f)},
                new float[]{0f, .72f, .86f, 1f}));
        root.addView(peek, new FrameLayout.LayoutParams(match, match));

        // Top bar, left: clock (semibold) + date (regular, 78%), 38px, shared baseline, no panel.
        // The text views carry padding so their shadows aren't clipped.
        int pad = px(30);
        timeBar = new LinearLayout(this);
        timeBar.setOrientation(LinearLayout.HORIZONTAL);
        timeBar.setBaselineAligned(true);
        timeBar.setClipChildren(false);
        TextClock clock = new ShadowTextClock(this, s);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTypeface(Build.VERSION.SDK_INT >= 28
                ? Typeface.create(Typeface.SANS_SERIF, 650, false)
                : Typeface.create("sans-serif-medium", Typeface.BOLD));
        clock.setLetterSpacing(0.3f / 38f);
        TextClock date = new ShadowTextClock(this, s);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        date.setAlpha(0.78f);
        for (TextClock tc : new TextClock[]{clock, date}) {
            tc.setTextColor(0xFFFFFFFF);
            tc.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(38));
            tc.setIncludeFontPadding(false);
            tc.setSingleLine(true);
            tc.setPadding(pad, pad, pad, pad);
        }
        timeBar.addView(clock);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = px(24) - 2 * pad; // 24px gap between the glyphs
        timeBar.addView(date, dlp);
        // Baseline at y=116: the 38px line box is centred on the 84px icon row that starts at y=60.
        int baseline = px(116);
        root.addView(timeBar, atPx(px(140) - pad,
                baseline - pad + Math.round(clock.getPaint().getFontMetrics().ascent)));

        // Top bar, right: Settings (cog) then Quick menu (≡).
        icons = new LinearLayout(this);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        icons.setClipChildren(false);
        icons.setClipToPadding(false);
        menuButton = new IconButton(this, IconButton.MENU, s, "Quick menu");
        settingsButton = new IconButton(this, IconButton.GEAR, s, "Settings");
        menuButton.setOnClickListener(v -> Actions.quickMenu(this));
        settingsButton.setOnClickListener(v -> Actions.settings(this));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(px(84), px(84));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(px(84), px(84));
        lp2.leftMargin = px(24);
        icons.addView(settingsButton, lp1);
        icons.addView(menuButton, lp2);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        ilp.topMargin = px(60);
        ilp.rightMargin = px(140);
        root.addView(icons, ilp);

        // App grid, anchored to the bottom: its bottom edge is 80px above the screen bottom.
        row = new Grid(this, 5, px(300), px(169), px(36));
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        glp.leftMargin = px(140);
        glp.bottomMargin = px(80);
        root.addView(row, glp);

        // HDMI inputs first, in port order.
        View.OnLongClickListener fixed = v -> {
            Actions.toast(this, "Inputs stay at the front");
            return true;
        };
        for (Inputs.Input in : Inputs.hdmi(this)) {
            CardView card = CardView.hdmi(this, s, in.number, in.label);
            card.setOnClickListener(v -> Inputs.open(this, in));
            card.setOnLongClickListener(fixed);
            row.addView(card);
        }
        inputCount = row.getChildCount();

        // Invisible focus holder used while idle, so no card shows a focus ring.
        sink = new View(this);
        sink.setFocusable(true);
        sink.setContentDescription("idle");
        root.addView(sink, new FrameLayout.LayoutParams(1, 1));

        setContentView(root);
    }

    private FrameLayout.LayoutParams atPx(int left, int top) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = left;
        lp.topMargin = top;
        return lp;
    }

    /** The first card: HDMI 1 if there are inputs, otherwise the first app. */
    private View firstCard() {
        return row.getChildCount() > 0 ? row.getChildAt(0) : null;
    }

    /** Back down from the icons: the top-row card that was focused before (default HDMI 1). */
    private void focusRow() {
        View target = lastRowFocus != null && lastRowFocus.getParent() == row
                && row.indexOfChild(lastRowFocus) < row.columns() ? lastRowFocus : firstCard();
        if (target != null) target.requestFocus();
    }

    // ---------------------------------------------------------------- apps

    private static String keyOf(ComponentName cn) {
        return cn.flattenToShortString();
    }

    private boolean isHidden(Set<String> hidden, String key) {
        ComponentName cn = ComponentName.unflattenFromString(key);
        return hidden.contains(key) || (cn != null && hidden.contains(cn.getPackageName()));
    }

    private Set<String> hiddenSet() {
        return new HashSet<>(prefs.getStringSet(PREF_HIDDEN, DEFAULT_HIDDEN));
    }

    private List<String> savedOrder() {
        String raw = prefs.getString(PREF_ORDER, "");
        List<String> out = new ArrayList<>();
        for (String k : TextUtils.split(raw, "\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    private void saveOrder(List<String> order) {
        prefs.edit().putString(PREF_ORDER, TextUtils.join("\n", order)).apply();
    }

    /** Re-query installed apps and rebuild the app part of the row if anything changed. */
    private void refreshApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(q, 0);
        } catch (RuntimeException e) {
            Log.e(TAG, "queryIntentActivities failed", e);
            return;
        }
        final Map<String, ResolveInfo> byKey = new HashMap<>();
        final Map<String, String> labels = new HashMap<>();
        for (ResolveInfo ri : ris) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || getPackageName().equals(ai.packageName)) continue;
            String key = keyOf(new ComponentName(ai.packageName, ai.name));
            byKey.put(key, ri);
            CharSequence l = ri.loadLabel(pm);
            labels.put(key, l == null ? ai.packageName : l.toString());
        }

        // Saved order, minus uninstalled apps, plus new apps at the end (alphabetically).
        List<String> order = savedOrder();
        List<String> merged = new ArrayList<>();
        for (String k : order) if (byKey.containsKey(k) && !merged.contains(k)) merged.add(k);
        List<String> fresh = new ArrayList<>();
        for (String k : byKey.keySet()) if (!merged.contains(k)) fresh.add(k);
        Collections.sort(fresh, (a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        merged.addAll(fresh);
        if (!merged.equals(order)) saveOrder(merged);

        Set<String> hidden = hiddenSet();
        List<String> visible = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (String k : merged) {
            if (isHidden(hidden, k)) continue;
            visible.add(k);
            ActivityInfo ai = byKey.get(k).activityInfo;
            sig.append(k).append('|').append(labels.get(k)).append('|').append(ai.banner).append('|')
                    .append(ai.applicationInfo.banner).append('|').append(ai.applicationInfo.sourceDir).append('\n');
        }
        if (sig.toString().equals(signature)) return;
        signature = sig.toString();
        visibleKeys = visible;

        // Remember focus so a rebuild doesn't lose the user's place.
        View focused = getCurrentFocus();
        String focusKey = null;
        int focusIndex = -1;
        if (focused instanceof CardView && focused.getParent() == row) {
            focusKey = ((CardView) focused).key;
            focusIndex = row.indexOfChild(focused);
        }

        while (row.getChildCount() > inputCount) row.removeViewAt(inputCount);
        components.clear();
        CardView refocus = null;
        for (String k : visible) {
            ResolveInfo ri = byKey.get(k);
            ActivityInfo ai = ri.activityInfo;
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            components.put(k, cn);
            Drawable banner = null, icon = null;
            try {
                banner = ai.loadBanner(pm);
                if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            } catch (RuntimeException e) {
                Log.w(TAG, "banner load failed for " + k, e);
            }
            if (banner == null) {
                try {
                    icon = ri.loadIcon(pm);
                } catch (RuntimeException e) {
                    Log.w(TAG, "icon load failed for " + k, e);
                }
            }
            CardView card = CardView.app(this, s, k, labels.get(k), banner, icon);
            card.setOnClickListener(v -> launch(k));
            card.setOnLongClickListener(v -> {
                showOptions(k, labels.get(k));
                return true;
            });
            row.addView(card);
            if (k.equals(focusKey)) refocus = card;
        }
        Log.i(TAG, "row rebuilt: " + visible);

        if (focusIndex >= 0) {
            if (refocus == null) {
                int idx = Math.min(focusIndex, row.getChildCount() - 1);
                refocus = (CardView) row.getChildAt(idx);
            }
            refocus.requestFocus();
        }
    }

    private void launch(String key) {
        ComponentName cn = components.get(key);
        if (cn == null) return;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Actions.start(this, i, "Couldn't open app");
    }

    // ---------------------------------------------------------------- long-press menu

    private void dismissDialog() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;
    }

    private void showOptions(String key, String label) {
        dismissDialog();
        String[] items = {"Move left", "Move right", "Hide", "Show hidden apps"};
        dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: move(key, -1); break;
                        case 1: move(key, 1); break;
                        case 2: hide(key); break;
                        case 3: showAll(key); break;
                    }
                })
                .create();
        dialog.show();
    }

    private CardView cardFor(String key) {
        for (int i = inputCount; i < row.getChildCount(); i++) {
            CardView c = (CardView) row.getChildAt(i);
            if (key.equals(c.key)) return c;
        }
        return null;
    }

    private void focusKeyLater(String key) {
        row.post(() -> {
            CardView c = key == null ? null : cardFor(key);
            View target = c != null ? c : firstCard();
            if (target != null) target.requestFocus();
        });
    }

    private void move(String key, int dir) {
        int idx = visibleKeys.indexOf(key);
        int target = idx + dir;
        if (idx < 0 || target < 0 || target >= visibleKeys.size()) {
            focusKeyLater(key);
            return;
        }
        List<String> order = savedOrder();
        int a = order.indexOf(key), b = order.indexOf(visibleKeys.get(target));
        if (a < 0 || b < 0) return;
        Collections.swap(order, a, b);
        saveOrder(order);
        refreshApps();
        focusKeyLater(key);
    }

    private void hide(String key) {
        int idx = visibleKeys.indexOf(key);
        String next = null;
        if (idx + 1 < visibleKeys.size()) next = visibleKeys.get(idx + 1);
        else if (idx - 1 >= 0) next = visibleKeys.get(idx - 1);
        Set<String> hidden = hiddenSet();
        hidden.add(key);
        prefs.edit().putStringSet(PREF_HIDDEN, hidden).apply();
        refreshApps();
        focusKeyLater(next);
    }

    private void showAll(String key) {
        prefs.edit().putStringSet(PREF_HIDDEN, new HashSet<String>()).apply();
        refreshApps();
        focusKeyLater(key);
    }
}
