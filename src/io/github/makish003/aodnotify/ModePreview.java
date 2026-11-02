package io.github.makish003.aodnotify;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Sketch of the AOD with the module's brief capsule or its detailed cards, for the mode picker
 * at the top of the settings. Same black fill and grey outline as AodViews; One UI's
 * proportions (a short wide screen excerpt) rather than ColorOS's whole-phone sketches.
 * Drawn in dp units on a 124x84 grid.
 */
final class ModePreview extends Drawable {
    private static final int WIDTH_DP = 124;
    private static final int HEIGHT_DP = 84;

    private static final int SCREEN = 0xFF000000;
    private static final int SCREEN_STROKE = 0xFF3A3A3A;
    private static final int STROKE = 0xFF5A5A5A;
    private static final int ICON = 0xFF1A8CFF;
    private static final int TITLE = 0xFFFFFFFF;
    private static final int BODY = 0xFF8C8C8C;

    private final boolean mDetailed;
    private final float mDensity;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private int mAlpha = 255;

    ModePreview(Context ctx, boolean detailed) {
        mDetailed = detailed;
        mDensity = ctx.getResources().getDisplayMetrics().density;
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(WIDTH_DP * mDensity);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(HEIGHT_DP * mDensity);
    }

    @Override
    public void draw(Canvas canvas) {
        int save = canvas.saveLayerAlpha(new RectF(getBounds()), mAlpha);
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / (float) WIDTH_DP, getBounds().height() / (float) HEIGHT_DP);
        shape(canvas, 0.5f, 0.5f, WIDTH_DP - 0.5f, HEIGHT_DP - 0.5f, 14, SCREEN_STROKE);
        if (mDetailed) {
            card(canvas, 12, 32, true);
            card(canvas, 50, 24, false);
        } else {
            // the capsule: icon, title and one line of text
            shape(canvas, 14, 14, 110, 36, 11, STROKE);
            dot(canvas, 25, 25, 7);
            bar(canvas, 38, 20.5f, 78, TITLE);
            bar(canvas, 38, 27, 96, BODY);
        }
        canvas.restoreToCount(save);
    }

    /** A detailed card: icon, app and time, title, one or two lines of text. */
    private void card(Canvas canvas, float top, float height, boolean twoLines) {
        shape(canvas, 10, top, 114, top + height, 12, STROKE);
        dot(canvas, 22, top + 12, 6);
        bar(canvas, 34, top + 6, 60, BODY);
        bar(canvas, 34, top + 11.5f, 82, TITLE);
        bar(canvas, 34, top + 17.5f, 104, BODY);
        if (twoLines) {
            bar(canvas, 34, top + 23.5f, 90, BODY);
        }
    }

    /** Black rounded shape with a 1dp outline. */
    private void shape(Canvas canvas, float l, float t, float r, float b, float radius, int stroke) {
        mRect.set(l, t, r, b);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(SCREEN);
        canvas.drawRoundRect(mRect, radius, radius, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(1f);
        mPaint.setColor(stroke);
        canvas.drawRoundRect(mRect, radius, radius, mPaint);
    }

    private void dot(Canvas canvas, float cx, float cy, float r) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(ICON);
        canvas.drawCircle(cx, cy, r, mPaint);
    }

    /** A line of text: 3.5dp thick with round ends. */
    private void bar(Canvas canvas, float l, float t, float r, int color) {
        mRect.set(l, t, r, t + 3.5f);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(color);
        canvas.drawRoundRect(mRect, 1.75f, 1.75f, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        mPaint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
