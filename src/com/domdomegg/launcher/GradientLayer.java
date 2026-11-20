package com.domdomegg.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** A top-to-bottom multi-stop linear gradient (CSS linear-gradient(180deg, ...)). */
final class GradientLayer extends Drawable {
    private final int[] colors;
    private final float[] stops;
    private final Paint paint = new Paint();

    GradientLayer(int[] colors, float[] stops) {
        this.colors = colors;
        this.stops = stops;
        paint.setDither(true);
    }

    /** CSS-style rgba(0,0,0,a). */
    static int black(float alpha) {
        return Math.round(alpha * 255) << 24;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        paint.setShader(new LinearGradient(0, b.top, 0, b.bottom, colors, stops, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        c.drawRect(getBounds(), paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
