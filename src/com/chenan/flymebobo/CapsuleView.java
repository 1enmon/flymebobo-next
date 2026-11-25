package com.chenan.flymebobo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * flymebobo 2.0 — 胶囊 (CapsuleView).
 *
 * The bubble capsule for one notification: a rounded pill with the
 * app icon on the left and two lines of text (title, content).
 * Default 200dp wide, 52dp tall, corner radius 26dp (fully rounded).
 *
 * Pin badge: the pin (balloon) sits OUTSIDE the capsule, at the
 * top-right corner straddling the edge, with a stroke around it so
 * it stays readable on any background (user 2026-11-18 "钉住图标
 * 改到胶囊外面" / "加上描边" / "防止看不清").
 *
 * Fresh code for 2.0 (not copied from 1.x).
 */
public class CapsuleView extends FrameLayout {
    public static final int DEFAULT_WIDTH_DP = 180;
    /** v347: 平板（最小宽度 >= 600dp）胶囊加宽到 260dp，否则 180dp 里
     * 标题/正文显示不全（用户 2026-11-22 平板截图：文字被截断）。 */
    public static final int TABLET_WIDTH_DP = 260;
    /** v347: 平板卡片同步加宽到 380dp（手机 300dp）。 */
    public static final int TABLET_CARD_WIDTH_DP = 380;
    public static final int DEFAULT_HEIGHT_DP = 44;
    /** v376: 来电胶囊放大（预览 A 案 240×60：头像 40dp、名字 16sp、
     * 接听钮 40dp、计时 16sp）。 */
    public static final int CALL_WIDTH_DP = 240;
    public static final int CALL_HEIGHT_DP = 60;
    /** 平板判定：最小宽度 >= 600dp 即平板。 */
    public static boolean isTablet(Context ctx) {
        return ctx.getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }
    /** v456: 布局用的最宽胶囊宽（dp）——取普通/音乐两档的最大值，
     * 拖拽窗、粒子数这些不分胶囊类型的布局按它算，不会把音乐胶囊
     * 截掉。单个胶囊自己的宽走实例方法 baseWidthDp()。 */
    public static int capsuleWidthDp(Context ctx) {
        int w = Appearance.capsuleWidthDp;
        int mw = Appearance.musicCapsuleWidthDp;
        return w > mw ? w : mw;
    }
    /** v456: 布局用的最宽卡片宽（dp）——取普通卡片/音乐卡片
     * 的最大值。单个卡片自己的宽走实例方法 cardWidthDp()。
     * v458: 音乐卡片宽也可调了，一起取最大。 */
    public static int cardWidthDp(Context ctx) {
        int w = Appearance.cardWidthDp;
        int mw = Appearance.musicCardWidthDp;
        int m = w > mw ? w : mw;
        return m > CARD_WIDTH_DP ? m : CARD_WIDTH_DP;
    }
    /** v350: 手势阈值按胶囊宽度等比缩放（平板胶囊 260dp、手机 180dp）。
     * 右滑消散 = 胶囊宽的 2/3（手机 120dp、平板约 173dp）；左轻滑钉住
     * = 1/3～7/9（手机 60～140dp、平板约 87～202dp）；左重滑收纳 =
     * 超过 7/9。写死 dp 的话，平板上消散太容易误触（用户 2026-11-22
     * “还有自动消除的距离”）。 */
    /** v456: a notification's dock-row height (dp) by type — 来电 /
     * 音乐 / 普通各走各栏的高度。BubbleWindow 的行距按它算。
     * v497: 1.0 模式下普通通知气泡高 52dp，行距也得按 52dp
     * 算——之前这里还返回 44dp（2.0 胶囊高），行距 44+6=50dp
     * 比气泡矮 2dp，两颗气泡上下叠在一起（用户 10:52 截图）。
     * v501: 1.0 模式全撤——这条也撤回，行距按 2.0 胶囊高算。 */
    public static int heightDpFor(NotifData d) {
        if (d != null && d.isCall) {
            return Appearance.callCapsuleHeightDp;
        }
        if (d != null && d.isMusic) {
            return Appearance.musicCapsuleHeightDp;
        }
        return Appearance.capsuleHeightDp;
    }

    /** v376: this capsule's effective width (dp) — v456: 按类型分流
     * （用户 “来电和音乐单独调整”）：来电走来电栏的宽，音乐走音乐
     * 栏的宽，普通通知走外观的宽。
     * v501: 1.0 模式全撤。 */
    public int baseWidthDp() {
        if (data != null && data.isCall) {
            return Appearance.callCapsuleWidthDp;
        }
        if (data != null && data.isMusic) {
            return Appearance.musicCapsuleWidthDp;
        }
        return Appearance.capsuleWidthDp;
    }

    /** v456: this capsule's effective height (dp) — 跟宽一个分流：
     * 来电 / 音乐 / 普通各走各栏的高度。
     * v501: 1.0 模式全撤。 */
    public int baseHeightDp() {
        if (data != null && data.isCall) {
            return Appearance.callCapsuleHeightDp;
        }
        if (data != null && data.isMusic) {
            return Appearance.musicCapsuleHeightDp;
        }
        return Appearance.capsuleHeightDp;
    }

    /** v462: 音乐卡片比例调整——内容按自然尺寸布局、按比例缩着画。
     * 胶囊本身的 LayoutParams 是缩放后的尺寸（BubbleWindow 的
     * cardLp 按 cardWidthDp()/cardHeightPx 设），但内容（播放卡）
     * 必须按自然尺寸排好再缩，不然控制 / 进度条先被截掉。所以
     * 这里量孩子时用自然尺寸（自然卡片宽 + 高度不限），量完再
     * 把上报的尺寸改成缩放后的（父容器按缩放尺寸排位置），内容
     * 由 musicCardView 自己的 setScale 缩着画（updateMusicCard
     * 里设，左上角为轴），正好填满缩放后的胶囊。只对已展开的
     * 音乐卡、比例 < 100% 时生效；其他情况走正常量法。
     * v484: 展开卡片（普通卡）也一样——已展开的普通卡、比例
     * < 100% 时，内容按自然尺寸排好再缩，cardView 自己 setScale
     * 缩着画（setCardExpanded 里设，左上角为轴）。 */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (data != null && data.isMusic && cardExpanded
                && musicCardScale() < 1.0f) {
            int naturalW = dp(Appearance.musicCardWidthDp);
            int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    naturalW, android.view.View.MeasureSpec.EXACTLY);
            int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    0, android.view.View.MeasureSpec.UNSPECIFIED);
            super.onMeasure(wSpec, hSpec);
            int naturalH = getMeasuredHeight();
            setMeasuredDimension(
                    Math.round(naturalW * musicCardScale()),
                    Math.round(naturalH * musicCardScale()));
            return;
        }
        // v484: 展开卡片（普通卡）比例——跟音乐卡一个路子：内容按
        // 自然卡片宽排好，量完上报缩放后的尺寸，cardView 自己
        // setScale 缩着画。
        if (data != null && !data.isMusic && cardExpanded
                && cardScale() < 1.0f) {
            // v516: navi cards lay out at the generic card
            // width too (v515's navi-only natural width is
            // reverted — card size follows the one global
            // adjustment, user 2026-11-25).
            int naturalW = dp(Appearance.cardWidthDp);
            int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    naturalW, android.view.View.MeasureSpec.EXACTLY);
            int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    0, android.view.View.MeasureSpec.UNSPECIFIED);
            super.onMeasure(wSpec, hSpec);
            int naturalH = getMeasuredHeight();
            setMeasuredDimension(
                    Math.round(naturalW * cardScale()),
                    Math.round(naturalH * cardScale()));
            return;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /** v462: 音乐卡片比例（0.6–1.0）——整张播放卡按比例缩放
     * 用的因子。100% = 1.0f，不缩。 */
    public static float musicCardScale() {
        return Appearance.musicCardScalePct / 100f;
    }

    /** v484: 展开卡片（普通卡）比例（0.6–1.0）——整张展开卡按
     * 比例缩放用的因子。100% = 1.0f，不缩。跟音乐卡片比例
     * 一个路子。 */
    public static float cardScale() {
        return Appearance.cardScalePct / 100f;
    }

    /** v456: this card's width (dp) — 普通卡走外观的卡片宽，音乐
     * 播放卡不动（300dp 手机 / 380dp 平板）。来电不开卡片。
     * v458: 音乐卡片也要支持大小调整——音乐播放卡走外观的音乐
     * 卡片宽（默认 300 手机 / 380 平板，跟普通卡片一个范围）。
     * v462: 音乐卡片比例调整——卡片宽 = 音乐卡片宽 × 比例
     * （60–100%），整张卡等比缩小。
     * v484: 展开卡片（普通卡）比例调整——卡片宽 = 卡片宽 × 比例
     * （60–100%），整张卡等比缩小，跟音乐卡一个路子。 */
    public int cardWidthDp() {
        if (data != null && data.isMusic) {
            return Math.round(
                    Appearance.musicCardWidthDp * musicCardScale());
        }
        // v516: navi cards take the generic card width like
        // every other non-music card (the v515 navi-only
        // 340dp width is reverted — one global card-size
        // adjustment for all cards, user 2026-11-25).
        return Math.round(Appearance.cardWidthDp * cardScale());
    }

    /** v456: this card's corner radius (dp) — 普通卡走外观的卡片
     * 圆角，音乐播放卡不动（22dp）。
     * v458: 音乐卡片圆角也走外观（默认 22）。 */
    public int cardCornerRadiusDp() {
        if (data != null && data.isMusic) {
            return Appearance.musicCardCornerRadiusDp;
        }
        return Appearance.cardCornerRadiusDp;
    }

    /** v456: this card's max rows — 普通卡走外观的最大行数，音乐
     * 播放卡不动（8 行）。v458: 音乐卡片的高度跟内容走（播放器
     * 布局固定，行数上限不截它，截了会把播放控制切掉），所以
     * 音乐卡片只调宽和圆角，最大行数仍是 8 行。 */
    public int cardMaxRows() {
        if (data != null && data.isMusic) {
            return MAX_CARD_ROWS;
        }
        return Appearance.cardMaxRows;
    }

    /** v376: the pill's rounded-end radius (dp) — v456: 按类型分流，
     * 各走各栏的圆角；生效值按 min(设定值, 高度/2) 钳住，高度改小
     * 了圆角不会溢出（圆角最大不超过高度一半）。
     * v501: 1.0 模式全撤。 */
    public int cornerRadiusDp() {
        int r;
        if (data != null && data.isCall) {
            r = Appearance.callCornerRadiusDp;
        } else if (data != null && data.isMusic) {
            r = Appearance.musicCornerRadiusDp;
        } else {
            r = Appearance.cornerRadiusDp;
        }
        int half = baseHeightDp() / 2;
        return r > half ? half : r;
    }

    public int dissipateDp() {
        return baseWidthDp() * 2 / 3;
    }
    public int pinMinDp() {
        return baseWidthDp() / 3;
    }
    public int pinMaxDp() {
        return baseWidthDp() * 7 / 9;
    }
    /** Card (v143): the capsule grown into a card. Same view, same
     * background tint — only bigger. Width 300dp; height snaps to the
     * capsule grid (k*50-6dp) so the bottom lands one gap above the
     * capsule below and the 22dp corners fit. */
    public static final int CARD_WIDTH_DP = 300;
    /** v456: 卡片圆角/最大行数的出厂值（音乐播放卡不动，用这两个）。 */
    public static final int DEF_CARD_CORNER_RADIUS = 22;
    public static final int MAX_CARD_ROWS = 8;
    public static final int CARD_PITCH_DP = 50;
    public static final int CARD_GAP_DP = 6;

    private final LinearLayout pill;
    private final EdgeBorderDrawable pillBg;
    /** Current pill colour (for the particle burst); 0 = not set yet. */
    private int pillColor;
    /** Whether the pill is light (for the stacked border colour). */
    private boolean pillLight;
    /** Whether this capsule is in a cascade stack (gets a border). */
    private boolean stacked;
    /** Current pill width in px (grows to 180+dx while dragged out). */
    private int pillWidthPx;
    /** Reusable long-capsule background (see LongCapsuleDrawable):
     * one drawable, one cached gradient shader — dragging never
     * allocates a bitmap. */
    private LongCapsuleDrawable longBg;
    private boolean longGrown;
    private int lastGrowW = -1;
    /** v409: the running spring-back animator for a call capsule
     * (see springBackCallCapsule). Cancelled on a new touch so a
     * fresh drag never fights the tail retraction. */
    private android.animation.ValueAnimator callSpringBackAnim;

    /** One background for the whole long capsule (one element, one
     * gradient, no seam): transparent at the screen edge, fading to
     * the tint, then solid — clipped to the half-pill shape (flat
     * against the screen edge, rounded toward the centre). The
     * LinearGradient shader is cached and only rebuilt when the
     * width / fade fraction / tint changes, so a swipe does no
     * per-frame bitmap allocation or texture upload (that was the
     * left/right swipe jank). The GPU renders the gradient
     * directly from the shader. */
    private static class LongCapsuleDrawable
            extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
        /** v472: 拖尾的描边画笔（用户 2026-11-23 “描边延长到拖尾”）。 */
        private final android.graphics.Paint borderPaint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path =
                new android.graphics.Path();
        private final android.graphics.RectF rect =
                new android.graphics.RectF();
        private android.graphics.LinearGradient shader;
        private int tint;
        private final float cornerR;
        private final int bodyW;
        private int lastW, lastH, lastTint;
        private float lastFade = -1f;
        /** v376: mirror the tail for a right-edge capsule — the
         * rounded end and the fade go on the LEFT (toward the
         * centre), the flat end on the RIGHT (the screen edge). */
        private boolean mirror;
        private boolean lastMirror;
        /** v472: 拖尾的描边颜色（0/透明 = 不描）与宽度。 */
        private int borderColor = android.graphics.Color.TRANSPARENT;
        private int borderWidthPx = 0;

        void setMirror(boolean mirror) {
            if (this.mirror != mirror) {
                this.mirror = mirror;
                shader = null;
                invalidateSelf();
            }
        }

        /** v472: 设拖尾描边（颜色 0/透明 = 不描）。只在变了时重画。 */
        void setBorder(int color, int widthPx) {
            if (this.borderColor != color
                    || this.borderWidthPx != widthPx) {
                this.borderColor = color;
                this.borderWidthPx = widthPx;
                invalidateSelf();
            }
        }

        LongCapsuleDrawable(int tint, float cornerR, int bodyW) {
            this.tint = tint;
            this.cornerR = cornerR;
            this.bodyW = bodyW;
        }

        void setTintColor(int tint) {
            if (tint == 0) {
                tint = android.graphics.Color.argb(204, 30, 32, 38);
            }
            if (this.tint != tint) {
                this.tint = tint;
                shader = null;
                invalidateSelf();
            }
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            int w = b.width();
            int h = b.height();
            if (w <= 0 || h <= 0) {
                return;
            }
            // The fade completes where the capsule body starts:
            // fade = tail / total width (0 when there is no tail).
            float fade = w > bodyW ? (float) (w - bodyW) / w : 0f;
            if (shader == null || w != lastW || h != lastH
                    || Math.abs(fade - lastFade) > 0.01f
                    || tint != lastTint || mirror != lastMirror) {
                rect.set(0, 0, w, h);
                path.reset();
                path.addRoundRect(rect,
                        mirror
                                // v377: same corner bug as
                                // applyPillShape — {r,r,r,r,0,0,0,0}
                                // rounds the TOP, not the LEFT. A
                                // proper left-right mirror rounds the
                                // LEFT end and flats the RIGHT end.
                                ? new float[]{cornerR, cornerR,
                                        0, 0, 0, 0, cornerR, cornerR}
                                : new float[]{0, 0, cornerR, cornerR,
                                        cornerR, cornerR, 0, 0},
                        android.graphics.Path.Direction.CW);
                if (fade < 0.02f) {
                    paint.setShader(null);
                    paint.setColor(tint);
                } else if (mirror) {
                    shader = new android.graphics.LinearGradient(
                            0, 0, w, 0,
                            new int[]{tint, tint,
                                    android.graphics.Color.TRANSPARENT},
                            new float[]{0f, 1f - fade, 1f},
                            android.graphics.Shader.TileMode.CLAMP);
                    paint.setShader(shader);
                } else {
                    shader = new android.graphics.LinearGradient(
                            0, 0, w, 0,
                            new int[]{
                                    android.graphics.Color.TRANSPARENT,
                                    tint, tint},
                            new float[]{0f, fade, 1f},
                            android.graphics.Shader.TileMode.CLAMP);
                    paint.setShader(shader);
                }
                lastW = w;
                lastH = h;
                lastFade = fade;
                lastTint = tint;
                lastMirror = mirror;
            }
            canvas.save();
            canvas.translate(b.left, b.top);
            canvas.drawPath(path, paint);
            // v472: 描边延长到拖尾（用户 2026-11-23 “描边延长到拖尾”）：
            // 沿着长胶囊的轮廓描三边（顶边、圆角那头、底边），贴边那头
            // （贴着屏幕边缘的直边）不画，跟胶囊本身的描边一个路子。
            // 透明那截（渐变淡出到透明）描边也跟着淡——描边画在
            // 渐变底色之上，透明处描边色本身不变，只是底下透出来，
            // 看起来就是描边跟着尾巴一起淡下去。
            if (borderColor != android.graphics.Color.TRANSPARENT
                    && borderWidthPx > 0) {
                float r = Math.min(cornerR, h / 2f);
                borderPaint.setColor(borderColor);
                borderPaint.setStyle(
                        android.graphics.Paint.Style.STROKE);
                borderPaint.setStrokeWidth(borderWidthPx);
                android.graphics.Path bp =
                        new android.graphics.Path();
                if (mirror) {
                    // 贴右边：不描右直边，只描顶、左圆角头、底。
                    bp.moveTo(w, 0);
                    bp.lineTo(r, 0);
                    bp.arcTo(new android.graphics.RectF(0, 0, 2 * r, 2 * r),
                            270, -90);
                    if (2 * r < h) {
                        bp.lineTo(0, h - r);
                    }
                    bp.arcTo(new android.graphics.RectF(0, h - 2 * r,
                            2 * r, h), 180, -90);
                    bp.lineTo(w, h);
                } else {
                    // 贴左边：不描左直边，只描顶、右圆角头、底。
                    bp.moveTo(0, 0);
                    bp.lineTo(w - r, 0);
                    bp.arcTo(new android.graphics.RectF(w - 2 * r, 0, w,
                            2 * r), 270, 90);
                    if (2 * r < h) {
                        bp.lineTo(w, h - r);
                    }
                    bp.arcTo(new android.graphics.RectF(w - 2 * r,
                            h - 2 * r, w, h), 0, 90);
                    bp.lineTo(0, h);
                }
                canvas.drawPath(bp, borderPaint);
            }
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(
                android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
    private final ImageView iconView;
    private final LinearLayout texts;
    private final TextView titleView;
    private final TextView textView;
    /** Pinned music lyric line: one line, enlarged (15sp), scrolling
     * marquee. Hidden unless the capsule is a pinned music capsule. */
    private final TextView lyricsView;
    /** v204: lyric roll — the pinned lyric line rolls up (old line
     * slides out, new line follows in, 320ms) instead of swapping
     * text in place. lyricsRoll clips to exactly one line; the
     * strip holds the current line (lyricsView) and the incoming
     * line (lyricsViewNext). */
    private final android.widget.FrameLayout lyricsRoll;
    private final android.widget.LinearLayout lyricsStrip;
    private final TextView lyricsViewNext;
    private boolean lyricsRolling;
    private final FrameLayout pinView;
    /** Call mode (v103, answer button redesigned v108): the answer
     * button at the right end (pin icon position) is a pill holding
     * a vector icon and, once answered, the call timer. States:
     * ringing = green handset; right swipe = red hang-up handset;
     * left swipe = green speaker; answered = icon + timer (handset
     * for normal, speaker for speakerphone); tapping the timer
     * toggles speaker. */
    private final LinearLayout answerBtn;
    private final ImageView answerIcon;
    private final TextView answerTimerText;
    private final android.graphics.drawable.GradientDrawable answerBtnBg;
    private Runnable onAnswer;
    private Runnable onHangup;
    private Runnable onToggleSpeaker;
    private boolean callRed;
    private boolean callAnswered;
    private boolean callSpeaker;
    // v415 (preview): after answering, the capsule collapses to
    // just the timer. True once collapseToTimer() has run for this
    // call; reset in bind() so a new call starts full-size.
    private boolean collapsedToTimer;
    private android.graphics.Bitmap iconCallBmp;
    private android.graphics.Bitmap iconCallEndBmp;
    private android.graphics.Bitmap iconVolumeBmp;
    /** Material icon path data (24x24 viewport), drawn by
     * parseSvgPath/renderIcon below — no emoji, the user found the
     * emoji icons ugly (2026-11-19). */
    private static final String ICON_CALL =
            "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2"
            + "c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 "
            + ".45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45"
            + "-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35"
            + ".03.74-.25 1.02l-2.2 2.2z";
    private static final String ICON_CALL_END =
            "M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.56.9-.98.49"
            + "-1.87 1.12-2.66 1.85-.18.18-.43.28-.7.28-.28 0-.53-.11-.71"
            + "-.29L.29 13.08c-.18-.17-.29-.42-.29-.7 0-.28.11-.53.29-.71"
            + "C3.34 8.78 7.46 7 12 7s8.66 1.78 11.71 4.67c.18.18.29.43"
            + ".29.71 0 .28-.11.53-.29.71l-2.48 2.48c-.18.18-.43.29-.71"
            + ".29-.27 0-.52-.11-.7-.28-.79-.74-1.69-1.36-2.67-1.85-.33"
            + "-.16-.56-.5-.56-.9v-3.1C15.15 9.25 13.6 9 12 9z";
    private static final String ICON_VOLUME_UP =
            "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03"
            + "v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86"
            + " 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7"
            + "-8.77s-2.99-7.86-7-8.77z";
    // v171: music card control icons (Material, 24x24 viewport).
    private static final String ICON_PLAY = "M8 5v14l11-7z";
    // v513: navi maneuver icon for the capsule icon disc and
    // the navi card; the Material "flag" replaces it on
    // arrival. v518: AMap-style per-maneuver stroke glyphs
    // (naviGlyphPath / renderNaviGlyph below) — one dedicated
    // shape per maneuver instead of a single rotated arrow.
    private static final String ICON_FLAG =
            "M14.4 6L14 4H5v17h2v-7h5.6l.4 2h7V6z";
    private static final int NAVI_BLUE = Color.rgb(2, 119, 189);
    // v518: maneuver types for the AMap-style glyph set.
    private static final int NAVI_STRAIGHT = 0;
    private static final int NAVI_FRONT_LEFT = 1;
    private static final int NAVI_LEFT = 2;
    private static final int NAVI_REAR_LEFT = 3;
    private static final int NAVI_UTURN = 4;
    private static final int NAVI_FRONT_RIGHT = 5;
    private static final int NAVI_RIGHT = 6;
    private static final int NAVI_REAR_RIGHT = 7;
    private static final int NAVI_ROUNDABOUT = 8;
    private static final String ICON_PAUSE =
            "M6 19h4V5H6v14zm8-14v14h4V5h-4z";
    private static final String ICON_PREV =
            "M6 6h2v12H6zm3.5 6l8.5 6V6z";
    private static final String ICON_NEXT =
            "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z";
    private static final String ICON_FAV =
            "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 "
            + "4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 "
            + "16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 "
            + "11.54L12 21.35z";
    private boolean pinned;
    private NotifData data;
    /** v376: false = this capsule is docked on the RIGHT edge (the
     * call capsule when the "来电放右边" switch is on). Swipe
     * directions mirror: toward the right edge = pin/dock, toward
     * the centre (left) = hang up / dissipate. Set once when the
     * capsule is created, before bind(). Default true (left). */
    private boolean dockLeft = true;

    public void setDockLeft(boolean dockLeft) {
        this.dockLeft = dockLeft;
        applyPillShape();
        applyDockMirror();
    }

    public boolean isLeftDock() {
        return dockLeft;
    }

    /** v376: the pill's corner radii follow the capsule size and
     * the dock side — the edge against the screen is flat, the end
     * toward the centre is rounded (radius = half the height). */
    private void applyPillShape() {
        if (pillBg == null) {
            return;
        }
        float r = dp(cornerRadiusDp());
        if (dockLeft) {
            pillBg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        } else {
            // v377: the right-dock radii were wrong — {r,r,r,r,0,0,0,0}
            // rounds the TOP and flats the BOTTOM (a top-bottom
            // distortion, not a left-right mirror), which is the
            // "左右翻转" the user saw in the screenshot. A proper
            // left-right mirror rounds the LEFT end (toward the
            // centre) and flats the RIGHT end (the screen edge):
            // {r,r, 0,0, 0,0, r,r}.
            pillBg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        }
        // v456: 卡片圆角也跟着走（普通卡外观值，音乐卡 v458 起也走外观）——
        // cardBg 和 pillBg 是两个 drawable，得各设各的。
        updateBorder();
        if (cardBg != null) {
            float cr = dp(cardCornerRadiusDp());
            if (dockLeft) {
                cardBg.setCornerRadii(
                        new float[]{0, 0, cr, cr, cr, cr, 0, 0});
            } else {
                cardBg.setCornerRadii(
                        new float[]{cr, cr, 0, 0, 0, 0, cr, cr});
            }
        }
    }

    /** v382: 横滑一律拦截——不管按在胶囊的哪里（号码、图标、
     * 接听钮）都能左右滑动。之前只有按在胶囊身上才滑得动，
     * 按在可点击的接听钮上滑不动：按钮吞了触摸，胶囊的
     * OnTouchListener 收不到 MOVE。DOWN 时记下起点、暂不拦截
     * （按钮还能点）；MOVE 一到横滑阈值（24dp 且横向为主）
     * 就拦截，触摸交给胶囊的 OnTouchListener，按钮收不到
     * UP、不会误触发接听。轻点（没滑够）照常走按钮的点击。 */
    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                swiping = false;
                return false;
            case android.view.MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX;
                float dy = e.getRawY() - downY;
                if (Math.abs(dx) > dp(24)
                        && Math.abs(dx) > Math.abs(dy) * 2) {
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    /** v379: right-dock mirror — when the capsule lives on the
     * right (the call capsule in the right window), the pill's
     * contents mirror: the answer button moves to the left (toward
     * the centre, reachable) and the app icon to the right (toward
     * the screen edge). Left dock keeps [icon][texts][answer].
     * The gaps follow the swap (icon's 8dp gap moves to its left,
     * the answer's 6dp gap to its right). Idempotent — safe to call
     * from setDockLeft and from bind(). */
    private void applyDockMirror() {
        if (pill == null || iconView == null || answerBtn == null) {
            return;
        }
        boolean mirror = !dockLeft;
        int iconIdx = pill.indexOfChild(iconView);
        int ansIdx = pill.indexOfChild(answerBtn);
        if (iconIdx < 0 || ansIdx < 0) {
            return;
        }
        if (mirror && iconIdx < ansIdx) {
            // [icon][texts]...[answer] -> [answer][texts]...[icon].
            pill.removeView(answerBtn);
            pill.removeView(iconView);
            pill.addView(answerBtn, 0);
            pill.addView(iconView);
            LinearLayout.LayoutParams iconLp =
                    (LinearLayout.LayoutParams)
                            iconView.getLayoutParams();
            iconLp.rightMargin = 0;
            iconLp.leftMargin = dp(8);
            iconView.setLayoutParams(iconLp);
            LinearLayout.LayoutParams ansLp =
                    (LinearLayout.LayoutParams)
                            answerBtn.getLayoutParams();
            ansLp.leftMargin = 0;
            ansLp.rightMargin = dp(6);
            answerBtn.setLayoutParams(ansLp);
        } else if (!mirror && ansIdx < iconIdx) {
            // Restore [icon][texts]...[answer].
            pill.removeView(answerBtn);
            pill.removeView(iconView);
            pill.addView(iconView, 0);
            pill.addView(answerBtn);
            LinearLayout.LayoutParams iconLp =
                    (LinearLayout.LayoutParams)
                            iconView.getLayoutParams();
            iconLp.leftMargin = 0;
            iconLp.rightMargin = dp(8);
            iconView.setLayoutParams(iconLp);
            LinearLayout.LayoutParams ansLp =
                    (LinearLayout.LayoutParams)
                            answerBtn.getLayoutParams();
            ansLp.rightMargin = 0;
            ansLp.leftMargin = dp(6);
            answerBtn.setLayoutParams(ansLp);
        }
    }
    /** Card mode (v143): the SAME view grown into a card. The pill
     * (compact content) hides and cardView shows; the background tint
     * does NOT change (user 2026-11-19 "长大不改变底色") — cardBg is
     * a separate drawable kept at the same pillColor. */
    private boolean cardExpanded;
    /** v239: download progress bar along the capsule bottom
     * edge — a translucent track + a white fill scaled to the
     * percent. Shown only for downloads, only while the pill
     * (not the card) is visible. */
    private android.view.View downloadTrack;
    private android.view.View downloadFill;
    private int downloadBarPct = -1;
    private LinearLayout cardView;
    private EdgeBorderDrawable cardBg;
    private ImageView cardIcon;
    private TextView cardAppName;
    private TextView cardTime;
    private TextView cardTitle;
    private TextView cardBody;
    // v244: enlarged code in the open card (30sp bold), shown
    // only when the notification carries a recognized code.
    private TextView cardCode;
    // v245: locker number under the big code (18sp), shown only
    // when the notification carries a cabinet number.
    private TextView cardCabinet;
    private Runnable onCardClose;
    private FrameLayout cardPin;
    private Runnable onCardPinToggle;
    // v171: music card (dedicated player card, 1.x approach) —
    // shown instead of the generic card when the notification is
    // music. Disc + title/artist, lyric line, controls (fav / prev
    // / play / next), progress + times. Built once, updated in
    // place by updateMusicCard() (1.x updatePlayerContent).
    private LinearLayout musicCardView;
    private ImageView musicDisc;
    private TextView musicTitle;
    private TextView musicArtist;
    private TextView musicLyric;
    private TextView musicLyricNext;
    private FrameLayout musicPin;
    private ImageView musicFavBtn;
    private ImageView musicPrevBtn;
    private ImageView musicPlayBtn;
    private ImageView musicNextBtn;
    private android.widget.SeekBar musicSeek;
    /** v232: which pillLight the seek track was last built for
     * (null = not built yet). */
    private Boolean seekLightApplied;
    private TextView musicCurTime;
    private TextView musicTotalTime;
    private boolean musicPlaying;
    private boolean musicFavorite;
    /** v329: floating music notes overlay (user picked option F
     * "音符粒子") — ♪ ♫ ♩ drift up out of the capsule while music
     * plays. Only for music capsules; GONE otherwise. */
    private NoteParticleView noteParticles;
    /** v329: bouncing volume bars on the right end of the music
     * capsule (user "右边加上跳动的音量条"). Only for music
     * capsules; GONE otherwise. */
    private VolumeBarView volumeBars;
    /** v329: breathing pulse animators for the music title/artist
     * (option C) — gentle alpha 0.8 ↔ 1.0 while playing. */
    private android.animation.ObjectAnimator breatheTitle;
    private android.animation.ObjectAnimator breatheText;
    /** Music control callback (command, seekMs): BubbleWindow sends
     * the ACTION_MUSIC_CONTROL broadcast to the module process. */
    public interface OnMusicControlListener {
        void onMusicControl(String command, long seekMs);
    }
    private OnMusicControlListener onMusicControl;
    private Runnable onTap;
    private Runnable onSwipeRight;
    private Runnable onSwipeLeft;
    private Runnable onDockSwipe;
    /** v153: the open card is an enlarged capsule (user "相当于一个
     * 放大版胶囊") — a manual right-swipe on it drags out the same
     * smooth transparent tail as a capsule, live. BubbleWindow owns
     * the tail (it lives in the dock), so the card reports its drag
     * here and BubbleWindow moves the card + grows the tail. */
    public interface OnCardDragListener {
        /** Drag in progress on the open card (dx in px). */
        void onCardDrag(float dx);
        /** Released or cancelled below the dissipate threshold:
         * spring back to the slot and drop the tail. */
        void onCardDragEnd(float dx);
    }
    private OnCardDragListener onCardDragListener;
    private float downX;
    private float downY;
    // v444: 锚点拖动用——长按开始拖时手指的起点 Y（DOWN 的 rawY）。
    public float getDownY() {
        return downY;
    }
    private boolean swiping;
    /** v393: drag diagnosis — true once the drag-start line is
     * logged for the current touch, so the log is not flooded. */
    private boolean dragStartLogged;
    /** True while the window is widened for the current drag. */
    private boolean dragWindowOpen;
    // v513: navi capsule/card redesign (wei-approved preview).
    // The capsule keeps the standard pill shape; only the
    // content changes: the icon spot becomes a white disc with
    // a Material navigation arrow rotated to the maneuver,
    // line 1 is "动作 · 路名", line 2 "距离 · 还剩", <50m to
    // the turn warms the tint and arrival turns it green. The
    // expanded card is a dedicated naviCardView (music-card
    // precedent): big arrow disc + big distance + route
    // progress + remaining line. All display state below is
    // re-parsed from data.title/data.text on every bind — the
    // Amap broadcasts carry strings only, no new protocol.
    private LinearLayout naviCardView;
    private FrameLayout naviDisc;
    private GradientDrawable naviDiscBg;
    private ImageView naviCardArrow;
    private TextView naviCardDist;
    private TextView naviCardAction;
    private android.widget.FrameLayout naviProgBox;
    private android.view.View naviProgFill;
    private GradientDrawable naviProgFillBg;
    private GradientDrawable naviProgTrackBg;
    private TextView naviCardBottom;
    // v517: the navi card's own pin icon (musicPin precedent) —
    // bindCardCapsule wired setOnCardPinToggle for navi too,
    // but naviCardView never had a button to tap.
    private FrameLayout naviPin;
    private GradientDrawable naviIconBg;
    private boolean naviParsed;
    private boolean naviArrived;
    private String naviAction;
    private String naviRoadPart;
    private int naviDistMeters = -1;
    private String naviDistSeg;
    private int naviRemainMeters = -1;
    private String naviRemainSeg;
    private String naviRemainMinSeg;
    private String naviEtaSeg;
    private int naviManeuver = NAVI_STRAIGHT;

    public CapsuleView(Context context) {
        super(context);
        setClipChildren(false);

        pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        // Left padding 2dp (user 2026-11-19 "贴近屏幕这边"): the
        // notification icon sits close to the screen edge.
        // v356: 平板上把上下内边距从 6dp 减到 2dp（用户 2026-11-22
        // 照片：第二行字被胶囊底边切掉，只剩上半截）。胶囊高 44dp，
        // 上下各 6dp 内边距只剩 32dp 给两行字（13sp+12sp 要 ~35dp），
        // 第二行就被切了；减到 2dp 后剩 40dp，两行放得下。胶囊本身
        // 高度不动，行距和卡片高度都不受影响。手机保持 6dp 不变。
        int vPad = isTablet(context) ? dp(2) : dp(6);
        pill.setPadding(dp(2), vPad, dp(14), vPad);
        pillBg = new EdgeBorderDrawable();
        pillBg.setShape(GradientDrawable.RECTANGLE);
        // The end against the screen edge (left) is NOT rounded — it
        // sits flat against the edge (user 2026-11-18 "贴边的一头不要
        // 圆角"). Only the end toward the centre (right) is rounded.
        // v456: 圆角走 cornerRadiusDp()（按类型分流，钳住到高度一半）。
        float r = dp(cornerRadiusDp());
        pillBg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        pillBg.setColor(Color.argb(191, 30, 32, 38));
        // No border on the capsule (user 2026-11-19 "胶囊描边去掉").
        pill.setBackground(pillBg);
        pillWidthPx = dp(baseWidthDp());
        addView(pill, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // v239: download progress bar (track + fill), bottom
        // edge, 3dp tall. The fill is full-width with pivotX 0
        // so setScaleX(pct/100) grows it from the left edge.
        downloadTrack = new android.view.View(context);
        downloadTrack.setBackgroundColor(
                Color.argb(90, 255, 255, 255));
        downloadTrack.setVisibility(GONE);
        FrameLayout.LayoutParams trackLp =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, dp(3),
                        Gravity.BOTTOM);
        trackLp.rightMargin = dp(22);
        addView(downloadTrack, trackLp);
        downloadFill = new android.view.View(context);
        downloadFill.setBackgroundColor(Color.WHITE);
        downloadFill.setVisibility(GONE);
        downloadFill.setPivotX(0f);
        FrameLayout.LayoutParams fillLp =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, dp(3),
                        Gravity.BOTTOM);
        fillLp.rightMargin = dp(22);
        addView(downloadFill, fillLp);

        iconView = new ImageView(context);
        LinearLayout.LayoutParams iconLp =
                new LinearLayout.LayoutParams(dp(30), dp(30));
        iconLp.rightMargin = dp(8);
        pill.addView(iconView, iconLp);

        texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pill.addView(texts, textsLp);

        // v329: bouncing volume bars on the right end of the music
        // capsule (user "右边加上跳动的音量条") — 4 bars, 20dp wide,
        // 28dp tall, centred vertically. GONE unless music.
        volumeBars = new VolumeBarView(context);
        volumeBars.setVisibility(GONE);
        LinearLayout.LayoutParams volLp =
                new LinearLayout.LayoutParams(dp(20), dp(28));
        volLp.leftMargin = dp(6);
        pill.addView(volumeBars, volLp);

        titleView = new TextView(context);
        titleView.setSingleLine(true);
        // v268: MARQUEE from the start (the lyric line does the
        // same) — unselected it shows "..." like END; selected it
        // scrolls. applyScrollFlag only toggles selection.
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        titleView.setTextColor(Color.WHITE);
        // v355: 宽必须 MATCH_PARENT（用户 2026-11-22 平板“字被截断了”）。
        // WRAP_CONTENT 时长标题量出来比 texts 宽，直接被父容器裁掉，
        // 跑马灯根本不启动；定宽后超长才滚动。
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        texts.addView(titleView);

        textView = new TextView(context);
        textView.setSingleLine(true);
        // v268: MARQUEE from the start, same as titleView.
        textView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        textView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // v249: was a muted gray (170,178,189) — unreadable on a
        // saturated dark pill (user 2026-11-21 "灰色字体看不清").
        // Near-white now, same as the card body.
        textView.setTextColor(Color.argb(235, 255, 255, 255));
        texts.addView(textView);
        // Pinned music lyric line (user 2026-11-19 "钉住显示加大滚动
        // 歌词"): one line, enlarged, scrolling. Marquee only moves
        // when the view is selected and the text overflows; a short
        // lyric stays still. Hidden by default.
        // v204: the lyric rolls (user 2026-11-20 "每句播完后面开始
        // 下一句接着滑出来，不要跳一下") — a one-line-high clipped
        // frame holding a two-line strip; a new lyric slides the
        // strip up 320ms, then the top line takes the new text.
        lyricsRoll = new android.widget.FrameLayout(context);
        lyricsRoll.setClipChildren(true);
        lyricsRoll.setVisibility(GONE);
        lyricsStrip = new android.widget.LinearLayout(context);
        lyricsStrip.setOrientation(
                android.widget.LinearLayout.VERTICAL);
        lyricsView = new TextView(context);
        lyricsView.setSingleLine(true);
        lyricsView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        lyricsView.setMarqueeRepeatLimit(-1);
        lyricsView.setSelected(true);
        lyricsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        lyricsView.setTypeface(lyricsView.getTypeface(),
                android.graphics.Typeface.BOLD);
        lyricsView.setTextColor(Color.WHITE);
        lyricsViewNext = new TextView(context);
        lyricsViewNext.setSingleLine(true);
        lyricsViewNext.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        lyricsViewNext.setMarqueeRepeatLimit(-1);
        lyricsViewNext.setSelected(false);
        lyricsViewNext.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        lyricsViewNext.setTypeface(lyricsViewNext.getTypeface(),
                android.graphics.Typeface.BOLD);
        lyricsViewNext.setTextColor(Color.WHITE);
        lyricsStrip.addView(lyricsView);
        lyricsStrip.addView(lyricsViewNext);
        lyricsRoll.addView(lyricsStrip);
        texts.addView(lyricsRoll);

        // Pin badge, INSIDE the pill at the right end (toward the
        // screen centre) (user 2026-11-19 "钉住图标改到通知里面，靠屏幕
        // 中心那边"). It used to stand alone outside the pill; now it
        // is the last child of the pill, after the texts.
        // The icon is the v1 Flyme balloon (user 2026-11-18 "钉住图标
        // 还是用版本1的那个"): the blue balloon PNG from 1.x (fixed108,
        // traced from the user's balloon images), on a white stroked
        // disc so it reads on any background.
        pinView = new FrameLayout(context);
        GradientDrawable pinBg = new GradientDrawable();
        pinBg.setShape(GradientDrawable.OVAL);
        pinBg.setColor(Color.WHITE);
        pinBg.setStroke(dp(2), Color.WHITE);
        pinView.setBackground(pinBg);
        ImageView pinIcon = new ImageView(context);
        pinIcon.setImageBitmap(pinBalloonBitmap());
        pinIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pinView.addView(pinIcon, new FrameLayout.LayoutParams(
                dp(20), dp(20), Gravity.CENTER));
        pinView.setVisibility(GONE);
        LinearLayout.LayoutParams pinLp =
                new LinearLayout.LayoutParams(dp(24), dp(24));
        pinLp.leftMargin = dp(6);
        pill.addView(pinView, pinLp);

        // Call mode (v103, button redesigned v108): the answer
        // button at the right end, in the pin icon's place (user
        // "这个图标改成绿色接听"), is a pill holding a vector icon
        // and, once answered, the timer text. Hidden unless this is
        // a call. The old separate timerView is gone — the timer
        // lives inside the button.
        answerBtn = new LinearLayout(context);
        answerBtn.setOrientation(LinearLayout.HORIZONTAL);
        answerBtn.setGravity(Gravity.CENTER_VERTICAL);
        answerBtnBg = new GradientDrawable();
        answerBtnBg.setShape(GradientDrawable.OVAL);
        answerBtnBg.setColor(Color.argb(255, 34, 197, 94));
        answerBtn.setBackground(answerBtnBg);
        answerBtn.setVisibility(GONE);
        answerBtn.setPadding(dp(6), 0, dp(6), 0);
        answerIcon = new ImageView(context);
        answerIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        answerBtn.addView(answerIcon,
                new LinearLayout.LayoutParams(dp(16), dp(16)));
        answerTimerText = new TextView(context);
        answerTimerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        answerTimerText.setTypeface(answerTimerText.getTypeface(),
                android.graphics.Typeface.BOLD);
        answerTimerText.setTextColor(Color.WHITE);
        // v416: never wrap the timer to a second line (the v415
        // 96dp capsule wrapped "0:27" as "0:2" / "7").
        answerTimerText.setSingleLine(true);
        answerTimerText.setVisibility(GONE);
        LinearLayout.LayoutParams ttLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        ttLp.leftMargin = dp(3);
        answerBtn.addView(answerTimerText, ttLp);
        LinearLayout.LayoutParams ansLp =
                new LinearLayout.LayoutParams(dp(28), dp(28));
        ansLp.leftMargin = dp(6);
        pill.addView(answerBtn, ansLp);
        setAnswerIcon(0);
        answerBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (callAnswered) {
                    // In-call: tapping the timer toggles speaker
                    // (user 2026-11-19 "这两个计时器点击是互相转换").
                    if (onToggleSpeaker != null) {
                        onToggleSpeaker.run();
                    }
                } else if (onAnswer != null) {
                    onAnswer.run();
                }
            }
        });

        // Card mode (v143): the SAME view grown into a card. A
        // vertical layout sharing the capsule's tint (cardBg, same
        // colour and corner radii as pillBg) — hidden until the
        // capsule grows. Header (icon + app + time + close), bold
        // title, full body, and the two open buttons.
        cardView = new LinearLayout(context);
        cardView.setOrientation(LinearLayout.VERTICAL);
        cardView.setPadding(dp(12), dp(12), dp(12), dp(12));
        cardBg = new EdgeBorderDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        // v456: 卡片圆角走 cardCornerRadiusDp()（普通卡外观值，音乐卡 v458 起也走外观）。
        float cr = dp(cardCornerRadiusDp());
        cardBg.setCornerRadii(new float[]{0, 0, cr, cr, cr, cr, 0, 0});
        cardBg.setColor(Color.argb(191, 30, 32, 38));
        cardView.setBackground(cardBg);
        cardView.setVisibility(GONE);
        addView(cardView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout cardHead = new LinearLayout(context);
        cardHead.setOrientation(LinearLayout.HORIZONTAL);
        cardHead.setGravity(Gravity.CENTER_VERTICAL);
        cardView.addView(cardHead, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        cardIcon = new ImageView(context);
        cardHead.addView(cardIcon, new LinearLayout.LayoutParams(
                dp(30), dp(30)));
        cardAppName = new TextView(context);
        cardAppName.setSingleLine(true);
        cardAppName.setEllipsize(TextUtils.TruncateAt.END);
        cardAppName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        cardAppName.setTextColor(Color.argb(217, 255, 255, 255));
        LinearLayout.LayoutParams appLp =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        appLp.leftMargin = dp(8);
        cardHead.addView(cardAppName, appLp);
        cardTime = new TextView(context);
        cardTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        cardTime.setTextColor(Color.argb(179, 255, 255, 255));
        cardHead.addView(cardTime);
        // v162: the X close button is gone; its spot is the pin
        // icon (user 2026-11-19 "去掉这个X按钮留给钉住图标").
        // Same white disc + balloon as the pill's pin badge; tap
        // toggles pin/unpin. Alpha shows the state (1.0 pinned,
        // 0.45 not pinned).
        cardPin = new FrameLayout(context);
        GradientDrawable cardPinBg = new GradientDrawable();
        cardPinBg.setShape(GradientDrawable.OVAL);
        cardPinBg.setColor(Color.WHITE);
        cardPinBg.setStroke(dp(2), Color.WHITE);
        cardPin.setBackground(cardPinBg);
        ImageView cardPinIcon = new ImageView(context);
        cardPinIcon.setImageBitmap(pinBalloonBitmap());
        cardPinIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cardPin.addView(cardPinIcon, new FrameLayout.LayoutParams(
                dp(20), dp(20), Gravity.CENTER));
        // v164: hidden by default (user 2026-11-19 "平时不显示") —
        // the pin icon only appears once the card is pinned.
        cardPin.setVisibility(GONE);
        // v517: pin placeholder shrunk (user 2026-11-25) — the
        // frame goes 24dp -> 22dp and the left margin 8dp -> 4dp;
        // the balloon bitmap itself stays 20dp.
        LinearLayout.LayoutParams cardPinLp =
                new LinearLayout.LayoutParams(dp(22), dp(22));
        cardPinLp.leftMargin = dp(4);
        cardHead.addView(cardPin, cardPinLp);
        cardPin.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (onCardPinToggle != null) {
                    onCardPinToggle.run();
                }
            }
        });

        cardTitle = new TextView(context);
        cardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cardTitle.setTypeface(cardTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        cardTitle.setTextColor(Color.WHITE);
        cardTitle.setMaxLines(2);
        cardTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(8);
        cardView.addView(cardTitle, titleLp);

        cardBody = new TextView(context);
        cardBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        cardBody.setTextColor(Color.argb(235, 255, 255, 255));
        cardBody.setMaxLines(6);
        cardBody.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bodyLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(4);
        cardView.addView(cardBody, bodyLp);
        // v244: enlarged code — big bold digits, shown only for
        // a recognized code. Hidden otherwise.
        cardCode = new TextView(context);
        cardCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        cardCode.setTypeface(cardCode.getTypeface(),
                android.graphics.Typeface.BOLD);
        cardCode.setTextColor(Color.WHITE);
        cardCode.setSingleLine(true);
        cardCode.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams codeLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = dp(10);
        cardView.addView(cardCode, codeLp);
        cardCode.setVisibility(GONE);
        // v245: locker number under the big code.
        cardCabinet = new TextView(context);
        cardCabinet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        cardCabinet.setTextColor(Color.argb(235, 255, 255, 255));
        cardCabinet.setSingleLine(true);
        LinearLayout.LayoutParams cabLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        cabLp.topMargin = dp(6);
        cardView.addView(cardCabinet, cabLp);
        cardCabinet.setVisibility(GONE);


        // v513: navi card — dedicated dashboard card for navi
        // capsules (music-card precedent), shown instead of the
        // generic card when the notification is navi. Big arrow
        // disc + big distance, "动作 · 路名", route progress
        // bar, bottom remaining line. Shares cardBg (same tint
        // as the pill, like the music card).
        naviCardView = new LinearLayout(context);
        naviCardView.setOrientation(LinearLayout.VERTICAL);
        naviCardView.setPadding(dp(14), dp(14), dp(14), dp(14));
        naviCardView.setBackground(cardBg);
        naviCardView.setVisibility(GONE);
        // v516: same LayoutParams as the generic cardView
        // (MATCH_PARENT x MATCH_PARENT) — the navi card fills
        // the capsule exactly like a generic card; its size
        // comes from cardWidthDp() / cardHeightForWidth, i.e.
        // the global card width + card scale (the v515 fixed
        // 340dp natural width is reverted, user 2026-11-25).
        addView(naviCardView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        LinearLayout naviTop = new LinearLayout(context);
        naviTop.setOrientation(LinearLayout.HORIZONTAL);
        naviTop.setGravity(Gravity.CENTER_VERTICAL);
        naviCardView.addView(naviTop, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        naviDisc = new FrameLayout(context);
        naviDiscBg = new GradientDrawable();
        naviDiscBg.setShape(GradientDrawable.OVAL);
        naviDiscBg.setColor(Color.WHITE);
        naviDisc.setBackground(naviDiscBg);
        naviCardArrow = new ImageView(context);
        naviCardArrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        naviDisc.addView(naviCardArrow, new FrameLayout.LayoutParams(
                dp(34), dp(34), Gravity.CENTER));
        naviTop.addView(naviDisc, new LinearLayout.LayoutParams(
                dp(56), dp(56)));
        LinearLayout naviTexts = new LinearLayout(context);
        naviTexts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams naviTextsLp =
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        naviTextsLp.leftMargin = dp(14);
        naviTop.addView(naviTexts, naviTextsLp);
        // v517: the navi card gets its own pin icon (musicPin
        // precedent, v174) — same white disc + balloon, only
        // visible when pinned (setCardPinned), tap toggles pin
        // through the same onCardPinToggle callback. New v517
        // placeholder size: 22dp frame, 4dp left margin, the
        // bitmap itself stays 20dp.
        naviPin = new FrameLayout(context);
        GradientDrawable naviPinBg = new GradientDrawable();
        naviPinBg.setShape(GradientDrawable.OVAL);
        naviPinBg.setColor(Color.WHITE);
        naviPinBg.setStroke(dp(2), Color.WHITE);
        naviPin.setBackground(naviPinBg);
        ImageView naviPinIcon = new ImageView(context);
        naviPinIcon.setImageBitmap(pinBalloonBitmap());
        naviPinIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        naviPin.addView(naviPinIcon, new FrameLayout.LayoutParams(
                dp(20), dp(20), Gravity.CENTER));
        naviPin.setVisibility(GONE);
        LinearLayout.LayoutParams naviPinLp =
                new LinearLayout.LayoutParams(dp(22), dp(22));
        naviPinLp.leftMargin = dp(4);
        naviTop.addView(naviPin, naviPinLp);
        naviPin.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (onCardPinToggle != null) {
                    onCardPinToggle.run();
                }
            }
        });
        naviCardDist = new TextView(context);
        naviCardDist.setSingleLine(true);
        naviCardDist.setEllipsize(TextUtils.TruncateAt.END);
        naviCardDist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        naviCardDist.setTypeface(naviCardDist.getTypeface(),
                android.graphics.Typeface.BOLD);
        naviCardDist.setTextColor(Color.WHITE);
        naviTexts.addView(naviCardDist, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        naviCardAction = new TextView(context);
        naviCardAction.setSingleLine(true);
        naviCardAction.setEllipsize(TextUtils.TruncateAt.END);
        naviCardAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        naviCardAction.setTextColor(Color.argb(235, 255, 255, 255));
        LinearLayout.LayoutParams naviActLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        naviActLp.topMargin = dp(2);
        naviTexts.addView(naviCardAction, naviActLp);
        // Route progress: translucent track + fill scaled from
        // the left (same pivot trick as the download bar).
        naviProgBox = new android.widget.FrameLayout(context);
        LinearLayout.LayoutParams naviProgLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        naviProgLp.topMargin = dp(14);
        naviCardView.addView(naviProgBox, naviProgLp);
        android.view.View naviProgTrack = new android.view.View(context);
        naviProgTrackBg = new GradientDrawable();
        naviProgTrackBg.setCornerRadius(dp(3));
        naviProgTrackBg.setColor(Color.argb(64, 255, 255, 255));
        naviProgTrack.setBackground(naviProgTrackBg);
        naviProgBox.addView(naviProgTrack,
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        naviProgFill = new android.view.View(context);
        naviProgFillBg = new GradientDrawable();
        naviProgFillBg.setCornerRadius(dp(3));
        naviProgFillBg.setColor(Color.WHITE);
        naviProgFill.setBackground(naviProgFillBg);
        naviProgFill.setPivotX(0f);
        naviProgBox.addView(naviProgFill,
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        naviCardBottom = new TextView(context);
        naviCardBottom.setSingleLine(true);
        naviCardBottom.setEllipsize(TextUtils.TruncateAt.END);
        naviCardBottom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        naviCardBottom.setTextColor(Color.argb(235, 255, 255, 255));
        LinearLayout.LayoutParams naviBotLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        naviBotLp.topMargin = dp(8);
        naviCardView.addView(naviCardBottom, naviBotLp);

        // v171: music card — dedicated player card for music
        // notifications (1.x buildPlayerView). Same background tint
        // as the generic card; shown instead of cardView when the
        // notification is music.
        musicCardView = new LinearLayout(context);
        musicCardView.setOrientation(LinearLayout.VERTICAL);
        musicCardView.setPadding(dp(12), dp(12), dp(12), dp(12));
        musicCardView.setBackground(cardBg);
        musicCardView.setVisibility(GONE);
        // v462: 卡片比例调整——播放卡内容按自然尺寸布局（自然卡片
        // 宽固定 + 内容高），再整体按比例缩放着画。不能用
        // MATCH_PARENT：胶囊本身的 LayoutParams 是缩放后的尺寸，
        // 内容在里面按缩放尺寸布局会被截掉（控制 / 进度条先被
        // 截）。固定自然宽 + WRAP_CONTENT 高，内容按自然尺寸
        // 排好，再 setScale 缩着画，正好填满缩放后的胶囊。
        // 缩放（setScaleX/Y + pivot 0,0）设在 updateMusicCard 里。
        addView(musicCardView, new LayoutParams(
                dp(Appearance.musicCardWidthDp),
                LayoutParams.WRAP_CONTENT));
        // Row 1: album disc + title/artist.
        LinearLayout musicTop = new LinearLayout(context);
        musicTop.setOrientation(LinearLayout.HORIZONTAL);
        musicTop.setGravity(Gravity.CENTER_VERTICAL);
        musicCardView.addView(musicTop,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        musicDisc = new ImageView(context);
        musicDisc.setScaleType(ImageView.ScaleType.CENTER_CROP);
        musicTop.addView(musicDisc, new LinearLayout.LayoutParams(
                dp(48), dp(48)));
        LinearLayout musicMeta = new LinearLayout(context);
        musicMeta.setOrientation(LinearLayout.VERTICAL);
        musicMeta.setPadding(dp(10), 0, 0, 0);
        LinearLayout.LayoutParams metaLp =
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        musicTop.addView(musicMeta, metaLp);
        musicTitle = new TextView(context);
        musicTitle.setSingleLine(true);
        musicTitle.setEllipsize(TextUtils.TruncateAt.END);
        musicTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        musicTitle.setTypeface(musicTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        musicTitle.setTextColor(Color.WHITE);
        musicMeta.addView(musicTitle,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        musicArtist = new TextView(context);
        musicArtist.setSingleLine(true);
        musicArtist.setEllipsize(TextUtils.TruncateAt.END);
        musicArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        musicArtist.setTextColor(Color.argb(217, 255, 255, 255));
        LinearLayout.LayoutParams artistLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        artistLp.topMargin = dp(2);
        musicMeta.addView(musicArtist, artistLp);
        // v174: the music card gets its own pin icon (user 2026-11-20
        // "that icon does not show when pinned") - cardPin lives in
        // the generic card's header, so a pinned music card showed no
        // pin at all. Same white disc + balloon, only visible when
        // pinned, tap toggles pin like the generic card's.
        musicPin = new FrameLayout(context);
        GradientDrawable musicPinBg = new GradientDrawable();
        musicPinBg.setShape(GradientDrawable.OVAL);
        musicPinBg.setColor(Color.WHITE);
        musicPinBg.setStroke(dp(2), Color.WHITE);
        musicPin.setBackground(musicPinBg);
        ImageView musicPinIcon = new ImageView(context);
        musicPinIcon.setImageBitmap(pinBalloonBitmap());
        musicPinIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        musicPin.addView(musicPinIcon, new FrameLayout.LayoutParams(
                dp(20), dp(20), Gravity.CENTER));
        musicPin.setVisibility(GONE);
        // v517: pin placeholder shrunk like cardPin — 22dp frame,
        // 4dp left margin; the bitmap itself stays 20dp.
        LinearLayout.LayoutParams musicPinLp =
                new LinearLayout.LayoutParams(dp(22), dp(22));
        musicPinLp.leftMargin = dp(4);
        musicTop.addView(musicPin, musicPinLp);
        musicPin.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (onCardPinToggle != null) {
                    onCardPinToggle.run();
                }
            }
        });
        // Row 2: lyric, redesigned (v174) - the hero, two centred
        // lines (current bold + previous dim) instead of one small
        // marquee line. The module only sends the current line, so
        // the dim line keeps the previous one (updateMusicLyric).
        musicLyric = new TextView(context);
        musicLyric.setSingleLine(true);
        musicLyric.setEllipsize(TextUtils.TruncateAt.END);
        musicLyric.setGravity(Gravity.CENTER);
        musicLyric.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        musicLyric.setTypeface(musicLyric.getTypeface(),
                android.graphics.Typeface.BOLD);
        musicLyric.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lyricLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        lyricLp.topMargin = dp(12);
        musicCardView.addView(musicLyric, lyricLp);
        musicLyricNext = new TextView(context);
        musicLyricNext.setSingleLine(true);
        musicLyricNext.setEllipsize(TextUtils.TruncateAt.END);
        musicLyricNext.setGravity(Gravity.CENTER);
        musicLyricNext.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        musicLyricNext.setTextColor(Color.argb(200, 255, 255, 255));
        LinearLayout.LayoutParams lyricNextLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        lyricNextLp.topMargin = dp(4);
        musicCardView.addView(musicLyricNext, lyricNextLp);
        // Row 3: controls (fav / prev / play-pause / next).
        LinearLayout musicCtrls = new LinearLayout(context);
        musicCtrls.setOrientation(LinearLayout.HORIZONTAL);
        musicCtrls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctrlsLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        ctrlsLp.topMargin = dp(10);
        musicCardView.addView(musicCtrls, ctrlsLp);
        musicFavBtn = new ImageView(context);
        musicFavBtn.setImageBitmap(renderIcon(ICON_FAV,
                Color.WHITE, dp(22)));
        musicFavBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                musicFavorite = !musicFavorite;
                updateMusicFav();
                if (onMusicControl != null) {
                    onMusicControl.onMusicControl("favorite", -1);
                }
            }
        });
        musicCtrls.addView(musicFavBtn,
                new LinearLayout.LayoutParams(dp(36), dp(36)));
        musicPrevBtn = new ImageView(context);
        musicPrevBtn.setImageBitmap(renderIcon(ICON_PREV,
                Color.WHITE, dp(24)));
        musicPrevBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (onMusicControl != null) {
                    onMusicControl.onMusicControl("prev", -1);
                }
            }
        });
        LinearLayout.LayoutParams prevLp =
                new LinearLayout.LayoutParams(dp(40), dp(40));
        prevLp.leftMargin = dp(16);
        musicCtrls.addView(musicPrevBtn, prevLp);
        musicPlayBtn = new ImageView(context);
        musicPlayBtn.setImageBitmap(renderIcon(ICON_PLAY,
                Color.WHITE, dp(30)));
        musicPlayBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                // v174: flip the icon immediately (optimistic) - the
                // old code only changed it when the module's 1s tick
                // came back, so the play/pause switch lagged up to a
                // second. The tick still corrects the state later.
                musicPlaying = !musicPlaying;
                updateMusicPlayIcon();
                // A short press pulse so the tap reads instantly.
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                v.animate().scaleX(1f).scaleY(1f)
                                        .setDuration(120).start();
                            }
                        }).start();
                if (onMusicControl != null) {
                    onMusicControl.onMusicControl("toggle", -1);
                }
            }
        });
        LinearLayout.LayoutParams playLp =
                new LinearLayout.LayoutParams(dp(48), dp(48));
        playLp.leftMargin = dp(16);
        musicCtrls.addView(musicPlayBtn, playLp);
        musicNextBtn = new ImageView(context);
        musicNextBtn.setImageBitmap(renderIcon(ICON_NEXT,
                Color.WHITE, dp(24)));
        musicNextBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (onMusicControl != null) {
                    onMusicControl.onMusicControl("next", -1);
                }
            }
        });
        LinearLayout.LayoutParams nextLp =
                new LinearLayout.LayoutParams(dp(40), dp(40));
        nextLp.leftMargin = dp(16);
        musicCtrls.addView(musicNextBtn, nextLp);
        // Row 4: progress (current + SeekBar + total).
        LinearLayout musicProg = new LinearLayout(context);
        musicProg.setOrientation(LinearLayout.HORIZONTAL);
        musicProg.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams progLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        progLp.topMargin = dp(8);
        musicCardView.addView(musicProg, progLp);
        musicCurTime = new TextView(context);
        musicCurTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        musicCurTime.setTextColor(Color.argb(200, 255, 255, 255));
        musicCurTime.setText("0:00");
        musicProg.addView(musicCurTime);
        musicSeek = new android.widget.SeekBar(context);
        // v349: 平板上进度条改细（24dp->18dp 高，轨道 5dp->3dp）。
        // v348 加粗是改反了，用户说“太宽了”——要的是细，不是粗。
        int seekH = isTablet(context) ? 18 : 24;
        LinearLayout.LayoutParams seekLp =
                new LinearLayout.LayoutParams(0, dp(seekH), 1f);
        seekLp.leftMargin = dp(6);
        seekLp.rightMargin = dp(6);
        musicProg.addView(musicSeek, seekLp);
        musicSeek.setOnSeekBarChangeListener(
                new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            android.widget.SeekBar sb, int p,
                            boolean fromUser) {
                    }
                    @Override
                    public void onStartTrackingTouch(
                            android.widget.SeekBar sb) {
                    }
                    @Override
                    public void onStopTrackingTouch(
                            android.widget.SeekBar sb) {
                        if (onMusicControl != null) {
                            onMusicControl.onMusicControl("seek",
                                    sb.getProgress());
                        }
                    }
                });
        musicTotalTime = new TextView(context);
        musicTotalTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        musicTotalTime.setTextColor(Color.argb(200, 255, 255, 255));
        musicTotalTime.setText("0:00");
        musicProg.addView(musicTotalTime);

        updateCardColors();

        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (onTap != null) {
                    onTap.run();
                }
            }
        });
        // Horizontal swipe: right (toward the edge) = dock into a
        // notification icon; left (toward the centre) = burst into
        // particles and vanish. A tap (small movement) still clicks.
        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View v,
                    android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        swiping = false;
                        dragStartLogged = false;
                        // v409: a fresh touch cancels a running
                        // spring-back tail so the new drag never
                        // fights the tail retraction.
                        if (callSpringBackAnim != null) {
                            callSpringBackAnim.cancel();
                            callSpringBackAnim = null;
                        }
                        break;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        // v444: 锚点拖动中——手指竖着走，改锚点，
                        // 不走左右滑那套。
                        BubbleWindow abw = BubbleWindow.getInstance();
                        if (abw != null && abw.isAnchorDragActive()) {
                            abw.updateAnchorDrag(e.getRawY());
                            return true;
                        }
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        // Horizontal-only slide (user 2026-11-19 "胶囊
                        // 全屏拖动取消"): the free full-screen drag is
                        // removed; the capsule only slides left/right.
                        if (Math.abs(dx) > dp(24)
                                && Math.abs(dx) > Math.abs(dy) * 2) {
                            swiping = true;
                            // v393: drag diagnosis — log the first
                            // threshold-hit MOVE for a right-dock
                            // call capsule, so a "jump" can be
                            // traced to real dx values.
                            if (!dockLeft && data != null && data.isCall
                                    && !dragStartLogged) {
                                dragStartLogged = true;
                                FileLog.i("CallDrag start downX="
                                        + downX + " rawX=" + e.getRawX()
                                        + " dx=" + dx + " dy=" + dy
                                        + " transX=" + getTranslationX()
                                        + " pos=" + screenPos());
                            }
                            // v386: a right-dock capsule lives in
                            // its own small window; widen that
                            // window for the drag so the finger
                            // cannot drift out of it mid-swipe
                            // (that broke the touch stream and left
                            // the capsule stuck halfway, never
                            // springing back). Only the window size
                            // changes — never its position or the
                            // capsule's margins — so nothing jumps.
                            if (!dockLeft) {
                                BubbleWindow.getInstance()
                                        .beginCallDragWindow();
                            }
                            // v153: the open card is an enlarged
                            // capsule — its drag goes to BubbleWindow
                            // (live tail + card move), not to the
                            // pill's growLongCapsule (the pill is
                            // hidden in card mode, so the user saw no
                            // tail at all while dragging).
                            // v174: only RIGHT drags go there. A LEFT
                            // drag on the card is pin (user 2026-11-20
                            // "the card has no left-swipe pin") — the
                            // old code sent it to onCardDrag too, so
                            // the card slid off-screen and the pin
                            // never fired.
                            // v367: 左滑不再把卡片往左顶 —— 顶到
                            // -80dp 就被窗口左边缘截住，看起来像
                            // “划到这里就划不动了”（用户 2026-11-22
                            // 05:25）。卡片留在原位不动，钉住/收进
                            // 扇子只看松手时手指划的距离（ACTION_UP
                            // 里 -60dp 钉住、-140dp 收进扇子）。
                            if (cardExpanded && dx > 0
                                    && onCardDragListener != null) {
                                if (!dragWindowOpen) {
                                    dragWindowOpen = true;
                                    BubbleWindow.getInstance()
                                            .beginCapsuleDragWindow();
                                }
                                onCardDragListener.onCardDrag(dx);
                                break;
                            }
                            if (cardExpanded && dx < 0) {
                                // v493（用户 2026-11-23 “改成可以滑到底”
                                // “以前就是可以滑到底”）：v367 以为卡片
                                // 左移会被窗口左边缘截住（-80dp），才
                                // 让它原地不动；v491 钳 -48dp、v492
                                // 带阻尼，都还是半道卡住。用户说以前
                                // 1:1 跟手就能一路滑到底——那就改回
                                // 去：卡片跟手 1:1，不钳不阻尼，手指
                                // 划多远卡片走多远，划过 -140dp 收进
                                // 扇子。v367 的截住说法不成立（卡片
                                // 本来就是往扇子方向收，收进去就没了）。
                                setTranslationX(dx);
                                break;
                            }
                            // v360: 放大胶囊没有 cardDragListener——
                            // 右拖时整张卡片跟手向右平移（pill 藏着，
                            // 长胶囊动画看不见），否则拖了半天卡片
                            // 一动不动，松手才突然消散。先开全屏拖拽
                            // 窗口，不然卡片右移会被窗口截掉。
                            if (cardExpanded && dx > 0) {
                                if (!dragWindowOpen) {
                                    dragWindowOpen = true;
                                    BubbleWindow.getInstance()
                                            .beginCapsuleDragWindow();
                                }
                                setTranslationX(dx);
                                break;
                            }
                            // v376: the swipe directions mirror with
                            // the dock side. Left dock: right (toward
                            // the centre) = hang up / dissipate,
                            // left (toward the edge) = pin/dock.
                            // Right dock: the reverse — left (toward
                            // the centre) = hang up / dissipate,
                            // right (toward the edge) = pin/dock.
                            boolean towardCenter =
                                    dockLeft ? dx > 0 : dx < 0;
                            float adx = Math.abs(dx);
                            if (towardCenter) {
                                // Toward the centre: hang up (call,
                                // the capsule turns red as it slides)
                                // or the dissipate tail (regular).
                                // The right-edge capsule's tail fits
                                // its own window, so no drag window.
                                if (dockLeft && !dragWindowOpen) {
                                    dragWindowOpen = true;
                                    BubbleWindow.getInstance()
                                            .beginCapsuleDragWindow();
                                }
                                if (data != null && data.isCall) {
                                    setCallRed(true);
                                }
                                // v455: 尾巴恢复（见 growLongCapsule
                                // 的 v455 注）——缩好的计时器右滑
                                // 也长尾巴，跟没缩的一样；v454 的
                                // 跟手平移撤掉，拖动反馈就是尾巴。
                                growLongCapsule(adx);
                                break;
                            } else {
                                resetLongCapsule();
                                setTranslationX(dx);
                            }
                        }
                        break;
                    }
                    case android.view.MotionEvent.ACTION_UP: {
                        // v444: 锚点拖动松手——落盘收尾，不走左右滑。
                        BubbleWindow ubw = BubbleWindow.getInstance();
                        if (ubw != null && ubw.isAnchorDragActive()) {
                            ubw.endAnchorDrag();
                            return true;
                        }
                        // v386: the drag is over — shrink the right
                        // call window back to its idle size.
                        if (!dockLeft) {
                            BubbleWindow.getInstance()
                                    .endCallDragWindow();
                        }
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        // v393: drag diagnosis — log the release so
                        // a stuck / non-springing capsule can be
                        // traced to the branch it took.
                        if (!dockLeft && data != null && data.isCall) {
                            FileLog.i("CallDrag up dx=" + dx
                                    + " adx=" + Math.abs(dx)
                                    + " transX=" + getTranslationX()
                                    + " pos=" + screenPos()
                                    + " dissipateDp=" + dissipateDp()
                                    + " pinMinDp=" + pinMinDp()
                                    + " pinMaxDp=" + pinMaxDp());
                        }
                        if (Math.abs(dx) > Math.abs(dy) * 2) {
                            // v376: mirror with the dock side (see
                            // ACTION_MOVE). towardCenter = the hang-up
                            // / dissipate direction; towardEdge = the
                            // pin / dock direction.
                            boolean towardCenter =
                                    dockLeft ? dx > 0 : dx < 0;
                            boolean towardEdge = !towardCenter;
                            float adx = Math.abs(dx);
                            // Left dock: left (toward the edge) =
                            // dock, right (toward the centre) =
                            // dissipate (mirrored, user 2026-11-18
                            // "你改成靠左边的").
                            if (data != null && data.isCall
                                    && towardCenter
                                    && adx > dp(dissipateDp())
                                    && onHangup != null) {
                                // Call swipe toward the centre =
                                // hang up.
                                endDragWindowIfOpen();
                                onHangup.run();
                                return true;
                            }
                            // Swipe toward the edge = pin/unpin,
                            // including on a call capsule (user
                            // 2026-11-19 "左滑继续改回钉住和解除钉
                            // 住"). The v108/v109 left-swipe speaker
                            // answer is gone; speaker is via the
                            // timer tap mid-call.
                            // v279: split by distance — a LIGHT swipe
                            // toward the edge (60–140dp) pins; a
                            // HEAVY one (past 140dp) docks into
                            // the fan.
                            // v494: 1.0 模式下轻滑不钉住（用户 “钉住是
                            // 按钮非左滑”）——钉住只走卡片上的图钉
                            // 按钮；轻滑只弹回，重滑（≥140dp）照常
                            // 收进扇子。
                            if (towardEdge && adx > dp(pinMinDp())
                                    && adx < dp(pinMaxDp())
                                    && onSwipeRight != null) {
                                endDragWindowIfOpen();
                                onSwipeRight.run();
                                // v387: pinning must spring the capsule
                                // back to its slot. The drag left it
                                // shifted right by dx (setTranslationX
                                // in ACTION_MOVE) and onSwipeRight only
                                // flips the pin state — without this
                                // the capsule stays stuck shifted
                                // right ("右滑松手不弹出来"). The old
                                // code only did this when cardExpanded
                                // (v178, for the open card); a call
                                // capsule is never a card, so it never
                                // sprang back. Left slide was normal
                                // because a short left drag falls
                                // through to the "Not far enough"
                                // spring-back below.
                                // v409: a call capsule springs back
                                // WITH a tail (springBackCallCapsule);
                                // regular capsules keep the bare slide.
                                if (data != null && data.isCall) {
                                    springBackCallCapsule();
                                } else {
                                    animate().translationX(0f)
                                            .setDuration(200).start();
                                }
                                return true;
                            } else if (towardEdge
                                    && adx >= dp(pinMaxDp())
                                    && onDockSwipe != null) {
                                endDragWindowIfOpen();
                                onDockSwipe.run();
                                return true;
                            } else if (towardCenter
                                    && adx > dp(dissipateDp())
                                    && onSwipeLeft != null) {
                                // Dissipate: drag it out a LONG way
                                // (2/3 胶囊宽) and then it dissipates
                                // (user 2026-11-18 "拉出去一长截后
                                // 消散"). A short drag springs back.
                                // The tail bursts together with the
                                // capsule (user 2026-11-19 "拖尾和胶囊
                                // 一起消散"): burst FIRST while the
                                // window is still wide, shrink it only
                                // after the particles finish (~450ms),
                                // or the tail particles are clipped.
                                dragWindowOpen = false;
                                onSwipeLeft.run();
                                BubbleWindow.getInstance()
                                        .endDragWindowDelayed();
                                return true;
                            }
                        }
                        // Not far enough: spring back.
                        if (cardExpanded
                                && onCardDragListener != null) {
                            // The card springs back to its slot and
                            // drops the tail; BubbleWindow owns both
                            // (and shrinks the window after the
                            // spring-back, so the tail is not
                            // clipped mid-flight).
                            dragWindowOpen = false;
                            onCardDragListener.onCardDragEnd(dx);
                            break;
                        }
                        endDragWindowIfOpen();
                        if (data != null && data.isCall) {
                            setCallRed(false);
                            if (!callAnswered) {
                                setAnswerIcon(0);
                            }
                            // v409: spring back WITH a tail. Do NOT
                            // resetLongCapsule() here — the animator
                            // retracts the pill (tail) in sync with
                            // the slide home, so the post-release
                            // bounce has a tail instead of a bare
                            // jump. Left-drag: the pill is already
                            // grown, it retracts; right-drag: a tail
                            // grows from the displacement and retracts.
                            springBackCallCapsule();
                        } else {
                            resetLongCapsule();
                            animate().translationX(0f)
                                    .setDuration(200).start();
                        }
                        break;
                    }
                    case android.view.MotionEvent.ACTION_CANCEL:
                        // v444: 锚点拖动被打断——同样落盘收尾。
                        BubbleWindow cbw = BubbleWindow.getInstance();
                        if (cbw != null && cbw.isAnchorDragActive()) {
                            cbw.endAnchorDrag();
                            return true;
                        }
                        swiping = false;
                        // v393: drag diagnosis — a cancel mid-drag
                        // snaps the capsule back; log it so a
                        // broken touch stream shows up.
                        if (!dockLeft && data != null && data.isCall) {
                            FileLog.i("CallDrag cancel transX="
                                    + getTranslationX());
                        }
                        // v386: drag aborted — shrink the right
                        // call window back to its idle size.
                        if (!dockLeft) {
                            BubbleWindow.getInstance()
                                    .endCallDragWindow();
                        }
                        if (cardExpanded
                                && onCardDragListener != null) {
                            dragWindowOpen = false;
                            onCardDragListener.onCardDragEnd(0f);
                            break;
                        }
                        endDragWindowIfOpen();
                        resetLongCapsule();
                        setTranslationX(0f);
                        break;
                    default:
                        break;
                }
                return swiping;
            }
        });
        // v444: 长按胶囊拖整组（锚点拖动）——按住不动半秒就进入
        // 拖动，上下拖改的是锚点 dockTopPct（跟滑块同一个值），
        // v454: 用户 2026-11-23 06:17 “把长按胶囊移动位置去掉”——
        // 锚点拖动（v444）撤掉，长按胶囊不再拖整组。
        // （startAnchorDrag / updateAnchorDrag / endAnchorDrag
        // 留在 BubbleWindow 里备用，不再有人调。）
        setOnLongClickListener(null);
        // v329: floating music notes overlay (option F) — added last
        // so it draws above the pill content; transparent and not
        // clickable, so it never intercepts the capsule's swipe/tap.
        noteParticles = new NoteParticleView(context);
        noteParticles.setVisibility(GONE);
        addView(noteParticles, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // v329: stop the music motion so nothing keeps ticking
        // after the capsule is gone (the particle / volume views
        // also stop themselves, but the breathing animators live
        // here).
        setMusicBreathing(false);
        if (noteParticles != null) {
            noteParticles.stop();
        }
        if (volumeBars != null) {
            volumeBars.stop();
        }
        // Never leave the window full-screen if the view is removed
        // mid-drag — a stuck full-screen overlay swallows the swipe-up
        // system gesture.
        endDragWindowIfOpen();
    }

    /**
     * Play the manual right-swipe dissipate automatically (user
     * 2026-11-19 "展开后逐条自动右滑消散"): the pill stretches out to
     * the right like a drag (growLongCapsule), then onDone runs (the
     * caller bursts it). The capsule itself does not translate — the
     * pill grows, exactly like the manual swipe. 240ms, same feel.
     */
    public void playAutoSwipeRight(final Runnable onDone) {
        playAutoSwipeRight(onDone, false);
    }

    /** v364: withTail — the auto right-slide drags the transparent
     * gradient tail too (user “自动消散也没有加拖尾”). Only the
     * dissipate paths pass true (auto-dissipate, tap-to-open); the
     * card-open ripple passes false — several cards swipe at once
     * there and one shared tail can't sit on all their rows. */
    public void playAutoSwipeRight(final Runnable onDone,
            final boolean withTail) {
        if (pill == null) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        // Narrow window (tail-width only), NOT full-screen, so
        // touches pass through to the screen during the auto-dissipate
        // (user 2026-11-19 "能不能做穿透").
        BubbleWindow.getInstance().beginAutoSwipeWindow();
        // v360: 放大胶囊（卡片内容）——pill 藏起来了，长胶囊动画
        // 看不见；改成整张卡片向右平移 200dp 再爆掉，和关卡片的
        // 右滑一致。窗口加宽是异步的，post 到下一帧再启动（v353）。
        if (cardExpanded) {
            final float tx = dp(200);
            post(new Runnable() {
                @Override
                public void run() {
                    if (withTail) {
                        BubbleWindow.getInstance()
                                .beginAutoSwipeTail(CapsuleView.this);
                    }
                    animate().translationX(tx).setDuration(240)
                            .setInterpolator(
                                    new android.view.animation.DecelerateInterpolator())
                            .setUpdateListener(
                                    new android.animation.ValueAnimator.AnimatorUpdateListener() {
                                        @Override
                                        public void onAnimationUpdate(
                                                android.animation.ValueAnimator a) {
                                            if (withTail) {
                                                BubbleWindow.getInstance()
                                                        .updateAutoSwipeTail(
                                                                CapsuleView.this);
                                            }
                                        }
                                    })
                            .withLayer()
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    if (withTail) {
                                        BubbleWindow.getInstance()
                                                .endAutoSwipeTail(
                                                        CapsuleView.this);
                                    }
                                    BubbleWindow.getInstance()
                                            .endDragWindowDelayed();
                                    if (onDone != null) {
                                        onDone.run();
                                    }
                                }
                            })
                            .start();
                }
            });
            return;
        }
        final float target = dp(200);
        final android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, target);
        anim.setDuration(240);
        anim.addUpdateListener(
                new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(
                            android.animation.ValueAnimator a) {
                        float dx = (Float) a.getAnimatedValue();
                        growLongCapsule(dx);
                    }
                });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animator) {
                BubbleWindow.getInstance().endDragWindowDelayed();
                if (onDone != null) {
                    onDone.run();
                }
            }
        });
        // v353: 窗口加宽是异步生效的（updateViewLayout 下一帧才起作用），
        // 动画立刻启动的话前几帧窗口还是窄的，胶囊一长就被截掉
        // （用户 2026-11-22 “自动消除右滑的时候”截断）。post 到下一帧
        // 再启动，让窗口先加宽。
        pill.post(new Runnable() {
            @Override
            public void run() {
                anim.start();
            }
        });
    }

    /** Shrink the window back to content size after a drag ends. */
    private void endDragWindowIfOpen() {
        if (dragWindowOpen) {
            dragWindowOpen = false;
            BubbleWindow.getInstance().endDragWindow();
        }
    }

    // Grow the pill into the long capsule (one element, one
    // background — no seam): the pill widens to (180 + dx), its
    // background becomes a single bitmap (smooth gradient + the
    // half-pill rounded corners), the content is pushed to the right
    /** v399: capsule screen position for drag diagnosis —
     * getLocationOnScreen gives the absolute screen x/y of the
     * capsule's left-top corner. If the capsule is off-screen
     * the numbers show it directly (x < 0, or x + width >
     * screen width). The v396–v398 logs had transX (the offset)
     * and the pill width, but not where the capsule actually
     * sits on screen — that is what tells a "vanished" capsule
     * (removed / invisible) apart from a "moved" one (still
     * there, just off-screen). */
    private String screenPos() {
        int[] loc = new int[2];
        try {
            getLocationOnScreen(loc);
        } catch (Throwable ignored) {
        }
        return loc[0] + "," + loc[1];
    }

    // end, and the pin moves along with the capsule body.
    /** v376: grow is the (positive) distance swiped toward the
     * centre. Left dock: the pill extends right, content to the
     * right end, tail fading at the left edge. Right dock: the
     * pill extends left, content to the left end, tail fading at
     * the right edge. */
    private void growLongCapsule(float grow) {
        if (pill == null) {
            return;
        }
        // v455: 用户 2026-11-23 06:20 “左右接听的拖尾都没了”——
        // v450 把缩好的计时器胶囊的尾巴全拦了，右滑挂断时连
        // 拖尾反馈都没了，两边都看着像坏了。尾巴是拖动时的
        // 正常反馈，拦掉它不对。v450 的拦法撤掉，尾巴拖动时
        // 照常长；拖完 `resetLongCapsule`（v444/v445 已修好，
        // 缩了的按回缩后宽、背景换回 pillBg）负责收尾，闲时
        // 不会留尾巴。闲时那截深绿是 v450 之前尾巴没收干净
        // 的问题，不是拖动时该不该长尾巴的问题。
        // v398: growing (toward the centre) and translating
        // (toward the edge) are mutually exclusive. A left-drag
        // after a right-drag left a stale translationX (the
        // 17:40 log: transX=149.80 at release — the capsule was
        // shifted right by an earlier right-drag segment and the
        // pill then grew from that shifted anchor, so the
        // capsule sat partly off-screen and looked wrong). The
        // pill must grow from the capsule's idle position, so
        // clear any translation before growing.
        if (getTranslationX() != 0f) {
            setTranslationX(0f);
        }
        int w = dp(baseWidthDp()) + (int) grow;
        pillWidthPx = w;
        // One reusable background — no new bitmap per frame (that was
        // the swipe jank). The drawable reads its bounds and only
        // rebuilds its cached gradient shader when the width/fade
        // changes, so the swipe is just a cheap width + translation.
        if (longBg == null) {
            longBg = new LongCapsuleDrawable(pillColor,
                    dp(cornerRadiusDp()), dp(baseWidthDp()));
        }
        longBg.setMirror(!dockLeft);
        longBg.setTintColor(pillColor);
        // v472: 描边延长到拖尾（用户 2026-11-23 “描边延长到拖尾”）：
        // 拖动时胶囊背景换成了 longBg（拖尾），描边也得跟过去，
        // 不然尾巴上没边、只有胶囊头上有边，看着断了。颜色跟
        // 胶囊本身的描边走同一处（borderColorForDraw），宽度
        // 1dp（v471 改细后的值）。
        longBg.setBorder(borderColorForDraw(), dp(1));
        if (pill.getBackground() != longBg) {
            pill.setBackground(longBg);
        }
        // Only touch layout when the width actually changed.
        if (w != lastGrowW) {
            lastGrowW = w;
            LayoutParams lp = (LayoutParams) pill.getLayoutParams();
            if (lp.width != w) {
                lp.width = w;
                // Right-edge capsule grows leftward (toward the
                // centre): anchor the pill to the right end so the
                // extra width extends left, not off the screen.
                lp.gravity = dockLeft ? Gravity.START : Gravity.END;
                pill.setLayoutParams(lp);
            }
        }
        pill.setGravity(Gravity.CENTER_VERTICAL
                | (dockLeft ? Gravity.END : Gravity.START));
        // Lock the texts to its measured width so it doesn't stretch
        // — the whole content (icon + text) must move to the right
        // end together with the capsule front. Once per drag only.
        if (!longGrown) {
            longGrown = true;
            // v396: drag diagnosis — first grow of this drag:
            // the pill's target width and whether it is visible.
            FileLog.i("CallDrag grow w=" + w + " pillVis="
                    + pill.getVisibility() + " pillW=" + pill.getWidth()
                    + " bgLong=" + (pill.getBackground() == longBg)
                    + " pos=" + screenPos());
            if (texts != null) {
                LinearLayout.LayoutParams tlp =
                        (LinearLayout.LayoutParams)
                                texts.getLayoutParams();
                tlp.weight = 0f;
                tlp.width = texts.getWidth();
                texts.setLayoutParams(tlp);
            }
        }
        if (pinView != null) {
            pinView.setTranslationX(dockLeft ? grow : -grow);
        }
    }

    private void resetLongCapsule() {
        if (pill == null) {
            return;
        }
        // v444: 已缩成计时器的来电胶囊，pill 宽不许打回
        // MATCH_PARENT——接听后 ACTION_UP 走 springBackCallCapsule，
        // 它收尾调 resetLongCapsule 会把 collapseToTimer 刚缩到
        // 120dp 的 pill 又撑回 240dp（用户 “外边框没缩到计时器
        // 位置”）。缩了就是缩了，别的路子别动它。
        // v445（用户 “你没发现计时器和胶囊右边的问题吗 / 那你上
        // 一版是虚空修复吗”——认了，v444 只修了宽、没修尾巴）：
        // v444 的 early return 只按了宽，没把背景换回 pillBg，
        // growLongCapsule 留下的 longBg（拖尾 drawable）还贴在
        // pill 上，右边就拖着一条半透明绿尾巴。缩了的胶囊背景
        // 也得换回去，尾巴状态一起清掉。
        if (collapsedToTimer) {
            pillWidthPx = dp(collapsedEndWidthDp());
            lastGrowW = -1;
            longGrown = false;
            LayoutParams lp = (LayoutParams) pill.getLayoutParams();
            lp.width = pillWidthPx;
            lp.gravity = Gravity.CENTER_VERTICAL
                    | (dockLeft ? Gravity.START : Gravity.END);
            pill.setLayoutParams(lp);
            if (pillBg != null) {
                pill.setBackground(pillBg);
            }
            return;
        }
        pillWidthPx = dp(baseWidthDp());
        lastGrowW = -1;
        longGrown = false;
        LayoutParams lp = (LayoutParams) pill.getLayoutParams();
        lp.width = LayoutParams.MATCH_PARENT;
        pill.setLayoutParams(lp);
        pill.setBackground(pillBg);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        if (texts != null) {
            LinearLayout.LayoutParams tlp =
                    (LinearLayout.LayoutParams) texts.getLayoutParams();
            tlp.weight = 1f;
            tlp.width = 0;
            texts.setLayoutParams(tlp);
        }
        if (pinView != null) {
            pinView.setTranslationX(0f);
        }
    }

    /** v409: spring a call capsule back to its slot WITH a tail.
     * The bare translationX spring-back (v387) slides the pill as
     * a rigid body, so the post-release bounce reads as a bare
     * jump with no tail (user 2026-11-22 "它异常弹动是没有拖尾的
     * / 你加个拖尾就完事了"). This animates translationX AND the
     * pill length together over the same 200ms: the pill stretches
     * into a tail proportional to the remaining displacement and
     * retracts as the capsule settles. Left-drag release: the pill
     * is already grown (tail exists) — animate it retracting
     * instead of an instant reset. Right-drag release: the pill
     * is normal — grow a tail proportional to the displacement and
     * retract it as the capsule slides home. The 250ms delayed
     * window shrink (v405) covers the 200ms, so the tail is never
     * clipped mid-flight. Call capsules only — regular capsules
     * keep the old spring-back. */
    private void springBackCallCapsule() {
        if (pill == null) {
            animate().translationX(0f).setDuration(200).start();
            return;
        }
        if (callSpringBackAnim != null) {
            callSpringBackAnim.cancel();
            callSpringBackAnim = null;
        }
        final float startTx = getTranslationX();
        final float startGrow = Math.max(0f,
                pillWidthPx - dp(baseWidthDp()));
        // Tail length at release: what is already grown plus the
        // remaining slide distance (so a right-drag, whose pill is
        // still normal, grows a tail as long as its displacement).
        final float tailBase = startGrow + Math.abs(startTx);
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(200);
        callSpringBackAnim = anim;
        anim.addUpdateListener(
                new android.animation.ValueAnimator
                        .AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(
                    android.animation.ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                float tx = startTx * (1f - f);
                float grow = tailBase * (1f - f);
                // Grow the pill (tail) first — growLongCapsule
                // clears translationX (v398), so re-apply it
                // right after, in the same frame.
                if (grow > 0.5f) {
                    growLongCapsule(grow);
                } else {
                    resetLongCapsule();
                }
                setTranslationX(tx);
            }
        });
        anim.addListener(
                new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(
                    android.animation.Animator animation) {
                callSpringBackAnim = null;
                resetLongCapsule();
                setTranslationX(0f);
            }

            @Override
            public void onAnimationCancel(
                    android.animation.Animator animation) {
                callSpringBackAnim = null;
            }
        });
        anim.start();
    }

    /** v463: 码的显示格式——标签 + 冒号 + 码（用户 2026-11-23
     * “显示成 xx码:xxxxx”）。识别出验证码 / 取件码后，不直接
     * 显示裸码，显示成 “验证码:123456” / “取件码:070797”。
     * 标签就是识别时命中的关键词（codeLabel：验证码 / 取件码 /
     * 取餐码 / 取货码…），没标签就只显示码。胶囊和卡片大码都
     * 走这个格式；柜号不拼进来（胶囊自己拼，卡片有单独一行）。
     * 自动复制的剪贴板还是纯码，不变。 */
    public static String formatCodeLabel(NotifData d) {
        if (d == null || d.code == null) {
            return "";
        }
        if (d.codeLabel != null && !d.codeLabel.isEmpty()) {
            return d.codeLabel + ":" + d.code;
        }
        return d.code;
    }

    public void bind(NotifData notifData) {
        // v447: 同一通来电重绑时别把已接听状态清掉——来电通知会
        // 重发，bind() 每次都把 callAnswered/collapsedToTimer
        // 打回 false、pill 宽打回 MATCH_PARENT（240dp），
        // collapseToTimer 刚缩好的 120dp 就被 bind 顶回去了，
        // 这才是“外边框没缩到计时器位置”的真根因（v444 找错
        // 了，只盯着 springBack）。同一 key = 同一通电话，
        // 保住已接听/已缩状态；换 key = 新来电，才恢复全尺寸。
        String prevKey = (this.data != null) ? this.data.key : null;
        boolean sameCall = prevKey != null
                && notifData != null && notifData.key != null
                && prevKey.equals(notifData.key);
        this.data = notifData;
        android.graphics.Bitmap tintBmp = null;
        if (notifData.icon != null) {
            this.iconView.setImageBitmap(notifData.icon);
            tintBmp = notifData.icon;
        } else if (notifData.largeIcon != null) {
            this.iconView.setImageBitmap(notifData.largeIcon);
            tintBmp = notifData.largeIcon;
        } else {
            // v24: no Bitmap comes across processes any more — load
            // the app icon here in SystemUI from the package, the
            // way v1 does it (shared Drawable, never copied, no IPC).
            android.graphics.drawable.Drawable d = loadAppIcon(notifData.pkg);
            if (d != null) {
                this.iconView.setImageDrawable(d);
                    tintBmp = drawableToBitmap(d);
            } else {
                this.iconView.setImageDrawable(null);
            }
        }
        this.titleView.setText(notifData.title != null ? notifData.title : "");
        // v244: a recognized code is the point of the capsule —
        // show "验证码 123456" / "取餐码 4821" as line 2
        // instead of the full sentence.
        if (notifData.code != null) {
            // v246/v247: a code is the whole point of the capsule
            // — show ONLY the code, enlarged. Verification: just
            // the digits. Pickup: digits + cabinet when there is
            // one ("070797 · 2-01柜" / "173351 · 第17箱").
            // No label, no sentence (user 2026-11-21 "识别到验证
            // 码后胶囊也只显示验证码" / "加大显示" / "取件码
            // 这些也显示啊").
            // v463（用户 2026-11-23 "显示成 xx码:xxxxx"）：
            // 不直接显示裸码，显示成标签 + 冒号 + 码——
            // "验证码:123456" / "取件码:070797"，取件码有柜号
            // 的还带上（"取件码:070797 · 2-01柜"）。
            String line2 = formatCodeLabel(notifData);
            if (notifData.isPickupCode && notifData.cabinet != null) {
                line2 += " · " + notifData.cabinet;
            }
            this.textView.setText(line2);
            // v395: a reused view may have had the line hidden
            // (empty text) — a code always shows.
            this.textView.setVisibility(VISIBLE);
            this.textView.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP, 20f);
            this.textView.setTypeface(
                    this.textView.getTypeface(),
                    android.graphics.Typeface.BOLD);
        } else {
            this.textView.setText(notifData.text != null ? notifData.text : "");
            // v395: an empty second line takes no space — a call
            // with no name has text="" (only the number as the
            // title), so hide the line and the title centres
            // alone in the pill instead of sitting on top with a
            // blank row under it. Non-call capsules with an empty
            // body behave the same (title only).
            this.textView.setVisibility(
                    (notifData.text == null
                            || notifData.text.isEmpty())
                            ? GONE : VISIBLE);
            // v246: reset size/weight — a CapsuleView reused
            // after a code capsule must not keep 20sp bold.
            this.textView.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP, 12f);
            this.textView.setTypeface(
                    this.textView.getTypeface(),
                    android.graphics.Typeface.NORMAL);
        }
        this.lyricsView.setText(
                notifData.lyrics != null ? notifData.lyrics : "");
        // v329: floating notes overlay only exists on music
        // capsules; it starts/stops with the playing state in
        // updateMusicProgress (1s tick). Paused or non-music =
        // hidden and stopped.
        if (noteParticles != null) {
            if (notifData.isMusic) {
                noteParticles.setVisibility(VISIBLE);
            } else {
                noteParticles.setVisibility(GONE);
                noteParticles.stop();
            }
        }
        // v329: volume bars only on music capsules, and never on a
        // pinned (locked) one (v330, user "胶囊锁定后就不要显示这个
        // 音量条了").
        if (volumeBars != null) {
            if (notifData.isMusic && !pinned) {
                volumeBars.setVisibility(VISIBLE);
                if (musicPlaying) {
                    volumeBars.start();
                }
            } else {
                volumeBars.setVisibility(GONE);
                volumeBars.stop();
            }
        }
        // v329: title entrance (option A) — slide + fade in when a
        // music capsule pops with a new song.
        if (notifData.isMusic) {
            playMusicTitleEntrance();
        }
        // v239: download bar from the first-pop percent.
        downloadBarPct = notifData.downloadPct;
        syncDownloadBar();
        if (notifData.isCall) {
            // Call capsule (v103): green pill, answer button at the
            // right end, NO pin icon (auto-pinned but the badge is
            // replaced by the answer button).
            // v376: bigger (preview A 240×60): 40dp icon, 16sp name,
            // 40dp answer button, 16sp timer, 30dp corner radius.
            setCallTint(true);
            answerBtn.setVisibility(VISIBLE);
            pinView.setVisibility(GONE);
            callRed = false;
            // v447: 同一通来电重绑（sameCall）且已经接听了，
            // 别把接听/缩计时器状态清掉——bind 每重发一次就
            // 把 pill 打回 240dp，缩了也白缩。新来电才恢复
            // 全尺寸。
            if (sameCall && callAnswered) {
                // 已接听的同一通电话：保住缩计时器状态，
                // 只把图标/正文藏起来（和 collapseToTimer
                // 做的一样），pill 宽不动。
                if (iconView != null) {
                    iconView.setVisibility(GONE);
                }
                if (texts != null) {
                    texts.setVisibility(GONE);
                }
                setAnswerIcon(callSpeaker ? 2 : 0);
            } else {
                callAnswered = false;
                callSpeaker = false;
                // v415: a (re)bound call starts full-size, not collapsed
                // to the timer. The pill width is restored in case this
                // view was collapsed by a previous call.
                collapsedToTimer = false;
                if (iconView != null) {
                    iconView.setVisibility(VISIBLE);
                }
                if (texts != null) {
                    texts.setVisibility(VISIBLE);
                }
                if (pill != null) {
                    try {
                        LayoutParams lp =
                                (LayoutParams) pill.getLayoutParams();
                        if (lp.width != LayoutParams.MATCH_PARENT) {
                            lp.width = LayoutParams.MATCH_PARENT;
                            lp.gravity = Gravity.CENTER_VERTICAL;
                            pill.setLayoutParams(lp);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                setAnswerIcon(0);
            }
            answerTimerText.setVisibility(GONE);
            answerTimerText.setText("");
            answerTimerText.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP, 16);
            answerBtnBg.setShape(GradientDrawable.OVAL);
            answerBtnBg.setCornerRadius(0);
            LinearLayout.LayoutParams ansLp =
                    (LinearLayout.LayoutParams)
                            answerBtn.getLayoutParams();
            ansLp.width = dp(40);
            ansLp.height = dp(40);
            answerBtn.setLayoutParams(ansLp);
            LinearLayout.LayoutParams aIconLp =
                    (LinearLayout.LayoutParams)
                            answerIcon.getLayoutParams();
            aIconLp.width = dp(22);
            aIconLp.height = dp(22);
            answerIcon.setLayoutParams(aIconLp);
            LinearLayout.LayoutParams iconLp =
                    (LinearLayout.LayoutParams)
                            iconView.getLayoutParams();
            iconLp.width = dp(40);
            iconLp.height = dp(40);
            iconView.setLayoutParams(iconLp);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            // v381: the number is centred (user 2026-11-22 "号码显示居中").
            titleView.setGravity(Gravity.CENTER);
            // v388: the NUMBER is the second line (textView) when
            // there is a contact name — make it bigger (16sp) and
            // centred too (user 2026-11-22 "号码和字体都要居中" /
            // "就号码加大居中"). When there is no name the number
            // is the title itself, already 16sp centred above.
            textView.setGravity(Gravity.CENTER);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            applyPillShape();
            // v376: the 60dp pill has room for 8dp vertical padding;
            // the horizontal padding mirrors with the dock side
            // (icon near the edge on the left dock, near the centre
            // on the right — the answer button stays at the edge).
            // v381: right dock right padding 2dp -> 14dp — the app
            // icon was clipped by the screen edge (user 2026-11-22
            // "应用图标再出来一点").
            pill.setPadding(dockLeft ? dp(2) : dp(14), dp(8),
                    dockLeft ? dp(14) : dp(14), dp(8));
            // v448（用户 “你把缩进代码再往里面写一下不行吗”——
            // 对，缩的代码就该写在 bind 里头，别指望外面哪
            // 个方法再调回来）：新造出来的胶囊要是已经接听
            // 了（offhook），直接就是计时器形态——pill 宽按
            // 到缩后宽、图标/正文藏起来、旗子立起来，不用等
            // setCallTimer 那一秒，也不用走 250ms 动画。
            // 每次重绑都跑一遍，缩了就是缩了。
            BubbleWindow bw = BubbleWindow.getInstance();
            if (bw != null && bw.isCallAnswered()) {
                callAnswered = true;
                collapsedToTimer = true;
                if (iconView != null) {
                    iconView.setVisibility(GONE);
                }
                if (texts != null) {
                    texts.setVisibility(GONE);
                }
                if (pinView != null) {
                    pinView.setVisibility(GONE);
                }
                if (pill != null) {
                    if (pillBg != null) {
                        pill.setBackground(pillBg);
                    }
                    lastGrowW = -1;
                    longGrown = false;
                    try {
                        LayoutParams lp =
                                (LayoutParams) pill.getLayoutParams();
                        lp.width = dp(collapsedEndWidthDp());
                        lp.gravity = Gravity.CENTER_VERTICAL
                                | (dockLeft ? Gravity.START
                                        : Gravity.END);
                        pill.setLayoutParams(lp);
                    } catch (Throwable ignored) {
                    }
                }
                // v449 diag: bind 里直接缩，记一下跑没跑、缩到多宽。
                try {
                    LayoutParams lpD = (LayoutParams) pill.getLayoutParams();
                    FileLog.i("CallTimerDiag bind DIRECT-COLLAPSE"
                            + " id=" + System.identityHashCode(this)
                            + " pillW=" + lpD.width
                            + " dockLeft=" + dockLeft);
                } catch (Throwable ignored) {
                }
            }
        } else {
            answerBtn.setVisibility(GONE);
            setCallTint(false);
            // v381: non-call titles stay left-aligned (only the
            // call number is centred).
            titleView.setGravity(Gravity.LEFT);
            // v388: reset the second line — a CapsuleView reused
            // after a call capsule must not keep the 16sp centred
            // number styling.
            textView.setGravity(Gravity.LEFT);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            applyPillShape();
        }
        applyIconTint(tintBmp);
        // v513: navi capsule presentation (arrow disc, two-line
        // reformat, warm/green tints, open-card refresh); any
        // other type resets the icon view's navi leftovers, since
        // CapsuleViews are reused across notifications.
        if (notifData.isNavi) {
            applyNaviBind();
        } else {
            resetNaviIcon();
        }
        updateMusicDisplay();
        applyScrollFlag();
        // v379: right-dock call capsule mirrors its pill contents.
        applyDockMirror();
    }

    /** Call pill tint (v103): green while ringing, red while the user
     * swipes right to hang up. */
    private void setCallTint(boolean green) {
        if (pillBg == null) {
            return;
        }
        if (green) {
            pillBg.setColor(Color.argb(217, 34, 139, 34));
            if (cardBg != null) {
                cardBg.setColor(Color.argb(217, 34, 139, 34));
            }
        }
    }

    public void setCallRed(boolean red) {
        if (pillBg == null || data == null || !data.isCall) {
            return;
        }
        callRed = red;
        // Keep pillColor in sync: the long-capsule tail reads
        // pillColor, so the tail turns red with the pill (v106).
        pillColor = red
                ? Color.argb(217, 180, 40, 40)
                : Color.argb(217, 34, 139, 34);
        pillBg.setColor(pillColor);
        pillBg.invalidateSelf();
        if (cardBg != null) {
            cardBg.setColor(pillColor);
        }
        // The answer button follows the pill (v107, icon v108):
        // green handset while ringing, red hang-up handset while
        // swiping right to hang up. Only while ringing — once the
        // call is answered the button is the timer, not the answer.
        if (answerBtnBg != null) {
            answerBtnBg.setColor(red
                    ? Color.argb(255, 180, 40, 40)
                    : Color.argb(255, 34, 197, 94));
        }
        if (!callAnswered) {
            setAnswerIcon(red ? 1 : 0);
        }
    }

    /** Answered: the button becomes icon + timer (v108). Normal
     * answer = green handset + timer; speaker answer = green
     * speaker + timer. The button turns into a pill (rounded rect)
     * and stays tappable — tapping it toggles speaker. */
    public void setCallTimer(String timer, boolean speaker) {
        if (data == null || !data.isCall || answerBtn == null) {
            return;
        }
        callAnswered = true;
        callSpeaker = speaker;
        setAnswerIcon(speaker ? 2 : 0);
        answerTimerText.setText(timer != null ? timer : "");
        answerTimerText.setVisibility(VISIBLE);
        answerBtnBg.setShape(GradientDrawable.RECTANGLE);
        // v376: the answered button is 40dp tall (was 28dp), so the
        // pill radius is 20dp.
        answerBtnBg.setCornerRadius(dp(20));
        LinearLayout.LayoutParams ansLp =
                (LinearLayout.LayoutParams)
                        answerBtn.getLayoutParams();
        ansLp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
        answerBtn.setLayoutParams(ansLp);
        answerBtn.setVisibility(VISIBLE);
        // v415 (preview): shrink to just the timer once answered
        // (user 2026-11-22 "要它缩成只剩计时器"). Runs once per
        // call; a re-post that recreates the capsule re-collapses
        // it the next time setCallTimer runs.
        // v444: 已缩过的，每秒 tick 顺手把 pill 宽再按到缩后宽
        // （自愈——万一有别的路子把它撑回去，1 秒内自己收回来）。
        // v445: 自愈也包括背景——万一 longBg 尾巴又贴回来了，
        // 一起换回 pillBg。
        if (collapsedToTimer) {
            if (pill != null) {
                LayoutParams lp = (LayoutParams) pill.getLayoutParams();
                int endW = dp(collapsedEndWidthDp());
                if (lp.width != endW) {
                    // v449 diag: 自愈在干活——pill 又被撑回去了。
                    FileLog.i("CallTimerDiag setCallTimer HEAL"
                            + " id=" + System.identityHashCode(this)
                            + " timer=" + timer
                            + " pillW=" + lp.width + "->" + endW
                            + " dockLeft=" + dockLeft);
                    lp.width = endW;
                    lp.gravity = Gravity.CENTER_VERTICAL
                            | (dockLeft ? Gravity.START : Gravity.END);
                    pill.setLayoutParams(lp);
                }
                if (pillBg != null
                        && pill.getBackground() != pillBg) {
                    pill.setBackground(pillBg);
                    lastGrowW = -1;
                    longGrown = false;
                }
            }
            return;
        }
        collapseToTimer();
    }

    /** v415 (preview): after answering, shrink the capsule to just
     * the timer. Hides the app icon and the number/name texts, then
     * animates the pill width down to fit only the answer button
     * (timer). The pill anchors to the screen edge (START for the
     * left dock, END for the right window) so the timer stays
     *贴边. The CapsuleView itself stays 240dp wide (the window /
     * dock layout is untouched); only the visible pill shrinks. */
    /** v444: 缩成计时器后的 pill 宽。左 120dp（v416 定：图标 +
     * 5 个字符一行不折行）；右 108dp——用户 “右边接听后再缩进
     * 一点”，比左再窄 12dp，仍在 96dp 折行线之上。
     * v452: 用户 2026-11-23 06:11 “接听电话后计时器和背景胶囊
     * 右边离得太远了”——亮绿按钮才 68dp，pill 120dp 右边空出
     * 37dp 深绿。左收到 96dp（v415 原值，计时器已单行不会
     * 折行），右保持 108dp（用户已认）。 */
    private int collapsedEndWidthDp() {
        return dockLeft ? 96 : 108;
    }

    public void collapseToTimer() {
        if (pill == null || answerBtn == null) {
            return;
        }
        if (collapsedToTimer) {
            return;
        }
        // v449 diag: 第一次缩，记一下从多宽缩到多宽。
        try {
            LayoutParams lp0 = (LayoutParams) pill.getLayoutParams();
            FileLog.i("CallTimerDiag collapseToTimer"
                    + " id=" + System.identityHashCode(this)
                    + " pillW=" + lp0.width
                    + "->" + dp(collapsedEndWidthDp())
                    + " dockLeft=" + dockLeft);
        } catch (Throwable ignored) {
        }
        collapsedToTimer = true;
        // v445: 缩的那一刻就把拖尾清掉——接听前要是有个划动
        // 留下了 longBg（拖尾 drawable），不换回 pillBg 的话
        // 尾巴会跟着缩后的 pill 一起留下来（截图里右边那条
        // 绿尾巴就是它）。
        if (pillBg != null) {
            pill.setBackground(pillBg);
        }
        lastGrowW = -1;
        longGrown = false;
        if (iconView != null) {
            iconView.setVisibility(GONE);
        }
        if (texts != null) {
            texts.setVisibility(GONE);
        }
        if (pinView != null) {
            pinView.setVisibility(GONE);
        }
        final int startW = pill.getWidth() > 0 ? pill.getWidth() : dp(240);
        final int endW = dp(collapsedEndWidthDp());
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofInt(startW, endW);
        anim.setDuration(250);
        anim.addUpdateListener(
                new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(
                    android.animation.ValueAnimator a) {
                int w = (Integer) a.getAnimatedValue();
                try {
                    LayoutParams lp =
                            (LayoutParams) pill.getLayoutParams();
                    if (lp.width != w) {
                        lp.width = w;
                        lp.gravity = Gravity.CENTER_VERTICAL
                                | (dockLeft ? Gravity.START : Gravity.END);
                        pill.setLayoutParams(lp);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        anim.start();
    }

    /** Backwards-compatible single-arg timer (normal answer). */
    public void setCallTimer(String timer) {
        setCallTimer(timer, callSpeaker);
    }

    /** Switch the timer icon between handset and speaker (v108):
     * called when the user taps the timer to toggle speaker. */
    public void setCallSpeaker(boolean speaker) {
        if (data == null || !data.isCall || !callAnswered) {
            return;
        }
        callSpeaker = speaker;
        setAnswerIcon(speaker ? 2 : 0);
    }

    public boolean isCallAnswered() {
        return callAnswered;
    }

    public boolean isCallSpeaker() {
        return callSpeaker;
    }

    public void setOnToggleSpeaker(Runnable r) {
        onToggleSpeaker = r;
    }

    /** Set the answer button icon: 0 = handset, 1 = hang-up
     * handset, 2 = speaker. Bitmaps are cached — this runs on every
     * MOVE event during a swipe, so it must not re-render. */
    public void setAnswerIcon(int which) {
        if (answerIcon == null) {
            return;
        }
        android.graphics.Bitmap bmp = null;
        if (which == 1) {
            if (iconCallEndBmp == null) {
                iconCallEndBmp = renderIcon(ICON_CALL_END,
                        Color.WHITE, dp(16));
            }
            bmp = iconCallEndBmp;
        } else if (which == 2) {
            if (iconVolumeBmp == null) {
                iconVolumeBmp = renderIcon(ICON_VOLUME_UP,
                        Color.WHITE, dp(16));
            }
            bmp = iconVolumeBmp;
        } else {
            if (iconCallBmp == null) {
                iconCallBmp = renderIcon(ICON_CALL,
                        Color.WHITE, dp(16));
            }
            bmp = iconCallBmp;
        }
        answerIcon.setImageBitmap(bmp);
    }

    /** Render a Material path-data icon to a white bitmap (v108). */
    private static android.graphics.Bitmap renderIcon(
            String pathData, int color, int sizePx) {
        android.graphics.Path p = parseSvgPath(pathData);
        android.graphics.Bitmap bmp =
                android.graphics.Bitmap.createBitmap(sizePx, sizePx,
                        android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Matrix m = new android.graphics.Matrix();
        // The icons live in a 24x24 viewport.
        m.setScale(sizePx / 24f, sizePx / 24f);
        p.transform(m);
        android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        c.drawPath(p, paint);
        return bmp;
    }

    /** v518: AMap-style maneuver glyphs on a 24x24 canvas,
     * stroke-drawn (the fill-based renderIcon above cannot do
     * them): one dedicated shape per maneuver — bent arrows
     * for turns, a U for 掉头, a near-full arc for 环岛. The
     * capsule disc and the navi card share these definitions. */
    private static android.graphics.Path naviGlyphPath(int type) {
        android.graphics.Path p = new android.graphics.Path();
        switch (type) {
            case NAVI_FRONT_LEFT:
                p.moveTo(17.5f, 17.5f);
                p.lineTo(8f, 8f);
                p.moveTo(13.5f, 7.6f);
                p.lineTo(7.6f, 7.6f);
                p.lineTo(7.6f, 13.5f);
                break;
            case NAVI_LEFT:
                p.moveTo(18.5f, 20.5f);
                p.lineTo(18.5f, 12.5f);
                p.arcTo(new android.graphics.RectF(
                        7.5f, 7f, 18.5f, 18f), 0f, -90f);
                p.lineTo(6f, 7f);
                p.moveTo(9.8f, 3.4f);
                p.lineTo(6.2f, 7f);
                p.lineTo(9.8f, 10.6f);
                break;
            case NAVI_REAR_LEFT:
                p.moveTo(17.5f, 6.5f);
                p.lineTo(8f, 16f);
                p.moveTo(7.6f, 10.5f);
                p.lineTo(7.6f, 16.4f);
                p.lineTo(13.5f, 16.4f);
                break;
            case NAVI_UTURN:
                p.moveTo(8.5f, 20.5f);
                p.lineTo(8.5f, 8.8f);
                p.arcTo(new android.graphics.RectF(
                        8.5f, 4f, 18.1f, 13.6f), 180f, 180f);
                p.lineTo(18.1f, 15f);
                p.moveTo(14.6f, 12.2f);
                p.lineTo(18.1f, 16f);
                p.lineTo(21.6f, 12.2f);
                break;
            case NAVI_FRONT_RIGHT:
                p.moveTo(6.5f, 17.5f);
                p.lineTo(16f, 8f);
                p.moveTo(10.5f, 7.6f);
                p.lineTo(16.4f, 7.6f);
                p.lineTo(16.4f, 13.5f);
                break;
            case NAVI_RIGHT:
                p.moveTo(5.5f, 20.5f);
                p.lineTo(5.5f, 12.5f);
                p.arcTo(new android.graphics.RectF(
                        5.5f, 7f, 16.5f, 18f), 180f, 90f);
                p.lineTo(18f, 7f);
                p.moveTo(14.2f, 3.4f);
                p.lineTo(17.8f, 7f);
                p.lineTo(14.2f, 10.6f);
                break;
            case NAVI_REAR_RIGHT:
                p.moveTo(6.5f, 6.5f);
                p.lineTo(16f, 16f);
                p.moveTo(16.4f, 10.5f);
                p.lineTo(16.4f, 16.4f);
                p.lineTo(10.5f, 16.4f);
                break;
            case NAVI_ROUNDABOUT:
                p.arcTo(new android.graphics.RectF(
                        3.8f, 3.8f, 20.2f, 20.2f), 317f, -300f);
                p.moveTo(16.9f, 11.4f);
                p.lineTo(20.2f, 14.5f);
                p.lineTo(22.8f, 10.5f);
                break;
            case NAVI_STRAIGHT:
            default:
                p.moveTo(12f, 20.5f);
                p.lineTo(12f, 6f);
                p.moveTo(6.3f, 11.2f);
                p.lineTo(12f, 5.5f);
                p.lineTo(17.7f, 11.2f);
                break;
        }
        return p;
    }

    /** v518: render a naviGlyphPath glyph to a bitmap with a
     * round-capped stroke (stroke width scales with size:
     * 2.4 units on the 24x24 canvas). No rotation — each
     * glyph already points where its maneuver goes. */
    private static android.graphics.Bitmap renderNaviGlyph(
            int type, int color, int sizePx) {
        android.graphics.Path p = naviGlyphPath(type);
        android.graphics.Bitmap bmp =
                android.graphics.Bitmap.createBitmap(sizePx, sizePx,
                        android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(sizePx / 24f, sizePx / 24f);
        p.transform(m);
        android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f * sizePx / 24f);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        c.drawPath(p, paint);
        return bmp;
    }

    /** Minimal SVG path-data parser (v108): handles M/m, C/c, S/s,
     * L/l, H/h, V/v, Z/z — the commands the three call icons use. */
    private static android.graphics.Path parseSvgPath(String d) {
        java.util.ArrayList<Object> toks = new java.util.ArrayList<>();
        int n = d.length();
        int i = 0;
        while (i < n) {
            char c = d.charAt(i);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t'
                    || c == '\r') {
                i++;
            } else if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')) {
                toks.add(c);
                i++;
            } else {
                int start = i;
                if (c == '-' || c == '+') {
                    i++;
                }
                boolean dotSeen = false;
                while (i < n) {
                    char ch = d.charAt(i);
                    if (ch >= '0' && ch <= '9') {
                        i++;
                    } else if (ch == '.' && !dotSeen) {
                        dotSeen = true;
                        i++;
                    } else {
                        break;
                    }
                }
                try {
                    toks.add(Float.parseFloat(
                            d.substring(start, i)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        android.graphics.Path path = new android.graphics.Path();
        float cx = 0, cy = 0, sx = 0, sy = 0;
        float px2 = 0, py2 = 0;
        boolean prevCubic = false;
        char cmd = 0;
        int t = 0;
        while (t < toks.size()) {
            Object tok = toks.get(t);
            if (tok instanceof Character) {
                cmd = (Character) tok;
                t++;
                if (cmd == 'Z' || cmd == 'z') {
                    path.close();
                    cx = sx;
                    cy = sy;
                    prevCubic = false;
                }
                continue;
            }
            if (cmd == 'M' || cmd == 'm') {
                float x = num(toks, t);
                float y = num(toks, t + 1);
                t += 2;
                if (cmd == 'm') {
                    x += cx;
                    y += cy;
                }
                path.moveTo(x, y);
                cx = x;
                cy = y;
                sx = x;
                sy = y;
                prevCubic = false;
                cmd = (cmd == 'M') ? 'L' : 'l';
            } else if (cmd == 'L' || cmd == 'l') {
                float x = num(toks, t);
                float y = num(toks, t + 1);
                t += 2;
                if (cmd == 'l') {
                    x += cx;
                    y += cy;
                }
                path.lineTo(x, y);
                cx = x;
                cy = y;
                prevCubic = false;
            } else if (cmd == 'H' || cmd == 'h') {
                float x = num(toks, t);
                t += 1;
                if (cmd == 'h') {
                    x += cx;
                }
                path.lineTo(x, cy);
                cx = x;
                prevCubic = false;
            } else if (cmd == 'V' || cmd == 'v') {
                float y = num(toks, t);
                t += 1;
                if (cmd == 'v') {
                    y += cy;
                }
                path.lineTo(cx, y);
                cy = y;
                prevCubic = false;
            } else if (cmd == 'C' || cmd == 'c') {
                float x1 = num(toks, t);
                float y1 = num(toks, t + 1);
                float x2 = num(toks, t + 2);
                float y2 = num(toks, t + 3);
                float x = num(toks, t + 4);
                float y = num(toks, t + 5);
                t += 6;
                if (cmd == 'c') {
                    x1 += cx;
                    y1 += cy;
                    x2 += cx;
                    y2 += cy;
                    x += cx;
                    y += cy;
                }
                path.cubicTo(x1, y1, x2, y2, x, y);
                px2 = x2;
                py2 = y2;
                cx = x;
                cy = y;
                prevCubic = true;
            } else if (cmd == 'S' || cmd == 's') {
                float x2 = num(toks, t);
                float y2 = num(toks, t + 1);
                float x = num(toks, t + 2);
                float y = num(toks, t + 3);
                t += 4;
                float x1 = prevCubic ? 2 * cx - px2 : cx;
                float y1 = prevCubic ? 2 * cy - py2 : cy;
                if (cmd == 's') {
                    x2 += cx;
                    y2 += cy;
                    x += cx;
                    y += cy;
                }
                path.cubicTo(x1, y1, x2, y2, x, y);
                px2 = x2;
                py2 = y2;
                cx = x;
                cy = y;
                prevCubic = true;
            } else {
                t++;
            }
        }
        return path;
    }

    private static float num(java.util.ArrayList<Object> toks, int i) {
        if (i < toks.size() && toks.get(i) instanceof Float) {
            return (Float) toks.get(i);
        }
        return 0f;
    }

    public void setOnAnswer(Runnable r) { onAnswer = r; }
    public void setOnHangup(Runnable r) { onHangup = r; }

    /** Border for stacked capsules (user 2026-11-19 "叠加后自动给叠加的
     * 添加描边" / "胶囊叠加后看不清"): stacked capsules are hard to
     * tell apart, so each stacked capsule gets an outline. */
    public void setStacked(boolean stacked) {
        if (this.stacked == stacked) {
            return;
        }
        this.stacked = stacked;
        updateBorder();
    }

    // v471: 黑描边改细（用户 2026-11-23 “黑描边细一点”“你这太粗了”）。
    // 描边做到背景 drawable 里（EdgeBorderDrawable，v470），跟着 view
    // 走，弹入、拖动、长大成卡片都跟着动。宽度从 2dp 改成 1dp。
    /** v473: 卡片拖尾用的描边色（BubbleWindow.ensureCardTail 读它，
     * 让拖尾的边跟卡片的边一个颜色）。 */
    public int getBorderColor() {
        return borderColorForDraw();
    }

    private int borderColorForDraw() {
        if (!FeatureFlags.autoTint) {
            // v466: 自动取色关着时胶囊是白色的，浅底上看不清，
            // 加一圈黑色描边（不管是单颗还是叠着的，都有）。
            return Color.BLACK;
        }
        if (stacked) {
            return pillLight
                    ? Color.argb(120, 0, 0, 0)
                    : Color.argb(160, 255, 255, 255);
        }
        return Color.TRANSPARENT;
    }

    private void updateBorder() {
        int c = borderColorForDraw();
        if (pillBg != null) {
            pillBg.setBorder(c, dp(1), dockLeft,
                    dp(cornerRadiusDp()));
        }
        if (cardBg != null) {
            cardBg.setBorder(c, dp(1), dockLeft,
                    dp(cardCornerRadiusDp()));
        }
    }

    /** Load the app icon in SystemUI from the package (v1 does it
     * this way too). */
    private android.graphics.drawable.Drawable loadAppIcon(String pkg) {
        if (pkg == null) {
            return null;
        }
        try {
            return getContext().getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Convert a Drawable to a small Bitmap for the tint calculation. */
    private static android.graphics.Bitmap drawableToBitmap(
            android.graphics.drawable.Drawable d) {
        if (d == null) {
            return null;
        }
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        }
        int size = 96;
        try {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            return b;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Current pill colour (0 if applyIconTint has not run yet). */
    /** Current pill width in px, tail included when dragged out. */
    public int getPillWidth() {
        if (pillWidthPx > 0) {
            return pillWidthPx;
        }
        if (pill != null && pill.getWidth() > 0) {
            return pill.getWidth();
        }
        return dp(capsuleWidthDp(getContext()));
    }

    public int getPillColor() {
        return pillColor;
    }

    public NotifData getData() {
        return data;
    }

    /** Show or hide the outside pin badge. */
    /**
     * Music display switch. A pinned music capsule shows ONE enlarged
     * scrolling lyric line (the pin badge is already on the pill);
     * anything else shows the usual song (title) + singer (text)
     * lines. Called from bind() and setPinned() — rebuildDock calls
     * bind() first and setPinned() after, so both must refresh it.
     */
    private void updateMusicDisplay() {
        boolean showLyrics = data != null && data.isMusic && pinned
                && data.lyrics != null && !data.lyrics.isEmpty();
        // v250: a recognized code needs no title — the capsule
        // shows only the enlarged code (user 2026-11-21 "如果识
        // 别出了取件码或者验证码，胶囊就不要标题了，直接显示验
        // 证码就行了").
        boolean showCode = data != null && data.code != null;
        lyricsRoll.setVisibility(showLyrics ? VISIBLE : GONE);
        titleView.setVisibility(
                (showLyrics || showCode) ? GONE : VISIBLE);
        textView.setVisibility(showLyrics ? GONE : VISIBLE);
        if (showLyrics) {
            // Re-select so the marquee restarts on every rebind.
            lyricsView.setSelected(false);
            lyricsView.setSelected(true);
        }
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
        // Calls auto-pin but NEVER show the pin icon (user 2026-11-19
        // "来电自动钉住但是不显示钉住图标") — the answer button sits
        // in the pin's place instead.
        if (data != null && data.isCall) {
            pinView.setVisibility(GONE);
        } else {
            pinView.setVisibility(pinned ? VISIBLE : GONE);
        }
        // v330: a pinned (locked) music capsule hides the volume
        // bars (user 2026-11-21 "胶囊锁定后就不要显示这个音量条
        // 了") — the pinned capsule shows the lyric, no bars.
        // Unpinning brings them back (if still playing).
        if (data != null && data.isMusic && volumeBars != null) {
            if (pinned) {
                volumeBars.setVisibility(GONE);
                volumeBars.stop();
            } else {
                volumeBars.setVisibility(VISIBLE);
                if (musicPlaying) {
                    volumeBars.start();
                }
            }
        }
        updateMusicDisplay();
    }

    // ---- Card mode (v143) ----
    public boolean isCardExpanded() {
        return cardExpanded;
    }

    /** Grow this capsule into its card (same view, same tint), or
     * shrink it back. The pill hides and the card content shows;
     * the size itself is set by BubbleWindow via LayoutParams. */
    public void setCardExpanded(boolean expanded) {
        if (cardExpanded == expanded) {
            return;
        }
        cardExpanded = expanded;
        if (expanded && data != null && data.isMusic) {
            // v171: music card — fill the player card in place
            // (1.x updatePlayerContent), not the generic card.
            updateMusicCard();
        }
        if (expanded && data != null && data.isNavi) {
            // v513: navi dashboard card, filled in place like
            // the music card.
            updateNaviCard();
        }
        if (expanded && data != null) {
            // Fill the card from the notification.
            if (cardIcon != null) {
                if (data.icon != null) {
                    cardIcon.setImageBitmap(data.icon);
                } else if (data.largeIcon != null) {
                    cardIcon.setImageBitmap(data.largeIcon);
                } else {
                    android.graphics.drawable.Drawable d =
                            loadAppIcon(data.pkg);
                    cardIcon.setImageDrawable(d);
                }
            }
            if (cardAppName != null) {
                cardAppName.setText(loadAppName(data.pkg));
            }
            if (cardTime != null) {
                cardTime.setText(formatTime(data.postTime));
            }
            if (cardTitle != null) {
                cardTitle.setText(
                        data.title != null ? data.title : "");
            }
            if (cardBody != null) {
                cardBody.setText(data.text != null ? data.text : "");
            }
            // v244: enlarged code in the open card.
            // v463（用户 2026-11-23 \"显示成 xx码:xxxxx\"）：卡片
            // 里的大码也改成标签 + 冒号 + 码（\"验证码:123456\" /
            // \"取件码:070797\"），跟胶囊一个格式。柜号不拼进来，
            // 它有自己的一行（cardCabinet）。
            if (cardCode != null) {
                if (data.code != null) {
                    cardCode.setText(formatCodeLabel(data));
                    cardCode.setVisibility(VISIBLE);
                } else {
                    cardCode.setVisibility(GONE);
                }
            }
            // v245: locker number under the big code.
            if (cardCabinet != null) {
                if (data.cabinet != null) {
                    cardCabinet.setText(data.cabinet);
                    cardCabinet.setVisibility(VISIBLE);
                } else {
                    cardCabinet.setVisibility(GONE);
                }
            }
        }
        // A swipe tail left on the pill (from a right swipe on the
        // open card) must not survive into the next mode.
        resetLongCapsule();
        setTranslationX(0f);
        if (pill != null) {
            pill.setVisibility(expanded ? GONE : VISIBLE);
        }
        // v239: the bar lives on the pill, not the card.
        syncDownloadBar();
        // v171: music shows the player card, everything else the
        // generic card.
        // v513: navi shows its dashboard card instead.
        boolean isMusicCard = expanded && data != null && data.isMusic;
        boolean isNaviCard = expanded && data != null && data.isNavi;
        if (cardView != null) {
            cardView.setVisibility(
                    (isMusicCard || isNaviCard) ? GONE
                            : (expanded ? VISIBLE : GONE));
            // v484: 展开卡片（普通卡）比例调整——卡片内容按比例
            // 缩放着画（左上角为轴）。内容本身按自然尺寸布局
            // （cardView 是固定自然宽 + WRAP_CONTENT 高），缩着画
            // 正好填满缩放后的胶囊（LayoutParams 是缩放后的宽高）。
            // 100% 时 scale=1，和原来一样。跟音乐卡
            // （updateMusicCard 里给 musicCardView 设 scale）一个路子。
            if (!isMusicCard) {
                float s = cardScale();
                cardView.setPivotX(0f);
                cardView.setPivotY(0f);
                cardView.setScaleX(s);
                cardView.setScaleY(s);
                if (naviCardView != null) {
                    naviCardView.setPivotX(0f);
                    naviCardView.setPivotY(0f);
                    naviCardView.setScaleX(s);
                    naviCardView.setScaleY(s);
                }
            }
        }
        if (musicCardView != null) {
            musicCardView.setVisibility(isMusicCard ? VISIBLE : GONE);
        }
        if (naviCardView != null) {
            naviCardView.setVisibility(isNaviCard ? VISIBLE : GONE);
        }
    }

    /** Natural card height at the given width, snapped to the
     * capsule grid (k*50-6dp, min 2 rows) so the bottom lands one
     * gap above the capsule below and the 22dp corners fit. Call
     * after setCardExpanded(true) so the content is populated. */
    public int cardHeightForWidth(int widthPx) {
        // v171: a music card measures its own (player) layout.
        android.view.View measureView = cardView;
        if (data != null && data.isMusic && musicCardView != null) {
            measureView = musicCardView;
        }
        // v513: a navi card measures its own (dashboard) layout.
        if (data != null && data.isNavi && naviCardView != null) {
            measureView = naviCardView;
        }
        if (measureView == null) {
            return dp(DEFAULT_HEIGHT_DP);
        }
        int wSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                widthPx, android.view.View.MeasureSpec.EXACTLY);
        int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                0, android.view.View.MeasureSpec.UNSPECIFIED);
        measureView.measure(wSpec, hSpec);
        int natural = measureView.getMeasuredHeight();
        // v162: truly adaptive — the card hugs its content instead
        // of snapping up to the 50dp grid (the ceil left up to a
        // whole extra row of blank space below, user 2026-11-19
        // "减少下面留白"). Clamp to 2..maxRows rows so a very long
        // message still does not make a full-screen card.
        // v456: 行距跟胶囊高走（普通卡：高 + 6dp 间隙；音乐卡不动，
        // 44 + 6 = 50dp）；最大行数走 cardMaxRows()（普通卡外观值，
        // 音乐卡 8 行）。
        int pitch = dp((data != null && data.isMusic)
                ? CARD_PITCH_DP : baseHeightDp() + CARD_GAP_DP);
        int gap = dp(CARD_GAP_DP);
        int maxRows = cardMaxRows();
        int minH = 2 * pitch - gap;
        int maxH = maxRows * pitch - gap;
        int h;
        if (natural < minH) {
            h = minH;
        } else if (natural > maxH) {
            h = maxH;
        } else {
            h = natural;
        }
        // v462: 音乐卡片比例调整——高度也按比例缩（整张卡等比）。
        // 歌词已固定 1 行（updateMusicCard 里把第二行藏了），
        // 所以 natural 本来就比原来矮一行，再乘比例。
        if (data != null && data.isMusic) {
            h = Math.round(h * musicCardScale());
        }
        // v484: 展开卡片（普通卡）比例调整——高度也按比例缩
        // （整张卡等比），跟音乐卡一个路子。
        if (data != null && !data.isMusic) {
            h = Math.round(h * cardScale());
        }
        return h;
    }

    public void setOnCardClose(Runnable r) {
        onCardClose = r;
    }

    public void setOnCardPinToggle(Runnable r) {
        onCardPinToggle = r;
    }

    /** Pin icon in the card header (v164): only shown when the
     * card is pinned (user 2026-11-19 "只有钉住后才显示" / "平时不
     * 显示"); hidden entirely when not pinned. */
    public void setCardPinned(boolean pinned) {
        if (cardPin != null) {
            cardPin.setVisibility(pinned ? VISIBLE : GONE);
            cardPin.setAlpha(1f);
        }
        // v174: the music card's own pin icon follows the same rule.
        if (musicPin != null) {
            musicPin.setVisibility(pinned ? VISIBLE : GONE);
            musicPin.setAlpha(1f);
        }
        // v517: the navi card's own pin icon follows the same rule.
        if (naviPin != null) {
            naviPin.setVisibility(pinned ? VISIBLE : GONE);
            naviPin.setAlpha(1f);
        }
    }

    /** v517: one-line diagnostic for the navi card path — which
     * card surface is VISIBLE and whether the navi strings
     * parsed. Used by BubbleWindow's Phase-1 FileLog line and
     * updateNaviCard's own, to pin down the auto-pinned card
     * missing its arrow disc (user report 2026-11-25). */
    public String naviCardDiag() {
        String surface = "none";
        if (naviCardView != null
                && naviCardView.getVisibility() == VISIBLE) {
            surface = "naviCardView";
        } else if (musicCardView != null
                && musicCardView.getVisibility() == VISIBLE) {
            surface = "musicCardView";
        } else if (cardView != null
                && cardView.getVisibility() == VISIBLE) {
            surface = "cardView";
        }
        return "isNavi=" + (data != null && data.isNavi)
                + " surface=" + surface
                + " naviParsed=" + naviParsed;
    }


    /** v171: music card control callback. */
    public void setOnMusicControl(OnMusicControlListener l) {
        onMusicControl = l;
    }

    /** v171: favorite heart tint — pink when favorited, else
     * follows the card's light/dark flip. */
    private void updateMusicFav() {
        if (musicFavBtn == null) {
            return;
        }
        int c = musicFavorite
                ? Color.rgb(255, 64, 129)
                : (pillLight ? Color.rgb(26, 26, 26) : Color.WHITE);
        musicFavBtn.setImageBitmap(renderIcon(ICON_FAV, c, dp(22)));
    }

    /** v174: the play/pause icon from the current playing state.
     * Used by the optimistic tap flip and by the 1s tick. */
    private void updateMusicPlayIcon() {
        if (musicPlayBtn == null) {
            return;
        }
        int iconC = pillLight
                ? Color.rgb(26, 26, 26) : Color.WHITE;
        musicPlayBtn.setImageBitmap(renderIcon(
                musicPlaying ? ICON_PAUSE : ICON_PLAY,
                iconC, dp(30)));
    }

    /** v174: the module reports the real heart-rating state, so the
     * heart reflects whether this song is actually favorited. */
    public void setMusicFavorite(boolean favorite) {
        musicFavorite = favorite;
        updateMusicFav();
    }

    /** v174: a thicker (5dp) progress track so the bar reads at a
     * glance. Rebuilt on every colour flip. */
    private void styleMusicSeek() {
        if (musicSeek == null) {
            return;
        }
        // v232: only rebuild when the colour actually flipped.
        // Re-setting the progress drawable on an already
        // laid-out SeekBar stretches the 5dp track to the full
        // 24dp height (pinned card: live lyric updates rebuilt
        // it every line, so the bar grew to ~84px). Same colour
        // = no-op, the thin track stays thin.
        if (seekLightApplied != null
                && seekLightApplied.booleanValue() == pillLight) {
            return;
        }
        seekLightApplied = Boolean.valueOf(pillLight);
        int trackC = pillLight ? Color.argb(70, 26, 26, 26)
                : Color.argb(70, 255, 255, 255);
        int fillC = pillLight ? Color.rgb(26, 26, 26) : Color.WHITE;
        int trackH = isTablet(getContext()) ? 3 : 5;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(3));
        bg.setColor(trackC);
        bg.setSize(dp(1), dp(trackH));
        GradientDrawable fg = new GradientDrawable();
        fg.setShape(GradientDrawable.RECTANGLE);
        fg.setCornerRadius(dp(3));
        fg.setColor(fillC);
        fg.setSize(dp(1), dp(trackH));
        android.graphics.drawable.ClipDrawable clip =
                new android.graphics.drawable.ClipDrawable(fg,
                        Gravity.LEFT,
                        android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{bg, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        musicSeek.setProgressDrawable(ld);
        musicSeek.requestLayout();
    }

    /** v171: circular-crop a bitmap (album disc). */
    private static android.graphics.Bitmap circleBitmap(
            android.graphics.Bitmap src, int sizePx) {
        if (src == null || sizePx <= 0) {
            return null;
        }
        try {
            android.graphics.Bitmap scaled =
                    android.graphics.Bitmap.createScaledBitmap(
                            src, sizePx, sizePx, true);
            android.graphics.Bitmap out =
                    android.graphics.Bitmap.createBitmap(
                            sizePx, sizePx,
                            android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv =
                    new android.graphics.Canvas(out);
            android.graphics.Paint paint =
                    new android.graphics.Paint(
                            android.graphics.Paint.ANTI_ALIAS_FLAG);
            cv.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f,
                    paint);
            paint.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.SRC_IN));
            cv.drawBitmap(scaled, 0, 0, paint);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** v171: ms -> "m:ss". */
    private static String formatMusicTime(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long totalSec = ms / 1000;
        return (totalSec / 60) + ":"
                + String.format("%02d", totalSec % 60);
    }

    /** v171: fill the player card from data, in place (1.x
     * updatePlayerContent). Called when the card expands and
     * whenever the song changes; lyric/progress updates go through
     * updateMusicLyric / updateMusicProgress so the card is never
     * rebuilt for a lyric line. */
    public void updateMusicCard() {
        if (data == null || !data.isMusic) {
            return;
        }
        if (musicDisc != null) {
            android.graphics.Bitmap art = data.largeIcon != null
                    ? data.largeIcon : data.icon;
            android.graphics.Bitmap circ = circleBitmap(art, dp(64));
            if (circ != null) {
                musicDisc.setImageBitmap(circ);
            } else {
                android.graphics.drawable.Drawable d =
                        loadAppIcon(data.pkg);
                musicDisc.setImageDrawable(d);
            }
        }
        // v221: keep the capsule icon in sync with the card disc.
        // The disc is re-read from data.largeIcon on every music
        // refresh (song change, state broadcast, card open), but
        // the capsule icon was only set at bind() and updateArt()
        // — so a song change could leave the disc on the new
        // cover while the capsule still showed the previous
        // album (user 2026-11-20 "卡片及时切换，胶囊还是之前
        // 的专辑图"). Re-read the same art onto the icon (and
        // its tint) here, so the capsule follows the disc on
        // every refresh. If there is no art, leave the icon
        // alone instead of clearing it.
        android.graphics.Bitmap iconArt = data.largeIcon != null
                ? data.largeIcon : data.icon;
        if (iconArt != null) {
            if (iconView != null) {
                iconView.setImageBitmap(iconArt);
            }
            applyIconTint(iconArt);
        }
        if (musicTitle != null) {
            musicTitle.setText(
                    data.title != null ? data.title : "");
        }
        if (musicArtist != null) {
            musicArtist.setText(data.text != null ? data.text : "");
        }
        if (musicLyric != null) {
            musicLyric.setText(
                    data.lyrics != null ? data.lyrics : "");
        }
        // v174: a fresh card / new song has no previous line yet.
        // v462（用户「歌名改成一行就可以了」）：歌词固定 1 行——
        // 只显示当前歌词，第二行（上一行歌词）永远藏起来，卡片
        // 矮一行。高度再走卡片比例调整一起缩。
        if (musicLyricNext != null) {
            musicLyricNext.setText("");
            musicLyricNext.setVisibility(android.view.View.GONE);
        }
        // v462: 卡片比例调整——播放卡内容按比例缩放着画（左上角
        // 为轴）。内容本身按自然尺寸布局（musicCardView 是固定
        // 自然宽 + WRAP_CONTENT 高），缩着画正好填满缩放后的
        // 胶囊（LayoutParams 是缩放后的宽高）。100% 时 scale=1，
        // 和原来一样。
        if (musicCardView != null) {
            float s = musicCardScale();
            musicCardView.setPivotX(0f);
            musicCardView.setPivotY(0f);
            musicCardView.setScaleX(s);
            musicCardView.setScaleY(s);
        }
        updateMusicFav();
    }

    // v513: navi actions, longest / most specific first so a
    // contains-match picks "向左前方直行" over "直行".
    // v518: added the rear / roundabout / 调头 keywords so the
    // new maneuver classifier can actually see them.
    private static final String[] NAVI_ACTIONS = {
        "向左前方直行", "向右前方直行", "向左前方", "向右前方",
        "向左转", "向右转", "左后方", "右后方", "环岛", "转盘",
        "掉头", "调头", "上过街天桥", "下过街天桥",
        "左转", "右转", "直行", "靠左", "靠右",
    };

    /** v518: classify an action string into a maneuver type
     * for the AMap-style glyph set (replaces v513–v517's
     * naviRotationFor: the dedicated glyphs are drawn already
     * pointing the right way, nothing rotates). Keyword
     * priority follows the shape specificity: U-turn and
     * roundabout first, then rear, front, plain turns;
     * anything unrecognized is STRAIGHT. Arrival is handled
     * by naviArrived, not here. */
    private static int naviManeuverFor(String action) {
        if (action != null) {
            if (action.contains("掉头") || action.contains("调头")) {
                return NAVI_UTURN;
            }
            if (action.contains("环岛") || action.contains("转盘")) {
                return NAVI_ROUNDABOUT;
            }
            if (action.contains("左后方")) {
                return NAVI_REAR_LEFT;
            }
            if (action.contains("右后方")) {
                return NAVI_REAR_RIGHT;
            }
            if (action.contains("左前方")
                    || action.contains("靠左")) {
                return NAVI_FRONT_LEFT;
            }
            if (action.contains("右前方")
                    || action.contains("靠右")) {
                return NAVI_FRONT_RIGHT;
            }
            if (action.contains("左转")) {
                return NAVI_LEFT;
            }
            if (action.contains("右转")) {
                return NAVI_RIGHT;
            }
        }
        return NAVI_STRAIGHT;
    }

    /** v513: first "<number>米" / "<number>公里" at or after
     * `from` in s. Returns {meters, 1 if followed by 后 else 0},
     * or null when there is no distance. */
    private static double[] scanDist(String s, int from) {
        if (s == null) {
            return null;
        }
        int n = s.length();
        for (int i = Math.max(0, from); i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            int j = i;
            boolean dot = false;
            while (j < n) {
                char d = s.charAt(j);
                if (d >= '0' && d <= '9') {
                    j++;
                } else if (d == '.' && !dot) {
                    dot = true;
                    j++;
                } else {
                    break;
                }
            }
            double mult = 0;
            int unitLen = 0;
            if (s.startsWith("公里", j)) {
                mult = 1000;
                unitLen = 2;
            } else if (j < n && s.charAt(j) == '米') {
                mult = 1;
                unitLen = 1;
            }
            if (mult > 0) {
                double v;
                try {
                    v = Double.parseDouble(s.substring(i, j));
                } catch (Throwable th) {
                    return null;
                }
                int after = j + unitLen;
                double hou = (after < n && s.charAt(after) == '后')
                        ? 1 : 0;
                return new double[]{v * mult, hou};
            }
            i = j - 1;
        }
        return null;
    }

    /** v513: display distance, BubbleWindow.formatDist style
     * with a space ("119 米" / "3.5 公里"). */
    private static String fmtNaviDist(double meters) {
        if (meters >= 1000) {
            return String.format(java.util.Locale.ROOT,
                    "%.1f 公里", meters / 1000.0);
        }
        return ((int) Math.round(meters)) + " 米";
    }

    /** v513: parse the navi display state out of data.title /
     * data.text. Foot broadcasts compose the title as "119米后
     * 直行进入建新东路" / "沿建新东路直行188米" / "直行128米"
     * and the content as "剩3.5公里 · 57分钟 · 预计00:34到达";
     * ride broadcasts carry only the instruction ("96米后左转",
     * remaining fields stay empty); the v322 computed foot
     * capsule uses "直行 200米" + "剩3.5公里 · 57分钟". Anything
     * unrecognized leaves naviParsed=false and the generic
     * title/text stand — degrade, never blank. */
    private void parseNavi() {
        naviParsed = false;
        naviArrived = false;
        naviAction = null;
        naviRoadPart = null;
        naviDistMeters = -1;
        naviDistSeg = null;
        naviRemainMeters = -1;
        naviRemainSeg = null;
        naviRemainMinSeg = null;
        naviEtaSeg = null;
        // v518: unparsed / placeholder state shows the
        // straight-ahead glyph.
        naviManeuver = NAVI_STRAIGHT;
        if (data == null) {
            return;
        }
        String title = data.title != null ? data.title : "";
        String text = data.text != null ? data.text : "";
        naviArrived = title.contains("到达");
        for (String a : NAVI_ACTIONS) {
            if (title.contains(a)) {
                naviAction = a;
                break;
            }
        }
        double[] dist = scanDist(title, 0);
        if (dist != null) {
            naviDistMeters = (int) Math.round(dist[0]);
            naviDistSeg = fmtNaviDist(dist[0])
                    + (dist[1] > 0 ? "后" : "");
        }
        // Road: "…进入X路" (road at the end of the title) or
        // "沿X路<动作>" (road before the action).
        int enterIdx = title.lastIndexOf("进入");
        if (enterIdx >= 0 && enterIdx + 2 < title.length()) {
            naviRoadPart = title.substring(enterIdx);
        } else if (title.startsWith("沿") && naviAction != null) {
            int actIdx = title.indexOf(naviAction);
            if (actIdx > 1) {
                naviRoadPart = "沿" + title.substring(1, actIdx);
            }
        }
        if (naviAction != null) {
            naviManeuver = naviManeuverFor(naviAction);
            naviParsed = true;
        }
        int sheng = text.indexOf('剩');
        if (sheng >= 0) {
            double[] rd = scanDist(text, sheng + 1);
            if (rd != null) {
                naviRemainMeters = (int) Math.round(rd[0]);
                naviRemainSeg = fmtNaviDist(rd[0]);
            }
        }
        int minIdx = text.indexOf("分钟");
        if (minIdx > 0) {
            int s = minIdx - 1;
            while (s >= 0) {
                char ch = text.charAt(s);
                if ((ch >= '0' && ch <= '9') || ch == '.') {
                    s--;
                } else {
                    break;
                }
            }
            String num = text.substring(s + 1, minIdx).trim();
            if (!num.isEmpty()) {
                naviRemainMinSeg = num + " 分钟";
            }
        }
        int etaIdx = text.indexOf("预计");
        if (etaIdx >= 0) {
            int end = text.indexOf("到达", etaIdx);
            String eta = (end > etaIdx)
                    ? text.substring(etaIdx + 2, end)
                    : text.substring(etaIdx + 2);
            eta = eta.replace("·", " ").trim();
            if (!eta.isEmpty()) {
                naviEtaSeg = eta;
            }
        }
    }

    /** v513: capsule-side navi presentation, run at the end of
     * bind() for navi data (after applyIconTint, so the tint
     * state is known). Reformats the two lines, swaps the app
     * icon for the turn-arrow disc, applies the warm/green tint
     * states, and refreshes the open card if there is one. */
    private void applyNaviBind() {
        parseNavi();
        if (naviArrived) {
            titleView.setText(
                    data.title != null ? data.title : "");
            textView.setText(data.text != null ? data.text : "");
            textView.setVisibility(
                    (data.text == null || data.text.isEmpty())
                            ? GONE : VISIBLE);
        } else if (naviParsed) {
            titleView.setText(naviAction
                    + (naviRoadPart != null
                            ? " · " + naviRoadPart : ""));
            String line2 = "";
            if (naviDistSeg != null) {
                line2 = naviDistSeg;
            }
            if (naviRemainSeg != null) {
                line2 += (line2.isEmpty() ? "" : " · ")
                        + "还剩 " + naviRemainSeg;
            }
            if (line2.isEmpty()) {
                line2 = data.text != null ? data.text : "";
            }
            textView.setText(line2);
            textView.setVisibility(
                    line2.isEmpty() ? GONE : VISIBLE);
        }
        // Icon disc: white circle + the maneuver's AMap-style
        // glyph (flag on arrival). v518: glyphs are dedicated
        // shapes, drawn unrotated.
        if (naviIconBg == null) {
            naviIconBg = new GradientDrawable();
            naviIconBg.setShape(GradientDrawable.OVAL);
        }
        naviIconBg.setColor(pillLight
                ? Color.rgb(220, 230, 236) : Color.WHITE);
        iconView.setBackground(naviIconBg);
        iconView.setPadding(dp(6), dp(6), dp(6), dp(6));
        iconView.setImageBitmap(naviArrived
                ? renderIcon(ICON_FLAG, NAVI_BLUE, dp(24))
                : renderNaviGlyph(naviManeuver, NAVI_BLUE, dp(24)));
        iconView.setRotation(0f);
        applyNaviTint();
        if (cardExpanded) {
            updateNaviCard();
        }
    }

    /** v513: undo the navi icon-disc presentation when a view
     * is re-bound to non-navi data (rotation / background /
     * padding only — bind has just set the real icon image). */
    private void resetNaviIcon() {
        if (iconView == null) {
            return;
        }
        iconView.setRotation(0f);
        iconView.setBackground(null);
        iconView.setPadding(0, 0, 0, 0);
    }

    /** v513: navi tint states on top of the icon-derived tint —
     * arrival green, <50m-to-the-turn warm orange-red; otherwise
     * whatever applyIconTint chose stands (the AMap-icon tint).
     * bind() runs applyIconTint first, so dropping out of a
     * state restores the normal tint on the next update.
     * v515: navi tints were OPAQUE (alpha 255) in every state
     * (anti-bleed-through fix). v519: user pointed out the
     * navi tint no longer matched the other capsules — every
     * state now uses the same alpha 204 as applyIconTint's
     * pillColor, so navi follows the global translucency.
     * The normal state keeps the icon-derived hue (pillColor
     * as applyIconTint just left it), re-wrapped at 204. */
    private void applyNaviTint() {
        if (pillBg == null || data == null || !data.isNavi) {
            return;
        }
        parseNavi();
        int tint;
        boolean stateTint = false;
        if (naviArrived) {
            tint = Color.argb(204, 34, 139, 34);
            stateTint = true;
        } else if (naviDistMeters >= 0 && naviDistMeters < 50) {
            tint = Color.argb(204, 150, 62, 28);
            stateTint = true;
        } else {
            tint = Color.argb(204, Color.red(pillColor),
                    Color.green(pillColor), Color.blue(pillColor));
        }
        pillColor = tint;
        pillBg.setColor(tint);
        if (cardBg != null) {
            cardBg.setColor(tint);
        }
        if (stateTint) {
            pillLight = false;
            titleView.setTextColor(Color.WHITE);
            textView.setTextColor(Color.argb(235, 255, 255, 255));
        }
    }

    /** v513: fill the navi dashboard card from the parsed state
     * (called when the card opens and on every live re-bind
     * while it is open). Missing data degrades: no route total
     * or no remaining distance → no progress bar; no remaining
     * fields at all → no bottom line. */
    public void updateNaviCard() {
        if (data == null || !data.isNavi || naviCardView == null) {
            return;
        }
        parseNavi();
        // v517: evidence line for the auto-pin icon report —
        // note the surface is read before setCardExpanded flips
        // visibility on the opening call; on live re-binds (and
        // in BubbleWindow's Phase-1 line) it is the final state.
        FileLog.i("NaviCard update " + naviCardDiag());
        int main = pillLight
                ? Color.argb(255, 26, 26, 26) : Color.WHITE;
        int sub = pillLight
                ? Color.argb(204, 60, 60, 60)
                : Color.argb(235, 255, 255, 255);
        if (naviDiscBg != null) {
            naviDiscBg.setColor(pillLight
                    ? Color.rgb(220, 230, 236) : Color.WHITE);
        }
        if (naviCardArrow != null) {
            naviCardArrow.setImageBitmap(naviArrived
                    ? renderIcon(ICON_FLAG, NAVI_BLUE, dp(34))
                    : renderNaviGlyph(naviManeuver, NAVI_BLUE,
                            dp(34)));
            naviCardArrow.setRotation(0f);
        }
        String line1;
        if (naviArrived || !naviParsed) {
            line1 = data.title != null ? data.title : "";
        } else {
            line1 = naviAction
                    + (naviRoadPart != null
                            ? " · " + naviRoadPart : "");
        }
        if (naviCardDist != null) {
            naviCardDist.setTextColor(main);
            naviCardDist.setText(naviArrived ? "已到达"
                    : (naviDistMeters >= 0
                            ? fmtNaviDist(naviDistMeters)
                            : line1));
        }
        if (naviCardAction != null) {
            naviCardAction.setTextColor(sub);
            naviCardAction.setText(line1);
            // Hidden when the big line already shows line1
            // (no distance parsed and not arrived).
            naviCardAction.setVisibility(
                    (naviArrived || naviDistMeters >= 0)
                            ? VISIBLE : GONE);
        }
        boolean showProg = data.naviTotalMeters > 0
                && naviRemainMeters >= 0;
        if (naviProgBox != null) {
            naviProgBox.setVisibility(showProg ? VISIBLE : GONE);
        }
        if (showProg && naviProgFill != null) {
            double frac = naviArrived ? 1.0
                    : 1.0 - (double) naviRemainMeters
                            / data.naviTotalMeters;
            if (frac < 0) {
                frac = 0;
            }
            if (frac > 1) {
                frac = 1;
            }
            naviProgFill.setScaleX((float) frac);
            if (naviProgFillBg != null) {
                naviProgFillBg.setColor(
                        pillLight ? NAVI_BLUE : Color.WHITE);
            }
            if (naviProgTrackBg != null) {
                naviProgTrackBg.setColor(pillLight
                        ? Color.argb(60, 0, 0, 0)
                        : Color.argb(64, 255, 255, 255));
            }
        }
        String bottom = "";
        if (naviRemainSeg != null) {
            bottom = "还剩 " + naviRemainSeg;
        }
        if (naviRemainMinSeg != null) {
            bottom += (bottom.isEmpty() ? "" : " · ")
                    + "约 " + naviRemainMinSeg;
        }
        if (naviEtaSeg != null) {
            bottom += (bottom.isEmpty() ? "" : " · ")
                    + naviEtaSeg + " 到达";
        }
        if (naviCardBottom != null) {
            naviCardBottom.setTextColor(sub);
            naviCardBottom.setText(bottom);
            naviCardBottom.setVisibility(
                    bottom.isEmpty() ? GONE : VISIBLE);
        }
    }

    /** v465: 胶囊自动取色开关拨动后，把已经弹出来的胶囊按新开关
     * 重新上色（跟 bind 里一个取图路子：icon → largeIcon → 现
     * 读 App 图标；音乐胶囊的 largeIcon 是专辑图，照样走它）。 */
    public void refreshTint() {
        if (data == null) {
            return;
        }
        android.graphics.Bitmap bmp = null;
        if (data.icon != null) {
            bmp = data.icon;
        } else if (data.largeIcon != null) {
            bmp = data.largeIcon;
        } else {
            android.graphics.drawable.Drawable d = loadAppIcon(data.pkg);
            if (d != null) {
                bmp = drawableToBitmap(d);
            }
        }
        applyIconTint(bmp);
        // v513: re-apply the navi warm/green state on top.
        if (data.isNavi) {
            applyNaviTint();
        }
    }

    /** v218: live session album art (pushed when it first appears
     * for a song). Applies the art in place — capsule icon + pill
     * tint + card disc — the same way bind() would have if the
     * art had been there at pop time. */
    public void updateArt(android.graphics.Bitmap art) {
        if (art == null || data == null || !data.isMusic) {
            return;
        }
        data.largeIcon = art;
        if (iconView != null) {
            iconView.setImageBitmap(art);
        }
        applyIconTint(art);
        updateMusicCard();
    }

    /** v171: progress + play/pause state from the module's
     * 1s tick (1.x fixed62: a stale repeat position is ignored
     * in the module, so the bar never jumps back). */
    public void updateMusicProgress(long positionMs, long durationMs,
            boolean playing) {
        musicPlaying = playing;
        // v329: music capsule motion follows the playing state —
        // notes (F) + volume bars + breathing (C) run only while
        // playing; paused = everything rests, text back to full
        // opacity. The 1s tick fires even when paused (with
        // playing=false), so this is reliable.
        if (data != null && data.isMusic) {
            if (playing) {
                if (noteParticles != null) {
                    noteParticles.start();
                }
                // v330: no volume bars on a pinned capsule.
                if (volumeBars != null && !pinned) {
                    volumeBars.start();
                }
                setMusicBreathing(true);
            } else {
                if (noteParticles != null) {
                    noteParticles.stop();
                }
                if (volumeBars != null) {
                    volumeBars.stop();
                }
                setMusicBreathing(false);
            }
        }
        if (musicSeek != null) {
            if (durationMs > 0) {
                musicSeek.setMax((int) durationMs);
                musicSeek.setProgress((int) Math.min(positionMs,
                        durationMs));
            }
        }
        if (musicCurTime != null) {
            musicCurTime.setText(formatMusicTime(positionMs));
        }
        if (musicTotalTime != null) {
            musicTotalTime.setText(formatMusicTime(durationMs));
        }
        updateMusicPlayIcon();
    }

    /** v329: title entrance (option A) — the music title/artist
     * slide in from the left + fade in when a new song arrives.
     * Animates the texts container (both lines together), 300ms.
     * Safe to call repeatedly: it cancels any in-flight entrance. */
    private void playMusicTitleEntrance() {
        if (texts == null) {
            return;
        }
        texts.animate().cancel();
        texts.setAlpha(0f);
        texts.setTranslationX(-dp(16));
        texts.animate().alpha(1f).translationX(0f)
                .setDuration(300)
                .setInterpolator(
                        new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** v329: breathing pulse (option C) — the music title/artist
     * gently breathe (alpha 0.8 ↔ 1.0, 2.6s) while playing.
     * Stopping restores full opacity. */
    private void setMusicBreathing(boolean on) {
        if (on) {
            if (breatheTitle == null && titleView != null) {
                breatheTitle = android.animation.ObjectAnimator
                        .ofFloat(titleView, "alpha", 0.8f, 1f);
                breatheTitle.setDuration(1300);
                breatheTitle.setRepeatMode(
                        android.animation.ValueAnimator.REVERSE);
                breatheTitle.setRepeatCount(
                        android.animation.ValueAnimator.INFINITE);
                breatheTitle.setInterpolator(
                        new android.view.animation.AccelerateDecelerateInterpolator());
            }
            if (breatheText == null && textView != null) {
                breatheText = android.animation.ObjectAnimator
                        .ofFloat(textView, "alpha", 0.8f, 1f);
                breatheText.setDuration(1300);
                breatheText.setRepeatMode(
                        android.animation.ValueAnimator.REVERSE);
                breatheText.setRepeatCount(
                        android.animation.ValueAnimator.INFINITE);
                breatheText.setInterpolator(
                        new android.view.animation.AccelerateDecelerateInterpolator());
                breatheText.setStartDelay(300);
            }
            if (breatheTitle != null && !breatheTitle.isRunning()) {
                breatheTitle.start();
            }
            if (breatheText != null && !breatheText.isRunning()) {
                breatheText.start();
            }
        } else {
            if (breatheTitle != null) {
                breatheTitle.cancel();
            }
            if (breatheText != null) {
                breatheText.cancel();
            }
            if (titleView != null) {
                titleView.setAlpha(1f);
            }
            if (textView != null) {
                textView.setAlpha(1f);
            }
        }
    }

    /** v171: live lyric line on the open music card. */
    public void updateMusicLyric(String lyric) {
        if (musicLyric != null && data != null && data.isMusic) {
            String next = lyric != null ? lyric : "";
            CharSequence curSeq = musicLyric.getText();
            String cur = curSeq != null ? curSeq.toString() : "";
            if (!next.equals(cur)) {
                // v174: the dim second line keeps the previous line,
                // so the lyric visibly advances instead of swapping.
                if (musicLyricNext != null) {
                    musicLyricNext.setText(cur);
                }
                musicLyric.setText(next);
            }
        }
    }

    /** Card text colours, following the pill's light/dark flip.
     * (v162: the open buttons are gone, so there is no button
     * section any more — only the text colours remain.) */
    private void updateCardColors() {
        // Card text follows the same light/dark flip as the pill.
        int titleC = pillLight
                ? Color.argb(255, 26, 26, 26) : Color.WHITE;
        int bodyC = pillLight
                ? Color.argb(204, 60, 60, 60)
                : Color.argb(235, 255, 255, 255);
        int subC = pillLight
                ? Color.argb(180, 60, 60, 60)
                : Color.argb(217, 255, 255, 255);
        if (cardTitle != null) {
            cardTitle.setTextColor(titleC);
        }
        if (cardBody != null) {
            cardBody.setTextColor(bodyC);
        }
        if (cardAppName != null) {
            cardAppName.setTextColor(subC);
        }
        if (cardTime != null) {
            cardTime.setTextColor(pillLight
                    ? Color.argb(160, 60, 60, 60)
                    : Color.argb(179, 255, 255, 255));
        }
        // v171: music card text follows the same flip.
        if (musicTitle != null) {
            musicTitle.setTextColor(titleC);
        }
        if (musicArtist != null) {
            musicArtist.setTextColor(subC);
        }
        if (musicLyric != null) {
            musicLyric.setTextColor(titleC);
        }
        if (musicLyricNext != null) {
            musicLyricNext.setTextColor(subC);
        }
        if (musicCurTime != null) {
            musicCurTime.setTextColor(pillLight
                    ? Color.argb(160, 60, 60, 60)
                    : Color.argb(200, 255, 255, 255));
        }
        if (musicTotalTime != null) {
            musicTotalTime.setTextColor(pillLight
                    ? Color.argb(160, 60, 60, 60)
                    : Color.argb(200, 255, 255, 255));
        }
        int iconC = pillLight ? Color.rgb(26, 26, 26) : Color.WHITE;
        if (musicPrevBtn != null) {
            musicPrevBtn.setImageBitmap(renderIcon(ICON_PREV,
                    iconC, dp(24)));
        }
        if (musicNextBtn != null) {
            musicNextBtn.setImageBitmap(renderIcon(ICON_NEXT,
                    iconC, dp(24)));
        }
        updateMusicPlayIcon();
        if (musicPlayBtn != null) {
            GradientDrawable playBg = new GradientDrawable();
            playBg.setShape(GradientDrawable.OVAL);
            playBg.setColor(pillLight ? Color.argb(36, 0, 0, 0)
                    : Color.argb(36, 255, 255, 255));
            musicPlayBtn.setBackground(playBg);
        }
        styleMusicSeek();
        updateMusicFav();
    }

    private String loadAppName(String pkg) {
        if (pkg == null) {
            return "";
        }
        try {
            android.content.pm.PackageManager pm =
                    getContext().getPackageManager();
            android.content.pm.ApplicationInfo ai =
                    pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label != null ? label.toString() : pkg;
        } catch (Throwable ignored) {
            return pkg;
        }
    }

    private static String formatTime(long postTime) {
        if (postTime <= 0) {
            return "";
        }
        try {
            return android.text.format.DateFormat.format(
                    "HH:mm", postTime).toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Live lyric update (v76): the MediaSession listener in the
     * module process sends the new lyric line while this capsule is
     * pinned. Updates the stored lyric and the visible line. */
    /** v239: download progress bar. pct is 0-100; -1 hides it.
     * Also stores the pct in the data so a rebuild restores it. */
    public void updateDownloadBar(int pct) {
        downloadBarPct = pct;
        if (data != null) {
            data.downloadPct = pct;
        }
        syncDownloadBar();
    }

    /** v239: show the bar only for a download capsule with a
     * known percent, while the pill (not the card) is showing. */
    private void syncDownloadBar() {
        if (downloadTrack == null || downloadFill == null) {
            return;
        }
        boolean show = data != null && data.isDownload
                && downloadBarPct >= 0 && !cardExpanded;
        if (!show) {
            downloadTrack.setVisibility(GONE);
            downloadFill.setVisibility(GONE);
            return;
        }
        int pct = downloadBarPct;
        if (pct > 100) {
            pct = 100;
        }
        downloadTrack.setVisibility(VISIBLE);
        downloadFill.setVisibility(VISIBLE);
        downloadFill.setScaleX(pct / 100f);
    }

    /** Live title update (v98, downloads): replace line 1 in place
     * ("正在下载 45%"), and keep the stored data in sync so a rebuild
     * shows the latest percent instead of the stale one. */
    public void updateTitle(String title) {
        // v329: a changed music title = a new song — replay the
        // entrance (option A). Only when the title actually
        // changed, so progress refreshes don't re-trigger it.
        boolean musicTitleChanged = data != null && data.isMusic
                && title != null && !title.equals(data.title);
        if (titleView != null) {
            titleView.setText(title != null ? title : "");
        }
        // v243: the open card shows the same title in cardTitle
        // (filled once at expand) — refresh it too, or the card
        // stays stale while the pill updates live.
        if (cardTitle != null) {
            cardTitle.setText(title != null ? title : "");
        }
        if (data != null) {
            data.title = title;
        }
        if (musicTitleChanged) {
            playMusicTitleEntrance();
        }
        // v355: 换歌词/标题后重新选中，跑马灯才继续滚（否则长标题
        // 只显示前半截就停住，看着像被截断）。
        applyScrollFlag();
    }

    /** Live text update (v95, downloads): replace line 2 in place,
     * and keep the stored data in sync so a rebuild shows the latest
     * percent instead of the stale one. */
    public void updateText(String text) {
        if (textView != null) {
            textView.setText(text != null ? text : "");
        }
        // v243: the open card shows the same text in cardBody
        // (the download progress line) — refresh it too, or the
        // card stays stale while the pill updates live.
        if (cardBody != null) {
            cardBody.setText(text != null ? text : "");
        }
        if (data != null) {
            data.text = text;
        }
        // v355: 同 updateTitle，换正文后重新选中，跑马灯才继续滚。
        applyScrollFlag();
    }

    public void updateLyrics(String lyric) {
        if (data != null) {
            data.lyrics = lyric;
        }
        rollPinnedLyric(lyric);
        updateMusicDisplay();
        // v171: a live lyric also refreshes the open music card.
        updateMusicLyric(lyric);
    }

    /** v204: pinned capsule lyric rolls up (old line slides out,
     * new line follows in, 320ms) instead of swapping text in
     * place. A long line marquees horizontally once it settles
     * (the marquee restarts on the settled line); the marquee is
     * stopped before a roll so it never scrolls mid-slide. If the
     * lyric line is hidden or a roll is already running, the text
     * is swapped directly. */
    private void rollPinnedLyric(String lyric) {
        String next = lyric != null ? lyric : "";
        String cur = lyricsView.getText() != null
                ? lyricsView.getText().toString() : "";
        if (next.equals(cur)) {
            return;
        }
        if (lyricsRoll.getVisibility() != VISIBLE || lyricsRolling) {
            lyricsView.setText(next);
            return;
        }
        int lineH = lyricsView.getHeight();
        if (lineH <= 0) {
            lineH = dp(24);
        }
        // Pin the roll window to exactly one line so the incoming
        // line is clipped until it slides in.
        android.view.ViewGroup.LayoutParams lp =
                lyricsRoll.getLayoutParams();
        if (lp.height != lineH) {
            lp.height = lineH;
            lyricsRoll.setLayoutParams(lp);
        }
        lyricsViewNext.setText(next);
        // Stop the marquee so the outgoing line does not scroll
        // while it slides out.
        lyricsView.setSelected(false);
        lyricsRolling = true;
        final int h = lineH;
        lyricsStrip.animate()
                .translationY(-h)
                .setDuration(320)
                .setInterpolator(
                        new android.view.animation
                                .DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        lyricsView.setText(next);
                        lyricsStrip.setTranslationY(0);
                        lyricsRolling = false;
                        // Restart the marquee on the settled line
                        // (a long line scrolls, a short one stays).
                        lyricsView.setSelected(false);
                        lyricsView.setSelected(true);
                    }
                })
                .start();
    }

    /** v264: apply the scroll switch to the capsule's title and
     * text — long content scrolls (marquee) when on, truncates
     * with ... when off. */
    public void applyScrollFlag() {
        if (titleView != null) {
            titleView.setSelected(FeatureFlags.scroll);
        }
        if (textView != null) {
            textView.setSelected(FeatureFlags.scroll);
        }
    }

    public void setOnTap(Runnable onTap) {
        this.onTap = onTap;
    }

    public void setOnSwipeRight(Runnable onSwipeRight) {
        this.onSwipeRight = onSwipeRight;
    }

    public void setOnSwipeLeft(Runnable onSwipeLeft) {
        this.onSwipeLeft = onSwipeLeft;
    }

    public void setOnDockSwipe(Runnable onDockSwipe) {
        this.onDockSwipe = onDockSwipe;
    }

    public void setOnCardDragListener(OnCardDragListener l) {
        onCardDragListener = l;
    }

    public static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private int dp(int v) {
        return dp(getContext(), v);
    }

    // v1 Flyme balloon PNG (128x128, from 1.x fixed108, traced from
    // the user's balloon images). Pinned = blue balloon.
    private static final String PIN_BALLOON_ON_B64 = "iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAYAAADDPmHLAAAYTklEQVR4nO2dfZRcR3Xgf7fqve6e7hl9Yoys2DiwGJCCs8YGAmtOiOGYBcfmYBiRQDAgdrXOJpgVxIFYH622JNsxOAewNwGbbPjwCTDjLyCg4ARwyBqzioGNsSWjjQ+WrWDLtixLmu7pfu9V3f3jvZ4ZSSM0Hz3f73fOnJnpj9fV7966deveW1XCnEOFKsIuhF5gjbjjXlLVwqndhPufhFO6kRbNFyglkxAvMtachWc5HlAviFEsQsyTKnoIg+ATpVgCz1ONxeziw9JaUdXyER9fNrC18FmA8qbWfSrm7kEJtrIMyxVEiOg034xJIzPdgDGjKqzB0H+0wCubkw8pNK2392ghWe29vhzMZaJaQRUUg7AMMCgFCqGIAUaKSkDd8Y+RxAlGHsXpwyp0SRC+0fv4cjG6k2b8ZOOa7ieOamNVDbsQ+vEwN5Rh9itAVQ1gqEkCwA3aVWrwfCMsJYovMoVwm0YO1Kt0hYKAxqTClOy31/QPVVDvEY4XjiIcez+MEaxFAsCDRolKMRB1QJI8huEebLAX5x4qddlvH/iYHBl6b59aHkKo4WazMsxCBVChD0M/QD/0r3EAi6/VpUkz2Y6R38XpckxQlhC0FSuIIALet/uxYeR3G/pLxvt9FdVUewRALKhDMQShSJhd24Mm7pcoO1X05oYPvk9NmkNX6VV7rOWaLcwuBejts22Bt+mqRudZay9T7y+VQrBSY8B7UA+qDhGbvlJ1AgKeOKoe8AAIgg2tBICCj5J/M9b8xKve3Thgb+VGaQFQ1YBd/Xrsd5xJZpECqLRNZbnqPimWF5G4ACMXSSkw2gKS2IGaVM7TKOyxkFoKDwhhaCQABHzLPYjVKxtJcDc18dlrhS3I0P8zyAzfxMyjr4kHla5q/CojdrMUzUVZ30JbDtQngEVmmdBPRGod0qGjEAYCqHM/VeEu9fbuwZr8CEgtwhbcTM4eZuiGZuP8Qyg18ZVN8TUUzduI3EukFIY6GCdDbRPMrOvt40HVgwqFgkgAxODx/yjOb6/XwnuAVBFmyFmc/htbVTNk+tbdH5ZXnPPnEsh6EIiTY8b1ecSwz2CkHBptJnUJgz/ReOCOeq3nKWBYERSmyypMnwL0qqUPj4h2f+Tw8/zSnvOI44+ZcvgGrcfDztRc7u1jJVNy6Q7wA26XWP5Kmo2vDVy76Onpbsr03Ow+te2IXbkWvwXMF8WaU1Agiudnjz8pqiiOIAykCNpy+9T7TzcIb+qG7gF4djqcxKlVgDSIAzXx5Y3xW6VkPkai5yPWpB49QhqXW7i0h4YgDMSCGh5hMLmjvjX8U6paoCbRVH781CmAqrTHscpWfQOJ+4aUbI824nRsmyse/XSRKYIUwkDVP6st//uN7eHdqc+ETpWDODVC6FXLKrQrjl9pCmazhPZijRLw89TB6ySqjiCw4CP1/NdGbeutUPNHOc8dpPMKMCLsWd4cP2QqwSodiByIyXv9GFGvGCNSDPCR+0FD7UXUZCCdJWQ5kQ7RWYFkzl5xw+Cv22J4iygXkHhHOgOeFtpfaNZmX8ZM6iRKOQy05e5zcfSu5vby453+lM4pQKadXZuiV5nAfF1Cu0IHYz8dTp4IWEmFHrv0SwUG3NzXAlBNpBQGmrh/V69rGwT38ATKzSSd8As6I5x1GlKTpLy59QFTCP8ZzAodjNxUCl8AI6mgowQO1eFwA4oBhBYOt+aDFQBEAm3GDpWVpiv4TsUnV3GzxPR1RnaTtwBZzy9vaH3QlAqf18SB91PW80VSwccOIgcuhjOfB28+y/D6M4XXnZFOPj51r/LZ+zxhmOYJ5zzeewILwoAmXNl42P41q5DJ+gSTU4BszO/a2LzUFIu345zHeaZC+EbAGmjG6c/yCqxcJFz+GmHN2Ybl5ePfc9YnHY8eVEphVhMy11FVREQWBfiD0fsb24tfnGysYOIK0Bb+Vc23m1J4O0493hnEdNSxbJv6gQh8BM9fAte/1fDGFxtO7U7NPYAbMUESgf0DcPanEgaidJiYD/IH0nhBYDwiz2gc/5fG1tK3JlNwMjFhDQm/vtIUi3tQKeNcR81+u8dHCTQieO0LhXNXCv/9t4SXP3+42YlPHcD2BFOzUrDDTXjVTQl7n4OCnUcKAKklCELBgLZaFze2lf5uZLh9PIx/elZVwxpxrH+syxSKfyvGlrXD8fzApD3eRVAqweffYVh7nhkSsvOpgpA5gW3ajzsPi0vwn84U/t+/pEOAm/HSiw4iIiSRx1okDO7s+Xhz9ZE1smciSjA+C5BFoxZ//LmlrtRzOwXzOzrYOeGbrCcfHoBzzhB6f0O48Ky05yvDAjbH9HavWRHgiG/zjd3K7/2tw9p54gSOhqqTQmDV+Z2lun3TgesZGG+l0TgUICviuO/xQqXntK9KxV6iA3FMWho5aQIDgwlELbj8dYZPvtVQKaTPOU3N/EicT4eIkfyv+z1f+rHiBXY/pRxuQiGYxwoAoN5JpWB9PflawwTvZmQF9RgYuwJk5qW8MfquqYQXaKNzwg8tPFeHM5bCljcZPnBeKtnkmB4PmRUwacP3PqesudXz2jOFRw8qX/+ZQmaLusJUQea18NuoxtIdhnok+mR9W/HK8YSMx6YAmZdZ3jT4ZlMq/b02kwSZgP8wCkbgSB3e9FLhc5caXrRMUsfOHN24kVYgcvDN3cr1/+TZuVeHwlmLukjL/8mWAHSigXMCVYw4jChO/3N9S/D90RbRjMbJFSBbmFGO66dIoXQvVl5I5CY9129/sFe44MXCre+yLC5B7CE85sojhX/LTs+NP1R+tl8xAj3FTNA6T0K/E0XVSSm02ox/Ut9aODe12CdfoXRyBWj3/s1JnynbXh2IHMZMyulrR/MON+Dr77Ncsipthtejzb1q+pg1cNuDyifu8ezcp1gLlcLw8zkZbSUYTK6pbws3jCU+8KsVQFUQ6N4Uv85bc7dACe8nVbcnko7tzUF429nCXe+1eE0bMvKqI5Xh//5SueRLjsefhSXdqR+QC34UVBVrUXRAQvuS+gbZP7IwZzROLMisEqVSHTgFSvsQG+ISnUxOXyTttYuL8JlLLO/4DUmFL0encZNsGNh3CNbd6fj+I+nKrEKQPpfzK1D1hIHR2O3paTzzyv3dLxhkC3oiJTjBOK7CagREVUs3SBiEuNhNtqDDAPXBVODn/dqwkzbyokIq/EeehfM/m7BjtxIYsDYX/pgQMcSxM5XgrIGuZZupiaf/xJnD0QWaBXy6NkWft5Xwg1rvTLCnHsG6VwuXv8awcpFwSvdwI1y2eLeZwA0/8Ny80/NUPR3rc8GPF1UwTgo2cHH8msFaYeeJ/IHjp3Kq6aLqZVqUA+7tGnkPasYbNDzqQwwMtOAvLjKsP390ZbRZaHfDdzyf+UdPeUk6l8+FPxFEUC9gVVSup/rghcCocYHjpdGPQUQrT0dXm6JdRuz8ZDJ8gUmLNV5zhrD+fEPis96ePe80FfIvnoXLvua4Zadn8bJUIXJHbxKIWG3GKoXgt0v+RecC6YzuGI5WgCzRU94UnUtor9DYeYQJm34jMBjBS0+FW95ucJlnb0c4fTZL6Hz0244v36dDjl4u/A4geLGoEfsyauJZenyHP/YBk039LpCiLeHS6o4Jf75AksBVF1hWnSrpyNRO5JBO5wYi+NT/9ux4WFm8NDf5HcZo7MDY9d1/1nwZN0ucDfHDLzjq5auz6YKX09Ll2RMf962BehNe/x+E956ThndHpm59Fu79w7sc6+/whPkUr/OIGGKPiH2Fdoc/rWyKr0Y4aigYdgJ7syKPqp4n1l2uzcmZfyHN5689V7LdVYafa0f3Hj0I39qtVHoWUtx+mhEB5xxK0Zvg70CUVcMpsuPHBO+2SmBLqJvwlis2c/w++kbDe88xeIVghOnXzPH70x2Og42sZCvXgKlCUBWMeAslAHaN2DUp/ZVtz1JVU/HxPoJgBS6ZkAK0Tf/Fq4Q722FeGd6wqy3oC//a8b1HlO5i7vBNOeo9hYLRKHmo8bzg3HRPQwDJVu9mNeYVdb8vRXsqzrmJ9n7V1Lm76nfSS7fj/O2/jcBXH1C+u0fpKeXCnxbEGKJEJQxWdz0Xn42I0pvKPJXSQ2hqBXS9YiZcQxsYONKAta8WzlspuBGOn/OpIty7V1nb5+juSh3BnOlCnQSodeZMAFal/TLIskX+hVUtPeM5jYT27prjQiQN456xHK5/iz0qwdPGCNx0n6cVQykP8c4EoqLnAv2sTnu5YU2/AXja1V9OIMvxE8v4WYFWBBe8OF2kMVS5y7DX/8CTyo6fK+WueValOzewGoN63n/qJ56osEYcqmJYdUrqDpjCVVKwBVQnvomhwm+uOLocq71j62AMa2/zHG7Os4UacwURIYlVQnvqoYHlvwnAGoyh9gaHqqCcrTEwwQWjmm3QeuZSOcr8t3v6t3/u+fFeZVEp7/0ziJNAsGp+HVRYhRgQLW7hhRhzOs5lO3VNEEkXZPisPs9nPyJw+4Oa7rTbwW+TM06GJOsvBVFWk04DjXdvlS7bhffJhGP/aSSBL/3EYyRdjtX+fd9jyp0PKuVi3vtnFMVorID8Fh97djFrxAUABv9mvIVJBP+dh0oJvvwTZXmX5z3nCMUA7t+nXPUdn27hnad4ZxYRg0scxfC0snbf1lC9MKBXrZK8jNT1m1TJlyp0FdKKnpt+lDp79SYUCxAGufBnCwKomK8iooZ+PCJxpy6uCovKw7X9i8rpyp881j+LEEB0P4BZfiXdon55ttH5pCxAG+dHVPz4XPizCkUxgOdCANPscpdIIXwBSTKp4o+cOYOkw72+AsAo+up05p/30wWCaFrss4KqGiPQPdMtypkRslxAFgvIWZgYVTkw043ImTkME9knKGfeYEY9RDFnISDQqa1ic+Yeki4VyxVg4aHZ2cmHqOUzgIVIFgdgOVUW+Hk9C5t2HCBnQSK5E7jQiSCfBi5EVCwIuo+aeIPSkd0+c+YMigFV2QNgEPlX2ge35iwY1MvPAYzGdoePkzo2X6O7kBBjHYAZ/I88KbCHQNJqkZz5j4DiSgDp4Q/IY1lEIFeAeY8YIu+MsQ8C7Vog3UeuAAsAdVIKjG+5H9Rr8j2qalIFQPbk5n8BoCgWRORHoMITP7ZpLYDHYbOIQD4XmL+IWG06543eAaIcVG+oqrGSfI9B9xTpCfa5JZiPqComEHX67KLuA7sB6MOb5Q0qA9tKuzX2O6QUyKSWh+fMZryEgOgD+698QYOqGkTUHChTR1VEklu05WJkcodB5Mx29IvZKSIGjtmpvbwx2iNh+GKSmMluDZ8zm0jNP87Vg7h++qHrlhxsHySRTv7Sc4FUjBwkRIB8Efd8QvESCop+89B1Sw62zT8ckw72olcQu4NYI7kzOK9I1wMKD2T/D8k9/aMmnl61g7XCjzTy/yQFa9CJHUacMwsREY1RUc4HoMaQbIctwCqEqhqx5p70gdwAzAtUnZQCq3H8lbp5ujfdKHr4/KCRQ4CnJt6JvVNbiUtnA/kwMKdRPIE12kwe7VkUrqO2skHf0f7dsALUxFNV09zC4+r1+1KwgubO4JxG1UvRCOq/uP9KqbNOw2NPDzu+JlBEjforNHFusqeD5swkqgTG+qY77Lz7G1SFFRzn1x0t4Jp4+tQObCvtxvm/ySKDYz6JOmcWoXgpWCVJtjW3l/eyBTvasfKjHRoFqCh8VhMmfVZgzozh1WCMFH8IKuwa3as/XgH60gOHTan5mMZxizDIncG5hqqTShjqYPwPEvMwVYT+0f25UX0AgIFSz4CY8LXE7ptSsAp5kmhOoOqlYI02/bcqHLz0yHVygC3oiU4RP7GTdzpRvSYPINwqRWPyGcEcwRhVj7rY/NHTtVMH6FP7qw6PPrECPITSpxbcwzroHyAMQ1RzJZjNeE2kbK0mrr+5XfZS1SCt+TwxJ3HwsrOEPrSnWF72on+R0L6CVmfOEc7pMKpeQmvU+z0aBW9ohOwHGM3zH8lJ5vmiVDXgxrNaOPc+vGsRGEHzTV9nHSKqaOxV39e4Rp5gF3Iy4cNYFofWJKFXbWNb8ac+TnpFxCDG5TODWYRqIoXAEvsnB7eE/4eqmtFOCh+NsUX6+sVR1WBwa+mbPkr+p3QHASc4jTpnmlF1hGEAtEC+M+6znsbxSUIVS02SStVdLSWzSQcTBxM/XTRnknjvpatgfOR2G+w7B2qyi6qasZj+NuPUFxWq6dhSqcbXSxhcqblTODOo9xIGqNd/pWXfUr9W9o9X+DChVQAq9PYb+te4yqboFxTCM2lFHmPyxNG0oYB4wsBIo7V64NrSLqoaUJNxD8sTEJooq3oVVVF4J+ojAmvQ3CmcFlQVpJUu8YqumYzwYTLrgPqy08Y3RH9ouuyNRN6hGuQp5ClEVRERWRzgD8V9jasL72rLYaKXnFym73Ma8t8krmyKrpAl4af1UALqFTF5BrHTqFcCi3o9jJFtobefPwSH2YL+qlDvyZicoFSFLQgDdFUWu4s19jeaYvi83DHsNKpgnARifewvamwNd3TqypPbKDrVPEW1UZfgq+VqUlK4AWuXkTiPyYeDSZPmXxKp2IIeif68sb24gw9pkRuJTpThGw+dEZAAVQ0ateALxLxc1f9MyqFBNcqdwwmiqqh6bGhkcVjwjfi2+vbix1mnYaeEDx3bJ1CUmiSs07Bek6fEJx/G80vpCgsEoeDzLOK4UFWMFQqhQdxeGv6mkoQfRFX4HEmnhA9TsRtAFoxYtP65ZW5R9/sRPiKhXanNOEEkP5vgZKgqRsBKLJ7/MdBjv8BHZTB7UjopfJiq7SBGRKR6NjZf6sLgdlOwq3XQgbp8ljAa6VjvsMZiRDX2Fw85e31qWZOW6nX6Y6fGSauJT8PGGhzZVvp549/3v1pbyRcIpUlQELwmeTYxIxvrpRga6QpDAovE7orG1nAHVS2ASraR15Tcr6nvib1q6Re3+MMHl7gllSvU2HVSNCt10IN3DsEs2PMKVROMCSS0aOJ+qIa7JGJvfVvQ175vU92EabrxKnyCMo+RdJUay01PaT2wTqxZpK0kTWkupLhBttZCusJAE9cS79cPbCn8FUBlY/IH9VNsPx+W1nQ0ZcZ6Xle1/mtGiu9A+AsJrNHBOMGke1jNVJumHPVpKLcUgoCP/I3Gx385UCs9nAXV7ERj+hNl+m+2qtDf3qASKtX4AqzZIgXz+mxYSEj3MJ4/QaS2gxeEIepQka+QuHsaWws3AxwVz59ASncyzFxvG6nx6+4PKyvPuRHhXRKaJZoAUZwdaD+XhwZVFE8htBKCb/n96v26wVr4DaC9M8tJCzenkpk3tyOcndKGxum22LUG794jRXsOHrQZ69xcnpbuwyRdFm26ezHc5qLo9ub28uNUNY2HTLO5H41ZcmNV6GW4kHGdhpXT3BoV3iZie0lij+KRueAjqKI4jAhGvHr5TGOLuXIoYzdN3v1YmV03cxRHqFyN/sSUwk/gQSMFn8zOGUO6v6JgrJHQQAC+EV3WuLr4ZfrU8hDCFtxkUrdTwexSgCGyAlSAmiSVDfGbCM0fI/rbEtol2pxFU8dstZSUQoMHVd9Uz18a5+4Y2Fq4dyqjeJ1glirACEZ4xaVq4wwrYRUxayU06GCcRhxnJLScmfowDCQAjfw1iL8nMcG/tTbJL7LXdDx232lmvwJAOm4CQ87ipui1NjAfEWvfqQ5IprEAJU1ve4y1UjJo5Hbh2Fa/OvjK0GuqatiFzKax/kTMDQVooyqsGXYWy5tbHxAbbBVrVmqUuCy9YKZk1pAK3hFkPT7xR1A+Vz/y1BZuWFFPF9KSLqqdwWndeJlbCtBmZA+rPrOoLEs+ZIp2Gx40IbMIHcwxqCbYIJCioC33hMKfqdrvDtZkH8BkCzNnkrmpAG1G3PjKxuQPCOU9eL9aisHp2sqiipOZOqp6BJVyaH3LPSOwlebg1+rX9uwf/vzZ6+CNhbmtAMDI1UoAiz+uS5Nici3GvFsKpkeb7azjOH0EVUchTN+j/mbbMtcf3i6PAKnge/GzbUo3EeaBAmT0qaWfIUexZ2PzJb4QfgTv3y2FYJEOxv6k+YV0SqcIVkohPnI/U9XNg7XwLgCqGszGufxkmD8KMMTRUcXKxtZqDe1nxNgLsvyCjKoI3nuKBSMWNPYJqvebpv3dI9fJgSx06+eSczdW5qECZKSJFtOOKpY3x5823cEV2gD8MSF4VS9dodHI7RTR6wiC3QMb5GFgTjt4Y2H+KkCbXrWsQnmWsHu5W6vKBxFeifeACKpKYBXl68HBw2sPfXrpc0PvzQ5VmLG2TwP/H8VirYVnF2dHAAAAAElFTkSuQmCC";

    private static Bitmap pinBalloonBitmap() {
        try {
            byte[] data = android.util.Base64.decode(
                    PIN_BALLOON_ON_B64, android.util.Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Semi-transparent capsule tinted from the app icon (user
     * 2026-11-18 "胶囊改成半透明根据APP图标自动调整颜色"): the
     * dominant colour of the icon becomes the pill background at
     * 80% opacity, and the text flips dark/light by luminance plus
     * a soft shadow halo, so the notification content stays
     * readable over any background showing through. No icon (or no
     * vivid colour) keeps the default dark pill.
     */
    private void applyIconTint(android.graphics.Bitmap icon) {
        if (data != null && data.isCall) {
            // Call capsule (v103): green pill, not the app-icon tint.
            pillColor = Color.argb(217, 34, 139, 34);
            if (callRed) {
                pillColor = Color.argb(217, 180, 40, 40);
            }
            pillBg.setColor(pillColor);
            pillLight = false;
            titleView.setTextColor(Color.WHITE);
            textView.setTextColor(Color.argb(204, 220, 230, 220));
            return;
        }
        // v465: 胶囊自动取色开关关着时，不取图标主色，全部用默认
        // 深色（跟没图标/没鲜艳色时一个 fallback）。来电胶囊上面
        // 已经 return 了，不受这个开关管。
        // v466: 用户 2026-11-23 “白色吧”——开关关着时改成白色，
        // 不再是深色。没图标/没鲜艳色时（开关开着）还是深色兜底。
        int color;
        if (FeatureFlags.autoTint) {
            color = dominantColor(icon);
            if (color == 0) {
                color = Color.rgb(30, 32, 38);
            }
        } else {
            color = Color.rgb(255, 255, 255);
        }
        pillColor = Color.argb(204,
                Color.red(color), Color.green(color), Color.blue(color));
        pillBg.setColor(pillColor);
        // Card keeps the SAME tint (user 2026-11-19 "长大不改变底色").
        if (cardBg != null) {
            cardBg.setColor(pillColor);
        }
        double lum = 0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color);
        boolean light = lum > 140;
        pillLight = light;
        updateBorder();
        titleView.setTextColor(light
                ? Color.argb(255, 26, 26, 26) : Color.WHITE);
        textView.setTextColor(light
                ? Color.argb(204, 60, 60, 60)
                : Color.argb(235, 255, 255, 255));
        lyricsView.setTextColor(light
                ? Color.argb(255, 26, 26, 26) : Color.WHITE);
        // Soft halo so the text stays readable where the app behind
        // shows through the translucent pill.
        int halo = light ? Color.argb(120, 255, 255, 255)
                : Color.argb(160, 0, 0, 0);
        titleView.setShadowLayer(2, 0, 1, halo);
        textView.setShadowLayer(2, 0, 1, halo);
        lyricsView.setShadowLayer(2, 0, 1, halo);
        updateCardColors();
    }

    /** Most frequent vivid colour of a bitmap (downscaled to 16x16). */
    private static int dominantColor(android.graphics.Bitmap bmp) {
        if (bmp == null) {
            return 0;
        }
        android.graphics.Bitmap small =
                android.graphics.Bitmap.createScaledBitmap(bmp, 16, 16, true);
        int w = small.getWidth(), h = small.getHeight();
        int[] pixels = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] count = new int[4096];
        int[] sumR = new int[4096], sumG = new int[4096];
        int[] sumB = new int[4096], sumSat = new int[4096];
        for (int px : pixels) {
            if (Color.alpha(px) < 128) {
                continue;
            }
            int r = Color.red(px), g = Color.green(px), b = Color.blue(px);
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int sat = max - min;
            if (sat < 32) {
                continue;
            }
            int idx = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
            count[idx]++;
            sumR[idx] += r;
            sumG[idx] += g;
            sumB[idx] += b;
            sumSat[idx] += sat;
        }
        int best = -1;
        long bestScore = -1;
        for (int i = 0; i < 4096; i++) {
            if (count[i] == 0) {
                continue;
            }
            long score = (long) count[i] * (sumSat[i] / count[i] + 32);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) {
            return 0;
        }
        return Color.rgb(sumR[best] / count[best],
                sumG[best] / count[best], sumB[best] / count[best]);
    }
}
