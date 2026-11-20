package com.domdomegg.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextPaint;
import android.widget.TextClock;

/**
 * TextClock with a two-layer text shadow (a wide soft one plus a tight one) so it reads on any
 * print. setShadowLayer only supports one layer, so the text is drawn twice.
 */
final class ShadowTextClock extends TextClock {
    private final float s;

    ShadowTextClock(Context ctx, float scale) {
        super(ctx);
        this.s = scale;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        TextPaint p = getPaint();
        p.setShadowLayer(18 * s, 0, 2 * s, 0x8C000000); // ~ 0 2px 18px rgba(0,0,0,.55)
        super.onDraw(canvas);
        p.setShadowLayer(4 * s, 0, 1 * s, 0x99000000);  // ~ 0 1px 4px rgba(0,0,0,.6)
        super.onDraw(canvas);
        p.clearShadowLayer();
    }
}
