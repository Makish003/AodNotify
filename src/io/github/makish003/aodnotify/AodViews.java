package io.github.makish003.aodnotify;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/**
 * AOD capsules drawn like the stock ColorOS AOD cards (black fill, thin grey outline,
 * monochrome icon on a dark tile, white text), animated like the One UI brief pop-up.
 */
final class AodViews {
    private static final int FILL = 0xFF000000;
    private static final int STROKE = 0xFF5A5A5A;
    private static final int TILE = 0xFF262626;
    private static final int TITLE = 0xFFFFFFFF;
    private static final int BODY = 0xFFE2E2E2;
    private static final int META = 0xFF8C8C8C;

    private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final PathInterpolator STANDARD = new PathInterpolator(0.3f, 0f, 0.1f, 1f);

    static final int EXPAND_MS = 520;
    static final int COLLAPSE_MS = 420;

    /** A capsule whose outer frame can be narrowed to a circle around the icon. */
    static final class Capsule {
        FrameLayout frame;
        View texts;
        int fullWidth;
        int height;
        Animator running;
    }

    private AodViews() {
    }

    // ---- brief pop-up ----

    static Capsule popup(Context ctx, AodItem item, int maxWidth) {
        LinearLayout content = row(ctx);
        content.setPadding(dp(ctx, 11), dp(ctx, 11), dp(ctx, 24), dp(ctx, 11));
        content.addView(tile(ctx, item, 36), new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)));

        LinearLayout texts = column(ctx);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(dp(ctx, 12));
        content.addView(texts, tlp);
        TextView title = text(ctx, 16, TITLE, true);
        title.setText(!TextUtils.isEmpty(item.title) ? item.title : item.appName);
        texts.addView(title);
        if (!TextUtils.isEmpty(item.text)) {
            TextView body = text(ctx, 14, BODY, false);
            body.setText(item.text);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(ctx, 3);
            texts.addView(body, blp);
        }

        // Measure once; the content keeps this size while the frame around it animates.
        content.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        Capsule c = new Capsule();
        c.fullWidth = content.getMeasuredWidth();
        c.height = content.getMeasuredHeight();
        c.texts = texts;
        c.frame = frame(ctx, c.height / 2f);
        c.frame.addView(content, new FrameLayout.LayoutParams(c.fullWidth, c.height,
                Gravity.START | Gravity.CENTER_VERTICAL));
        c.frame.setLayoutParams(new LinearLayout.LayoutParams(c.fullWidth, c.height));
        return c;
    }

    /** Icon bubble pops in, stretches into the full capsule, then the text fades in. */
    static void expand(Capsule c) {
        cancel(c);
        setWidth(c, c.height);
        c.frame.setAlpha(0f);
        c.frame.setScaleX(0.6f);
        c.frame.setScaleY(0.6f);
        c.texts.setAlpha(0f);

        AnimatorSet pop = new AnimatorSet();
        pop.playTogether(
                ObjectAnimator.ofFloat(c.frame, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(c.frame, View.SCALE_X, 0.6f, 1f),
                ObjectAnimator.ofFloat(c.frame, View.SCALE_Y, 0.6f, 1f));
        pop.setDuration(200);
        pop.setInterpolator(STANDARD);

        ValueAnimator stretch = ValueAnimator.ofInt(c.height, c.fullWidth);
        stretch.addUpdateListener(a -> setWidth(c, (Integer) a.getAnimatedValue()));
        stretch.setStartDelay(140);
        stretch.setDuration(EXPAND_MS - 140);
        stretch.setInterpolator(EMPHASIZED);

        ObjectAnimator reveal = ObjectAnimator.ofFloat(c.texts, View.ALPHA, 0f, 1f);
        reveal.setStartDelay(300);
        reveal.setDuration(EXPAND_MS - 300);

        AnimatorSet all = new AnimatorSet();
        all.playTogether(pop, stretch, reveal);
        start(c, all, null);
    }

    /** Reverse of {@link #expand}: text out, capsule shrinks to the icon, bubble fades. */
    static void collapse(Capsule c, Runnable end) {
        cancel(c);
        ObjectAnimator hideText = ObjectAnimator.ofFloat(c.texts, View.ALPHA, c.texts.getAlpha(), 0f);
        hideText.setDuration(120);

        ValueAnimator shrink = ValueAnimator.ofInt(c.frame.getLayoutParams().width, c.height);
        shrink.addUpdateListener(a -> setWidth(c, (Integer) a.getAnimatedValue()));
        shrink.setStartDelay(60);
        shrink.setDuration(260);
        shrink.setInterpolator(EMPHASIZED);

        AnimatorSet vanish = new AnimatorSet();
        vanish.playTogether(
                ObjectAnimator.ofFloat(c.frame, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(c.frame, View.SCALE_X, 1f, 0.7f),
                ObjectAnimator.ofFloat(c.frame, View.SCALE_Y, 1f, 0.7f));
        vanish.setStartDelay(COLLAPSE_MS - 160);
        vanish.setDuration(160);
        vanish.setInterpolator(STANDARD);

        AnimatorSet all = new AnimatorSet();
        all.playTogether(hideText, shrink, vanish);
        start(c, all, end);
    }

    static void cancel(Capsule c) {
        if (c.running != null) {
            Animator a = c.running;
            c.running = null;
            a.removeAllListeners();
            a.cancel();
        }
    }

    // ---- detailed cards ----

    static View card(Context ctx, AodItem item) {
        LinearLayout content = row(ctx);
        content.setGravity(Gravity.TOP);
        content.setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14));
        content.setBackground(outline(ctx, dp(ctx, 28)));
        content.addView(tile(ctx, item, 36), new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)));

        LinearLayout texts = column(ctx);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(ctx, 12));
        content.addView(texts, tlp);

        TextView meta = text(ctx, 12, META, false);
        meta.setText(item.appName + " · " + DateFormat.getTimeFormat(ctx).format(new Date(item.when)));
        texts.addView(meta);
        if (!TextUtils.isEmpty(item.title)) {
            TextView title = text(ctx, 16, TITLE, true);
            title.setText(item.title);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(ctx, 4);
            texts.addView(title, lp);
        }
        if (!TextUtils.isEmpty(item.text)) {
            TextView body = text(ctx, 14, BODY, false);
            body.setSingleLine(false);
            body.setMaxLines(2);
            body.setText(item.text);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(ctx, 3);
            texts.addView(body, lp);
        }
        return content;
    }

    /** New card slides down a little while fading in. */
    static void appear(View v) {
        v.setAlpha(0f);
        v.setTranslationY(-dp(v.getContext(), 12));
        v.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(EMPHASIZED).start();
    }

    static TextView more(Context ctx, String label) {
        TextView tv = text(ctx, 13, META, false);
        tv.setText(label);
        return tv;
    }

    // ---- building blocks ----

    private static FrameLayout frame(Context ctx, float radius) {
        FrameLayout f = new FrameLayout(ctx);
        f.setBackground(outline(ctx, radius));
        f.setClipToOutline(true);
        return f;
    }

    private static GradientDrawable outline(Context ctx, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(FILL);
        g.setStroke(Math.max(2, dp(ctx, 1) + dp(ctx, 1) / 2), STROKE);
        g.setCornerRadius(radius);
        return g;
    }

    /** Set from the module settings; read on the SystemUI main thread only. */
    private static boolean sMonoIcons;
    private static int sIconColor = Prefs.DEF_ICON_COLOR;
    /** Whether an icon is a real glyph (transparent shape) rather than a filled picture. */
    private static final LruCache<String, Boolean> GLYPHS = new LruCache<>(64);

    static void setIconStyle(boolean mono, int color) {
        sMonoIcons = mono;
        sIconColor = color;
    }

    private static View tile(Context ctx, AodItem item, int sizeDp) {
        FrameLayout tile = new FrameLayout(ctx);
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (!sMonoIcons) {
            // Default: the app's own colourful icon, already shaped by the launcher mask.
            iv.setImageDrawable(appIcon(ctx, item.pkg));
            tile.addView(iv, new FrameLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp), Gravity.CENTER));
            return tile;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TILE);
        bg.setCornerRadius(dp(ctx, 10));
        tile.setBackground(bg);
        Drawable glyph = glyph(ctx, item);
        int inner = dp(ctx, sizeDp * 11 / 18);
        if (glyph != null) {
            glyph.setTint(sIconColor);
            iv.setImageDrawable(glyph);
        } else {
            // No usable glyph (e.g. the app posts its launcher picture): a one-colour app icon.
            iv.setImageDrawable(appIcon(ctx, item.pkg));
            iv.setColorFilter(monoFilter(sIconColor));
            inner = dp(ctx, sizeDp * 3 / 4);
        }
        tile.addView(iv, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        return tile;
    }

    /** The notification's status-bar glyph, or ColorOS's replacement glyph; null if neither is a glyph. */
    private static Drawable glyph(Context ctx, AodItem item) {
        for (Icon icon : new Icon[]{item.smallIcon, item.oplusIcon}) {
            if (icon == null) {
                continue;
            }
            try {
                Drawable d = icon.loadDrawable(ctx);
                if (d != null && isGlyph(icon, item.pkg, d)) {
                    return d.mutate();
                }
            } catch (Throwable ignored) {
                // try the next source
            }
        }
        return null;
    }

    /**
     * Status-bar glyphs are mostly transparent shapes; a launcher picture fills most of its
     * square, and tinting it gives a solid blob. Measured once per icon on a 48px raster.
     */
    private static boolean isGlyph(Icon icon, String pkg, Drawable d) {
        String key = icon.getType() == Icon.TYPE_RESOURCE
                ? icon.getResPackage() + ':' + icon.getResId()
                : pkg + '@' + System.identityHashCode(icon);
        Boolean cached = GLYPHS.get(key);
        if (cached != null) {
            return cached;
        }
        int size = 48;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, size, size);
        copy.draw(new Canvas(bmp));
        int[] px = new int[size * size];
        bmp.getPixels(px, 0, size, 0, 0, size, size);
        bmp.recycle();
        int solid = 0;
        for (int p : px) {
            if ((p >>> 24) > 128) {
                solid++;
            }
        }
        boolean glyph = solid < px.length * 0.6f;
        GLYPHS.put(key, glyph);
        return glyph;
    }

    /** Maps brightness to the chosen colour, so a coloured icon stays readable in one hue. */
    private static ColorMatrixColorFilter monoFilter(int color) {
        float r = Color.red(color) / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color) / 255f;
        float[] lum = {0.299f, 0.587f, 0.114f};
        return new ColorMatrixColorFilter(new float[]{
                lum[0] * r, lum[1] * r, lum[2] * r, 0, 0,
                lum[0] * g, lum[1] * g, lum[2] * g, 0, 0,
                lum[0] * b, lum[1] * b, lum[2] * b, 0, 0,
                0, 0, 0, 1, 0});
    }

    private static Drawable appIcon(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static LinearLayout row(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private static LinearLayout column(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static TextView text(Context ctx, int sp, int color, boolean medium) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setIncludeFontPadding(false);
        if (medium) {
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return tv;
    }

    private static void setWidth(Capsule c, int width) {
        ViewGroup.LayoutParams lp = c.frame.getLayoutParams();
        lp.width = width;
        c.frame.setLayoutParams(lp);
    }

    private static void start(Capsule c, Animator a, Runnable end) {
        c.running = a;
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (c.running == animation) {
                    c.running = null;
                }
                if (end != null) {
                    end.run();
                }
            }
        });
        a.start();
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
