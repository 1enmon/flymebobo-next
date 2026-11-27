package com.chenan.flymebobo;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * flymebobo v529/v531 — the fullscreen "it really turned on"
 * receipt animation, one builder for every trigger:
 *  - APP settings page (MainActivity decorView): the in-app
 *    silence button, and (v531) flipping the 场景模式 switch
 *    on — wei picked the fullscreen "B" design for the switch;
 *  - SystemUI (BubbleWindow fullscreen overlay window): the
 *    silence pulled up by the DND linkage (v529), and (v531)
 *    entering a game/video scene — wei: the scene entry plays
 *    the SAME fullscreen animation as the switch, so both
 *    scene triggers look identical.
 * v531 design (wei approved previews/anim-v2-preview.html):
 * the veil is a VERTICAL GRADIENT — transparent at the top,
 * darkest (~92% deep indigo) in the middle, transparent at
 * the bottom, no hard edges; the centre is a circular
 * ILLUSTRATION (assets, round frame + thin white ring + a
 * white halo ring) rebounding in, then title + subtitle.
 * Whole timeline ~2.8 s.
 * v533 (wei): the animation is NOT tappable and must play
 * to completion — there is no dismiss path at all. The
 * veil never consumes touches (the SystemUI window is
 * FLAG_NOT_TOUCHABLE, the in-app veil is not clickable),
 * so the user keeps operating whatever is underneath while
 * it plays. The caller attaches Handle.veil wherever it
 * lives and calls Handle.play(); the detach Runnable
 * (run exactly once, on animation end) is the caller's
 * removal hook (decorView.removeView /
 * WindowManager.removeView).
 */
public final class SilenceAnim {

    public static final String TAG = "silence_anim";

    /** What to show in the middle + which title to print. */
    public static final class Style {
        public final String asset;
        public final String title;

        private Style(String asset, String title) {
            this.asset = asset;
            this.title = title;
        }
    }

    public static final Style SILENCE =
            new Style("anim_silence.webp", "临时静默已开启");
    public static final Style SCENE =
            new Style("anim_scene.webp", "场景模式已开启");

    /** v532: scene EXIT receipt — same illustration as the
     * entry, only the title differs (wei: exit gets the same
     * fullscreen treatment, copy swapped). */
    public static final Style SCENE_EXIT =
            new Style("anim_scene.webp", "场景模式已退出");

    /** v533: silence EXIT receipt — the moon illustration
     * again, exit copy (wei: silence gets the same exit
     * treatment the scene exit got in v532). */
    public static final Style SILENCE_EXIT =
            new Style("anim_silence.webp", "临时静默已结束");

    private SilenceAnim() {
    }

    public static final class Handle {
        public final View veil;
        private final AnimatorSet set;

        private Handle(View veil, AnimatorSet set) {
            this.veil = veil;
            this.set = set;
        }

        public void play() {
            set.start();
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources()
                .getDisplayMetrics().density);
    }

    /** Subtitle for a timed silence: the real end time. */
    public static String subtitleTimed(long untilMs) {
        String endAt = new SimpleDateFormat("HH:mm",
                Locale.CHINA).format(new Date(untilMs));
        return "到 " + endAt
                + " 自动恢复 · 期间消息先排队，结束时补弹";
    }

    /** Subtitle for a DND-linkage silence: it has no fixed end
     * — it lasts while the system DND lasts. */
    public static String subtitleDnd() {
        return "勿扰关闭后自动恢复 · 期间消息先排队，结束时补弹";
    }

    /** Subtitle for the scene-mode animation (both triggers). */
    public static String subtitleScene() {
        return "游戏、视频中 · 通知先排队，退出后补弹";
    }

    /** v532: scene-exit subtitle when the exit flush actually
     * re-delivered queued notifications — the real count. */
    public static String subtitleSceneExitFlushed(int n) {
        return n + " 条消息已补弹";
    }

    /** v532: scene-exit subtitle when nothing was queued. */
    public static String subtitleSceneExitEmpty() {
        return "通知恢复正常";
    }

    /** v533: silence-exit subtitle when the end flush
     * actually re-delivered queued notifications — the
     * real count, same wording as the scene exit. */
    public static String subtitleSilenceExitFlushed(int n) {
        return n + " 条消息已补弹";
    }

    /** v533: silence-exit subtitle when nothing was queued. */
    public static String subtitleSilenceExitEmpty() {
        return "通知恢复正常";
    }

    // ---- 插画加载（圆形裁切，进程内缓存） ----

    private static final Map<String, Bitmap> ART_CACHE =
            new HashMap<>();

    /** Load an assets/ illustration as a circle-cropped
     * bitmap. In the APP process the assets are our own; in
     * SystemUI the module's classes run under SystemUI's
     * context, so fall back to createPackageContext (the
     * GamePackages / TempSilence pattern). Null on failure —
     * the caller draws a plain disc instead. */
    private static Bitmap loadArt(Context ctx, String asset) {
        synchronized (ART_CACHE) {
            Bitmap hit = ART_CACHE.get(asset);
            if (hit != null) {
                return hit;
            }
        }
        Bitmap raw = null;
        try {
            InputStream in = ctx.getAssets().open(asset);
            raw = BitmapFactory.decodeStream(in);
            in.close();
        } catch (Throwable ignored) {
        }
        if (raw == null) {
            try {
                Context appCtx = ctx.createPackageContext(
                        "com.chenan.flymebobo",
                        Context.CONTEXT_IGNORE_SECURITY);
                InputStream in = appCtx.getAssets().open(asset);
                raw = BitmapFactory.decodeStream(in);
                in.close();
            } catch (Throwable ignored) {
            }
        }
        if (raw == null) {
            return null;
        }
        Bitmap circ = circleCrop(raw);
        synchronized (ART_CACHE) {
            ART_CACHE.put(asset, circ);
        }
        return circ;
    }

    private static Bitmap circleCrop(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - size) / 2;
        int y = (src.getHeight() - size) / 2;
        Bitmap squared = Bitmap.createBitmap(src, x, y, size, size);
        Bitmap out = Bitmap.createBitmap(size, size,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(squared,
                Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return out;
    }

    /** The v2 veil: vertical gradient, transparent at both
     * ends, ~92% deep indigo (13,15,32) in the middle — the
     * stops are wei's approved preview values. The window /
     * decor behind it must be truly full-height (v531
     * edge-to-edge fix) or the gradient never reaches the
     * screen edges. */
    private static final class VeilDrawable extends Drawable {
        private final Paint paint = new Paint();

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            LinearGradient g = new LinearGradient(
                    0, b.top, 0, b.bottom,
                    new int[]{
                            Color.argb(0, 13, 15, 32),
                            Color.argb(140, 13, 15, 32),
                            Color.argb(235, 13, 15, 32),
                            Color.argb(140, 13, 15, 32),
                            Color.argb(0, 13, 15, 32)},
                    new float[]{0f, 0.22f, 0.5f, 0.78f, 1f},
                    Shader.TileMode.CLAMP);
            paint.setShader(g);
            canvas.drawRect(b, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(
                android.graphics.ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    public static Handle create(Context ctx, String subtitle,
            Runnable detach) {
        return create(ctx, SILENCE, subtitle, detach);
    }

    public static Handle create(Context ctx, Style style,
            String subtitle, final Runnable detach) {
        final boolean[] detached = new boolean[]{false};
        final Runnable fireDetach = new Runnable() {
            @Override
            public void run() {
                if (detached[0]) {
                    return;
                }
                detached[0] = true;
                if (detach != null) {
                    detach.run();
                }
            }
        };

        final FrameLayout veil = new FrameLayout(ctx);
        veil.setTag(TAG);
        veil.setBackground(new VeilDrawable());
        veil.setAlpha(0f);
        // v533: never consume touches — the animation must
        // play to completion and the user keeps operating
        // whatever is underneath (the SystemUI window also
        // carries FLAG_NOT_TOUCHABLE).
        veil.setClickable(false);
        veil.setFocusable(false);

        LinearLayout center = new LinearLayout(ctx);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams clp = new FrameLayout
                .LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        center.setLayoutParams(clp);

        // 光晕（白环）+ 插画圆图（细白边）
        FrameLayout discWrap = new FrameLayout(ctx);
        LinearLayout.LayoutParams dwLp = new LinearLayout
                .LayoutParams(dp(ctx, 150), dp(ctx, 150));
        dwLp.gravity = Gravity.CENTER_HORIZONTAL;
        discWrap.setLayoutParams(dwLp);
        final View halo = new View(ctx);
        GradientDrawable haloBg = new GradientDrawable();
        haloBg.setShape(GradientDrawable.OVAL);
        haloBg.setStroke(dp(ctx, 2), Color.WHITE);
        haloBg.setColor(Color.TRANSPARENT);
        halo.setBackground(haloBg);
        halo.setLayoutParams(new FrameLayout.LayoutParams(
                dp(ctx, 150), dp(ctx, 150)));
        discWrap.addView(halo);

        final FrameLayout artWrap = new FrameLayout(ctx);
        FrameLayout.LayoutParams awLp = new FrameLayout
                .LayoutParams(dp(ctx, 148), dp(ctx, 148));
        awLp.gravity = Gravity.CENTER;
        artWrap.setLayoutParams(awLp);
        View ring = new View(ctx);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        ringBg.setStroke(dp(ctx, 3), Color.argb(56, 255, 255, 255));
        ringBg.setColor(Color.TRANSPARENT);
        ring.setBackground(ringBg);
        ring.setLayoutParams(new FrameLayout.LayoutParams(
                dp(ctx, 148), dp(ctx, 148)));
        artWrap.addView(ring);
        ImageView art = new ImageView(ctx);
        Bitmap artBmp = loadArt(ctx, style.asset);
        if (artBmp != null) {
            art.setImageBitmap(artBmp);
        } else {
            // 素材没读到时的兜底：一块深靛圆盘，不开天窗。
            GradientDrawable fb = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(56, 58, 96),
                            Color.rgb(24, 26, 48)});
            fb.setShape(GradientDrawable.OVAL);
            art.setBackground(fb);
        }
        art.setLayoutParams(new FrameLayout.LayoutParams(
                dp(ctx, 148), dp(ctx, 148)));
        artWrap.addView(art);
        discWrap.addView(artWrap);
        center.addView(discWrap);

        final TextView big = new TextView(ctx);
        big.setText(style.title);
        big.setTextSize(21);
        big.setTextColor(Color.WHITE);
        big.setTypeface(big.getTypeface(),
                android.graphics.Typeface.BOLD);
        big.setShadowLayer(dp(ctx, 6), 0, dp(ctx, 2),
                Color.argb(115, 0, 0, 0));
        try {
            big.setLetterSpacing(0.08f);
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams bigLp = new LinearLayout
                .LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        bigLp.topMargin = dp(ctx, 26);
        bigLp.gravity = Gravity.CENTER_HORIZONTAL;
        big.setLayoutParams(bigLp);
        center.addView(big);

        final TextView small = new TextView(ctx);
        small.setText(subtitle);
        small.setTextSize(13);
        small.setTextColor(Color.argb(217, 255, 255, 255));
        small.setShadowLayer(dp(ctx, 4), 0, dp(ctx, 1),
                Color.argb(115, 0, 0, 0));
        LinearLayout.LayoutParams smLp = new LinearLayout
                .LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        smLp.topMargin = dp(ctx, 9);
        smLp.gravity = Gravity.CENTER_HORIZONTAL;
        small.setLayoutParams(smLp);
        center.addView(small);

        veil.addView(center);

        // ---- 时间线（毫秒，对齐 v2 预览 2.8s） ----
        AnimatorSet set = new AnimatorSet();
        // 幕布（整层 alpha）：280ms 淡入，2.35s 起 448ms 淡出。
        ObjectAnimator veilIn = ObjectAnimator.ofFloat(
                veil, "alpha", 0f, 1f);
        veilIn.setDuration(280);
        ObjectAnimator veilOut = ObjectAnimator.ofFloat(
                veil, "alpha", 1f, 0f);
        veilOut.setStartDelay(2352);
        veilOut.setDuration(448);
        // 插画：回弹放大（0.4→1.05→1.0）+ 淡入 + 上浮，
        // 84% 后随幕布一起收（自身再叠一层淡出，对齐预览）。
        PropertyValuesHolder aScaleX = PropertyValuesHolder
                .ofKeyframe("scaleX",
                        Keyframe.ofFloat(0f, 0.4f),
                        Keyframe.ofFloat(0.16f, 1.05f),
                        Keyframe.ofFloat(0.22f, 1.0f),
                        Keyframe.ofFloat(0.84f, 1.0f),
                        Keyframe.ofFloat(1f, 0.97f));
        PropertyValuesHolder aScaleY = PropertyValuesHolder
                .ofKeyframe("scaleY",
                        Keyframe.ofFloat(0f, 0.4f),
                        Keyframe.ofFloat(0.16f, 1.05f),
                        Keyframe.ofFloat(0.22f, 1.0f),
                        Keyframe.ofFloat(0.84f, 1.0f),
                        Keyframe.ofFloat(1f, 0.97f));
        PropertyValuesHolder aAlpha = PropertyValuesHolder
                .ofKeyframe("alpha",
                        Keyframe.ofFloat(0f, 0f),
                        Keyframe.ofFloat(0.16f, 1f),
                        Keyframe.ofFloat(0.84f, 1f),
                        Keyframe.ofFloat(1f, 0f));
        PropertyValuesHolder aTrans = PropertyValuesHolder
                .ofKeyframe("translationY",
                        Keyframe.ofFloat(0f, dp(ctx, 14)),
                        Keyframe.ofFloat(0.16f, 0f),
                        Keyframe.ofFloat(1f, 0f));
        ObjectAnimator artAnim = ObjectAnimator
                .ofPropertyValuesHolder(artWrap, aScaleX, aScaleY,
                        aAlpha, aTrans);
        artAnim.setDuration(2800);
        // 光晕：白环扩散（0.6→1.9），透明度 0→0.55→0（64% 走完）。
        PropertyValuesHolder hScaleX = PropertyValuesHolder
                .ofKeyframe("scaleX",
                        Keyframe.ofFloat(0f, 0.6f),
                        Keyframe.ofFloat(0.64f, 1.9f),
                        Keyframe.ofFloat(1f, 1.9f));
        PropertyValuesHolder hScaleY = PropertyValuesHolder
                .ofKeyframe("scaleY",
                        Keyframe.ofFloat(0f, 0.6f),
                        Keyframe.ofFloat(0.64f, 1.9f),
                        Keyframe.ofFloat(1f, 1.9f));
        PropertyValuesHolder hAlpha = PropertyValuesHolder
                .ofKeyframe("alpha",
                        Keyframe.ofFloat(0f, 0f),
                        Keyframe.ofFloat(0.18f, 0f),
                        Keyframe.ofFloat(0.32f, 0.55f),
                        Keyframe.ofFloat(0.64f, 0f),
                        Keyframe.ofFloat(1f, 0f));
        ObjectAnimator haloAnim = ObjectAnimator
                .ofPropertyValuesHolder(halo, hScaleX, hScaleY,
                        hAlpha);
        haloAnim.setDuration(2800);
        // 主/副文案：依次上浮淡入（淡出交给整层幕布）。
        ObjectAnimator bigAnim = ObjectAnimator
                .ofPropertyValuesHolder(big,
                        PropertyValuesHolder.ofFloat(
                                "alpha", 0f, 1f),
                        PropertyValuesHolder.ofFloat(
                                "translationY", dp(ctx, 8), 0f));
        bigAnim.setStartDelay(336);
        bigAnim.setDuration(340);
        ObjectAnimator smallAnim = ObjectAnimator
                .ofPropertyValuesHolder(small,
                        PropertyValuesHolder.ofFloat(
                                "alpha", 0f, 1f),
                        PropertyValuesHolder.ofFloat(
                                "translationY", dp(ctx, 8), 0f));
        smallAnim.setStartDelay(616);
        smallAnim.setDuration(340);

        big.setAlpha(0f);
        big.setTranslationY(dp(ctx, 8));
        small.setAlpha(0f);
        small.setTranslationY(dp(ctx, 8));
        artWrap.setAlpha(0f);
        artWrap.setScaleX(0.4f);
        artWrap.setScaleY(0.4f);
        artWrap.setTranslationY(dp(ctx, 14));
        halo.setAlpha(0f);
        halo.setScaleX(0.6f);
        halo.setScaleY(0.6f);

        set.playTogether(veilIn, veilOut, artAnim, haloAnim,
                bigAnim, smallAnim);
        set.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator a) {
            }

            @Override
            public void onAnimationEnd(Animator a) {
                fireDetach.run();
            }

            @Override
            public void onAnimationCancel(Animator a) {
            }

            @Override
            public void onAnimationRepeat(Animator a) {
            }
        });
        // v533: no click-to-dismiss any more (wei: 不可点击、
        // 必须播完) — the set runs its full ~2.8 s and the
        // end listener above is the only removal path.
        return new Handle(veil, set);
    }
}
