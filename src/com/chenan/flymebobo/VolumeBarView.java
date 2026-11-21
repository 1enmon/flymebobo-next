package com.chenan.flymebobo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Random;

/**
 * flymebobo 2.0 — music capsule volume bars (v329, user 2026-11-21
 * "右边加上跳动的音量条").
 *
 * Four small vertical bars on the right end of the music capsule
 * that bounce like a volume / equalizer indicator while music is
 * playing. Each bar has its own phase so they jump out of step.
 * Pure appearance — decorative only, not clickable, and it only
 * runs while the music is playing; paused = bars rest at minimum.
 *
 * v331: rewritten on ValueAnimator. The old Handler ticker left
 * the bars stuck at minimum height ("....", user 2026-11-21
 * "这个音符条不会动" / "是静态的") — ValueAnimator is driven by
 * the Choreographer and is the reliable way to keep a View
 * redrawing.
 */
public class VolumeBarView extends View {
    private static final int BAR_COUNT = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final Random random = new Random();
    private final float[] phase = new float[BAR_COUNT];
    private ValueAnimator animator;
    // 0..1, advances once per animator cycle; onDraw reads it.
    private float animPhase;

    public VolumeBarView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        paint.setColor(0xFFFFFFFF);
        paint.setShadowLayer(dp(3), 0, 0, 0x80FFFFFF);
        for (int i = 0; i < BAR_COUNT; i++) {
            phase[i] = random.nextFloat() * (float) Math.PI * 2f;
        }
    }

    /** Start bouncing (music playing). Idempotent. */
    public void start() {
        if (animator != null && animator.isRunning()) {
            return;
        }
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(900);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    animPhase = (float) a.getAnimatedValue();
                    invalidate();
                }
            });
        }
        animator.start();
    }

    /** Stop: bars rest at minimum height. Idempotent. */
    public void stop() {
        if (animator != null) {
            animator.cancel();
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float gap = dp(3);
        float barW = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT;
        float minH = dp(5);
        float maxH = h;
        boolean running = animator != null && animator.isRunning();
        for (int i = 0; i < BAR_COUNT; i++) {
            float frac;
            if (running) {
                // Sine bounce driven by the animator phase, each bar
                // out of step so they jump like a live equalizer.
                float s = (float) Math.sin(
                        animPhase * (float) Math.PI * 2f * 1.5f + phase[i]);
                frac = 0.5f + 0.5f * s;
            } else {
                frac = 0f;
            }
            float bh = minH + (maxH - minH) * frac;
            float left = i * (barW + gap);
            float top = h - bh;
            barRect.set(left, top, left + barW, h);
            canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, paint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
