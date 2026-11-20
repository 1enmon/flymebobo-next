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
    public static final int DEFAULT_HEIGHT_DP = 44;
    /** Card (v143): the capsule grown into a card. Same view, same
     * background tint — only bigger. Width 300dp; height snaps to the
     * capsule grid (k*50-6dp) so the bottom lands one gap above the
     * capsule below and the 22dp corners fit. */
    public static final int CARD_WIDTH_DP = 300;
    public static final int CARD_PITCH_DP = 50;
    public static final int CARD_GAP_DP = 6;

    private final LinearLayout pill;
    private final GradientDrawable pillBg;
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
                    || tint != lastTint) {
                rect.set(0, 0, w, h);
                path.reset();
                path.addRoundRect(rect,
                        new float[]{0, 0, cornerR, cornerR,
                                cornerR, cornerR, 0, 0},
                        android.graphics.Path.Direction.CW);
                if (fade < 0.02f) {
                    paint.setShader(null);
                    paint.setColor(tint);
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
            }
            canvas.save();
            canvas.translate(b.left, b.top);
            canvas.drawPath(path, paint);
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
    private GradientDrawable cardBg;
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
    /** Music control callback (command, seekMs): BubbleWindow sends
     * the ACTION_MUSIC_CONTROL broadcast to the module process. */
    public interface OnMusicControlListener {
        void onMusicControl(String command, long seekMs);
    }
    private OnMusicControlListener onMusicControl;
    private Runnable onTap;
    private Runnable onSwipeRight;
    private Runnable onSwipeLeft;
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
    private boolean swiping;
    /** True while the window is widened for the current drag. */
    private boolean dragWindowOpen;

    public CapsuleView(Context context) {
        super(context);
        setClipChildren(false);

        pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        // Left padding 2dp (user 2026-11-19 "贴近屏幕这边"): the
        // notification icon sits close to the screen edge.
        pill.setPadding(dp(2), dp(6), dp(14), dp(6));
        pillBg = new GradientDrawable();
        pillBg.setShape(GradientDrawable.RECTANGLE);
        // The end against the screen edge (left) is NOT rounded — it
        // sits flat against the edge (user 2026-11-18 "贴边的一头不要
        // 圆角"). Only the end toward the centre (right) is rounded.
        float r = dp(22);
        pillBg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        pillBg.setColor(Color.argb(191, 30, 32, 38));
        // No border on the capsule (user 2026-11-19 "胶囊描边去掉").
        pill.setBackground(pillBg);
        pillWidthPx = dp(DEFAULT_WIDTH_DP);
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

        titleView = new TextView(context);
        titleView.setSingleLine(true);
        // v268: MARQUEE from the start (the lyric line does the
        // same) — unselected it shows "..." like END; selected it
        // scrolls. applyScrollFlag only toggles selection.
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        titleView.setTextColor(Color.WHITE);
        texts.addView(titleView);

        textView = new TextView(context);
        textView.setSingleLine(true);
        // v268: MARQUEE from the start, same as titleView.
        textView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
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
        cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
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
        LinearLayout.LayoutParams cardPinLp =
                new LinearLayout.LayoutParams(dp(24), dp(24));
        cardPinLp.leftMargin = dp(8);
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


        // v171: music card — dedicated player card for music
        // notifications (1.x buildPlayerView). Same background tint
        // as the generic card; shown instead of cardView when the
        // notification is music.
        musicCardView = new LinearLayout(context);
        musicCardView.setOrientation(LinearLayout.VERTICAL);
        musicCardView.setPadding(dp(12), dp(12), dp(12), dp(12));
        musicCardView.setBackground(cardBg);
        musicCardView.setVisibility(GONE);
        addView(musicCardView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
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
        LinearLayout.LayoutParams musicPinLp =
                new LinearLayout.LayoutParams(dp(24), dp(24));
        musicPinLp.leftMargin = dp(8);
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
        LinearLayout.LayoutParams seekLp =
                new LinearLayout.LayoutParams(0, dp(24), 1f);
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
                        break;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        // Horizontal-only slide (user 2026-11-19 "胶囊
                        // 全屏拖动取消"): the free full-screen drag is
                        // removed; the capsule only slides left/right.
                        if (Math.abs(dx) > dp(24)
                                && Math.abs(dx) > Math.abs(dy) * 2) {
                            swiping = true;
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
                            // never fired. Left just nudges the card
                            // toward the edge; ACTION_UP past -60dp
                            // pins it via onSwipeRight.
                            if (cardExpanded && dx > 0
                                    && onCardDragListener != null) {
                                if (!dragWindowOpen) {
                                    dragWindowOpen = true;
                                    BubbleWindow.getInstance()
                                            .beginDragWindow();
                                }
                                onCardDragListener.onCardDrag(dx);
                                break;
                            }
                            if (cardExpanded && dx < 0) {
                                setTranslationX(Math.max(dx, -dp(80)));
                                break;
                            }
                            if (data != null && data.isCall && dx > 0) {
                                // Call right swipe = hang up: the
                                // capsule turns red as it slides
                                // (user 2026-11-19 "手动右滑为挂断颜色
                                // 变成红色") AND grows the long tail
                                // like a regular dissipate (user
                                // 2026-11-19 "手动右滑你没发现没有拖尾
                                // 吗"). The tail is hang-up red.
                                if (!dragWindowOpen) {
                                    dragWindowOpen = true;
                                    BubbleWindow.getInstance()
                                            .beginDragWindow();
                                }
                                setCallRed(true);
                                growLongCapsule(dx);
                                break;
                            }
                            if (dx > 0) {
                                // The capsule IS the long element: one
                                // element, one background gradient —
                                // no seam possible. The pill grows to
                                // (180 + dx) wide, the gradient fades
                                // smoothly from transparent at the
                                // screen edge to the tint (80%) at dx,
                                // then stays solid; the content is
                                // pushed to the right end and the pin
                                // moves along.
                                if (!dragWindowOpen) {
                                    dragWindowOpen = true;
                                    BubbleWindow.getInstance()
                                            .beginDragWindow();
                                }
                                growLongCapsule(dx);
                            } else {
                                resetLongCapsule();
                                setTranslationX(dx);
                            }
                        }
                        break;
                    }
                    case android.view.MotionEvent.ACTION_UP: {
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > Math.abs(dy) * 2) {
                            // Left dock: left (toward the edge) =
                            // dock, right (toward the centre) =
                            // dissipate (mirrored, user 2026-11-18
                            // "你改成靠左边的").
                            if (data != null && data.isCall
                                    && dx > dp(120)
                                    && onHangup != null) {
                                // Call right swipe = hang up.
                                endDragWindowIfOpen();
                                onHangup.run();
                                return true;
                            }
                            // Left swipe = pin/unpin, including on a
                            // call capsule (user 2026-11-19 "左滑继续
                            // 改回钉住和解除钉住"). The v108/v109
                            // left-swipe speaker answer is gone;
                            // speaker is via the timer tap mid-call.
                            if (dx < -dp(60)
                                    && onSwipeRight != null) {
                                endDragWindowIfOpen();
                                onSwipeRight.run();
                                // v178: pinning the OPEN card via left
                                // swipe must spring the card back to
                                // its slot. The drag left it shifted
                                // (up to -80dp) and onSwipeRight only
                                // flips the pin state — without this
                                // the card stays stuck off-slot.
                                if (cardExpanded) {
                                    animate().translationX(0f)
                                            .setDuration(200).start();
                                }
                                return true;
                            } else if (dx > dp(120)
                                    && onSwipeLeft != null) {
                                // Dissipate: drag it out a LONG way
                                // (120dp) and then it dissipates
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
                        resetLongCapsule();
                        if (data != null && data.isCall) {
                            setCallRed(false);
                            if (!callAnswered) {
                                setAnswerIcon(0);
                            }
                        }
                        animate().translationX(0f)
                                .setDuration(200).start();
                        break;
                    }
                    case android.view.MotionEvent.ACTION_CANCEL:
                        swiping = false;
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
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
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
        final float target = dp(200);
        android.animation.ValueAnimator anim =
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
        anim.start();
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
    // end, and the pin moves along with the capsule body.
    private void growLongCapsule(float dx) {
        if (pill == null) {
            return;
        }
        int w = dp(DEFAULT_WIDTH_DP) + (int) dx;
        pillWidthPx = w;
        // One reusable background — no new bitmap per frame (that was
        // the swipe jank). The drawable reads its bounds and only
        // rebuilds its cached gradient shader when the width/fade
        // changes, so the swipe is just a cheap width + translation.
        if (longBg == null) {
            longBg = new LongCapsuleDrawable(pillColor, dp(22),
                    dp(DEFAULT_WIDTH_DP));
        }
        longBg.setTintColor(pillColor);
        if (pill.getBackground() != longBg) {
            pill.setBackground(longBg);
        }
        // Only touch layout when the width actually changed.
        if (w != lastGrowW) {
            lastGrowW = w;
            LayoutParams lp = (LayoutParams) pill.getLayoutParams();
            if (lp.width != w) {
                lp.width = w;
                pill.setLayoutParams(lp);
            }
        }
        pill.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        // Lock the texts to its measured width so it doesn't stretch
        // — the whole content (icon + text) must move to the right
        // end together with the capsule front. Once per drag only.
        if (!longGrown) {
            longGrown = true;
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
            pinView.setTranslationX(dx);
        }
    }

    private void resetLongCapsule() {
        if (pill == null) {
            return;
        }
        pillWidthPx = dp(DEFAULT_WIDTH_DP);
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

    public void bind(NotifData notifData) {
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
            String line2 = notifData.code;
            if (notifData.isPickupCode && notifData.cabinet != null) {
                line2 += " · " + notifData.cabinet;
            }
            this.textView.setText(line2);
            this.textView.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP, 20f);
            this.textView.setTypeface(
                    this.textView.getTypeface(),
                    android.graphics.Typeface.BOLD);
        } else {
            this.textView.setText(notifData.text != null ? notifData.text : "");
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
        // v239: download bar from the first-pop percent.
        downloadBarPct = notifData.downloadPct;
        syncDownloadBar();
        if (notifData.isCall) {
            // Call capsule (v103): green pill, answer button at the
            // right end, NO pin icon (auto-pinned but the badge is
            // replaced by the answer button).
            setCallTint(true);
            answerBtn.setVisibility(VISIBLE);
            pinView.setVisibility(GONE);
            callRed = false;
            callAnswered = false;
            callSpeaker = false;
            setAnswerIcon(0);
            answerTimerText.setVisibility(GONE);
            answerTimerText.setText("");
            answerBtnBg.setShape(GradientDrawable.OVAL);
            answerBtnBg.setCornerRadius(0);
            LinearLayout.LayoutParams ansLp =
                    (LinearLayout.LayoutParams)
                            answerBtn.getLayoutParams();
            ansLp.width = dp(28);
            answerBtn.setLayoutParams(ansLp);
        } else {
            answerBtn.setVisibility(GONE);
            setCallTint(false);
        }
        applyIconTint(tintBmp);
        updateMusicDisplay();
        applyScrollFlag();
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
        answerBtnBg.setCornerRadius(dp(14));
        LinearLayout.LayoutParams ansLp =
                (LinearLayout.LayoutParams)
                        answerBtn.getLayoutParams();
        ansLp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
        answerBtn.setLayoutParams(ansLp);
        answerBtn.setVisibility(VISIBLE);
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
        updateStackStroke();
    }

    private void updateStackStroke() {
        if (pillBg == null) {
            return;
        }
        if (!stacked) {
            pillBg.setStroke(0, Color.TRANSPARENT);
            return;
        }
        int stroke = pillLight
                ? Color.argb(120, 0, 0, 0)
                : Color.argb(160, 255, 255, 255);
        pillBg.setStroke(dp(2), stroke);
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
        return dp(DEFAULT_WIDTH_DP);
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
            if (cardCode != null) {
                if (data.code != null) {
                    cardCode.setText(data.code);
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
        boolean isMusicCard = expanded && data != null && data.isMusic;
        if (cardView != null) {
            cardView.setVisibility(
                    isMusicCard ? GONE : (expanded ? VISIBLE : GONE));
        }
        if (musicCardView != null) {
            musicCardView.setVisibility(isMusicCard ? VISIBLE : GONE);
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
        // "减少下面留白"). Clamp to 2..8 rows so a very long message
        // still does not make a full-screen card.
        int pitch = dp(CARD_PITCH_DP);
        int gap = dp(CARD_GAP_DP);
        int minH = 2 * pitch - gap;
        int maxH = 8 * pitch - gap;
        if (natural < minH) {
            return minH;
        }
        if (natural > maxH) {
            return maxH;
        }
        return natural;
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
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(3));
        bg.setColor(trackC);
        bg.setSize(dp(1), dp(5));
        GradientDrawable fg = new GradientDrawable();
        fg.setShape(GradientDrawable.RECTANGLE);
        fg.setCornerRadius(dp(3));
        fg.setColor(fillC);
        fg.setSize(dp(1), dp(5));
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
        if (musicLyricNext != null) {
            musicLyricNext.setText("");
        }
        updateMusicFav();
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
    private static final String PIN_BALLOON_ON_B64 = "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";

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
        int color = dominantColor(icon);
        if (color == 0) {
            color = Color.rgb(30, 32, 38);
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
        updateStackStroke();
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
        // No border on a lone capsule (user 2026-11-19 "胶囊描边去掉");
        // stacked capsules get one via updateStackStroke().
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
