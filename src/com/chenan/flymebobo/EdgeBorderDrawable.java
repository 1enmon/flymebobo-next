package com.chenan.flymebobo;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;

/**
 * flymebobo 2.0 — 带三边描边的 pill 背景（v470 建，v471 改细）。
 *
 * v468 把描边画在 CapsuleView.dispatchDraw 里，但描边不跟胶囊动：
 * dispatchDraw 用的是 CapsuleView 自己的边界，而真正动的（弹入、
 * 拖动、长大成卡片）是里面的 pill / cardView——它们的尺寸和位置
 * 跟 CapsuleView 不总是一致，描边就留在原地了。改成把描边做到
 * 背景 drawable 里：背景本来就是跟着 view 走的（尺寸、位置、
 * 平移都跟着），描边自然跟胶囊动。
 *
 * 描边只画三边：顶边、圆角那头、底边；贴边那头（贴着屏幕边缘的
 * 直边）不画（用户 2026-11-23 “描边不要加在贴边那点”）。
 * 圆角半径和贴边方向由 CapsuleView 在 applyPillShape / updateBorder
 * 里传进来；fill 颜色和圆角仍走 GradientDrawable 原来的 setColor /
 * setCornerRadii，描边只额外画一层。
 * v471：黑描边改细（用户 2026-11-23 “黑描边细一点”“你这太粗了”）
 * ——宽度由调用方传，CapsuleView 里改成 1dp。
 */
public class EdgeBorderDrawable extends GradientDrawable {
    private final Paint borderPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG);
    private int borderColor = Color.TRANSPARENT;
    private int borderWidthPx = 0;
    private boolean dockLeft = true;
    /** 圆角那头的半径（px），由 CapsuleView 随圆角一起传进来。 */
    private float cornerRadiusPx = 0f;
    /** v474: 是否描圆角那头的竖边。默认 true（胶囊/卡片：描三边）。
     * 拖尾（cardTail）设 false——拖尾跟卡片共用一条边，只描顶边
     * 和底边（把卡片的边延长到拖尾上），不单独描竖边（用户
     * 2026-11-23 “拖尾不要单独描边”“他和展开卡片一个描边”）。
     * 设 false 时竖边不画，顶边和底边直接连到卡片的边上，中间
     * 没有竖线隔开。 */
    private boolean drawEnd = true;

    public EdgeBorderDrawable() {
        super();
    }

    /** 设描边（颜色 0 / 透明 = 不描）。调完自动 invalidate。 */
    public void setBorder(int color, int widthPx, boolean dockLeft,
            float radiusPx) {
        this.borderColor = color;
        this.borderWidthPx = widthPx;
        this.dockLeft = dockLeft;
        this.cornerRadiusPx = radiusPx;
        invalidateSelf();
    }

    /** 只改颜色（圆角/方向不变），调完自动 invalidate。 */
    public void setBorderColor(int color) {
        this.borderColor = color;
        invalidateSelf();
    }

    /** v474: 设是否描圆角那头的竖边（拖尾设 false，只描顶/底边）。 */
    public void setDrawEnd(boolean drawEnd) {
        if (this.drawEnd != drawEnd) {
            this.drawEnd = drawEnd;
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        if (borderColor == Color.TRANSPARENT || borderWidthPx <= 0) {
            return;
        }
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        if (w <= 0 || h <= 0) {
            return;
        }
        // v489（用户 2026-11-23 “搞反了”“调整胶囊圆角度会不会异常”）：
        // 描边改画实心圆环，不再用 STROKE。v487 把描边往里缩半个
        // 线宽是反的——原来露出来的只有里面半个线宽（外面那半被
        // 裁掉了），缩进去整条 1dp 全露出来，边反而变粗了；而且
        // 圆角比直边黑的根因是 STROKE 画弧线时抗锯齿混出来的像素
        // 比直边多，缩进去照样黑。圆环（外圈形状减内圈形状，
        // EVEN_ODD 填充）每处都是一样宽、一样实，圆角和直边看起
        // 来就一样了。圆角调大调小都不会异常：外圈半径跟底色圆角
        // 用同一个值（CapsuleView 传进来的 cornerRadiusPx，已钳到
        // 高度一半），内圈半径 = 外圈半径 − 线宽（小于 0 就按 0，
        // 方角也一样画）；贴边那头内圈不往里缩，圆环在那头没有边，
        // 顶/底/圆角头三边照旧。
        float bw = (float) borderWidthPx;
        float r = Math.min(cornerRadiusPx, h / 2f);
        borderPaint.setColor(borderColor);
        borderPaint.setStyle(Paint.Style.FILL);
        float left = b.left;
        float top = b.top;
        float right = b.right;
        float bottom = b.bottom;
        // v474: 拖尾（drawEnd=false）只描顶边和底边——两条实心横条，
        // 把卡片的边延长到拖尾上，不单独描竖边（跟卡片共用一条边，
        // 中间没有竖线隔开）。
        if (!drawEnd) {
            canvas.drawRect(left, top, right, top + bw, borderPaint);
            canvas.drawRect(left, bottom - bw, right, bottom,
                    borderPaint);
            return;
        }
        Path ring = new Path();
        ring.setFillType(Path.FillType.EVEN_ODD);
        addPillPath(ring, left, top, right, bottom, r, dockLeft);
        float ir = Math.max(0f, r - bw);
        if (dockLeft) {
            // 贴左边：内圈左边不缩（贴边头不描边），只缩顶/右/底。
            addPillPath(ring, left, top + bw, right - bw, bottom - bw,
                    ir, dockLeft);
        } else {
            // 贴右边：镜像，内圈右边不缩。
            addPillPath(ring, left + bw, top + bw, right, bottom - bw,
                    ir, dockLeft);
        }
        canvas.drawPath(ring, borderPaint);
    }

    /** 加一段一头方、一头圆的 pill 形状到 path（不闭合由调用方定，
     * 这里直接 close）。dockLeft：方左圆右；否则圆左方右。r=0 时
     * 就是矩形。 */
    private void addPillPath(Path p, float left, float top, float right,
            float bottom, float r, boolean dockLeft) {
        float hh = bottom - top;
        if (r > hh / 2f) {
            r = hh / 2f;
        }
        if (r < 0f) {
            r = 0f;
        }
        if (dockLeft) {
            p.moveTo(left, top);
            p.lineTo(right - r, top);
            if (r > 0f) {
                p.arcTo(new RectF(right - 2 * r, top, right,
                        top + 2 * r), 270, 90);
            }
            if (2 * r < hh) {
                p.lineTo(right, bottom - r);
            }
            if (r > 0f) {
                p.arcTo(new RectF(right - 2 * r, bottom - 2 * r,
                        right, bottom), 0, 90);
            }
            p.lineTo(left, bottom);
        } else {
            p.moveTo(right, top);
            p.lineTo(left + r, top);
            if (r > 0f) {
                p.arcTo(new RectF(left, top, left + 2 * r,
                        top + 2 * r), 270, -90);
            }
            if (2 * r < hh) {
                p.lineTo(left, bottom - r);
            }
            if (r > 0f) {
                p.arcTo(new RectF(left, bottom - 2 * r,
                        left + 2 * r, bottom), 180, -90);
            }
            p.lineTo(right, bottom);
        }
        p.close();
    }
}
