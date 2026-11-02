package io.github.makish003.aodnotify;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Hand-made widget set that copies the COUI library used by ColorOS 16/17 Settings.
 * Sizes, colors and timings come from the decompiled Settings.apk (COUI resources) and
 * were checked against screenshots of the system Settings on the device.
 */
final class CouiUi {
    final Context ctx;
    final boolean dark;
    // Theme.COUI attributes, light / night
    final int bg;        // couiColorBackgroundWithCard
    final int card;      // couiColorCardBackground (#1affffff over black at night)
    final int text;      // couiColorLabelPrimary
    final int summary;   // couiColorSecondNeutral
    final int tertiary;  // couiColorLabelTertiary
    final int controls;  // couiColorControls, switch track when off
    final int pressBg;   // couiColorPressBackground, pressed and highlighted rows
    final int accent;    // couiColorPrimary
    final int popupBg;   // blurred popup, measured on the device
    final int backBg;    // round back button, measured on the device
    final int backStroke;

    /** {@code alwaysDark}: AOD's pages stay dark whatever the system mode is. */
    CouiUi(Context ctx, boolean alwaysDark) {
        this.ctx = ctx;
        dark = alwaysDark || (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF000000 : 0xFFF0F1F2;
        card = dark ? 0xFF1A1A1A : 0xFFFFFFFF;
        text = dark ? 0xE6FFFFFF : 0xE6000000;
        summary = dark ? 0x8AFFFFFF : 0x8A000000;
        tertiary = dark ? 0x4DFFFFFF : 0x42000000;
        controls = dark ? 0x40FFFFFF : 0x29000000;
        pressBg = dark ? 0x26FFFFFF : 0x14000000;
        popupBg = dark ? 0xFF161616 : 0xFFF5F5F5;
        backBg = dark ? 0xFF202020 : 0xFFF8F8F8;
        backStroke = dark ? 0x1AFFFFFF : 0x0F000000;
        // Theme.COUI.Blue; screenshots show it as #377EF7 because the display works in P3
        accent = dark ? 0xFF1A8CFF : 0xFF0080FF;
    }

    int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ---- text ----

    TextView label(CharSequence s, float sp, int color, boolean medium) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return tv;
    }

    /** couiTextAppearanceHeadline6: preference title. */
    TextView title(CharSequence s) {
        TextView tv = label(s, 16, text, true);
        tv.setLineSpacing(0, 1.05f);
        return tv;
    }

    /** couiTextAppearanceArticleBody: preference summary. */
    TextView summaryText(CharSequence s) {
        TextView tv = label(s, 13, summary, false);
        tv.setLineSpacing(0, 1.1f);
        return tv;
    }

    // ---- page structure ----

    /** Collapsed COUIToolbar: 52dp tall, round 40dp back button, 18sp medium title. */
    View header(CharSequence title, View.OnClickListener onBack) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(16), 0, dp(16), 0);
        row.setMinimumHeight(dp(52));

        BackButton back = new BackButton(ctx);
        back.setOnClickListener(onBack);
        back.setContentDescription(ctx.getString(R.string.back));
        back.setElevation(dp(2));
        row.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView t = label(title, 18, text, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(10));
        row.addView(t, lp);
        return row;
    }

    /** COUIPreferenceCategory title: 12sp medium, grey, 8dp above and below. */
    TextView section(CharSequence s, boolean first) {
        TextView tv = label(s, 12, summary, true);
        tv.setPaddingRelative(dp(32), dp(first ? 8 : 24), dp(32), dp(8));
        return tv;
    }

    /** COUI preference footer: small grey note under the last card. */
    TextView footer(CharSequence s) {
        TextView tv = label(s, 12, summary, false);
        tv.setLineSpacing(0, 1.1f);
        tv.setPaddingRelative(dp(32), dp(12), dp(32), 0);
        return tv;
    }

    /** A card of rows. */
    LinearLayout group() {
        return new Card(ctx);
    }

    LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(16));
        lp.setMarginEnd(dp(16));
        return lp;
    }

    /** A preference row: title + optional summary on the left, a widget on the right. */
    LinearLayout row(CharSequence titleText, CharSequence summaryText, View end) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPaddingRelative(dp(16), dp(10), dp(16), dp(10));
        row.setBackground(pressBackground());

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(title(titleText));
        if (summaryText != null) {
            texts.addView(summaryText(summaryText));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (end != null) {
            ViewGroup.LayoutParams own = end.getLayoutParams();
            LinearLayout.LayoutParams lp = own != null
                    ? new LinearLayout.LayoutParams(own.width, own.height)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(end instanceof CouiSwitch || end instanceof CheckView ? dp(16) : dp(8));
            if (end instanceof CouiSwitch) {
                // the switch view keeps 2dp around the track for the knob shadow
                lp.setMarginStart(dp(14));
                lp.setMarginEnd(-dp(2));
            }
            row.addView(end, lp);
        }
        return row;
    }

    /** Row press feedback of ListSelectedItemLayout; also stays on while the row's popup is open. */
    private StateListDrawable pressBackground() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(pressBg));
        d.addState(new int[]{android.R.attr.state_activated}, new ColorDrawable(pressBg));
        d.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        d.setEnterFadeDuration(100);
        d.setExitFadeDuration(300);
        return d;
    }

    /**
     * Two pictures to pick from, as Settings' light/dark choice (darkmode_preview_preference):
     * picture, 14dp medium label (couiColorPrimary when chosen) and a radio button under it,
     * 15dp on each side of the middle. {@code selected} -1 picks neither.
     */
    LinearLayout picker(CharSequence[] labels, Drawable[] pictures, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setPadding(dp(20), dp(24), dp(20), dp(20));
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            LinearLayout half = new LinearLayout(ctx);
            half.setOrientation(LinearLayout.VERTICAL);
            half.setGravity(Gravity.CENTER_HORIZONTAL);
            half.setPaddingRelative(i == 0 ? 0 : dp(15), 0, i == 0 ? dp(15) : 0, 0);
            ImageView picture = new ImageView(ctx);
            picture.setImageDrawable(pictures[i]);
            half.addView(picture);
            TextView label = label(labels[i], 14, i == selected ? accent : text, true);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(118), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(14);
            lp.bottomMargin = dp(6);
            half.addView(label, lp);
            half.addView(new RadioView(ctx, i == selected));
            half.setOnClickListener(v -> onPick.accept(index));
            row.addView(half, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    /** Right side of a value row: grey value and the jump arrow (or up-down arrows for a popup). */
    LinearLayout valueEnd(CharSequence value, boolean popup) {
        LinearLayout end = new LinearLayout(ctx);
        end.setOrientation(LinearLayout.HORIZONTAL);
        end.setGravity(Gravity.CENTER_VERTICAL);
        end.addView(label(value, 14, summary, false));
        View arrow = popup ? new UpDownView(ctx, tertiary) : new ChevronView(ctx, tertiary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(popup ? 14 : 12), dp(24));
        lp.setMarginStart(dp(4));
        end.addView(arrow, lp);
        return end;
    }

    // ---- popup list (COUIClickSelectMenu + COUIPopupListWindow) ----

    /**
     * Choice list that opens where the finger touched the row: centred on the touch x, below the
     * touch point when it fits, otherwise above it, 16dp from the screen sides (PopupMenuLocateHelper).
     * The finger can slide over the items and lift to pick one (COUITouchRecyclerView).
     */
    void choose(View anchor, float touchX, float touchY, CharSequence[] items, int selected,
                java.util.function.IntConsumer onPick) {
        Rect screen = ctx.getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds();
        WindowInsets insets = anchor.getRootWindowInsets();
        Insets bars = insets != null
                ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()) : Insets.NONE;

        FrameLayout root = new FrameLayout(ctx);
        PopupWindow popup = new PopupWindow(root, screen.width(), screen.height(), true);
        GlassMenu menu = new GlassMenu(ctx, items, selected, popup, onPick);
        root.addView(menu, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                menu.close(null);
            }
            return true;
        });

        menu.measure(View.MeasureSpec.makeMeasureSpec(dp(256), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screen.height(), View.MeasureSpec.AT_MOST));
        int w = Math.min(dp(256), Math.max(dp(168), menu.getMeasuredWidth()));
        int h = menu.getMeasuredHeight();

        int left = dp(16);
        int right = screen.width() - dp(16);
        int top = bars.top + dp(16);
        int bottom = screen.height() - bars.bottom - dp(32);
        int x = Math.max(left, Math.min(right - w, Math.round(touchX) - w / 2));
        boolean below = bottom - touchY >= h;
        int y;
        if (below) {
            y = Math.round(touchY);
        } else if (touchY - top >= h) {
            y = Math.round(touchY) - h;
        } else {
            y = Math.max(top, bottom - h);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        lp.width = w;
        lp.leftMargin = x;
        lp.topMargin = y;
        menu.setPivotX(Math.max(0, Math.min(w, touchX - x)));
        menu.setPivotY(below ? 0 : h);

        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setAnimationStyle(0);
        popup.setClippingEnabled(false);
        popup.setIsLaidOutInScreen(true);
        anchor.setActivated(true);
        popup.setOnDismissListener(() -> anchor.setActivated(false));
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, 0, 0);
        menu.open();
    }

    /** Spring with a response (seconds) and bounce, as COUISpringForce takes them, sampled over its settle time. */
    static final class Spring implements TimeInterpolator {
        final long durationMs;
        private final double mW0;
        private final double mZeta;
        private final double mSeconds;

        Spring(double response, double bounce) {
            mW0 = 2 * Math.PI / response;
            mZeta = 1 - bounce;
            mSeconds = bounce > 0 ? 6.2 / (mZeta * mW0) : 8.6 / mW0;
            durationMs = Math.round(mSeconds * 1000);
        }

        @Override
        public float getInterpolation(float t) {
            if (t >= 1f) {
                return 1f;
            }
            double time = t * mSeconds;
            if (mZeta >= 1) {
                return (float) (1 - (1 + mW0 * time) * Math.exp(-mW0 * time));
            }
            double wd = mW0 * Math.sqrt(1 - mZeta * mZeta);
            double decay = Math.exp(-mZeta * mW0 * time);
            return (float) (1 - decay * (Math.cos(wd * time) + mZeta * mW0 / wd * Math.sin(wd * time)));
        }
    }

    /**
     * The popup body: system background blur (RoundFrameLayout + COUIBackgroundBlurBuilder), a light
     * rim (COUIShadowEdgeDrawable), a glow under the finger (COUISpotLightEffect) and the shared
     * selection pill that springs from item to item.
     */
    final class GlassMenu extends LinearLayout {
        private final Path mPath = new Path();
        private final Path mPillPath = new Path();
        private final RectF mRect = new RectF();
        private final RectF mPill = new RectF();
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mPillColor = dark ? 0x33FFFFFF : 0x1F000000; // couiColorPress
        private final float mRadius = dp(28);
        private final PopupWindow mPopup;
        private final java.util.function.IntConsumer mOnPick;
        private final Spring mPillSpring = new Spring(0.15, 0);
        private boolean mBlurred;
        private int mTarget = -1;
        private float mPillAlpha;
        private float mPillFromTop;
        private float mPillFromBottom;
        private ValueAnimator mPillMove;
        private ValueAnimator mPillFade;
        private float mGlowX;
        private float mGlowY;
        private float mGlowLevel;
        private ValueAnimator mGlowAnim;
        private boolean mClosing;

        GlassMenu(Context c, CharSequence[] items, int selected, PopupWindow popup, java.util.function.IntConsumer onPick) {
            super(c);
            mPopup = popup;
            mOnPick = onPick;
            setOrientation(VERTICAL);
            setPadding(0, dp(8), 0, dp(8));
            setWillNotDraw(false);
            setElevation(dp(24));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mRadius);
                    outline.setAlpha(dark ? 0.5f : 0.25f);
                }
            });
            mFill.setColor(popupBg);
            mRim.setStyle(Paint.Style.STROKE);
            mRim.setStrokeWidth(dp(dark ? 2 : 4));
            mPillPaint.setColor(mPillColor);
            for (int i = 0; i < items.length; i++) {
                LinearLayout item = new LinearLayout(c);
                item.setOrientation(HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setMinimumHeight(dp(40));
                item.setPaddingRelative(dp(20), dp(8), dp(20), dp(8));
                TextView name = label(items[i], 16, text, false);
                name.setLineSpacing(0, 1.1f);
                item.addView(name, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                View mark = i == selected ? new CheckView(c, text) : new View(c);
                LayoutParams lp = new LayoutParams(dp(24), dp(24));
                lp.setMarginStart(dp(12));
                item.addView(mark, lp);
                addView(item, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            mBlurred = applySystemBlur(this, mRadius);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            mRect.set(0, 0, w, h);
            smoothRoundRect(mPath, mRect, mRadius, 2.5f);
            // COUIShadowEdgeDrawable: bright rim fading down the sides
            int top = dark ? 0x66FFFFFF : 0xE6FFFFFF;
            int middle = dark ? 0x1AFFFFFF : 0x80FFFFFF;
            int bottom = dark ? 0x33FFFFFF : 0xB3FFFFFF;
            mRim.setShader(new LinearGradient(0, 0, 0, h, new int[]{top, middle, bottom},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            if (!mBlurred) {
                canvas.drawPath(mPath, mFill);
            }
            super.draw(canvas);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            canvas.save();
            canvas.clipPath(mPath);
            if (mPillAlpha > 0f && mTarget >= 0) {
                mPillPaint.setAlpha(Math.round(Color.alpha(mPillColor) * mPillAlpha));
                smoothRoundRect(mPillPath, mPill, mPill.height() / 2f, 3f);
                canvas.drawPath(mPillPath, mPillPaint);
            }
            if (mGlowLevel > 0f) {
                float r = dp(48) + dp(40) * mGlowLevel;
                int peak = Math.round((dark ? 0.12f : 0.6f) * 255 * mGlowLevel);
                mGlow.setShader(new RadialGradient(mGlowX, mGlowY, r, Color.argb(peak, 255, 255, 255),
                        Color.TRANSPARENT, Shader.TileMode.CLAMP));
                canvas.drawCircle(mGlowX, mGlowY, r, mGlow);
            }
            super.dispatchDraw(canvas);
            canvas.drawPath(mPath, mRim);
            canvas.restore();
        }

        void open() {
            setScaleX(0f);
            setScaleY(0f);
            setAlpha(0f);
            Spring spring = new Spring(0.35, 0.12);
            animate().scaleX(1f).scaleY(1f).setDuration(spring.durationMs).setInterpolator(spring).start();
            ValueAnimator fade = ValueAnimator.ofFloat(0f, 1f);
            fade.setDuration(150);
            fade.addUpdateListener(a -> setAlpha((Float) a.getAnimatedValue()));
            fade.start();
        }

        void close(Runnable then) {
            if (mClosing) {
                return;
            }
            mClosing = true;
            animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(200)
                    .setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f))
                    .withEndAction(() -> {
                        mPopup.dismiss();
                        if (then != null) {
                            then.run();
                        }
                    }).start();
        }

        private int itemAt(float x, float y) {
            if (x < 0 || x > getWidth()) {
                return -1;
            }
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (y >= child.getTop() && y < child.getBottom()) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (mClosing) {
                return true;
            }
            float x = e.getX();
            float y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    glow(x, y, true);
                    select(itemAt(x, y), false);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    mGlowX = x;
                    mGlowY = y;
                    int over = itemAt(x, y);
                    if (over != mTarget) {
                        select(over, true);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    glow(x, y, false);
                    int picked = itemAt(x, y);
                    if (picked >= 0) {
                        close(() -> mOnPick.accept(picked));
                    } else {
                        select(-1, false);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    glow(x, y, false);
                    select(-1, false);
                    return true;
                default:
                    return true;
            }
        }

        /** Moves the shared pill to an item with the 0.15s spring, or fades it out for -1. */
        private void select(int index, boolean tick) {
            if (index < 0) {
                fadePill(0f);
                mTarget = -1;
                return;
            }
            View child = getChildAt(index);
            float toTop = child.getTop();
            float toBottom = child.getBottom();
            boolean visible = mTarget >= 0 && mPillAlpha > 0f;
            mTarget = index;
            mPill.left = dp(8);
            mPill.right = getWidth() - dp(8);
            if (mPillMove != null) {
                mPillMove.cancel();
            }
            if (!visible) {
                mPill.top = toTop;
                mPill.bottom = toBottom;
                fadePill(1f);
                return;
            }
            if (tick) {
                performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK);
            }
            mPillFromTop = mPill.top;
            mPillFromBottom = mPill.bottom;
            mPillMove = ValueAnimator.ofFloat(0f, 1f);
            mPillMove.setDuration(mPillSpring.durationMs);
            mPillMove.setInterpolator(mPillSpring);
            mPillMove.addUpdateListener(a -> {
                float f = (Float) a.getAnimatedValue();
                mPill.top = mPillFromTop + (toTop - mPillFromTop) * f;
                mPill.bottom = mPillFromBottom + (toBottom - mPillFromBottom) * f;
                invalidate();
            });
            mPillMove.start();
        }

        private void fadePill(float to) {
            if (mPillFade != null) {
                mPillFade.cancel();
            }
            mPillFade = ValueAnimator.ofFloat(mPillAlpha, to);
            mPillFade.setDuration(to > 0 ? 100 : 250);
            mPillFade.addUpdateListener(a -> {
                mPillAlpha = (Float) a.getAnimatedValue();
                invalidate();
            });
            mPillFade.start();
        }

        private void glow(float x, float y, boolean on) {
            mGlowX = x;
            mGlowY = y;
            if (mGlowAnim != null) {
                mGlowAnim.cancel();
            }
            mGlowAnim = ValueAnimator.ofFloat(mGlowLevel, on ? 1f : 0f);
            mGlowAnim.setDuration(on ? 300 : 250);
            mGlowAnim.setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f));
            mGlowAnim.addUpdateListener(a -> {
                mGlowLevel = (Float) a.getAnimatedValue();
                invalidate();
            });
            mGlowAnim.start();
        }
    }

    /**
     * Puts the window's background blur behind the view the way COUIBackgroundBlurBuilder does for
     * popups: com.oplus.view.ViewRootManager with fast Kawase blur, 160px radius, glow-overlay material.
     * Returns false where the ColorOS framework is missing.
     */
    private boolean applySystemBlur(View view, float radius) {
        try {
            Class<?> managerClass = Class.forName("com.oplus.view.ViewRootManager");
            Class<?> paramClass = Class.forName("com.oplus.graphics.OplusBlurParam");
            Object manager = managerClass.getConstructor(View.class).newInstance(view);
            Drawable blur = (Drawable) managerClass.getMethod("getBackgroundBlurDrawable").invoke(manager);
            if (blur == null) {
                return false;
            }
            Object param = paramClass.getConstructor().newInstance();
            paramClass.getMethod("setBlurType", int.class).invoke(param, 2);
            // coui_popup_list_blend_blur_* and coui_popup_list_mix_blur_*, as {r, g, b, a}
            float[] blend = argbToFloats(dark ? 0x99262626 : 0xBFEEEEEE);
            float[] mix = argbToFloats(dark ? 0x99262626 : 0x99CDCDCD);
            paramClass.getMethod("setMaterialParams", int.class, float[].class, float[].class).invoke(param, 4, blend, mix);
            try {
                paramClass.getMethod("setSmoothCornerWeight", float.class).invoke(param, 2.5f);
            } catch (NoSuchMethodException ignored) {
                // framework without smooth blur corners
            }
            managerClass.getMethod("setBlurParams", paramClass).invoke(manager, param);
            managerClass.getMethod("setBlurRadius", int.class).invoke(manager, 160);
            managerClass.getMethod("setCornerRadius", float.class).invoke(manager, radius);
            view.setBackground(blur);
            return true;
        } catch (Throwable t) {
            Log.w("AodNotify", "system blur unavailable: " + t);
            return false;
        }
    }

    private static float[] argbToFloats(int c) {
        return new float[]{Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, Color.alpha(c) / 255f};
    }

    // ---- smooth corners ----

    private static Constructor<?> sAdapterCtor;
    private static Method sAddSmooth;
    private static boolean sSmoothResolved;

    /**
     * ColorOS draws cards with "smooth" (squircle) corners through com.oplus.graphics.OplusPathAdapter
     * from the system framework, weight 2.5 on OS 16+. Falls back to a plain round rect elsewhere.
     */
    static void smoothRoundRect(Path path, RectF rect, float radius) {
        smoothRoundRect(path, rect, radius, 2.5f);
    }

    static void smoothRoundRect(Path path, RectF rect, float radius, float weight) {
        path.reset();
        if (!sSmoothResolved) {
            sSmoothResolved = true;
            try {
                Class<?> c = Class.forName("com.oplus.graphics.OplusPathAdapter");
                sAdapterCtor = c.getConstructor(Path.class, int.class);
                sAddSmooth = c.getMethod("addSmoothRoundRect", RectF.class, float[].class, Path.Direction.class, float.class);
            } catch (Throwable t) {
                sAdapterCtor = null;
            }
        }
        if (sAdapterCtor != null) {
            try {
                float[] radii = {radius, radius, radius, radius, radius, radius, radius, radius};
                sAddSmooth.invoke(sAdapterCtor.newInstance(path, 1), rect, radii, Path.Direction.CCW, weight);
                return;
            } catch (Throwable t) {
                sAdapterCtor = null;
                path.reset();
            }
        }
        path.addRoundRect(rect, radius, radius, Path.Direction.CCW);
    }

    /** COUICardListSelectedItemLayout as one container: 24dp smooth corners, 2dp extra on the first and last row. */
    final class Card extends LinearLayout {
        private final Path mPath = new Path();
        private final RectF mRect = new RectF();
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Card(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            mPaint.setColor(card);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                View child = getChildAt(i);
                int[] base = (int[]) child.getTag();
                if (base == null) {
                    base = new int[]{child.getPaddingTop(), child.getPaddingBottom(), child.getMinimumHeight()};
                    child.setTag(base);
                }
                int top = i == 0 ? dp(2) : 0;
                int bottom = i == n - 1 ? dp(2) : 0;
                child.setPaddingRelative(child.getPaddingStart(), base[0] + top, child.getPaddingEnd(), base[1] + bottom);
                child.setMinimumHeight(base[2] + top + bottom);
            }
            super.onMeasure(w, h);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            mRect.set(0, 0, w, h);
            smoothRoundRect(mPath, mRect, dp(24));
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawPath(mPath, mPaint);
            canvas.save();
            canvas.clipPath(mPath);
            super.draw(canvas);
            canvas.restore();
        }
    }

    // ---- painted pieces ----

    /** COUIToolbar navigation button: 40dp glass circle with the 24dp back arrow. */
    final class BackButton extends View {
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mArrow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();

        BackButton(Context c) {
            super(c);
            mFill.setColor(backBg);
            mStroke.setColor(backStroke);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(dp(0.5f));
            mArrow.setColor(text);
            mArrow.setStyle(Paint.Style.STROKE);
            mArrow.setStrokeCap(Paint.Cap.ROUND);
            mArrow.setStrokeJoin(Paint.Join.ROUND);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            setClickable(true);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            animate().scaleX(isPressed() ? 0.92f : 1f).scaleY(isPressed() ? 0.92f : 1f)
                    .setDuration(isPressed() ? 100 : 300).start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float r = w / 2f;
            canvas.drawCircle(r, r, r, mFill);
            canvas.drawCircle(r, r, r - mStroke.getStrokeWidth() / 2, mStroke);
            // coui_back_arrow_normal: 24x24 viewport, 1.4 stroke
            float s = dp(24) / 24f;
            float ox = r - dp(12);
            float oy = r - dp(12);
            mArrow.setStrokeWidth(1.4f * s);
            mPath.reset();
            mPath.moveTo(ox + 10.1f * s, oy + 4.4f * s);
            mPath.lineTo(ox + 2.5f * s, oy + 12f * s);
            mPath.lineTo(ox + 10.1f * s, oy + 19.6f * s);
            mPath.moveTo(ox + 2.5f * s, oy + 12f * s);
            mPath.lineTo(ox + 21.5f * s, oy + 12f * s);
            canvas.drawPath(mPath, mArrow);
        }
    }

    /** COUISwitchParams toggleSpring: response 0.3s, bounce 0.4. */
    private static final Spring SWITCH_SPRING = new Spring(0.3, 0.4);

    /** COUISwitch: 44x24dp track, 18dp white knob with a soft shadow, 3dp padding. */
    final class CouiSwitch extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mKnob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private boolean mChecked;
        private float mPos;
        private ValueAnimator mAnim;

        CouiSwitch(Context c, boolean checked) {
            super(c);
            mChecked = checked;
            mPos = checked ? 1f : 0f;
            mKnob.setColor(Color.WHITE);
            mKnob.setShadowLayer(8f, 0f, 4f, Color.argb(25, 0, 0, 0));
            setLayoutParams(new ViewGroup.LayoutParams(dp(48), dp(28)));
        }

        boolean isChecked() {
            return mChecked;
        }

        void setChecked(boolean checked) {
            if (mChecked == checked) {
                return;
            }
            mChecked = checked;
            if (mAnim != null) {
                mAnim.cancel();
            }
            mAnim = ValueAnimator.ofFloat(mPos, checked ? 1f : 0f);
            mAnim.setDuration(SWITCH_SPRING.durationMs);
            mAnim.setInterpolator(SWITCH_SPRING);
            mAnim.addUpdateListener(a -> {
                mPos = (Float) a.getAnimatedValue();
                invalidate();
            });
            mAnim.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(48), dp(28));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = dp(2);
            float w = getWidth() - 2 * inset;
            float h = getHeight() - 2 * inset;
            float p = Math.max(0f, Math.min(1f, mPos));
            mPaint.setColor(blend(controls, accent, p));
            mRect.set(inset, inset, inset + w, inset + h);
            canvas.drawRoundRect(mRect, h / 2, h / 2, mPaint);
            float pad = dp(3);
            float r = dp(9);
            float cx = inset + pad + r + (w - 2 * pad - 2 * r) * mPos;
            canvas.drawCircle(cx, inset + h / 2, r, mKnob);
        }
    }

    CouiSwitch switchView(boolean checked) {
        return new CouiSwitch(ctx, checked);
    }

    static int blend(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Check mark of COUIMarkPreference and the popup list, drawn in a 24dp box. */
    final class CheckView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();

        CheckView(Context c, int color) {
            super(c);
            mPaint.setColor(color);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            setLayoutParams(new ViewGroup.LayoutParams(dp(24), dp(24)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = getWidth() / 24f;
            mPaint.setStrokeWidth(1.5f * s);
            mPath.reset();
            mPath.moveTo(4.5f * s, 12.5f * s);
            mPath.lineTo(9.5f * s, 17.5f * s);
            mPath.lineTo(19.5f * s, 6.5f * s);
            canvas.drawPath(mPath, mPaint);
        }
    }

    /** COUI radio button, 20dp: a 1.5dp ring, or the accent disc with a white centre when checked. */
    final class RadioView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean mChecked;

        RadioView(Context c, boolean checked) {
            super(c);
            mChecked = checked;
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(24), dp(24));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float c = getWidth() / 2f;
            if (mChecked) {
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(accent);
                canvas.drawCircle(c, c, dp(10), mPaint);
                mPaint.setColor(Color.WHITE);
                canvas.drawCircle(c, c, dp(4), mPaint);
            } else {
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(dp(1.5f));
                mPaint.setColor(tertiary);
                canvas.drawCircle(c, c, dp(10) - dp(1.5f) / 2f, mPaint);
            }
        }
    }

    /** coui_btn_next: 12x24dp, 1.4 stroke. */
    static final class ChevronView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();

        ChevronView(Context c, int color) {
            super(c);
            mPaint.setColor(color);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = getHeight() / 24f;
            mPaint.setStrokeWidth(1.4f * s);
            mPath.reset();
            mPath.moveTo(5f * s, 6f * s);
            mPath.lineTo(10.8f * s, 12f * s);
            mPath.lineTo(5f * s, 18f * s);
            canvas.drawPath(mPath, mPaint);
        }
    }

    /** coui_pop_up_next: 14x24dp up and down arrows of a row that opens a popup list. */
    static final class UpDownView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();

        UpDownView(Context c, int color) {
            super(c);
            mPaint.setColor(color);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = getHeight() / 24f;
            mPaint.setStrokeWidth(1.4f * s);
            mPath.reset();
            mPath.moveTo(4.2f * s, 10f * s);
            mPath.lineTo(8.7f * s, 5.2f * s);
            mPath.lineTo(13.2f * s, 10f * s);
            mPath.moveTo(13.2f * s, 14f * s);
            mPath.lineTo(8.7f * s, 18.8f * s);
            mPath.lineTo(4.2f * s, 14f * s);
            canvas.drawPath(mPath, mPaint);
        }
    }
}
