package com.chenan.flymebobo;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * flymebobo 2.0 — 贴边图标 (PileIcon).
 *
 * A single docked icon in the edge pile. The frame is 22dp wide; the
 * app icon inside (34dp) overflows toward the screen edge, so the pile
 * reads as a row of icons flush against the edge. The newest icon is
 * the FIXED ANCHOR — it sits at the edge and never moves; older icons
 * fan out from it toward the screen centre.
 *
 * Built fresh for 2.0 (not copied from 1.x). The host (DockedPile)
 * decides the horizontal offset; this view only knows its icon and
 * which edge it overflows toward.
 */
public class PileIcon extends FrameLayout {
    private static final int FRAME_DP = 22;
    private static final int ICON_DP = 34;

    private final ImageView iconView;
    private boolean dockLeft;
    private Runnable onTap;
    /** Which app this icon stands for (package name); null = unset. */
    private String pkg;

    public PileIcon(Context context, boolean dockLeft) {
        super(context);
        this.dockLeft = dockLeft;
        setClipChildren(false);
        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(iconView, new LayoutParams(dp(ICON_DP), dp(ICON_DP), overflowGravity()));
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onTap != null) {
                    onTap.run();
                }
            }
        });
    }

    /** The gravity that pushes the inner icon toward the anchored edge. */
    private int overflowGravity() {
        // Right edge: icon hugs END; left edge: icon hugs START.
        return dockLeft
                ? Gravity.START | Gravity.CENTER_VERTICAL
                : Gravity.END | Gravity.CENTER_VERTICAL;
    }

    public void setDockLeft(boolean dockLeft) {
        if (this.dockLeft == dockLeft) {
            return;
        }
        this.dockLeft = dockLeft;
        iconView.setLayoutParams(new LayoutParams(dp(ICON_DP), dp(ICON_DP), overflowGravity()));
    }

    public void setIcon(Drawable d) {
        iconView.setImageDrawable(d);
    }

    /** v460: 只给里面的图标（34dp）设透明度，不给外框（22dp）设。
     * 外框设 alpha 会让系统把它渲染进一个 22dp 大小的图层，
     * 34dp 图标溢出框外的 12dp 就被图层裁掉了——自动变淡后图标
     * 只剩一半，就是这个原因。图标自己 34dp，图层就是 34dp，
     * 整颗都在，不裁。 */
    public void setIconAlpha(float alpha) {
        iconView.setAlpha(alpha);
    }

    public void setOnTap(Runnable onTap) {
        this.onTap = onTap;
    }

    public void setPkg(String pkg) {
        this.pkg = pkg;
    }

    public String getPkg() {
        return pkg;
    }



    /** Width of the frame in px (the visible strip per icon is the step). */
    public int framePx() {
        return dp(FRAME_DP);
    }

    public static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private int dp(int v) {
        return dp(getContext(), v);
    }
}
