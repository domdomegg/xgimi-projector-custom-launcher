package com.domdomegg.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * A 300x169 (design px) rounded card: an HDMI input (glass + big numeral) or an app banner. The inner content is clipped to the rounded outline;
 * the focus ring is drawn by this wrapper just outside the bounds (parent must not clip children).
 */
final class CardView extends FrameLayout {
    final String key; // app key, or null for an HDMI card
    private final float s;
    private final float radius;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();
    private float progress;
    private ValueAnimator anim;
    private final Content content;

    /** HDMI input card: "big numeral" style. title is e.g. "HDMI 1". */
    static CardView hdmi(Context ctx, float s, String title) {
        String numeral = title.substring(title.length() - 1);
        CardView c = new CardView(ctx, s, null, numeral, null, null, null);
        c.setContentDescription(title);
        return c;
    }

    static CardView app(Context ctx, float s, String key, String label, Drawable banner, Drawable icon) {
        return new CardView(ctx, s, key, null, label, banner, icon);
    }

    private CardView(Context ctx, float s, String key, String title, String label, Drawable banner, Drawable icon) {
        super(ctx);
        this.s = s;
        this.key = key;
        this.radius = 20 * s;
        this.content = new Content(this, ctx, title, label, banner, icon);
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setWillNotDraw(false);
        setClipChildren(false);
        setContentDescription(content.label);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        ring.setStrokeWidth(5 * s);
        ViewOutlineProvider rounded = new RoundOutline(radius);
        setOutlineProvider(rounded);
        setElevation(10 * s); // soft shadow, ~ 0 10px 30px rgba(0,0,0,.3)
        if (key == null) Style.hideShadow(this); // HDMI cards are translucent glass
        content.setOutlineProvider(rounded);
        content.setClipToOutline(true);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(progress, gainFocus ? 1f : 0f);
        anim.setDuration(90);
        anim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            float sc = 1f + 0.08f * progress;
            setScaleX(sc);
            setScaleY(sc);
            setTranslationZ(10 * s * progress); // on top of the base elevation
            invalidate();
            content.invalidate();
        });
        anim.start();
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (progress > 0.001f) {
            float half = ring.getStrokeWidth() / 2f;
            ringRect.set(-half, -half, getWidth() + half, getHeight() + half);
            ring.setAlpha((int) (255 * progress));
            c.drawRoundRect(ringRect, radius + half, radius + half, ring);
        }
    }

    // Static nested class, not anonymous: JDK 21 javac + d8 34.0.0 crash on anonymous/inner class constructors.
    private static final class RoundOutline extends ViewOutlineProvider {
        private final float radius;

        RoundOutline(float radius) {
            this.radius = radius;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    }

    /** The clipped card face. */
    private static final class Content extends View {
        private final CardView card;
        final String numeral; // HDMI card numeral ("1"/"2"), or null for an app card
        final String label;   // app label
        private final Drawable banner, icon;
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Rect bounds = new Rect();
        private final RectF r = new RectF();

        Content(CardView card, Context ctx, String numeral, String label, Drawable banner, Drawable icon) {
            super(ctx);
            this.card = card;
            float s = card.s;
            this.numeral = numeral;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2 * s);
            edge.setColor(Style.EDGE);
            bgPaint.setColor(Style.GLASS); // HDMI glass; also behind banners while they load
            if (numeral != null) {
                big.setColor(0xF2FFFFFF); // white @ 95%
                big.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                text.setColor(0xD9FFFFFF); // white @ 85%
                text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            } else {
                text.setColor(0xFFF2F2F0);
                text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                text.setTextSize(22 * s);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float s = card.s;
            if (numeral != null) {
                // The numeral is deliberately bigger than the card: its glyph (measured from the
                // real ink bounds of "1", so both cards match) is 1.14x the card height and is
                // cropped equally top and bottom by the rounded-rect clip.
                big.setTextSize(100);
                big.getTextBounds("1", 0, 1, bounds);
                big.setTextSize(100 * (h * 1.14f) / Math.max(1, bounds.height()));
                // "HDMI" label: 24px, letter-spacing 0.10em.
                text.setTextSize(24 * s);
                text.setLetterSpacing(0.10f);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float s = card.s, radius = card.radius, progress = card.progress;
            c.drawRect(0, 0, w, h, bgPaint);
            if (numeral != null) {
                // Label first (bottom-left), numeral layered on top.
                // CSS left 25px, bottom 19px (box bottom; the baseline sits one descent above it).
                c.drawText("HDMI", 25 * s, h - 19 * s - text.descent(), text);
                big.getTextBounds(numeral, 0, numeral.length(), bounds);
                float glyphH = bounds.height();
                float baseline = (h - glyphH) / 2f - bounds.top; // centre the ink vertically
                float x = (w - 0.13f * h) - bounds.right;         // right-align the ink edge
                c.drawText(numeral, x, baseline, big);
            } else if (banner != null) {
                banner.setBounds(0, 0, w, h);
                banner.draw(c);
            } else {
                float is = 80 * s;
                int left = (int) ((w - is) / 2f), top = (int) (h * 0.14f);
                if (icon != null) {
                    icon.setBounds(left, top, left + (int) is, top + (int) is);
                    icon.draw(c);
                }
                CharSequence t = TextUtils.ellipsize(label == null ? "" : label, text, w - 36 * s, TextUtils.TruncateAt.END);
                float tw = text.measureText(t, 0, t.length());
                c.drawText(t, 0, t.length(), (w - tw) / 2f, h - 20 * s - text.descent(), text);
            }
            // Hairline inner edge; fades out as the 5px focus ring fades in.
            if (progress < 0.999f) {
                float half = edge.getStrokeWidth() / 2f;
                r.set(half, half, w - half, h - half);
                edge.setAlpha((int) (0x33 * (1f - progress)));
                c.drawRoundRect(r, radius - half, radius - half, edge);
            }
        }
    }
}
