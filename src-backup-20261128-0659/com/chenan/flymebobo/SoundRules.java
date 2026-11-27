package com.chenan.flymebobo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Telephony;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * flymebobo v535 — 按应用提示音规则（wei 2026-11-26 定的提示音
 * 完整版第三件：总开关 + 选音效之外，每个应用还能单独定）。
 *
 * v538 语义反转（wei 拍板：应用默认不响，设了才响）：
 *   无规则（不存）   — 默认「无」：不响。例外是默认名单
 *                      （微信 / QQ / 默认短信应用 / 钉钉 /
 *                      TIM / 企业微信，见 isDefaultFollow）：
 *                      没存规则按 "follow" 解析；
 *   "follow"         — 跟随全局（用 FeatureFlags.
 *                      bubbleSoundId 响）；
 *   "none"           — 显式「无」：不响。只给默认名单应用用
 *                      ——压住它们的默认 follow；普通应用的
 *                      「无」就是不存条目，不写这个值；
 *   "mute"           — v535 的旧「静音」值，v538 起与「无」
 *                      统一：解析、判定都按不响处理，不迁移；
 *   音效 id          — 这个应用的气泡用指定音效响（id 见
 *                      BubbleSound 的音效目录；"custom" = 与全局
 *                      共用同一份自定义文件）。
 *
 * 存储照 AppRules / SceneExceptions 的老路子：App 侧专用 prefs
 * （"flymebobo_soundrules"，键为 "soundrule_<包名>"），
 * SettingsStore 备份时以 "s." 前缀落盘（s.<包名>=<值>），
 * SystemUI 侧的实时通道是 ACTION_SOUND_RULES 广播里拍扁的
 * 全量规则（soundrules_flat）——绝不靠跨进程重读 prefs
 * （1.x v209 的教训）。BubbleWindow 弹气泡时按
 * BubbleSound.play(ctx, pkg) 查这里。
 */
public final class SoundRules {

    public static final String PREFS = "flymebobo_soundrules";
    public static final String KEY_PREFIX = "soundrule_";
    /** v538: 显式「跟随全局」。 */
    public static final String VALUE_FOLLOW = "follow";
    /** v538: 显式「无」（只给默认名单应用压默认 follow 用）。 */
    public static final String VALUE_NONE = "none";
    /** v535 的旧「静音」值，v538 起按「无」处理（见类注释）。 */
    public static final String VALUE_MUTE = "mute";

    /** v538: 默认「跟随全局」名单的固定部分（wei 逐个点名：
     * 微信 / QQ / 钉钉 / TIM / 企业微信）。短信不在这里——
     * 默认短信应用包名运行时解析（见 isDefaultFollow）。 */
    private static final Set<String> DEFAULT_FOLLOW_PKGS =
            new HashSet<>();
    static {
        DEFAULT_FOLLOW_PKGS.add("com.tencent.mm");
        DEFAULT_FOLLOW_PKGS.add("com.tencent.mobileqq");
        DEFAULT_FOLLOW_PKGS.add("com.alibaba.android.rimet");
        DEFAULT_FOLLOW_PKGS.add("com.tencent.tim");
        DEFAULT_FOLLOW_PKGS.add("com.tencent.wework");
    }

    /** 解析不到默认短信应用时的兜底包名。 */
    private static final String FALLBACK_SMS_PKG =
            "com.android.mms";

    /**
     * 这个应用是不是默认名单成员（没存规则时按 follow 解析）。
     * 固定名单之外，当前默认短信应用也算——包名不写死，
     * 运行时用 Telephony.Sms.getDefaultSmsPackage 解析，
     * 解析不到兜底 com.android.mms。
     */
    public static boolean isDefaultFollow(Context ctx,
            String pkg) {
        if (pkg == null) {
            return false;
        }
        if (DEFAULT_FOLLOW_PKGS.contains(pkg)) {
            return true;
        }
        return pkg.equals(defaultSmsPackage(ctx));
    }

    private static String defaultSmsPackage(Context ctx) {
        if (ctx != null) {
            try {
                String p = Telephony.Sms.getDefaultSmsPackage(ctx);
                if (p != null && !p.isEmpty()) {
                    return p;
                }
            } catch (Throwable ignored) {
            }
        }
        return FALLBACK_SMS_PKG;
    }

    /** 存储值归一成有效值：none / mute → null（不响）；
     * follow / 音效 id 原样。 */
    public static String normalize(String stored) {
        if (stored == null || VALUE_NONE.equals(stored)
                || VALUE_MUTE.equals(stored)) {
            return null;
        }
        return stored;
    }

    /**
     * SystemUI 侧：某应用的有效值——存了按存储值归一；没存
     * 看默认名单（名单内 follow，其余 null = 不响）。返回
     * "follow" / 音效 id / null。
     */
    public static String effectiveValue(Context ctx, String pkg) {
        if (pkg == null) {
            return null;
        }
        String stored = rules.get(pkg);
        if (stored != null) {
            return normalize(stored);
        }
        return isDefaultFollow(ctx, pkg) ? VALUE_FOLLOW : null;
    }

    /** Intent extra：全量规则拍扁成 "pkg=value;pkg=value"。 */
    public static final String EXTRA_RULES = "soundrules_flat";

    private static volatile Map<String, String> rules =
            new HashMap<>();

    private SoundRules() {
    }

    /** 校验一个存值：follow / none / mute（旧值）或合法音效
     * id 才有效，其余当无规则。 */
    public static String parseValue(String v) {
        if (v == null) {
            return null;
        }
        if (VALUE_FOLLOW.equals(v) || VALUE_NONE.equals(v)
                || VALUE_MUTE.equals(v)
                || BubbleSound.isSoundId(v)) {
            return v;
        }
        return null;
    }

    /** SystemUI 侧：某应用的原始存储值（null = 没存规则；
     * 有效值要走 effectiveValue，它还看默认名单）。 */
    public static String ruleFor(String pkg) {
        if (pkg == null) {
            return null;
        }
        return rules.get(pkg);
    }

    public static String flatten(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static void applyFlat(String flat) {
        Map<String, String> m = new HashMap<>();
        if (flat != null) {
            for (String part : flat.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String v = parseValue(
                        part.substring(eq + 1).trim());
                if (v != null) {
                    m.put(part.substring(0, eq).trim(), v);
                }
            }
        }
        rules = m;
        FileLog.i("SoundRules applyFlat " + flatten(m));
    }

    /** SystemUI 侧：重建静态表。备份文件优先于 prefs
     * （同 AppRules.load 的顺序）。 */
    public static void load(Context ctx) {
        if (ctx == null) {
            return;
        }
        Map<String, String> m = new HashMap<>();
        try {
            Context app = ctx.createPackageContext(
                    "com.chenan.flymebobo",
                    Context.CONTEXT_IGNORE_SECURITY);
            SharedPreferences p = app.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                String pkg = pkgOfKey(e.getKey());
                if (pkg != null
                        && e.getValue() instanceof String) {
                    String v = parseValue((String) e.getValue());
                    if (v != null) {
                        m.put(pkg, v);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Map<String, String> file = SettingsStore.readAll();
            for (Map.Entry<String, String> e : file.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(
                        SettingsStore.PREFIX_SOUND)) {
                    String pkg = e.getKey().substring(
                            SettingsStore.PREFIX_SOUND.length());
                    String v = parseValue(e.getValue());
                    if (!pkg.isEmpty() && v != null) {
                        m.put(pkg, v);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        rules = m;
        FileLog.i("SoundRules load " + flatten(m));
    }

    private static String pkgOfKey(String key) {
        if (key == null || !key.startsWith(KEY_PREFIX)) {
            return null;
        }
        String pkg = key.substring(KEY_PREFIX.length());
        return pkg.isEmpty() ? null : pkg;
    }

    // ---- APP side ----

    /** App 侧：读全部已存规则（包名 → 值）。 */
    public static Map<String, String> readAll(Context appCtx) {
        Map<String, String> m = new HashMap<>();
        if (appCtx == null) {
            return m;
        }
        try {
            SharedPreferences p = appCtx.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                String pkg = pkgOfKey(e.getKey());
                if (pkg != null
                        && e.getValue() instanceof String) {
                    String v = parseValue((String) e.getValue());
                    if (v != null) {
                        m.put(pkg, v);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    /** App 侧：存一条规则（value 为 null = 删条目，回到
     * 默认解析：普通应用不响、默认名单应用 follow），
     * 落盘备份 + 广播给 SystemUI 立刻生效。 */
    public static void setRule(Context appCtx, String pkg,
            String value) {
        if (appCtx == null || pkg == null) {
            return;
        }
        String v = parseValue(value);
        try {
            SharedPreferences.Editor ed = appCtx
                    .getSharedPreferences(PREFS,
                            Context.MODE_PRIVATE)
                    .edit();
            if (v == null) {
                ed.remove(KEY_PREFIX + pkg);
            } else {
                ed.putString(KEY_PREFIX + pkg, v);
            }
            // commit 不用 apply：广播到达时 SystemUI 可能回读，
            // 必须先落盘（同 AppRules 的规矩）。
            ed.commit();
        } catch (Throwable ignored) {
        }
        SettingsStore.saveAll(appCtx);
        try {
            Intent intent = new Intent(
                    NotificationService.ACTION_SOUND_RULES);
            intent.setPackage("com.android.systemui");
            intent.putExtra(EXTRA_RULES,
                    flatten(readAll(appCtx)));
            appCtx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }
}
