package com.chenan.flymebobo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** v643: 信息卡自学（wei 定：不加开关，开 AI 就默
 * 认学；只属于轻气泡，经典那边不碰）——AI 兜底认出
 * 本地没认出的票/单/账单后，把「这个应用 + 这种标
 * 题长相 → 这种卡」记成本地规则（learned_rules
 * .json，公共目录，SystemUI 可读）。下次同形状直
 * 接按种类走本地解析出卡，不再麻烦 AI。 */
public class LearnedRules {

    private static JSONArray cache;
    private static long cacheMtime = -1;

    private static File file() {
        return new File(SettingsStore.DIR_PUBLIC
                + "/learned_rules.json");
    }

    /** 标题形状：数字串压成 #、空白归一，让「11月
     * 账单」和「12月账单」长一个样。 */
    public static String shapeOf(String title) {
        if (title == null) {
            return "";
        }
        String t = title.trim().replaceAll("\\d+", "#");
        t = t.replaceAll("\\s+", " ");
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    private static JSONArray load() {
        File f = file();
        long mt = f.exists() ? f.lastModified() : 0;
        if (cache != null && mt == cacheMtime) {
            return cache;
        }
        JSONArray arr = new JSONArray();
        try {
            if (f.exists()) {
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int n = in.read(buf);
                in.close();
                if (n > 0) {
                    arr = new JSONArray(
                            new String(buf, 0, n, "UTF-8"));
                }
            }
        } catch (Throwable ignored) {
        }
        cache = arr;
        cacheMtime = mt;
        return arr;
    }

    /** 命中返回种类（train/flight/courier/bill）。 */
    public static String matchKind(String pkg,
            String title) {
        if (pkg == null || title == null) {
            return null;
        }
        String shape = shapeOf(title);
        if (shape.isEmpty()) {
            return null;
        }
        JSONArray arr = load();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r != null && pkg.equals(
                    r.optString("pkg", ""))
                    && shape.equals(
                            r.optString("shape", ""))) {
                String kind = r.optString("kind", "");
                return kind.isEmpty() ? null : kind;
            }
        }
        return null;
    }

    public static int count() {
        return load().length();
    }

    /** 学一条：同应用同形状不重复记；上限 30 条，
     * 满了丢最老的。 */
    public static void learn(String pkg, String title,
            String kind) {
        if (pkg == null || pkg.isEmpty() || kind == null
                || kind.isEmpty()
                || "none".equals(kind)) {
            return;
        }
        String shape = shapeOf(title);
        if (shape.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = load();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r != null && pkg.equals(
                        r.optString("pkg", ""))
                        && shape.equals(
                                r.optString("shape", ""))) {
                    return;
                }
            }
            JSONObject r = new JSONObject();
            r.put("pkg", pkg);
            r.put("shape", shape);
            r.put("kind", kind);
            arr.put(r);
            while (arr.length() > 30) {
                arr.remove(0);
            }
            FileOutputStream out = new FileOutputStream(
                    file());
            out.write(arr.toString().getBytes("UTF-8"));
            out.close();
            cache = arr;
            cacheMtime = file().lastModified();
        } catch (Throwable ignored) {
        }
    }
}
