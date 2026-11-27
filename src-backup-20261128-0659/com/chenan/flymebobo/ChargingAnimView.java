package com.chenan.flymebobo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** v477: 充电全屏动画——樱花飞舞（用户 2026-11-23 定）。
 * 插上充电器时播一次：粉色花瓣从顶部飘落，左右摇摆、轻轻
 * 旋转，落到底部淡出。只在插上那一刻播（ACTION_POWER_CONNECTED
 * 触发，播的时候有锁，充着电的电量广播不重播）。
 * 本 View 不可点、不抢焦点，窗口本身还挂 FLAG_NOT_TOUCHABLE，
 * 盖在最上层也不影响点击。 */
public class ChargingAnimView extends View {

    /** 花瓣数。 */
    private static final int PETAL_COUNT = 22;

    /** 动画总时长（毫秒，含花瓣错开的延迟）。 */
    private static final long DURATION_MS = 3600L;

    private final Paint petalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path petalPath = new Path();
    private final Random random = new Random();
    private final List<Petal> petals = new ArrayList<Petal>();

    /** 动画跑完后的回调（BubbleWindow 用来撤窗）。 */
    private Runnable onDone;

    /** 整体进度 0..1（ValueAnimator 推）。 */
    private float progress = 0f;

    private static class Petal {
        /** 起始 x（相对屏宽 0..1）。 */
        float x0;
        /** 大小（dp，约 10..18）。 */
        float size;
        /** 下落时长占比（相对总时长 0.55..0.95）。 */
        float fallFrac;
        /** 开始延迟占比（0..0.35）。 */
        float delayFrac;
        /** 摇摆幅度（px）。 */
        float sway;
        /** 摇摆频率（每程几个来回）。 */
        float swayFreq;
        /** 旋转总角度（度）。 */
        float spin;
        /** 随机相位。 */
        float phase;
    }

    public ChargingAnimView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        petalPaint.setStyle(Paint.Style.FILL);
        // 花瓣形：水滴状（80% 圆角一头尖），画在以原点为中心、
        // 边长为 size 的框里，绘制时再平移旋转。
        petalPath.reset();
        petalPath.moveTo(0, -0.5f);
        petalPath.cubicTo(0.5f, -0.5f, 0.5f, 0.35f, 0, 0.5f);
        petalPath.cubicTo(-0.5f, 0.35f, -0.5f, -0.5f, 0, -0.5f);
        petalPath.close();
    }

    public void setOnDone(Runnable r) {
        onDone = r;
    }

    public void start() {
        if (petals.isEmpty()) {
            initPetals();
        }
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION_MS);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(
                    android.animation.Animator animation) {
                if (onDone != null) {
                    onDone.run();
                }
            }
        });
        anim.start();
    }

    private void initPetals() {
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < PETAL_COUNT; i++) {
            Petal p = new Petal();
            p.x0 = random.nextFloat();
            p.size = (10 + random.nextFloat() * 8) * density;
            p.fallFrac = 0.55f + random.nextFloat() * 0.4f;
            p.delayFrac = random.nextFloat() * 0.35f;
            p.sway = (12 + random.nextFloat() * 26) * density;
            p.swayFreq = 1.5f + random.nextFloat() * 1.5f;
            p.spin = 180 + random.nextFloat() * 360;
            p.phase = random.nextFloat() * (float) Math.PI * 2;
            petals.add(p);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || progress <= 0f) {
            return;
        }
        for (Petal p : petals) {
            // 本花瓣的局部进度：没到延迟不画，落完不画。
            float local = (progress - p.delayFrac) / p.fallFrac;
            if (local < 0f || local > 1f) {
                continue;
            }
            // y 从顶部外（-20）落到底部外（h+20）。
            float y = -20 + local * (h + 40);
            // x 左右摇摆（正弦），幅度 sway。
            float x = p.x0 * w
                    + (float) Math.sin(local * p.swayFreq
                            * Math.PI * 2 + p.phase) * p.sway;
            // 旋转：从 0 转到 spin。
            float rot = local * p.spin;
            // 透明度：开头 10% 淡入，末尾 15% 淡出。
            float alpha = 1f;
            if (local < 0.1f) {
                alpha = local / 0.1f;
            } else if (local > 0.85f) {
                alpha = (1f - local) / 0.15f;
            }
            if (alpha <= 0f) {
                continue;
            }
            // 粉色：中心浅、边缘深，带一点透明，落着看像花瓣。
            int a = (int) (235 * alpha);
            petalPaint.setColor(argb(a, 253, 164, 192));
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(rot);
            canvas.scale(p.size, p.size);
            canvas.drawPath(petalPath, petalPaint);
            canvas.restore();
        }
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
