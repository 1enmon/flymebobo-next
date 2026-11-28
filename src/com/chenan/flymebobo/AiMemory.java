package com.chenan.flymebobo;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** v635: AI 记忆本（wei 预览定版 + 扩围）——只存本
 * 机 App 私有目录 ai_memory.json，四组：pending 没
 * 了结的事 / habits 习惯 / places 位置（家、常去的
 * 地方）/ daily 日常。NS 在早报 prep 时更新，设置
 * 页 MemoryActivity 可看、可删、可手动加、可清空。 */
public class AiMemory {

    public static final String[] KEYS = {
            "pending", "habits", "places", "daily"};
    public static final String[] LABELS = {
            "还没了结的事", "记住的习惯",
            "记住的位置", "记住的日常"};

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "ai_memory.json");
    }

    public static JSONObject load(Context ctx) {
        try {
            File f = file(ctx);
            if (f.exists()) {
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int n = in.read(buf);
                in.close();
                if (n > 0) {
                    JSONObject o = new JSONObject(
                            new String(buf, 0, n, "UTF-8"));
                    for (String k : KEYS) {
                        if (o.optJSONArray(k) == null) {
                            o.put(k, new JSONArray());
                        }
                    }
                    return o;
                }
            }
        } catch (Throwable ignored) {
        }
        return empty();
    }

    public static JSONObject empty() {
        try {
            JSONObject o = new JSONObject();
            for (String k : KEYS) {
                o.put(k, new JSONArray());
            }
            return o;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static void save(Context ctx, JSONObject mem) {
        try {
            FileOutputStream out = new FileOutputStream(
                    file(ctx));
            out.write(mem.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    public static int count(JSONObject mem, String key) {
        JSONArray a = mem == null ? null
                : mem.optJSONArray(key);
        return a == null ? 0 : a.length();
    }

    public static boolean isEmpty(JSONObject mem) {
        int n = 0;
        for (String k : KEYS) {
            n += count(mem, k);
        }
        return n == 0;
    }

    /** 给提示词用的一段文本；空记忆返回空串。 */
    public static String promptText(JSONObject mem) {
        if (mem == null || isEmpty(mem)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < KEYS.length; g++) {
            JSONArray a = mem.optJSONArray(KEYS[g]);
            if (a == null || a.length() == 0) {
                continue;
            }
            sb.append(LABELS[g]).append("：\n");
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null) {
                    sb.append("- ").append(
                            e.optString("text", ""));
                    String since = e.optString("since", "");
                    if (!since.isEmpty()) {
                        sb.append("（").append(since)
                                .append("记下）");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString().trim();
    }
}
