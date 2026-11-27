package com.chenan.flymebobo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** v478: 反方向的钟——音乐跑马灯（用户 2026-11-23 定名）。
 * 放音乐时屏幕边缘一圈 LED 灯珠，像 00 年代金立/中天/天语老按键机
 * 机身上的跑马灯：灯珠密密嵌满屏幕一圈，追逐、齐闪、交替、随机闪
 * 几个花样轮着来（每 4 秒换一个）。音乐停了就灭（BubbleWindow
 * 负责显隐）。本 View 不可点、不抢焦点，窗口本身还挂
 * FLAG_NOT_TOUCHABLE，盖在最上层也不影响点击。 */
public class MusicLedView extends View {

    /** 灯珠间距（dp）。 */
    private static final float GAP_DP = 14f;
    /** 灯珠半径（dp）。 */
    private static final float LED_R_DP = 3f;
    /** 每帧间隔（毫秒），约 8 帧/秒。 */
    private static final long FRAME_MS = 120L;
    /** 每 4 秒换一个花样。 */
    private static final int MODE_TICKS = 32;

    /** 花样：0 追逐、1 齐闪、2 交替、3 随机闪。 */
    private static final int MODE_COUNT = 4;

    private static final int[] COLORS = new int[]{
            0xFFEF4444, 0xFF22C55E, 0xFF3B82F6, 0xFFEAB308,
            0xFFA855F7, 0xFF06B6D4, 0xFFF97316,
    };

    private final Paint ledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<float[]> leds = new ArrayList<float[]>();

    private int mode = 0;
    private int tick = 0;
    private boolean running = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            tick++;
            if (tick % MODE_TICKS == 0) {
                mode = (mode + 1) % MODE_COUNT;
            }
            invalidate();
            handler.postDelayed(this, FRAME_MS);
        }
    };

    public MusicLedView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
    }

    /** 开始跑马灯（放音乐时调）。 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        mode = 0;
        tick = 0;
        handler.post(ticker);
    }

    /** 停掉跑马灯（音乐停了调）。 */
    public void stop() {
        running = false;
        handler.removeCallbacks(ticker);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 灯珠沿四边布一圈（贴边 4dp，间距 14dp），跟视频里老机子
        // 机身嵌满灯珠一个路子。
        // v480 修正：顶上一排就贴着 y=4dp——要盖到状态栏上去
        // （用户 2026-11-23 “为什么灯珠没有覆盖到状态栏去”、
        // “不是让你放下来”）。v479 把顶排挪到状态栏下面是错的，
        // 已改回。顶排盖不住状态栏的根因不在这儿，在窗口层级：
        // 窗口缺了 FLAG_LAYOUT_IN_SCREEN，系统没让它铺满全屏、
        // 盖到状态栏区域，顶排灯珠被状态栏挡在下面。
        // 修法见 BubbleWindow.showMusicLed（加 FLAG_LAYOUT_IN_SCREEN）。
        leds.clear();
        if (w <= 0 || h <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float gap = GAP_DP * density;
        float m = 4 * density;
        for (float x = m; x <= w - m; x += gap) {
            leds.add(new float[]{x, m});
        }
        for (float y = m + gap; y <= h - m; y += gap) {
            leds.add(new float[]{w - m, y});
        }
        for (float x = w - m - gap; x >= m; x -= gap) {
            leds.add(new float[]{x, h - m});
        }
        for (float y = h - m - gap; y >= m + gap; y -= gap) {
            leds.add(new float[]{m, y});
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!running || leds.isEmpty()) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float r = LED_R_DP * density;
        int n = leds.size();
        for (int i = 0; i < n; i++) {
            boolean on = false;
            if (mode == 0) {
                // 追逐：一小段（5 颗）亮灯沿着边跑。
                int d = (i - tick) % n;
                if (d < 0) {
                    d += n;
                }
                on = d < 5;
            } else if (mode == 1) {
                // 齐闪：整圈一起亮一起灭。
                on = (tick % 8) < 4;
            } else if (mode == 2) {
                // 交替：隔一颗亮一颗，来回换。
                on = ((i + tick / 2) % 2) == 0;
            } else {
                // 随机闪：每颗 35% 概率亮。
                on = random.nextFloat() < 0.35f;
            }
            float[] p = leds.get(i);
            if (!on) {
                // 灭着的灯珠：暗灰色小点，不抢眼。
                ledPaint.setColor(0xFF222222);
                ledPaint.setAlpha(64);
                ledPaint.setShadowLayer(0, 0, 0, 0);
                canvas.drawCircle(p[0], p[1], r, ledPaint);
                continue;
            }
            // 亮着的灯珠：本颗的颜色 + 光晕。
            int c = COLORS[(i + tick) % COLORS.length];
            ledPaint.setColor(c);
            ledPaint.setAlpha(255);
            ledPaint.setShadowLayer(r * 2.5f, 0, 0, c);
            canvas.drawCircle(p[0], p[1], r, ledPaint);
            ledPaint.setShadowLayer(0, 0, 0, 0);
        }
    }
}
