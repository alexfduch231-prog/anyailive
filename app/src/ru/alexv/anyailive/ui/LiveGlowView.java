package ru.alexv.anyailive.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;

/** A quiet, low-frame-rate ambient background for the live conversation. */
public class LiveGlowView extends View {
    private static final int FRAME_DELAY_MS = 50;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RadialGradient blue = new RadialGradient(0, 0, 1,
            new int[]{0xB52B72E8, 0x693055C2, 0x0011246A},
            new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
    private final RadialGradient cyan = new RadialGradient(0, 0, 1,
            new int[]{0x8038BEEA, 0x46317ED8, 0x000B244D},
            new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
    private final ContentObserver animationObserver = new ContentObserver(new Handler()) {
        @Override public void onChange(boolean selfChange) {
            invalidate();
        }
    };
    private boolean attached;
    private long startedAt;

    public LiveGlowView(Context context) { this(context, null); }
    public LiveGlowView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public LiveGlowView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        startedAt = SystemClock.uptimeMillis();
        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationObserver);
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        getContext().getContentResolver().unregisterContentObserver(animationObserver);
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (attached && isShown()) invalidate();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (attached && visibility == VISIBLE) invalidate();
    }

    private boolean animationsEnabled() {
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
        return Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(4, 8, 18));
        float w = getWidth();
        float h = getHeight();
        boolean animate = animationsEnabled();
        double t = animate ? (SystemClock.uptimeMillis() - startedAt) / 1000.0 : 0.0;
        float size = Math.max(w, h);
        drawGlow(canvas, blue, w * (0.26f + 0.09f * (float) Math.sin(t * 0.22)),
                h * (0.72f + 0.04f * (float) Math.cos(t * 0.19)), size * 0.66f);
        drawGlow(canvas, cyan, w * (0.79f + 0.08f * (float) Math.cos(t * 0.17)),
                h * (0.86f + 0.035f * (float) Math.sin(t * 0.23)), size * 0.52f);
        if (animate && attached && getWindowVisibility() == VISIBLE && isShown()) {
            postInvalidateDelayed(FRAME_DELAY_MS);
        }
    }

    private void drawGlow(Canvas canvas, RadialGradient gradient, float x, float y, float radius) {
        if (radius <= 0) return;
        matrix.reset();
        matrix.setScale(radius, radius);
        matrix.postTranslate(x, y);
        gradient.setLocalMatrix(matrix);
        paint.setShader(gradient);
        canvas.drawCircle(x, y, radius, paint);
    }
}
