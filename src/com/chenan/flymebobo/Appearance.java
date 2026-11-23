package com.chenan.flymebobo;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * v439: 外观偏好（APP「外观」区）—— 只有一个滑块，扇子位置。
 * 胶囊和扇子是一个整体（用户 2026-11-23 “他们是一个整体” /
 * “胶囊始终是从扇子的地方弹出来的”），所以不设两个位置：
 * 滑块只定扇子（整组）的锚点 Y，胶囊从扇子那里弹出来、跟着走。
 * 存在 SharedPreferences ("flymebobo_appearance")，以屏幕高度百分比
 * 存（dockTopPct，0–85），默认 0（顶部，跟 v427 一样）。
 * SystemUI 侧通过 createPackageContext 读（跟 FeatureFlags 一个路子）。
 * 关键：锚点是靠把整个窗口下移实现的（主窗口 params.y、扇子窗
 * pileTargetY 都加 anchorY），窗口本身仍包内容、不撑全屏——
 * FLAG_NOT_TOUCH_MODAL 只放行窗口外的触控，全屏窗会把整屏
 * 触控吞掉（v4 教训，用户 “一半屏幕无法被触控”），所以绝不
 * 在窗内留一段空位去垫位置。
 * APP 改完发一条 ACTION_APPEARANCE 广播、SystemUI 重排一次就
 * 立即生效，不用重启。
 */
public class Appearance {

    public static final String PREFS = "flymebobo_appearance";
    public static final String KEY_DOCK_PCT = "dockTopPct";
    public static final String KEY_CALL_PCT = "callTopPct";

    /** 默认值：顶部，跟 v427 一样。 */
    public static final int DEF_DOCK_PCT = 0;

    public static final int MAX_DOCK_PCT = 85;

    /** v442: 来电高度——右边来电胶囊的中心高度（屏幕高度百分比）。
     * 默认 55（跟原来写死的 0.55 一样，胶囊中心在屏幕 55% 处）。
     * 范围 0–85，滑条在「来电放右边」开关下面，跟开关联动。 */
    public static final int DEF_CALL_PCT = 55;

    public static final int MAX_CALL_PCT = 85;

    // ---- v456: 外观细调（用户 2026-11-23 “调整胶囊圆角”“胶囊卡片
    // 圆角”“大小也加上”“高度和宽度2项”“扇子图标重叠度”“扇子图标
    // 透明度”“扇子图标排列方向改变”“卡片的高度和宽度圆角”“恢复默认”
    // “来电和音乐单独调整”“来电的在来电那一栏”“音乐在音乐那一栏”）----
    // 普通通知胶囊（来电、音乐不走这三项，各自在来电/音乐栏里调）。
    public static final String KEY_CORNER_RADIUS = "cornerRadiusDp";
    public static final String KEY_CAPSULE_HEIGHT = "capsuleHeightDp";
    public static final String KEY_CAPSULE_WIDTH = "capsuleWidthDp";
    /** 胶囊圆角 dp：0 方角，22 全圆（44dp 高的一半）。范围 0–30，
     * 生效时按 min(设定值, 高度/2) 钳住，高度改小了圆角不会溢出。 */
    public static final int DEF_CORNER_RADIUS = 22;
    public static final int MAX_CORNER_RADIUS = 30;
    /** 胶囊高度 dp：36–60，默认 44。 */
    public static final int DEF_CAPSULE_HEIGHT = 44;
    public static final int MIN_CAPSULE_HEIGHT = 36;
    public static final int MAX_CAPSULE_HEIGHT = 60;
    /** 胶囊宽度 dp：140–260，默认 180（手机）/ 260（平板）。 */
    public static final int DEF_CAPSULE_WIDTH = 180;
    public static final int MIN_CAPSULE_WIDTH = 140;
    public static final int MAX_CAPSULE_WIDTH = 260;

    // 普通通知卡片（来电不开卡片；音乐播放卡不动）。
    public static final String KEY_CARD_WIDTH = "cardWidthDp";
    public static final String KEY_CARD_CORNER_RADIUS = "cardCornerRadiusDp";
    public static final String KEY_CARD_MAX_ROWS = "cardMaxRows";
    // v484: 展开卡片比例（60–100%，默认 100%）——用户 2026-11-23
    // “给展开卡片也加个比例”，跟音乐卡片的比例一个路子
    // （v462 musicCardScalePct）：整张展开卡按比例缩放。
    public static final String KEY_CARD_SCALE = "cardScalePct";
    /** 卡片宽度 dp：240–380，默认 300（独立的，不跟胶囊走）。 */
    public static final int DEF_CARD_WIDTH = 300;
    public static final int MIN_CARD_WIDTH = 240;
    public static final int MAX_CARD_WIDTH = 380;
    /** 卡片圆角 dp：0–22，默认 22（独立的）。 */
    public static final int DEF_CARD_CORNER_RADIUS = 22;
    public static final int MAX_CARD_CORNER_RADIUS = 22;
    /** 卡片最大行数：2–8，默认 8（高度跟内容走，超了截断）。 */
    public static final int DEF_CARD_MAX_ROWS = 8;
    public static final int MIN_CARD_MAX_ROWS = 2;
    public static final int MAX_CARD_MAX_ROWS = 8;
    /** 展开卡片比例 %：60–100，默认 100（不缩）。 */
    public static final int DEF_CARD_SCALE = 100;
    public static final int MIN_CARD_SCALE = 60;
    public static final int MAX_CARD_SCALE = 100;

    // 扇子图标。
    public static final String KEY_FAN_OVERLAP = "fanOverlapDp";
    public static final String KEY_FAN_DIRECTION = "fanDirection";
    /** 扇子图标重叠度 dp：0 不重叠，16 重叠最多，默认 4（现在这样）。
     * 图标框 22dp，重叠 = 22 - 步距，步距 = 22 - 重叠。 */
    public static final int DEF_FAN_OVERLAP = 4;
    public static final int MAX_FAN_OVERLAP = 16;
    /** 扇子图标排列方向：0 横向（现在，往中心扇开），1 纵向（往下叠）。 */
    public static final int FAN_DIR_HORIZONTAL = 0;
    public static final int FAN_DIR_VERTICAL = 1;
    public static final int DEF_FAN_DIRECTION = FAN_DIR_HORIZONTAL;

    // 来电胶囊（在「来电」栏里调，单独的三个值）。
    public static final String KEY_CALL_CORNER_RADIUS = "callCornerRadiusDp";
    public static final String KEY_CALL_CAPSULE_HEIGHT = "callCapsuleHeightDp";
    public static final String KEY_CALL_CAPSULE_WIDTH = "callCapsuleWidthDp";
    /** 来电胶囊圆角 dp：0–30，默认 30（60dp 高的一半）。 */
    public static final int DEF_CALL_CORNER_RADIUS = 30;
    public static final int MAX_CALL_CORNER_RADIUS = 30;
    /** 来电胶囊高度 dp：48–72，默认 60。 */
    public static final int DEF_CALL_CAPSULE_HEIGHT = 60;
    public static final int MIN_CALL_CAPSULE_HEIGHT = 48;
    public static final int MAX_CALL_CAPSULE_HEIGHT = 72;
    /** 来电胶囊宽度 dp：200–280，默认 240（响铃宽；缩后计时器
     * 96/108dp 不动，不归这管）。 */
    public static final int DEF_CALL_CAPSULE_WIDTH = 240;
    public static final int MIN_CALL_CAPSULE_WIDTH = 200;
    public static final int MAX_CALL_CAPSULE_WIDTH = 280;

    // 音乐胶囊（在「音乐胶囊」栏里调，单独的三个值；播放卡不动）。
    public static final String KEY_MUSIC_CORNER_RADIUS = "musicCornerRadiusDp";
    public static final String KEY_MUSIC_CAPSULE_HEIGHT = "musicCapsuleHeightDp";
    public static final String KEY_MUSIC_CAPSULE_WIDTH = "musicCapsuleWidthDp";
    /** 音乐胶囊圆角 dp：0–22，默认 22。 */
    public static final int DEF_MUSIC_CORNER_RADIUS = 22;
    public static final int MAX_MUSIC_CORNER_RADIUS = 22;
    /** 音乐胶囊高度 dp：36–60，默认 44。 */
    public static final int DEF_MUSIC_CAPSULE_HEIGHT = 44;
    public static final int MIN_MUSIC_CAPSULE_HEIGHT = 36;
    public static final int MAX_MUSIC_CAPSULE_HEIGHT = 60;
    /** 音乐胶囊宽度 dp：140–260，默认 180（手机）/ 260（平板）。 */
    public static final int DEF_MUSIC_CAPSULE_WIDTH = 180;
    public static final int MIN_MUSIC_CAPSULE_WIDTH = 140;
    public static final int MAX_MUSIC_CAPSULE_WIDTH = 260;

    // v458: 音乐卡片（播放卡，在「音乐胶囊」栏里调，跟普通卡片
    // 分开；普通卡片的宽/圆角/行数不管它）。宽 240–380，默认
    // 300（手机）/ 380（平板），跟普通卡片一个范围；圆角 0–22，
    // 默认 22。
    // v461（用户「音乐卡缺少了高度调整」）：音乐卡片高度 = 歌词
    // 行数（1–2 行，默认 2 行）。播放器布局是固定的，高度只能
    // 跟内容走——直接拿行数上限去截会把播放控制切掉，所以高度
    // 不走行数上限，改走歌词行数：1 行只显示当前歌词，2 行再
    // 加上一行（上一行歌词），卡片随之变矮 / 变高，播放控制
    // 永远在，不会被截。歌词只有当前 + 上一这两行数据，行数
    // 上限就是 2。
    // v462（用户「改成卡片比例调整」「歌名改成一行就可以了」）：
    // 歌词行数滑条撤掉，改成卡片比例调整——整张播放卡按比例
    // 缩放（60–100%，默认 100%），宽、高、圆盘、字、按钮一起
    // 缩。歌词固定 1 行（只显示当前歌词，第二行藏起来，卡片
    // 再矮一行）。比例用百分比存。
    public static final String KEY_MUSIC_CARD_WIDTH = "musicCardWidthDp";
    public static final String KEY_MUSIC_CARD_CORNER_RADIUS =
            "musicCardCornerRadiusDp";
    public static final String KEY_MUSIC_CARD_SCALE = "musicCardScalePct";
    /** 音乐卡片宽度 dp：240–380，默认 300（手机）/ 380（平板）。 */
    public static final int DEF_MUSIC_CARD_WIDTH = 300;
    public static final int MIN_MUSIC_CARD_WIDTH = 240;
    public static final int MAX_MUSIC_CARD_WIDTH = 380;
    /** 音乐卡片圆角 dp：0–22，默认 22。 */
    public static final int DEF_MUSIC_CARD_CORNER_RADIUS = 22;
    /** 音乐卡片比例 %：60–100，默认 100（不缩）。 */
    public static final int DEF_MUSIC_CARD_SCALE = 100;
    public static final int MIN_MUSIC_CARD_SCALE = 60;
    public static final int MAX_MUSIC_CARD_SCALE = 100;
    public static final int MAX_MUSIC_CARD_CORNER_RADIUS = 22;

    /** 当前值（SystemUI 进程内的缓存；load() / setter 更新）。 */
    public static int dockTopPct = DEF_DOCK_PCT;

    /** 来电高度当前值（SystemUI 进程内的缓存；load() / setCall() 更新）。 */
    public static int callTopPct = DEF_CALL_PCT;

    /** 平板判定（跟 CapsuleView.isTablet 一个标准）：最小宽度 >= 600dp。 */
    public static boolean isTablet(Context ctx) {
        try {
            return ctx.getResources().getConfiguration()
                    .smallestScreenWidthDp >= 600;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 胶囊宽度的设备默认值：平板 260（v347 文字不截断），手机 180。 */
    public static int defCapsuleWidth(Context ctx) {
        return isTablet(ctx) ? 260 : DEF_CAPSULE_WIDTH;
    }

    /** 卡片宽度的设备默认值：平板 380，手机 300。 */
    public static int defCardWidth(Context ctx) {
        return isTablet(ctx) ? 380 : DEF_CARD_WIDTH;
    }

    /** 音乐卡片宽度的设备默认值：平板 380，手机 300（跟普通卡片一样）。 */
    public static int defMusicCardWidth(Context ctx) {
        return isTablet(ctx) ? 380 : DEF_MUSIC_CARD_WIDTH;
    }

    public static int cornerRadiusDp = DEF_CORNER_RADIUS;
    public static int capsuleHeightDp = DEF_CAPSULE_HEIGHT;
    public static int capsuleWidthDp = DEF_CAPSULE_WIDTH;
    public static int cardWidthDp = DEF_CARD_WIDTH;
    public static int cardCornerRadiusDp = DEF_CARD_CORNER_RADIUS;
    public static int cardMaxRows = DEF_CARD_MAX_ROWS;
    // v484: 展开卡片比例 %（60–100，默认 100）。
    public static int cardScalePct = DEF_CARD_SCALE;
    public static int fanOverlapDp = DEF_FAN_OVERLAP;
    public static int fanDirection = DEF_FAN_DIRECTION;
    public static int callCornerRadiusDp = DEF_CALL_CORNER_RADIUS;
    public static int callCapsuleHeightDp = DEF_CALL_CAPSULE_HEIGHT;
    public static int callCapsuleWidthDp = DEF_CALL_CAPSULE_WIDTH;
    public static int musicCornerRadiusDp = DEF_MUSIC_CORNER_RADIUS;
    public static int musicCapsuleHeightDp = DEF_MUSIC_CAPSULE_HEIGHT;
    public static int musicCapsuleWidthDp = DEF_MUSIC_CAPSULE_WIDTH;
    // v458: 音乐卡片（播放卡）宽 / 圆角。
    public static int musicCardWidthDp = DEF_MUSIC_CARD_WIDTH;
    public static int musicCardCornerRadiusDp = DEF_MUSIC_CARD_CORNER_RADIUS;

    /** v464: 文件优先，文件里没有再读 prefs（跟 FeatureFlags
     * 一个路子）。 */
    private static int fileOrPrefsInt(SharedPreferences prefs,
            String key, int def) {
        Integer v = SettingsStore.fileAppearance(key);
        if (v != null) {
            return v;
        }
        return prefs.getInt(key, def);
    }

    /** 从 APP 的 prefs 读（SystemUI 上下文经 createPackageContext）。 */
    public static void load(Context systemUiContext) {
        try {
            Context app = systemUiContext.createPackageContext(
                    "com.chenan.flymebobo",
                    Context.CONTEXT_IGNORE_SECURITY);
            SharedPreferences prefs = app.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            dockTopPct = clamp(fileOrPrefsInt(prefs, KEY_DOCK_PCT, DEF_DOCK_PCT),
                    0, MAX_DOCK_PCT);
            callTopPct = clamp(fileOrPrefsInt(prefs, KEY_CALL_PCT, DEF_CALL_PCT),
                    0, MAX_CALL_PCT);
            cornerRadiusDp = clamp(fileOrPrefsInt(prefs, KEY_CORNER_RADIUS,
                    DEF_CORNER_RADIUS), 0, MAX_CORNER_RADIUS);
            capsuleHeightDp = clamp(fileOrPrefsInt(prefs, KEY_CAPSULE_HEIGHT,
                    DEF_CAPSULE_HEIGHT),
                    MIN_CAPSULE_HEIGHT, MAX_CAPSULE_HEIGHT);
            capsuleWidthDp = clamp(fileOrPrefsInt(prefs, KEY_CAPSULE_WIDTH,
                    defCapsuleWidth(systemUiContext)),
                    MIN_CAPSULE_WIDTH, MAX_CAPSULE_WIDTH);
            cardWidthDp = clamp(fileOrPrefsInt(prefs, KEY_CARD_WIDTH,
                    defCardWidth(systemUiContext)),
                    MIN_CARD_WIDTH, MAX_CARD_WIDTH);
            cardCornerRadiusDp = clamp(fileOrPrefsInt(prefs, KEY_CARD_CORNER_RADIUS,
                    DEF_CARD_CORNER_RADIUS), 0, MAX_CARD_CORNER_RADIUS);
            cardMaxRows = clamp(fileOrPrefsInt(prefs, KEY_CARD_MAX_ROWS,
                    DEF_CARD_MAX_ROWS),
                    MIN_CARD_MAX_ROWS, MAX_CARD_MAX_ROWS);
            // v484: 展开卡片比例（60–100%，默认 100%）。
            cardScalePct = clamp(fileOrPrefsInt(prefs, KEY_CARD_SCALE,
                    DEF_CARD_SCALE),
                    MIN_CARD_SCALE, MAX_CARD_SCALE);
            fanOverlapDp = clamp(fileOrPrefsInt(prefs, KEY_FAN_OVERLAP,
                    DEF_FAN_OVERLAP), 0, MAX_FAN_OVERLAP);
            fanDirection = fileOrPrefsInt(prefs, KEY_FAN_DIRECTION,
                    DEF_FAN_DIRECTION) == FAN_DIR_VERTICAL
                    ? FAN_DIR_VERTICAL : FAN_DIR_HORIZONTAL;
            callCornerRadiusDp = clamp(fileOrPrefsInt(prefs, KEY_CALL_CORNER_RADIUS,
                    DEF_CALL_CORNER_RADIUS), 0, MAX_CALL_CORNER_RADIUS);
            callCapsuleHeightDp = clamp(fileOrPrefsInt(prefs, KEY_CALL_CAPSULE_HEIGHT,
                    DEF_CALL_CAPSULE_HEIGHT),
                    MIN_CALL_CAPSULE_HEIGHT, MAX_CALL_CAPSULE_HEIGHT);
            callCapsuleWidthDp = clamp(fileOrPrefsInt(prefs, KEY_CALL_CAPSULE_WIDTH,
                    DEF_CALL_CAPSULE_WIDTH),
                    MIN_CALL_CAPSULE_WIDTH, MAX_CALL_CAPSULE_WIDTH);
            musicCornerRadiusDp = clamp(fileOrPrefsInt(prefs, KEY_MUSIC_CORNER_RADIUS,
                    DEF_MUSIC_CORNER_RADIUS), 0, MAX_MUSIC_CORNER_RADIUS);
            musicCapsuleHeightDp = clamp(fileOrPrefsInt(prefs, 
                    KEY_MUSIC_CAPSULE_HEIGHT, DEF_MUSIC_CAPSULE_HEIGHT),
                    MIN_MUSIC_CAPSULE_HEIGHT, MAX_MUSIC_CAPSULE_HEIGHT);
            musicCapsuleWidthDp = clamp(fileOrPrefsInt(prefs, KEY_MUSIC_CAPSULE_WIDTH,
                    defCapsuleWidth(systemUiContext)),
                    MIN_MUSIC_CAPSULE_WIDTH, MAX_MUSIC_CAPSULE_WIDTH);
            musicCardWidthDp = clamp(fileOrPrefsInt(prefs, KEY_MUSIC_CARD_WIDTH,
                    defMusicCardWidth(systemUiContext)),
                    MIN_MUSIC_CARD_WIDTH, MAX_MUSIC_CARD_WIDTH);
            musicCardCornerRadiusDp = clamp(fileOrPrefsInt(prefs, 
                    KEY_MUSIC_CARD_CORNER_RADIUS,
                    DEF_MUSIC_CARD_CORNER_RADIUS),
                    0, MAX_MUSIC_CARD_CORNER_RADIUS);
            // v462: 音乐卡片比例（60–100%，默认 100%）。
            musicCardScalePct = clamp(fileOrPrefsInt(prefs, 
                    KEY_MUSIC_CARD_SCALE,
                    DEF_MUSIC_CARD_SCALE),
                    MIN_MUSIC_CARD_SCALE, MAX_MUSIC_CARD_SCALE);
        } catch (Throwable ignored) {
            // 默认值 stand。
        }
    }

    public static void setDock(int pct) {
        dockTopPct = clamp(pct, 0, MAX_DOCK_PCT);
    }

    public static void setCall(int pct) {
        callTopPct = clamp(pct, 0, MAX_CALL_PCT);
    }

    public static void setCornerRadius(int v) {
        cornerRadiusDp = clamp(v, 0, MAX_CORNER_RADIUS);
    }

    public static void setCapsuleHeight(int v) {
        capsuleHeightDp = clamp(v, MIN_CAPSULE_HEIGHT, MAX_CAPSULE_HEIGHT);
    }

    public static void setCapsuleWidth(int v) {
        capsuleWidthDp = clamp(v, MIN_CAPSULE_WIDTH, MAX_CAPSULE_WIDTH);
    }

    public static void setCardWidth(int v) {
        cardWidthDp = clamp(v, MIN_CARD_WIDTH, MAX_CARD_WIDTH);
    }

    public static void setCardCornerRadius(int v) {
        cardCornerRadiusDp = clamp(v, 0, MAX_CARD_CORNER_RADIUS);
    }

    public static void setCardMaxRows(int v) {
        cardMaxRows = clamp(v, MIN_CARD_MAX_ROWS, MAX_CARD_MAX_ROWS);
    }

    // v484: 展开卡片比例（60–100%）。
    public static void setCardScalePct(int v) {
        cardScalePct = clamp(v, MIN_CARD_SCALE, MAX_CARD_SCALE);
    }

    public static void setFanOverlap(int v) {
        fanOverlapDp = clamp(v, 0, MAX_FAN_OVERLAP);
    }

    public static void setFanDirection(int v) {
        fanDirection = v == FAN_DIR_VERTICAL
                ? FAN_DIR_VERTICAL : FAN_DIR_HORIZONTAL;
    }

    public static void setCallCornerRadius(int v) {
        callCornerRadiusDp = clamp(v, 0, MAX_CALL_CORNER_RADIUS);
    }

    public static void setCallCapsuleHeight(int v) {
        callCapsuleHeightDp = clamp(v,
                MIN_CALL_CAPSULE_HEIGHT, MAX_CALL_CAPSULE_HEIGHT);
    }

    public static void setCallCapsuleWidth(int v) {
        callCapsuleWidthDp = clamp(v,
                MIN_CALL_CAPSULE_WIDTH, MAX_CALL_CAPSULE_WIDTH);
    }

    public static void setMusicCornerRadius(int v) {
        musicCornerRadiusDp = clamp(v, 0, MAX_MUSIC_CORNER_RADIUS);
    }

    public static void setMusicCapsuleHeight(int v) {
        musicCapsuleHeightDp = clamp(v,
                MIN_MUSIC_CAPSULE_HEIGHT, MAX_MUSIC_CAPSULE_HEIGHT);
    }

    public static void setMusicCapsuleWidth(int v) {
        musicCapsuleWidthDp = clamp(v,
                MIN_MUSIC_CAPSULE_WIDTH, MAX_MUSIC_CAPSULE_WIDTH);
    }

    // v458: 音乐卡片（播放卡）宽 / 圆角。v462：音乐卡片比例
    // （60–100%，默认 100%）。
    public static int musicCardScalePct = DEF_MUSIC_CARD_SCALE;

    public static void setMusicCardWidth(int v) {
        musicCardWidthDp = clamp(v,
                MIN_MUSIC_CARD_WIDTH, MAX_MUSIC_CARD_WIDTH);
    }

    public static void setMusicCardCornerRadius(int v) {
        musicCardCornerRadiusDp = clamp(v, 0, MAX_MUSIC_CARD_CORNER_RADIUS);
    }

    public static void setMusicCardScalePct(int v) {
        musicCardScalePct = clamp(v,
                MIN_MUSIC_CARD_SCALE, MAX_MUSIC_CARD_SCALE);
    }

    /** 百分比 → 屏幕像素 Y（SystemUI 侧用，整组窗口的下移量）。 */
    public static int dockTopPx(Context ctx) {
        return pctToPx(ctx, dockTopPct);
    }

    public static int pctToPx(Context ctx, int pct) {
        try {
            int sh = ctx.getResources().getDisplayMetrics().heightPixels;
            return (int) (sh * (pct / 100f));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 百分比 → dp（APP 侧展示用）。 */
    public static int pctToDp(Context ctx, int pct) {
        try {
            float density = ctx.getResources().getDisplayMetrics().density;
            int sh = ctx.getResources().getDisplayMetrics().heightPixels;
            return (int) (sh * (pct / 100f) / density);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
