package com.chenan.flymebobo;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * flymebobo 2.0 — 贴边 (DockedPile).
 *
 * The horizontal fan of docked icons at the screen edge. The NEWEST
 * icon is the FIXED ANCHOR: it sits flush against the edge and never
 * moves. Each older icon is offset toward the screen centre, so the
 * pile reads as a horizontal fan.
 *
 * Total width is FIXED at ~136dp for every state (the pin-state
 * width rule was dropped by the user 2026-11-18 "这个不做要求").
 * As more icons dock, the step between them shrinks so they all still
 * fit — each icon's visible strip gets thinner, nothing is hidden.
 * Normal step is 18dp; the step budget is total - frame(22dp) -
 * edge overflow(12dp) = 102. step = min(18, budget / (n - 1)),
 * floored at 6dp.
 *
 * Built fresh for 2.0 (not copied from 1.x). The host sets the pin
 * state; this container only lays the fan out.
 */
public class DockedPile extends FrameLayout {
    private static final int FRAME_DP = 22;
    private static final int OVERFLOW_DP = 12;
    private static final int NORMAL_STEP_DP = 18;
    private static final int MIN_STEP_DP = 6;
    private static final int TOTAL_WIDE_DP = 136;

    private boolean dockLeft;
    private final List<PileIcon> icons = new ArrayList<>();

    public DockedPile(Context context, boolean dockLeft) {
        super(context);
        this.dockLeft = dockLeft;
        setClipChildren(false);
    }

    public int getIconCount() {
        return icons.size();
    }

    public void setDockLeft(boolean dockLeft) {
        if (this.dockLeft == dockLeft) {
            return;
        }
        this.dockLeft = dockLeft;
        for (PileIcon icon : icons) {
            icon.setDockLeft(dockLeft);
        }
        relayout();
    }

    /** Step budget in dp: total - frame - overflow. */
    private int stepBudgetDp() {
        return TOTAL_WIDE_DP - FRAME_DP - OVERFLOW_DP;
    }

    /** Current step in dp for the fan. */
    /** Current step between icons, in px. */
    public int stepPx() {
        return dp(stepDp());
    }

    public int stepDp() {
        int n = icons.size();
        if (n <= 1) {
            return NORMAL_STEP_DP;
        }
        int fit = stepBudgetDp() / (n - 1);
        if (fit >= NORMAL_STEP_DP) {
            return NORMAL_STEP_DP;
        }
        return Math.max(MIN_STEP_DP, fit);
    }

    /** Total fan width in dp (frame + steps + overflow). */
    public int totalWidthDp() {
        int n = icons.size();
        if (n == 0) {
            return 0;
        }
        return FRAME_DP + (n - 1) * stepDp() + OVERFLOW_DP;
    }

    /** The anchor: the newest icon (last added), flush at the edge. */
    public PileIcon anchor() {
        if (icons.isEmpty()) {
            return null;
        }
        return icons.get(icons.size() - 1);
    }

    public int size() {
        return icons.size();
    }

    public void addIcon(PileIcon icon) {
        if (icon == null || icons.contains(icon)) {
            return;
        }
        // One icon per app: if this app already has an icon in the fan,
        // drop the old one first so the same app never stacks up into
        // duplicates (user 2026-11-19 "同一个应用多次打开会一直叠加").
        // The new icon becomes the newest (the anchor, flush at the edge).
        if (icon.getPkg() != null) {
            removeIconForPkg(icon.getPkg());
        }
        icon.setDockLeft(dockLeft);
        icons.add(icon);
        addView(icon);
        relayout();
    }

    /** Remove the fan icon for an app, if it has one. */
    public void removeIconForPkg(String pkg) {
        if (pkg == null) {
            return;
        }
        for (int i = icons.size() - 1; i >= 0; i--) {
            PileIcon icon = icons.get(i);
            if (pkg.equals(icon.getPkg())) {
                icons.remove(i);
                removeView(icon);
            }
        }
        relayout();
    }

    /** Does the fan already hold an icon for this app? */
    public boolean hasIconForPkg(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (PileIcon icon : icons) {
            if (pkg.equals(icon.getPkg())) {
                return true;
            }
        }
        return false;
    }

    /** Move an icon to a new slot in the fan (long-press drag reorder). */
    public void moveIcon(PileIcon icon, int toIndex) {
        if (icon == null || !icons.contains(icon)) {
            return;
        }
        int n = icons.size();
        if (toIndex < 0) {
            toIndex = 0;
        }
        if (toIndex > n - 1) {
            toIndex = n - 1;
        }
        icons.remove(icon);
        icons.add(toIndex, icon);
        // v202: keep the view z-order in sync with the list order.
        // moveIcon used to reorder only the list, so a dragged icon
        // kept the draw order from when it was added (newest = on
        // top) and floated ABOVE the icon it should tuck under
        // (user 2026-11-20 "浮在c上面了" / "叠加到a下面"). Rebuild
        // the child order to match the list: oldest (index 0) drawn
        // first/at the bottom, newest last/on top.
        for (int i = 0; i < icons.size(); i++) {
            PileIcon ic = icons.get(i);
            removeView(ic);
            addView(ic);
        }
        relayout();
    }

    /** Slot index (0 = oldest, n-1 = newest/anchor) of an icon, or -1. */
    public int indexOfIcon(PileIcon icon) {
        return icons.indexOf(icon);
    }

    public void removeIcon(PileIcon icon) {
        if (icon == null || !icons.remove(icon)) {
            return;
        }
        removeView(icon);
        relayout();
    }

    public void clearIcons() {
        icons.clear();
        removeAllViews();
    }

    /**
     * Lay the fan out. Oldest first so the newest (anchor) is drawn
     * last, on top. The anchor sits at offset 0 (flush at the edge);
     * each older icon is offset one more step toward the centre.
     */
    public void relayout() {
        int n = icons.size();
        int step = dp(stepDp());
        for (int i = 0; i < n; i++) {
            PileIcon icon = icons.get(i);
            // Never leave an icon stuck translated (v25: a translated
            // icon sits outside the small pile window and gets clipped
            // -> "missing" icon). A relayout always means "icons at
            // their fan spots", so cancel any in-flight animation and
            // clear the translation first.
            icon.animate().cancel();
            icon.setTranslationX(0f);
            icon.setTranslationY(0f);
            // gi = 0 for the newest (anchor), growing for older icons.
            int gi = (n - 1) - i;
            FrameLayout.LayoutParams lp =
                    new FrameLayout.LayoutParams(dp(FRAME_DP),
                            LayoutParams.WRAP_CONTENT,
                            dockLeft ? android.view.Gravity.START : android.view.Gravity.END);
            if (dockLeft) {
                lp.leftMargin = gi * step;
            } else {
                lp.rightMargin = gi * step;
            }
            icon.setLayoutParams(lp);
        }
        requestLayout();
    }

    /** Screen position of the anchor, for the expand/shrink animations. */
    public int[] anchorLocationOnScreen() {
        PileIcon a = anchor();
        if (a == null) {
            return null;
        }
        int[] loc = new int[2];
        a.getLocationOnScreen(loc);
        loc[0] += a.getWidth() / 2;
        loc[1] += a.getHeight() / 2;
        return loc;
    }

    private int dp(int v) {
        return Math.round(v * getContext().getResources().getDisplayMetrics().density);
    }

    /**
     * Stack the icons horizontally toward the anchor, one at a time,
     * starting from the LOWEST (user 2026-11-18 "各个贴边图标会主动
     * 横向叠加，由最低向锚点开始一次叠加"). The icons start in a
     * vertical column at the edge (where they landed when they
     * dissipated) and slide into the horizontal fan, lowest first.
     * Newest ends up flush at the edge, older ones fan toward the
     * centre; the whole fan sits at the anchor's row.
     */
}
