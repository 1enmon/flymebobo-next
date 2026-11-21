package com.chenan.flymebobo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/**
 * flymebobo 2.0 — music capsule floating notes (v329, user 2026-11-21
 * picked option F "音符粒子").
 *
 * A transparent overlay that floats small music notes (♪ ♫ ♩) up out
 * of the capsule while music is playing: each note spawns near the
 * bottom at a random x, drifts right and rises while fading out over
 * ~3s. Pure appearance — it never touches input (not clickable, so
 * the capsule's swipe / tap handling is unaffected) and it only runs
 * while the music is playing; paused = no new notes, live ones fade.
 */
public class NoteParticleView extends View {
    /** One floating note. */
    private static class Particle {
        String glyph;
        float startX;
        float startY;
        float sizePx;
        float driftPx;
        float risePx;
        long bornAt;
        long lifeMs = 3000;
    }

    private static final String[] GLYPHS = {"\u266A", "\u266B", "\u2669"};
    private static final long SPAWN_INTERVAL_MS = 550;
    private static final long TICK_MS = 33;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = System.currentTimeMillis();
            // Spawn a new note on schedule.
            if (now - lastSpawnAt >= SPAWN_INTERVAL_MS) {
                spawn(now);
                lastSpawnAt = now;
            }
            // Drop dead particles.
            Iterator<Particle> it = particles.iterator();
            while (it.hasNext()) {
                Particle p = it.next();
                if (now - p.bornAt >= p.lifeMs) {
                    it.remove();
                }
            }
            invalidate();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private boolean running;
    private long lastSpawnAt;

    public NoteParticleView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        paint.setColor(0xFFFFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        // Soft glow so the notes read on any tint.
        paint.setShadowLayer(dp(4), 0, 0, 0xBFFFFFFF);
    }

    /** Start floating notes (music playing). Idempotent. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        lastSpawnAt = 0;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    /** Stop: no new notes, live ones are cleared. Idempotent. */
    public void stop() {
        if (!running && particles.isEmpty()) {
            return;
        }
        running = false;
        handler.removeCallbacks(ticker);
        particles.clear();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void spawn(long now) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Particle p = new Particle();
        p.glyph = GLYPHS[random.nextInt(GLYPHS.length)];
        // Across the capsule, biased away from the disc at the left.
        p.startX = w * (0.15f + random.nextFloat() * 0.80f);
        p.startY = h - dp(8);
        p.sizePx = dp(12 + random.nextFloat() * 7);
        p.driftPx = dp(24);
        // v330: the rise must fit inside the view — a View's onDraw
        // is clipped to its own bounds, so the old fixed 72dp rise
        // sailed straight out of a 44dp capsule and the notes were
        // invisible there (only the tall card showed them). Cap the
        // rise to the view height so notes stay visible on the
        // collapsed capsule too.
        p.risePx = Math.min(dp(72), Math.max(dp(16), h - dp(20)));
        p.bornAt = now;
        particles.add(p);
        // Cap the live count (3s life / 550ms spawn ≈ 6 alive).
        if (particles.size() > 8) {
            particles.remove(0);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (particles.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Particle p : particles) {
            float age = (float) (now - p.bornAt) / p.lifeMs;
            if (age < 0f || age >= 1f) {
                continue;
            }
            // Fade in fast (0-15%), hold, fade out (70-100%).
            float alpha;
            if (age < 0.15f) {
                alpha = age / 0.15f;
            } else if (age < 0.70f) {
                alpha = 1f;
            } else {
                alpha = 1f - (age - 0.70f) / 0.30f;
            }
            float x = p.startX + p.driftPx * age;
            float y = p.startY - p.risePx * age;
            paint.setTextSize(p.sizePx);
            paint.setAlpha((int) (alpha * 255));
            canvas.drawText(p.glyph, x, y, paint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
