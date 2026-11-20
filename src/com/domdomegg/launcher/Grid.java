package com.domdomegg.launcher;

import android.content.Context;
import android.view.ViewGroup;

/** Fixed-size cards laid out in a grid of {@code columns} columns, filling rows top to bottom. */
final class Grid extends ViewGroup {
    private final int columns, cardW, cardH, gap;

    Grid(Context ctx, int columns, int cardW, int cardH, int gap) {
        super(ctx);
        this.columns = columns;
        this.cardW = cardW;
        this.cardH = cardH;
        this.gap = gap;
        setClipChildren(false);
        setClipToPadding(false);
    }

    int columns() {
        return columns;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int n = getChildCount();
        int rows = (n + columns - 1) / columns;
        int cw = MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY);
        for (int i = 0; i < n; i++) getChildAt(i).measure(cw, ch);
        int w = columns * cardW + (columns - 1) * gap;
        int h = rows == 0 ? 0 : rows * cardH + (rows - 1) * gap;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            int x = (i % columns) * (cardW + gap);
            int y = (i / columns) * (cardH + gap);
            getChildAt(i).layout(x, y, x + cardW, y + cardH);
        }
    }
}
