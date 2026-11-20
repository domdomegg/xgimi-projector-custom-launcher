package com.domdomegg.launcher;

/** Shared visual constants (design values are in px on a 1920x1080 canvas). */
final class Style {
    private Style() {}

    /** Dark translucent "glass" fill: rgba(12,12,18,.62). There's no live blur on API 30. */
    static final int GLASS = 0x9E0C0C12;
    /** Hairline edge on glass and cards: white @ 20%. */
    static final int EDGE = 0x33FFFFFF;
    static final int WHITE = 0xFFFFFFFF;

    /**
     * Elevation shadows render underneath a view and show through a translucent fill as stepped
     * rectangles, so glass elements keep their z-order but get an invisible shadow.
     */
    static void hideShadow(android.view.View v) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            v.setOutlineAmbientShadowColor(0);
            v.setOutlineSpotShadowColor(0);
        }
    }

    /** Linear interpolation between two ARGB colours. */
    static int lerpColor(int a, int b, float t) {
        int aa = (a >>> 24), ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int ba = (b >>> 24), br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
