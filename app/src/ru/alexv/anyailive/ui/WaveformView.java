package ru.alexv.anyailive.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Анимированная волна живого режима: полоски, которые дышат в такт звуку.
 *
 * Классу плевать откуда звук: любой поток (микрофон, плеер модели) дёргает
 * {@link #push(float)} уровнем 0..1 (RMS), вью сама плавно ведёт огибающую
 * вниз и рисует полоски с лёгким «живым» дрожанием в простое.
 * Потокобезопасно: push можно звать из аудиопотока.
 */
public class WaveformView extends View {

    private static final int BARS = 5;
    private static final long FRAME_MS = 16;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] phase = new float[BARS];   // собственная фаза каждой полоски
    private final float[] level = new float[BARS];   // текущее сглаженное значение 0..1

    /** Огибающая энергии: 1.0 = громко, стремится к нулю в тишине. */
    private volatile float energy;
    private volatile int barColor = Color.WHITE;

    private long lastFrame;
    private boolean animating;

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint.setColor(barColor);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < BARS; i++) {
            phase[i] = (float) (i * 1.7 + 0.4);
        }
    }

    /** Цвет полосок. */
    public void setBarColor(int color) {
        barColor = color;
        paint.setColor(color);
        invalidate();
    }

    /**
     * Толчок уровня громкости 0..1. Зовут из любого потока; чем чаще,
     * тем живее волна (микрофон льётся ~50 раз в секунду — идеально).
     */
    public void push(float rms) {
        float v = rms < 0f ? 0f : (rms > 1f ? 1f : rms);
        // Немного бустим тихие значения, чтобы волна читалась глазами.
        v = (float) Math.min(1.0, Math.pow(v * 3.2, 0.65));
        if (v > energy) {
            energy = v;
        }
        wake();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        wake();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        animating = false;
    }

    /** Тикаем анимацию, пока вью видима и прикреплена. */
    private void wake() {
        if (animating || getWindowVisibility() != VISIBLE || getWindowToken() == null) {
            return;
        }
        animating = true;
        lastFrame = 0;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.nanoTime() / 1_000_000L;
        float dt = lastFrame == 0 ? FRAME_MS / 1000f : (now - lastFrame) / 1000f;
        if (dt > 0.2f) dt = 0.2f;
        lastFrame = now;

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            postInvalidateOnAnimation();
            return;
        }

        // Огибающая плавно падает в тишину.
        float e = energy * (float) Math.exp(-dt * 4.5);
        if (e < 0.01f) e = 0f;
        energy = e;

        // В простое лёгкое дыхание, при звуке — энергия.
        float t = now / 1000f;
        float idle = 0.10f + 0.05f * (float) Math.sin(t * 2.1);

        float slot = w / (BARS * 2f - 1f); // полоска + зазор, зазоры по краям нет
        float barW = slot * 0.62f;
        float radius = barW / 2f;
        float centerY = h / 2f;

        for (int i = 0; i < BARS; i++) {
            // Каждая полоска дрожит по своей фазе — «дышит» неровно, как речь.
            float jitter = (float) Math.sin(t * (6f + i * 1.3f) + phase[i]);
            float target = idle + e * (0.55f + 0.45f * (0.5f + 0.5f * jitter));
            // Сглаживание: полоска догоняет цель с разной скоростью.
            float k = 1f - (float) Math.exp(-dt * (9f + i * 2f));
            level[i] += (target - level[i]) * k;
            float lv = Math.max(0.06f, Math.min(1f, level[i]));
            float half = (h * 0.92f / 2f) * lv;
            float left = i * slot * 2f;
            canvas.drawRoundRect(left, centerY - half, left + barW, centerY + half,
                    radius, radius, paint);
        }

        // Продолжаем кадр за кадром: волна живёт всегда (тихое дыхание + затухание).
        if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) {
            postInvalidateOnAnimation();
        } else {
            animating = false;
        }
    }
}
