package com.domdomegg.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * 84px circular "dark glass" icon button: translucent dark fill, 2px white-20% edge, white icon,
 * no shadow (it would show through the translucent fill). Focused: white fill, dark icon, 1.12 scale and a 5px white-35% halo.
 */
final class IconButton extends View {
    static final int MENU = 0;
    static final int GEAR = 1;

    private static final int HALO = 0x59FFFFFF;    // white @ 35%
    private static final int ICON_FOCUSED = 0xFF15171C;

    private final int kind;
    private final float s;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable gear;
    private float progress; // 0 = unfocused, 1 = focused
    private ValueAnimator anim;

    IconButton(Context ctx, int kind, float scale, String description) {
        super(ctx);
        this.kind = kind;
        this.s = scale;
        setFocusable(true);
        setClickable(true);
        setContentDescription(description);
        stroke.setStyle(Paint.Style.STROKE);
        fg.setStyle(Paint.Style.STROKE);
        fg.setStrokeCap(Paint.Cap.ROUND);
        gear = kind == GEAR ? ctx.getDrawable(R.drawable.ic_settings).mutate() : null;
        setOutlineProvider(new OvalOutline());
        Style.hideShadow(this);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(progress, gainFocus ? 1f : 0f);
        anim.setDuration(90);
        anim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            float sc = 1f + 0.12f * progress;
            setScaleX(sc);
            setScaleY(sc);
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;

        if (progress > 0.001f) { // halo just outside the circle (parent doesn't clip)
            stroke.setStrokeWidth(5 * s);
            stroke.setColor(HALO);
            stroke.setAlpha((int) (0x59 * progress));
            c.drawCircle(cx, cy, r + 2.5f * s, stroke);
        }
        fill.setColor(Style.lerpColor(Style.GLASS, Style.WHITE, progress));
        c.drawCircle(cx, cy, r, fill);
        if (progress < 0.999f) {
            stroke.setStrokeWidth(2 * s);
            stroke.setColor(Style.EDGE);
            stroke.setAlpha((int) (0x33 * (1f - progress)));
            c.drawCircle(cx, cy, r - s, stroke);
        }

        int iconColor = Style.lerpColor(Style.WHITE, ICON_FOCUSED, progress);
        // Icons use a 24-unit viewBox, rendered at 40px (design px).
        float size = 40 * s;
        float left = (w - size) / 2f, top = (h - size) / 2f;
        if (kind == MENU) {
            float k = size / 24f;
            c.save();
            c.translate(left, top);
            c.scale(k, k);
            fg.setColor(iconColor);
            fg.setStrokeWidth(2f);
            c.drawLine(4, 7, 20, 7, fg);
            c.drawLine(4, 12, 20, 12, fg);
            c.drawLine(4, 17, 20, 17, fg);
            c.restore();
        } else {
            gear.setTint(iconColor);
            gear.setBounds(Math.round(left), Math.round(top), Math.round(left + size), Math.round(top + size));
            gear.draw(c);
        }
    }

    // Static nested class, not anonymous: JDK 21 javac + d8 34.0.0 crash on anonymous/inner class constructors.
    private static final class OvalOutline extends ViewOutlineProvider {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setOval(0, 0, view.getWidth(), view.getHeight());
        }
    }
}
