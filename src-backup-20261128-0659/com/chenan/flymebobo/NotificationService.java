package com.chenan.flymebobo;

import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * flymebobo 2.0 — 通知监听 (NotificationService).
 *
 * Reads notifications from the system and broadcasts them to the
 * bubble window, which lives in the SystemUI process (see XposedEntry).
 * Fresh code for 2.0 (not copied from 1.x).
 *
 * Why a broadcast: the bubble window runs inside SystemUI, so it can
 * draw without the overlay permission (SystemUI already holds it).
 * This service runs in the module's own process, so it hands the
 * notification over via a broadcast instead of touching the window
 * directly.
 *
 * Rules (kept from the 1.x behaviour the user confirmed):
 *  - only real notifications (no ongoing / foreground-service ones
 *    unless they carry progress);
 *  - the notification is cancelled after reading so it does not sit
 *    in the shade as well as in a bubble.
 */
public class NotificationService extends NotificationListenerService {
    public static final String ACTION_NOTIFY =
            "com.chenan.flymebobo.NOTIFY";
    /** v427 (push diagnostic): cancelNotif reports its 500ms
     * still-active check to SystemUI, so the result lands in the
     * SystemUI log the user sends — the listener process's own
     * FileLog lines never appear in those files, so without this
     * we cannot tell whether the listener's cancel ran or worked.
     * extras: key, stillActive, pkg, tag, id. */
    public static final String ACTION_CANCEL_DIAG =
            "com.chenan.flymebobo.CANCEL_DIAG";
    /** Live lyric line, service -> SystemUI (BubbleWindow). */
    public static final String ACTION_LYRIC =
            "com.chenan.flymebobo.LYRIC";
    public static final String ACTION_DOWNLOAD =
            "com.chenan.flymebobo.DOWNLOAD";
    /** v242: download finished/cancelled and its notification
     * is gone — service -> SystemUI, remove the capsule. */
    public static final String ACTION_DOWNLOAD_REMOVE =
            "com.chenan.flymebobo.DOWNLOAD_REMOVE";
    public static final String ACTION_CALL =
            "com.chenan.flymebobo.CALL";

    // v125 (Flyme Island): SystemUI -> module, fire one of the
    // incallui notification's own actions. extra "command" is one
    // of answer / reject / hangup / speaker_on / speaker_off.
    public static final String ACTION_CALL_CONTROL =
            "com.chenan.flymebobo.CALL_CONTROL";
    /** Card open (v143): SystemUI asks the module to dismiss a
     * notification after the user opened its app from the card. The
     * module is the NotificationListenerService, so it can cancel any
     * notification by key. */
    public static final String ACTION_DISMISS =
            "com.chenan.flymebobo.DISMISS";
    // v137: fireCallAction reports its outcome here so SystemUI can
    // Toast it (module-side Toast may not display).
    public static final String ACTION_CALL_RESULT =
            "com.chenan.flymebobo.CALL_RESULT";
    public static final String CALL_RINGING = "ringing";
    public static final String CALL_OFFHOOK = "offhook";
    public static final String CALL_IDLE = "idle";
    /** Start/stop the lyric listener, SystemUI -> service. Sent by
     * BubbleWindow.togglePin when a music capsule is pinned or
     * unpinned. Extras: "key", "listen" (boolean). */
    public static final String ACTION_LYRIC_LISTEN =
            "com.chenan.flymebobo.LYRIC_LISTEN";
    /** v600: 轻气泡 AI 分析（App 进程执行）——读自带
     * Key（flymebobo_ai 私有 prefs），结果广播回
     * SystemUI。只发标题+正文（SystemUI 侧已剔除验证
     * 码条目），异步线程执行，不挡通知主流程。失败只
     * 记日志。v614: 打标与摘要分家——打标走小模型
     * （aiModelFast，留空跟随摘要模型）求快，摘要走
     * aiModel 求稳，两路并行、各自回广播（onAiResult
     * 按 summary/tags 分开收，缺哪路不动哪路）。 */
    private void handleAiAnalyze(
            final android.content.Intent intent) {
        final String sig = intent.getStringExtra("sig");
        final String blob = intent.getStringExtra("blob");
        final boolean wantSummary =
                intent.getBooleanExtra("summary", true);
        final boolean wantTags =
                intent.getBooleanExtra("tags", true);
        if (sig == null || blob == null || blob.isEmpty()) {
            return;
        }
        android.content.SharedPreferences aiPrefs =
                getSharedPreferences("flymebobo_ai",
                        android.content.Context.MODE_PRIVATE);
        // v603: Key 落盘是 AiKey 密文（系统密钥库），
        // 读时解密；旧版明文原样兼容。
        final String apiKey = AiKey.decrypt(
                aiPrefs.getString("aiKey", ""));
        if (apiKey == null || apiKey.isEmpty()) {
            FileLog.i("Light ai skip: no key");
            return;
        }
        final String apiBase = aiPrefs.getString("aiBase",
                "https://api.deepseek.com");
        final String apiModel = aiPrefs.getString("aiModel",
                "deepseek-chat");
        String fastPref = aiPrefs.getString("aiModelFast",
                "");
        final String fastModel = (fastPref == null
                || fastPref.trim().isEmpty())
                ? apiModel : fastPref.trim();
        final java.util.List<String> keys =
                new java.util.ArrayList<>();
        StringBuilder items = new StringBuilder();
        for (String ln : blob.split("\n")) {
            String[] parts = ln.split("\u0001", -1);
            if (parts.length < 3) {
                continue;
            }
            items.append("[").append(keys.size())
                    .append("] 标题：").append(parts[1])
                    .append(" 正文：").append(parts[2])
                    .append("\n");
            keys.add(parts[0]);
        }
        if (keys.isEmpty()) {
            return;
        }
        final String itemsText = items.toString();
        final int itemCount = keys.size();
        if (wantTags) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String prompt = "下面是手机通知收纳盒里的"
                            + itemCount
                            + "条通知，逐条打标后只输出一个 JSON 对象，不要解释。\n"
                            + itemsText
                            + "输出格式：{\"tags\":[{\"i\":序号,\"tag\":\"suspect/promo/important/none\"}]}。tag 定义：suspect=疑似诈骗或账户风险；promo=促销广告营销；important=重要事务（账单到期、行程出行、停水停电、缴费提醒等）；none=普通。每条都要给 tag。";
                    String out = aiChatOnce(apiBase, apiKey,
                            fastModel, prompt);
                    if (out == null) {
                        return;
                    }
                    try {
                        int l = out.indexOf('{');
                        int r = out.lastIndexOf('}');
                        if (l < 0 || r <= l) {
                            FileLog.i("Light ai tags bad json");
                            return;
                        }
                        org.json.JSONObject j =
                                new org.json.JSONObject(
                                        out.substring(l, r + 1));
                        StringBuilder tagsBlob =
                                new StringBuilder();
                        int hit = 0;
                        if (j.has("tags")) {
                            org.json.JSONArray tags =
                                    j.getJSONArray("tags");
                            for (int i = 0; i < tags.length();
                                    i++) {
                                org.json.JSONObject t =
                                        tags.getJSONObject(i);
                                int idx = t.optInt("i", -1);
                                String tag = t.optString("tag",
                                        "none");
                                if (idx >= 0 && idx < keys.size()
                                        && ("suspect".equals(tag)
                                                || "promo".equals(tag)
                                                || "important".equals(
                                                        tag))) {
                                    tagsBlob.append(
                                                    keys.get(idx))
                                            .append("\u0001")
                                            .append(tag)
                                            .append("\n");
                                    hit++;
                                }
                            }
                        }
                        android.content.Intent result =
                                new android.content.Intent(
                                        ACTION_AI_RESULT);
                        result.putExtra("sig", sig);
                        result.putExtra("tagsBlob",
                                tagsBlob.toString());
                        sendBroadcast(result);
                        FileLog.i("Light ai tags sig=" + sig
                                + " model=" + fastModel
                                + " hit=" + hit);
                    } catch (Throwable t) {
                        FileLog.i("Light ai tags fail: "
                                + t.getMessage());
                    }
                }
            }).start();
        }
        if (wantSummary) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String prompt = "下面是手机通知收纳盒里的"
                            + itemCount
                            + "条通知，请处理后只输出一个 JSON 对象，不要解释。\n"
                            + itemsText
                            + "输出格式：{\"summary\":\"一句话总结这些通知（中文，30字以内，没有值得总结的就空字符串）\"}";
                    String out = aiChatOnce(apiBase, apiKey,
                            apiModel, prompt);
                    if (out == null) {
                        return;
                    }
                    try {
                        int l = out.indexOf('{');
                        int r = out.lastIndexOf('}');
                        if (l < 0 || r <= l) {
                            FileLog.i("Light ai summary bad json");
                            return;
                        }
                        org.json.JSONObject j =
                                new org.json.JSONObject(
                                        out.substring(l, r + 1));
                        String summary = j.optString("summary",
                                "");
                        android.content.Intent result =
                                new android.content.Intent(
                                        ACTION_AI_RESULT);
                        result.putExtra("sig", sig);
                        result.putExtra("summaryText", summary);
                        sendBroadcast(result);
                        FileLog.i("Light ai summary sig=" + sig
                                + " model=" + apiModel);
                    } catch (Throwable t) {
                        FileLog.i("Light ai summary fail: "
                                + t.getMessage());
                    }
                }
            }).start();
        }
    }

    /** v614: 一次 OpenAI 兼容 chat 调用（系统词固定
     * 为通知整理助手），返回 message content；任何
     * 失败返回 null 并记日志。 */
    private String aiChatOnce(String apiBase, String apiKey,
            String model, String userText) {
        try {
            org.json.JSONObject req =
                    new org.json.JSONObject();
            req.put("model", model);
            org.json.JSONArray msgs =
                    new org.json.JSONArray();
            org.json.JSONObject sys =
                    new org.json.JSONObject();
            sys.put("role", "system");
            sys.put("content",
                    "你是通知整理助手，只输出 JSON。");
            msgs.put(sys);
            org.json.JSONObject usr =
                    new org.json.JSONObject();
            usr.put("role", "user");
            usr.put("content", userText);
            msgs.put(usr);
            req.put("messages", msgs);
            req.put("temperature", 0.1);
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection)
                            new java.net.URL(apiBase
                                    + "/chat/completions")
                                    .openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type",
                    "application/json");
            conn.setRequestProperty("Authorization",
                    "Bearer " + apiKey);
            conn.setDoOutput(true);
            java.io.OutputStream os =
                    conn.getOutputStream();
            os.write(req.toString().getBytes(
                    java.nio.charset.StandardCharsets
                            .UTF_8));
            os.close();
            int code = conn.getResponseCode();
            java.io.BufferedReader br =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    code >= 200 && code < 300
                                            ? conn.getInputStream()
                                            : conn.getErrorStream(),
                                    java.nio.charset
                                            .StandardCharsets
                                            .UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            if (code < 200 || code >= 300) {
                FileLog.i("Light ai http " + code
                        + " model=" + model);
                return null;
            }
            org.json.JSONObject resp =
                    new org.json.JSONObject(sb.toString());
            return resp.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
        } catch (Throwable t) {
            FileLog.i("Light ai call fail: "
                    + t.getMessage());
            return null;
        }
    }

    /** v605: 信息卡 AI 兜底+核对（App 进程执行）——
     * SystemUI 发来 infoBlob（每行 key、localKind、
     * 标题、正文），本进程调 AI 逐条判断/核对，结果
     * 用 ACTION_AI_INFO_RESULT 广播回去。通道与
     * handleAiAnalyze 同款（同 Key/地址/模型）。 */
    private void handleAiInfoCheck(
            final android.content.Intent intent) {
        final String blob = intent.getStringExtra("infoBlob");
        if (blob == null || blob.isEmpty()) {
            return;
        }
        android.content.SharedPreferences aiPrefs =
                getSharedPreferences("flymebobo_ai",
                        android.content.Context.MODE_PRIVATE);
        final String apiKey = AiKey.decrypt(
                aiPrefs.getString("aiKey", ""));
        if (apiKey == null || apiKey.isEmpty()) {
            FileLog.i("Light info ai skip: no key");
            return;
        }
        final String apiBase = aiPrefs.getString("aiBase",
                "https://api.deepseek.com");
        final String apiModel = aiPrefs.getString("aiModel",
                "deepseek-chat");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] lines = blob.split("\n");
                    java.util.List<String> keys =
                            new java.util.ArrayList<>();
                    java.util.List<String> locals =
                            new java.util.ArrayList<>();
                    StringBuilder prompt =
                            new StringBuilder();
                    prompt.append("下面是手机通知收纳盒里的")
                            .append(lines.length)
                            .append("条通知，逐条处理后只输出一个 JSON 数组，不要解释。\n");
                    for (int i = 0; i < lines.length; i++) {
                        String[] parts = lines[i].split(
                                "\u0001", -1);
                        if (parts.length < 4) {
                            continue;
                        }
                        keys.add(parts[0]);
                        locals.add(parts[1]);
                        prompt.append("[").append(keys.size() - 1)
                                .append("]");
                        if (!parts[1].isEmpty()) {
                            prompt.append("(本地已识别为 ")
                                    .append(parts[1])
                                    .append("，请核对)");
                        }
                        prompt.append(" 标题：").append(parts[2])
                                .append(" 正文：").append(parts[3])
                                .append("\n");
                    }
                    if (keys.isEmpty()) {
                        return;
                    }
                    prompt.append("每条输出 {\"i\":序号,\"kind\":\"train/flight/courier/bill/none\",\"title\":\"...\",\"sub\":\"...\",\"event\":\"yyyy-MM-dd HH:mm 或空字符串\",\"copy\":\"快递单号或空字符串\"}。kind 定义：train=火车票/高铁票，flight=机票/航班，courier=快递物流，bill=信用卡账单，都不是填 none。title 格式：火车票「车次 次 · 出发站 → 到达站」；机票「航班号 · 出发 → 到达」；快递「公司 · 单号 xxx」；账单「信用卡账单 ¥金额」。sub 放日期时间、座位、检票口等要点（40 字内）。event 是发车/起飞/账单到期时间，没有填空字符串。带核对标记的：本地识别正确就保持 kind 并补全字段；明显不对就改成正确 kind 或 none。");
                    org.json.JSONObject req =
                            new org.json.JSONObject();
                    req.put("model", apiModel);
                    org.json.JSONArray msgs =
                            new org.json.JSONArray();
                    org.json.JSONObject sys =
                            new org.json.JSONObject();
                    sys.put("role", "system");
                    sys.put("content",
                            "你是通知信息提取器，只输出 JSON 数组。");
                    msgs.put(sys);
                    org.json.JSONObject usr =
                            new org.json.JSONObject();
                    usr.put("role", "user");
                    usr.put("content", prompt.toString());
                    msgs.put(usr);
                    req.put("messages", msgs);
                    req.put("temperature", 0.1);
                    java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection)
                                    new java.net.URL(apiBase
                                            + "/chat/completions")
                                            .openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("Content-Type",
                            "application/json");
                    conn.setRequestProperty("Authorization",
                            "Bearer " + apiKey);
                    conn.setDoOutput(true);
                    java.io.OutputStream os =
                            conn.getOutputStream();
                    os.write(req.toString().getBytes(
                            java.nio.charset.StandardCharsets
                                    .UTF_8));
                    os.close();
                    int code = conn.getResponseCode();
                    java.io.BufferedReader br =
                            new java.io.BufferedReader(
                                    new java.io.InputStreamReader(
                                            code >= 200 && code < 300
                                                    ? conn.getInputStream()
                                                    : conn.getErrorStream(),
                                            java.nio.charset
                                                    .StandardCharsets
                                                    .UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    br.close();
                    if (code < 200 || code >= 300) {
                        FileLog.i("Light info ai http " + code);
                        return;
                    }
                    org.json.JSONObject resp =
                            new org.json.JSONObject(
                                    sb.toString());
                    String out = resp.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");
                    int l = out.indexOf('[');
                    int r = out.lastIndexOf(']');
                    if (l < 0 || r <= l) {
                        FileLog.i("Light info ai bad json");
                        return;
                    }
                    org.json.JSONArray arr =
                            new org.json.JSONArray(
                                    out.substring(l, r + 1));
                    java.text.SimpleDateFormat sdf =
                            new java.text.SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm",
                                    java.util.Locale.CHINA);
                    sdf.setLenient(false);
                    StringBuilder result = new StringBuilder();
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o =
                                arr.getJSONObject(i);
                        int idx = o.optInt("i", -1);
                        if (idx < 0 || idx >= keys.size()) {
                            continue;
                        }
                        String kind = o.optString("kind",
                                "none");
                        long ev = 0;
                        String evs = o.optString("event", "");
                        if (!evs.isEmpty()) {
                            try {
                                java.util.Date dt =
                                        sdf.parse(evs);
                                if (dt != null) {
                                    ev = dt.getTime();
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        result.append(keys.get(idx))
                                .append("\u0001")
                                .append(kind).append("\u0001")
                                .append(o.optString("title", ""))
                                .append("\u0001")
                                .append(o.optString("sub", ""))
                                .append("\u0001")
                                .append(ev).append("\u0001")
                                .append(o.optString("copy", ""))
                                .append("\n");
                    }
                    android.content.Intent res =
                            new android.content.Intent(
                                    ACTION_AI_INFO_RESULT);
                    res.putExtra("infoResult",
                            result.toString());
                    sendBroadcast(res);
                    FileLog.i("Light info ai result n="
                            + arr.length());
                } catch (Throwable t) {
                    FileLog.i("Light info ai fail: "
                            + t.getMessage());
                }
            }
        }).start();
    }

    /** v389: recognise a phone number vs a contact name (user
     * 2026-11-22 "识别名称和号码"). The incallui notification
     * posts TWICE for one call: first title = the raw number
     * ("18883204388", text empty, titleOverlay = "188 8320
     * 4388"), then 16ms later title = the name ("我的", text =
     * "188 8320 4388"). The old code took EXTRA_TITLE as the name
     * and EXTRA_TEXT as the number blindly — no judgement at all —
     * so the first post showed the raw unspaced number as the
     * "name" and an empty second line, and the key dedup dropped
     * the second post, so the name never arrived.
     * A string is a phone number when, after stripping spaces,
     * dashes and a leading +, it is all digits and 7+ digits
     * long. Anything else non-empty is a name. */
    private static boolean isPhoneNumber(String s) {
        if (s == null) {
            return false;
        }
        String d = s.trim().replace(" ", "").replace("-", "");
        if (d.startsWith("+")) {
            d = d.substring(1);
        }
        if (d.length() < 7) {
            return false;
        }
        for (int i = 0; i < d.length(); i++) {
            if (!Character.isDigit(d.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Digits only, for comparing numbers ("18883204388" ==
     * "188 8320 4388"). */
    private static String digitsOnly(String s) {
        if (s == null) {
            return null;
        }
        String d = s.trim().replace(" ", "").replace("-", "");
        if (d.startsWith("+")) {
            d = d.substring(1);
        }
        return d;
    }

    /** Display form: an 11-digit mobile with no spaces becomes
     * "188 8320 4388" (3-4-4). Anything else is returned trimmed. */
    private static String formatNumber(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return t;
        }
        String d = digitsOnly(t);
        if (d != null && d.length() == 11 && !t.contains(" ")) {
            return d.substring(0, 3) + " " + d.substring(3, 7)
                    + " " + d.substring(7);
        }
        return t;
    }

    /** 1.x isCallNotification: category call, full-screen intent
     * from a dialer/phone/telecom/incallui package, or a title that
     * looks like a phone number (7+ digits) or carries a call word. */
    private boolean isCallNotification(
            android.service.notification.StatusBarNotification sbn,
            android.app.Notification n) {
        if (n == null) {
            return false;
        }
        // v134: the incallui call notification itself — the ongoing
        // one after answering has no fullScreenIntent (used up at
        // RINGING) and may not carry category "call", so it stopped
        // matching here and the re-post never reached the call
        // branch at all: no key update, no speaker extraction, and
        // SystemUI never got a speaker button ("无扬声器按钮").
        // The diagnostic build matches incallui by package alone.
        if (sbn != null
                && "com.android.incallui".equals(sbn.getPackageName())) {
            return true;
        }
        if ("call".equals(n.category)) {
            return true;
        }
        if (n.fullScreenIntent != null) {
            String pkg = sbn != null ? sbn.getPackageName() : null;
            if (pkg != null) {
                String lower = pkg.toLowerCase();
                if (lower.contains("dialer") || lower.contains("phone")
                        || lower.contains("telecom")
                        || lower.contains("incallui")
                        || lower.contains("call")) {
                    return true;
                }
            }
            try {
                android.os.Bundle b = n.extras;
                String title = b != null ? b.getString(
                        android.app.Notification.EXTRA_TITLE) : null;
                if (title != null) {
                    String digits = title.replaceAll("[^0-9]", "");
                    if (digits.length() >= 7) {
                        return true;
                    }
                    if (title.contains("来电") || title.contains("呼入")
                            || title.contains("正在呼叫")) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // v125 (Flyme Island, MusicListenerService): fire the
    // incallui notification's own action by matching its title
    // (answer / reject / hangup / speaker on / speaker off) and
    // confirming the PendingIntent was created by incallui.
    // The system then answers / routes the audio itself, which
    // is why Flyme Island's speaker works while our direct
    // TelecomManager + setSpeakerphoneOn calls do not.
    // v129 (Flyme Island performCallAction): match the
    // notification by its KEY when the sender supplies one —
    // the key identifies THIS call. Fall back to package-only
    // when no key is given.
    private boolean fireCallAction(String command, String key) {
        // v135: two passes. First try the given key; if no incallui
        // notification matches it (the key went stale when the
        // notification refreshed after answering), fall back to
        // package-only — there is only ever one active call, so the
        // first incallui notification IS the call.
        if (fireCallActionPass(command, key, true)) {
            reportCallResult("切换: 已点扬声器");
            return true;
        }
        if (fireCallActionPass(command, key, false)) {
            reportCallResult("切换: 已点扬声器(按包)");
            return true;
        }
        // v137: report what the lookup actually saw, so we can tell
        // "no call notification" from "notification has no speaker".
        reportCallResult(describeCallActions());
        return false;
    }

    private void reportCallResult(String text) {
        try {
            android.content.Intent i =
                    new android.content.Intent(ACTION_CALL_RESULT);
            // No setPackage: this goes to SystemUI (XposedEntry),
            // which is a different package. The action name is
            // unique to this module so nobody else listens for it.
            i.putExtra("call_result", text);
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    private String describeCallActions() {
        try {
            android.service.notification.StatusBarNotification[] act =
                    getActiveNotifications();
            if (act == null) {
                return "切换查找: 无通知列表";
            }
            for (android.service.notification.StatusBarNotification sbn
                    : act) {
                if (sbn == null
                        || !"com.android.incallui".equals(
                                sbn.getPackageName())) {
                    continue;
                }
                android.app.Notification n = sbn.getNotification();
                if (n == null || n.actions == null) {
                    return "切换查找: 通话通知无按钮";
                }
                StringBuilder sb = new StringBuilder("切换查找:");
                for (android.app.Notification.Action a : n.actions) {
                    if (a != null && a.title != null) {
                        sb.append(" [").append(a.title.toString())
                                .append("]");
                    }
                }
                sb.append(" 无扬声器");
                return sb.toString();
            }
            return "切换查找: 无通话通知";
        } catch (Throwable t) {
            return "切换查找失败: " + t;
        }
    }

    private boolean fireCallActionPass(String command, String key,
            boolean matchKey) {
        try {
            android.service.notification.StatusBarNotification[] act =
                    getActiveNotifications();
            if (act == null) {
                return false;
            }
            int want = classifyCallAction(command);
            if (want < 0) {
                return false;
            }
            for (android.service.notification.StatusBarNotification sbn
                    : act) {
                if (sbn == null
                        || !"com.android.incallui".equals(
                                sbn.getPackageName())) {
                    continue;
                }
                if (matchKey && key != null && !key.isEmpty()
                        && !key.equals(sbn.getKey())) {
                    continue;
                }
                android.app.Notification n = sbn.getNotification();
                if (n == null || n.actions == null) {
                    continue;
                }
                for (android.app.Notification.Action a : n.actions) {
                    if (a == null || a.actionIntent == null
                            || a.title == null) {
                        continue;
                    }
                    int kind = classifyCallAction(a.title.toString());
                    // v138: the notification carries ONE speaker
                    // action (a toggle, "打开扬声器"), not separate
                    // on/off actions. If the UI's speaker flag is out
                    // of sync the tap sends speaker_off (want 4)
                    // while only kind 3 exists, so nothing matches.
                    // For a speaker command, fire whichever speaker
                    // action exists — it toggles the speaker.
                    boolean speakerCmd = want == 3 || want == 4;
                    boolean speakerAct = kind == 3 || kind == 4;
                    if (speakerCmd ? !speakerAct : kind != want) {
                        continue;
                    }
                    try {
                        if (!"com.android.incallui".equals(
                                a.actionIntent.getCreatorPackage())) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                        continue;
                    }
                    a.actionIntent.send();
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // v125 (Flyme Island CallActionPolicy.classify, same titles):
    // 0 answer, 1 reject, 2 hangup, 3 speaker on, 4 speaker off,
    // 5 silence.
    private int classifyCallAction(String title) {
        if (title == null) {
            return -1;
        }
        String t = title.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.equals("接听") || t.equals("接聽")
                || t.equals("answer") || t.equals("accept")) {
            return 0;
        }
        if (t.equals("拒接") || t.equals("拒绝") || t.equals("拒絕")
                || t.equals("decline") || t.equals("reject")) {
            return 1;
        }
        if (t.equals("挂断") || t.equals("掛斷")
                || t.equals("hang up")) {
            return 2;
        }
        // v130: the system button shows an icon, not text — the
        // real title is often just "扬声器" / "speaker" / "免提"
        // (a single toggle), not "打开扬声器". Match by CONTAINS.
        if (t.contains("扬声器") || t.contains("揚聲器")
                || t.contains("speaker") || t.contains("免提")) {
            if (t.contains("关") || t.contains("關")
                    || t.contains("off")) {
                return 4;
            }
            return 3;
        }
        if (t.equals("静音") || t.equals("靜音")
                || t.equals("silence")) {
            return 5;
        }
        return -1;
    }

    private final android.content.BroadcastReceiver callControlReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context,
                        android.content.Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    // v546: 长按菜单「这个应用不再弹」——在
                    // App 进程里走 AppRules.setRule 落盘（单一
                    // 数据源），它自己会回广播刷新 SystemUI。
                    if (ACTION_SET_APP_RULE.equals(intent.getAction())) {
                        String rp = intent.getStringExtra("pkg");
                        String rm = intent.getStringExtra("mode");
                        if (rp != null) {
                            AppRules.setRule(context, rp,
                                    AppRules.parse(rm));
                            FileLog.i("SetAppRule pkg=" + rp
                                    + " mode=" + rm);
                        }
                        return;
                    }
                    // v564: 轻气泡小签拖动落盘——写 prefs +
                    // 备份，再回 FEATURE_TOGGLE 让 SystemUI
                    // 对齐位置（同 collectDotPos 的旧路）。
                    if (ACTION_SET_LIGHT_DOT_POS.equals(
                            intent.getAction())) {
                        String pos = intent.getStringExtra("pos");
                        pos = FeatureFlags.parseLightDotPos(pos);
                        context.getSharedPreferences(
                                "flymebobo_features",
                                android.content.Context.MODE_PRIVATE)
                                .edit().putString("lightDotPos", pos)
                                .commit();
                        SettingsStore.saveAll(context);
                        android.content.Intent back =
                                new android.content.Intent(
                                        ACTION_FEATURE_TOGGLE);
                        back.putExtra("key", "lightDotPos");
                        back.putExtra("pos", pos);
                        context.sendBroadcast(back);
                        FileLog.i("SetLightDotPos pos=" + pos);
                        return;
                    }
                    // v171: music card control from SystemUI.
                    if (ACTION_MUSIC_CONTROL.equals(intent.getAction())) {
                        String mcmd = intent.getStringExtra("command");
                        long seek = intent.getLongExtra(
                                "seek_position", -1);
                        handleMusicControl(mcmd, seek);
                        return;
                    }
                    // v143: dismiss a notification after the card
                    // opened its app. v490: use cancelByKey (all
                    // three cancel attempts) — the plain
                    // cancelNotification(key) alone does not remove
                    // delegated push notifications (v488 proved it:
                    // HookCancel sent dismiss, CancelDiag still
                    // stillActive=true 0.5s later).
                    if (ACTION_DISMISS.equals(intent.getAction())) {
                        String dkey = intent.getStringExtra("key");
                        if (dkey != null) {
                            cancelByKey(dkey,
                                    intent.getStringExtra("postPkg"));
                        }
                        return;
                    }
                    String cmd = intent.getStringExtra("command");
                    if (cmd == null) {
                        return;
                    }
                    String key = intent.getStringExtra("call_key");
                    fireCallAction(cmd, key);
                }
            };

    // v130: scan a call notification's actions for the speaker
    // button(s) and keep their PendingIntents.
    private void extractCallSpeakerIntents(
            android.app.Notification n) {
        callSpeakerOnIntent = null;
        callSpeakerOffIntent = null;
        if (n == null || n.actions == null) {
            return;
        }
        for (android.app.Notification.Action a : n.actions) {
            if (a == null || a.actionIntent == null
                    || a.title == null) {
                continue;
            }
            int kind = classifyCallAction(a.title.toString());
            if (kind == 3) {
                callSpeakerOnIntent = a.actionIntent;
            } else if (kind == 4) {
                callSpeakerOffIntent = a.actionIntent;
            }
        }
    }

    private void sendCallBroadcast(String state, String name,
            String number) {
        try {
            Intent intent = new Intent(ACTION_CALL);
            intent.putExtra("call_state", state);
            intent.putExtra("call_name", name);
            intent.putExtra("call_number", number);
            // v129: the REAL system notification key, so the
            // SystemUI side can tell the module which
            // notification's action to fire (Flyme Island passes
            // its island_source_key the same way).
            if (callKey != null) {
                intent.putExtra("call_key", callKey);
            }
            // v130: hand the speaker buttons to SystemUI so it
            // can fire them directly at tap time.
            if (callSpeakerOnIntent != null) {
                intent.putExtra("speaker_on_intent",
                        callSpeakerOnIntent);
            }
            if (callSpeakerOffIntent != null) {
                intent.putExtra("speaker_off_intent",
                        callSpeakerOffIntent);
            }
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    /** v522: true for Meituan's Flyme live-activity notification
     * (is_live / live capsule extras, delegated to or posted by
     * com.sankuai.meituan). These belong to the system's own live
     * window; the module must not intercept them. */
    private static boolean isMeituanLiveNotif(
            StatusBarNotification sbn, android.app.Notification n) {
        try {
            if (sbn == null || n == null || n.extras == null) {
                return false;
            }
            boolean live = n.extras.getBoolean("is_live", false)
                    || n.extras.getBundle(
                            "notification.live.capsule") != null;
            if (!live) {
                return false;
            }
            String delegated = n.extras.getString(
                    "notification.live.app.pkg.delegated");
            return "com.sankuai.meituan".equals(delegated)
                    || "com.sankuai.meituan".equals(
                            sbn.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }
        try {
            android.app.Notification n = sbn.getNotification();
            // 1.x fixed33: one log line per posted notification, so a
            // double delivery shows up as two Posted lines for the same
            // key. FileLog writes to /data/local/tmp/BubbleNotify.log
            // (and /sdcard/BubbleNotify.log) — export it after a double.
            FileLog.i("Posted pkg=" + sbn.getPackageName() + " key=" + sbn.getKey()
                    + " flags=" + n.flags);
            // Incoming call (v103): handle it on its own path, not as
            // a regular notification. Cancel the system banner so only
            // the call bubble shows (1.x handleCallNotification).
            if (isCallNotification(sbn, n)) {
                // v112/v113: the system re-posts the call
                // notification when it is answered (incoming ->
                // ongoing). Without this guard the same call sent
                // RINGING again, and BubbleWindow.onCall("ringing")
                // wiped the answered state (speaker choice, timer)
                // — the tap answered via earpiece and the button
                // stayed a ringing handset. v112 deduped by
                // notification key, but the ongoing notification
                // can carry a different key, so v113 dedups by the
                // phone number too: same caller + a call already up
                // = the same call updating, skip it. A different
                // number still rings (call waiting).
                android.os.Bundle cb = n.extras;
                String rawTitle = cb != null ? cb.getString(
                        android.app.Notification.EXTRA_TITLE) : null;
                String rawText = cb != null ? cb.getString(
                        android.app.Notification.EXTRA_TEXT) : null;
                // v389: recognise name vs number (user 2026-11-22
                // "识别名称和号码"). Flyme's display strings live
                // in notification.live.titleOverlay /
                // contentOverlay — the formatted number with spaces
                // ("188 8320 4388"), which EXTRA_TITLE/EXTRA_TEXT
                // do not always carry (first post: title = raw
                // "18883204388", text empty).
                String titleOverlay = cb != null ? cb.getString(
                        "notification.live.titleOverlay") : null;
                String contentOverlay = cb != null ? cb.getString(
                        "notification.live.contentOverlay") : null;
                // Number: first phone-number-like string, preferring
                // one that already has spaces (formatted). Name:
                // first non-empty string that is NOT a number.
                String number = null;
                if (isPhoneNumber(titleOverlay)) {
                    number = formatNumber(titleOverlay);
                } else if (isPhoneNumber(rawText)) {
                    number = formatNumber(rawText);
                } else if (isPhoneNumber(contentOverlay)) {
                    number = formatNumber(contentOverlay);
                } else if (isPhoneNumber(rawTitle)) {
                    number = formatNumber(rawTitle);
                }
                String name = null;
                if (rawTitle != null && !rawTitle.trim().isEmpty()
                        && !isPhoneNumber(rawTitle)) {
                    name = rawTitle.trim();
                } else if (titleOverlay != null
                        && !titleOverlay.trim().isEmpty()
                        && !isPhoneNumber(titleOverlay)) {
                    name = titleOverlay.trim();
                }
                if (callKey != null
                        && callKey.equals(sbn.getKey())) {
                    // v389: same key, but the second post may be the
                    // name resolving (first post had only the number
                    // as the title). If we stored no real name yet
                    // and this post brings one, update the capsule
                    // instead of dropping it. Still RINGING (not
                    // offhook), so re-sending RINGING only refreshes
                    // the capsule — BubbleWindow drops it once
                    // offhook.
                    boolean storedHasName = callName != null
                            && !callName.trim().isEmpty()
                            && !isPhoneNumber(callName);
                    if (name != null && !storedHasName) {
                        callName = name;
                        if (number != null) {
                            callNumber = digitsOnly(number);
                        }
                        extractCallSpeakerIntents(n);
                        sendCallBroadcast(CALL_RINGING, name, number);
                    }
                    return;
                }
                // v132: match by name too — after answering, the
                // re-posted notification's EXTRA_TEXT is the call
                // duration, not the number, so the number check
                // misses it and the re-post falls through to the
                // "new call" branch, re-sending RINGING. The caller
                // name (EXTRA_TITLE) does not change.
                // v135: but only when a call is actually OFFHOOK. A
                // stale callNumber/callName (v131 kept them when the
                // removal raced the telephony state) makes the NEXT
                // call from the same number look like a re-post, so
                // no RINGING is sent and no bubble pops. A re-post
                // only happens after answering (OFFHOOK); a new
                // incoming call arrives while RINGING.
                // v389: callNumber is stored digits-only, so the
                // formatted display number ("188 8320 4388") matches
                // the raw one ("18883204388") in this comparison.
                boolean sameNumber = callNumber != null
                        && number != null
                        && callNumber.equals(digitsOnly(number));
                boolean sameName = callName != null && name != null
                        && callName.equals(name);
                boolean callOffhook = false;
                try {
                    android.telephony.TelephonyManager tm =
                            (android.telephony.TelephonyManager)
                            getSystemService(
                                    android.content.Context
                                            .TELEPHONY_SERVICE);
                    callOffhook = tm != null
                            && tm.getCallState()
                            == android.telephony.TelephonyManager
                                    .CALL_STATE_OFFHOOK;
                } catch (Throwable ignored) {
                }
                if ((sameNumber || sameName) && callOffhook) {
                    // v129: same call, re-posted after answering
                    // (incoming -> ongoing). The ongoing
                    // notification can carry a DIFFERENT key, and
                    // its actions are the ones that matter now
                    // (speaker on/off). Update the stored key and
                    // tell SystemUI, so the speaker toggle fires
                    // the refreshed notification's action — but do
                    // NOT re-send RINGING (that would wipe the
                    // answered state, v112/v113).
                    if (sbn.getKey() != null
                            && !sbn.getKey().equals(callKey)) {
                        callKey = sbn.getKey();
                        extractCallSpeakerIntents(n);
                        sendCallBroadcast(CALL_OFFHOOK, name, number);
                    }
                    return;
                }
                callKey = sbn.getKey();
                // v389: store digits-only for the dedup above; the
                // broadcast still carries the formatted number for
                // display.
                callNumber = digitsOnly(number);
                callName = name;
                extractCallSpeakerIntents(n);
                sendCallBroadcast(CALL_RINGING, name, number);
                // v125 (Flyme Island): do NOT cancel the call
                // notification. Its actions (answer / speaker /
                // hangup) must stay live so they can be fired via
                // their own PendingIntents. The banner is suppressed
                // by CallBannerSuppress and the full-screen UI by
                // InCallUiHide — the cancel is no longer needed.
                return;
            }
            // Music is detected by a MediaSession token in the
            // notification extras, OR by CATEGORY_TRANSPORT (1.x
            // fixed175: handleMediaNotification is entered when the
            // token is present, regardless of category). Some players
            // post a media notification with a token but without the
            // TRANSPORT category — 2.0 only checked the category, so
            // they were never recognised as music and the pinned
            // capsule only ever showed the song name. A music
            // player's notification is an ongoing foreground-service
            // notification, and the user wants it as a bubble (user
            // 2026-11-19 "下面开始音乐播放器通知").
            android.media.session.MediaSession.Token mediaToken = null;
            Bundle exForToken = n.extras;
            if (exForToken != null) {
                try {
                    android.os.Parcelable tok = exForToken.getParcelable(
                            android.app.Notification.EXTRA_MEDIA_SESSION);
                    if (tok instanceof android.media.session.MediaSession.Token) {
                        mediaToken = (android.media.session.MediaSession.Token) tok;
                    }
                } catch (Throwable ignored) {
                }
            }
            boolean isMusic = !isSongRecognition(n)
                    && (android.app.Notification.CATEGORY_TRANSPORT
                            .equals(n.category) || mediaToken != null
                            || isKnownMusicApp(sbn.getPackageName(), n));
            // v532: 视频App开关（FeatureFlags.musicVideo，默认
            // 开 = 现状）——判别口径与 NotifCollectionHook 一致：
            // 音乐信号不分音频视频，用内置视频 App 包名集合判
            // 来源；关时视频 App 的播放不算音乐，按普通通知走
            // 后面的流程（见 isVideoApp）。
            if (isMusic && !FeatureFlags.musicVideo
                    && isVideoApp(sbn.getPackageName())) {
                isMusic = false;
            }
            // v522 (user 2026-11-25): Meituan's Flyme live activity
            // is posted by com.meizu.suggestion (delegated to
            // com.sankuai.meituan) and the SYSTEM renders its live
            // window from that very notification. Converting it to
            // a bubble and cancelling the original kills the native
            // live display — hands off entirely: no bubble, no
            // cancel, no dedup.
            if (isMeituanLiveNotif(sbn, n)) {
                FileLog.i("LivePass meituan key=" + sbn.getKey());
                return;
            }
            // Skip ongoing / foreground-service notifications unless
            // they carry download progress (or are music).
            boolean ongoing = (n.flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0
                    || (n.flags & android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0;
            Bundle extras = n.extras;
            // 1.x fixed133/136: progress from the extras, refined by the
            // live RemoteViews ProgressBar (highest percent wins). A
            // notification only "has progress" when the max is real
            // (> 0) or it is indeterminate — mere key presence (stale
            // 0/0 extras from chat apps) does NOT count. 2.0's old
            // containsKey check wrongly exempted those from the stale
            // check, the flags mask and the fingerprint dedup.
            int notifProgress = extras != null ? extras.getInt(
                    "android.progress", 0) : 0;
            int notifProgressMax = extras != null ? extras.getInt(
                    "android.progressMax", 0) : 0;
            boolean notifProgressIndeterminate = extras != null
                    && extras.getBoolean("android.progressIndeterminate", false);
            int[] rvProgress = readProgressFromRemoteViews(n);
            if (rvProgress != null && rvProgress[1] > 0) {
                int rvPct = (rvProgress[0] * 100) / rvProgress[1];
                int exPct = notifProgressMax > 0
                        ? (notifProgress * 100) / notifProgressMax : -1;
                if (rvPct > exPct) {
                    notifProgress = rvProgress[0];
                    notifProgressMax = rvProgress[1];
                }
            }
            boolean hasProgress = notifProgressMax > 0
                    || notifProgressIndeterminate;
            if (ongoing && !hasProgress && !isMusic) {
                return;
            }
            // 1.x verbatim (fixed33): title/text with the full fallback
            // chain — some apps keep content outside android.text
            // (WeChat MessagingStyle -> android.messages / textLines,
            // Meituan / custom content -> bigText / RemoteViews). Without
            // these, the dedup digest is built from an empty text and a
            // re-delivery whose text moved fields pops again.
            String title = getText(extras, "android.title");
            String text = getText(extras, "android.text");
            // v604: 完整正文单独留一份随广播带走（经典侧
            // 取件码/验证码识别也要看全文，wei 定）——下
            // 面的 text 链条只有 text 为空时才用 bigText。
            String bigTextRaw = getText(extras,
                    "android.bigText");
            if (text == null || text.isEmpty()) {
                text = getText(extras, "android.bigText");
            }
            if (text == null || text.isEmpty()) {
                text = getLastTextLine(extras);
            }
            if (text == null || text.isEmpty()) {
                text = getLatestMessageText(extras);
            }
            if (text == null || text.isEmpty()) {
                CharSequence ticker = n.tickerText;
                if (ticker != null && !ticker.toString().trim().isEmpty()) {
                    text = ticker.toString();
                }
            }
            if (text == null || text.isEmpty()) {
                text = getText(extras, "android.subText");
            }
            // Music: song / singer / lyrics. Prefer the MediaSession
            // metadata (definitive title + artist); fall back to the
            // notification's own title / text. Lyrics come from the
            // notification (sub-text / ticker / big text — 1.x read
            // lyrics from ticker -> text -> bigText -> subtitle);
            // when no lyric is available, the song name is used.
            // Music: resolve song / singer / lyrics with the 1.x
            // logic (resolveMusicLyric — swapped layout + validated
            // pick). The notification title is the stable song anchor
            // (1.x fixed18); the MediaSession TITLE may itself be a
            // lyric line.
            // Download: a regular notification carrying progress.
            // Append the percent to line 2 (1.x fixed133) and start
            // the 1s live tick so the capsule updates in real time.
            int downloadPct = -1;
            boolean isDownloadType = false;
            if (!isMusic) {
                downloadPct = readDownloadPct(n, title, text);
                // Dual condition (v110): real progress AND a download
                // signal. v102's pure-progress check mis-detected chat
                // messages — a WeChat job post was treated as a
                // download (user 2026-11-19 "微信这种消息他都识别成
                // 下载"), because chat apps can carry stale progress
                // extras and hasProgress is mere key presence. The
                // signal (下载 in text, a download channel, or a known
                // store/browser/downloader) plus the upload/backup/
                // sync/install exclusion keeps those out.
                isDownloadType = downloadPct >= 0
                        && hasDownloadSignal(sbn.getPackageName(),
                                n.getChannelId(), title, text);
                if (isDownloadType) {
                    // v240: bytes / speed / ETA from the text
                    // fields first (Coolapk infoText, browser
                    // text+subText, TapTap text), extras byte
                    // counts as the fallback.
                    String dlInfo = extras != null
                            ? getText(extras, "android.infoText")
                            : null;
                    String dlSub = extras != null
                            ? getText(extras, "android.subText")
                            : null;
                    long[] dlParsed = parseDownloadedTotal(
                            text, dlInfo);
                    // v613: 大小对可能只在标题里（夸克）。
                    if (dlParsed == null) {
                        dlParsed = parseDownloadedTotal(
                                title, null);
                    }
                    long[] dlBytes = dlParsed != null
                            ? dlParsed : readDownloadBytes(n);
                    long dlB = dlBytes != null ? dlBytes[0] : -1;
                    long dlT = dlBytes != null ? dlBytes[1] : -1;
                    long dlSpeed = parseSpeedFromText(text);
                    long dlEta = parseEtaSeconds(text, dlSub);
                    lastDownloadBytes = dlB;
                    lastDownloadBytesTime =
                            android.os.SystemClock.uptimeMillis();
                    String[] lines = normalizeDownload(
                            title, text, downloadPct, dlB, dlT,
                            dlSpeed, dlEta);
                    title = lines[0];
                    text = lines[1];
                    downloadKey = sbn.getKey();
                    lastDownloadPct = downloadPct;
                    lastDownloadSpeed = -1;
                    lastDownloadEta = -1;
                    startDownloadTick();
                } else if (downloadPct < 0
                        && hasDownloadSignal(sbn.getPackageName(),
                                n.getChannelId(), title, text)
                        && isDownloadComplete(title, text)) {
                    // v319: small file that finished before any
                    // progress-bearing post was seen — the hook may
                    // already have popped its capsule, but the tick
                    // never started (isDownloadType needs pct >= 0),
                    // so nothing would ever remove it. Track it so
                    // the tick's completed/gone path drops it.
                    downloadKey = sbn.getKey();
                    lastDownloadPct = 100;
                    lastDownloadSpeed = -1;
                    lastDownloadEta = -1;
                    startDownloadTick();
                }
            }
            String lyrics = null;
            if (isMusic && extras != null) {
                String prevMusicKey = musicKey;
                String rawTitle = null;
                String subtitle = null;
                String artistMeta = null;
                String albumArtist = null;
                try {
                    // Token was already read above (isMusic check);
                    // reuse it here.
                    android.media.session.MediaSession.Token tok = mediaToken;
                    if (tok != null) {
                        // v199: if the session token changed, the user
                        // switched music apps. The old controller is
                        // bound to the old app's session (e.g. Flyme
                        // QQ Music), so progress / playback state /
                        // lyrics would all keep coming from the old
                        // app while the card shows the new app's song
                        // name (the name comes from the notification,
                        // the rest from the controller). Drop the old
                        // controller (and its callback) so
                        // ensureMusicController below builds one for
                        // the new token.
                        if (musicToken != null && !musicToken.equals(tok)) {
                            if (musicController != null) {
                                if (musicCallback != null) {
                                    try {
                                        musicController.unregisterCallback(musicCallback);
                                    } catch (Throwable ignored) {
                                    }
                                }
                                musicController = null;
                            }
                            musicCallback = null;
                            lastLyricSent = null;
                        }
                        // Remember the session so the lyric listener
                        // (v76) can attach when the capsule is pinned.
                        // If already listening, re-attach to the new
                        // token (a song change may bring a new one).
                        musicToken = tok;
                        musicKey = sbn.getKey();
                        // v172: a session attached — make sure the
                        // controller + progress tick are running even
                        // when nothing is pinned, so the open card
                        // refreshes.
                        ensureMusicController();
                        if (musicListening) {
                            setupMusicListener();
                        }
                        android.media.session.MediaController mc =
                                new android.media.session.MediaController(
                                        this,
                                        (android.media.session.MediaSession.Token) tok);
                        android.media.MediaMetadata md = mc.getMetadata();
                        if (md != null) {
                            rawTitle = md.getString(
                                    android.media.MediaMetadata.METADATA_KEY_TITLE);
                            artistMeta = md.getString(
                                    android.media.MediaMetadata.METADATA_KEY_ARTIST);
                            subtitle = md.getString(
                                    android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                            albumArtist = md.getString(
                                    android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                        }
                    }
                } catch (Throwable ignored) {
                }
                // Store this notification's lyric candidates into
                // the persistent slots (1.x updateLyricCandidates).
                // Same key as the PREVIOUS music notification = same
                // song: only fill slots that have a value, so a
                // lyric-less re-post does not wipe the lyric we
                // already have. (musicKey was just overwritten with
                // this key above, so compare against prevMusicKey.)
                boolean sameSong = prevMusicKey != null
                        && prevMusicKey.equals(sbn.getKey());
                updateLyricCandidates(n, sameSong);
                // v196: fix the song/artist BEFORE resolving the
                // lyric (user 2026-11-20 "所有歌词的地方全部放上了
                // 歌名，歌名的地方是空白的"). QQ Music (Flyme build
                // and the original) posts a player notification
                // whose EXTRA_TITLE is the app name ("QQ音乐") and
                // EXTRA_TEXT is a status message ("当前列表为空，
                // 点击选择歌曲播放") — the song name never reaches
                // the title slot, and the lyric resolver then
                // accepts the song name itself as a lyric
                // (checkTitle is null when there is no MediaSession
                // TITLE), so every lyric slot shows the song name
                // and the title slot is blank. Fix the title first,
                // in this order: (a) parse "Artist - Song" from
                // ticker/bigText/text (v175, Flyme build); (b)
                // MediaSession metadata TITLE/ARTIST (the original
                // QQ Music carries them; the token came from this
                // notification's own extras, and this only runs
                // when the notification itself has no song info,
                // so it cannot overwrite a real title); (c) the
                // bare ticker/bigText/text as the song name when
                // it is short and not a status message or a
                // credits line (the original QQ Music ticker is
                // just the song name, no dash). Once the title is
                // the real song name, the lyric resolver below
                // rejects it as a lyric and the real lyric is
                // found.
                if (title != null && !title.isEmpty()) {
                    String parsedSong = null;
                    String parsedArtist = null;
                    try {
                        CharSequence label = getPackageManager()
                                .getApplicationLabel(
                                        getPackageManager()
                                                .getApplicationInfo(
                                                        sbn.getPackageName(),
                                                        0));
                        if (label != null
                                && label.toString().equals(title)) {
                            String candidate = currentLyricTicker;
                            if (candidate == null
                                    || candidate.isEmpty()) {
                                candidate = currentLyricBigText;
                            }
                            if (candidate == null
                                    || candidate.isEmpty()) {
                                candidate = currentLyricText;
                            }
                            String[] parsed =
                                    parseArtistSong(candidate);
                            if (parsed != null) {
                                parsedArtist = parsed[0];
                                parsedSong = parsed[1];
                            }
                            if (parsedSong == null
                                    && rawTitle != null
                                    && !rawTitle.isEmpty()
                                    && !isCreditOrLyricTitle(rawTitle)
                                    && !rawTitle.equals(title)) {
                                parsedSong = rawTitle;
                                if (artistMeta != null
                                        && !artistMeta.isEmpty()
                                        && !isCreditOrLyricTitle(
                                                artistMeta)) {
                                    parsedArtist = artistMeta;
                                }
                            }
                            if (parsedSong == null
                                    && candidate != null
                                    && !candidate.isEmpty()
                                    && candidate.length() <= 30
                                    && !isCreditOrLyricTitle(candidate)
                                    && !isStatusMessage(candidate)) {
                                parsedSong = candidate.trim();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (parsedSong != null) {
                        title = parsedSong;
                        if (parsedArtist != null) {
                            text = parsedArtist;
                        }
                    }
                }
                lyrics = resolveMusicLyric(rawTitle, subtitle, artistMeta,
                        albumArtist, title);
                // Song/artist: the notification's own EXTRA_TITLE /
                // EXTRA_TEXT are app-specific and authoritative. NEVER
                // overwrite them with MediaSession metadata — both
                // apps here use the SYSTEM player (one shared
                // MediaSession), so the session's metadata is
                // whichever app last wrote to it (here Flyme QQ
                // Music's song), and the NetEase capsule ended up
                // showing Flyme QQ Music's song/artist. The MediaSession
                // is used only for lyric resolution above, never for
                // song/artist. (Swapped-layout parse still runs inside
                // resolveMusicLyric for the lyric; it no longer
                // rewrites title/text.)
                // Only fill in a missing artist from the metadata.
                if ((text == null || text.isEmpty())
                        && artistMeta != null && !artistMeta.isEmpty()
                        && !isCreditOrLyricTitle(artistMeta)) {
                    text = artistMeta;
                }
                // v196: the v175 title fix moved BEFORE the
                // lyric resolution above (it must run first, or the
                // lyric resolver accepts the song name as a lyric).
                // v189 (1.0.5 method): NO fallback of the lyric to
                // the song name. 1.0.5 never does this — no lyric
                // means no lyric, and the broadcast simply omits
                // the lyric extra. Falling back to the title is
                // why a pinned capsule showed the song name where
                // the lyric belongs (user 2026-11-20 "钉住应该读
                // 歌词它读的歌名").
            }
            if ((title == null || title.isEmpty())
                    && (text == null || text.isEmpty())) {
                // v375: an empty notification is still a visible
                // card — cancel it so it does not leak onto the
                // lock screen / shade.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // Full 1.x dedup (v105, user "完整套用"), in 1.x order:
            // absolute stale -> flags mask -> per-package fingerprint
            // -> key (content-aware, progress-suffixed) -> redundant
            // message (lifecycle) -> duplicate (pkg fp + content fp +
            // summary/aggregated, skipped for progress) -> group
            // summary. Any hit drops the notification outright (it
            // never reaches the bubble).
            String pkg = sbn.getPackageName();
            // notifProgress/notifProgressMax were read (and refined by
            // RemoteViews) above for hasProgress; reuse them here.
            if (downloadPct >= 0 && notifProgressMax <= 0) {
                notifProgress = downloadPct;
                notifProgressMax = 100;
            }
            // 1.x fixed136: refine progress from the text ("45%",
            // "12MB/50MB") — the text often carries the live percent
            // while the extras stay at 0. Highest wins.
            int textPct = parseProgressPercent(text);
            if (textPct < 0) {
                textPct = parseProgressPercent(title);
            }
            if (textPct >= 0) {
                int curPct = notifProgressMax > 0
                        ? (notifProgress * 100) / notifProgressMax : -1;
                if (textPct > curPct) {
                    if (notifProgressMax > 0) {
                        notifProgress = (textPct * notifProgressMax) / 100;
                    } else {
                        notifProgressMax = 100;
                        notifProgress = textPct;
                    }
                    hasProgress = true;
                }
            }
            // Absolute stale (1.x fixed28, Flyme Island STALE): postTime
            // more than 5s ago means the system re-delivered an old
            // notification (e.g. after the listener reconnected). A
            // download lasts minutes — exempt progress notifications;
            // music is exempt too (1.x returns early for music before
            // this check, and lyric re-posts reuse the old postTime).
            if (!hasProgress && !isMusic && System.currentTimeMillis()
                    - sbn.getPostTime() > STALE_WINDOW_MS) {
                FileLog.i("Stale notification dropped (>5s old): " + pkg);
                // v375: a re-delivered stale notification is a
                // fresh visible card — cancel it (no-op if it is
                // already gone).
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // Flags mask (1.x fixed28/31/135/137): 578 = 0x242 =
            // ONGOING | FOREGROUND_SERVICE | GROUP_SUMMARY. Ongoing /
            // foreground-service notifications are not user messages,
            // and a group summary is redundant once its children are
            // shown. Delivery orders are exempt from the ongoing bit
            // only (576); progress notifications are exempt from both
            // ongoing bits (512, GROUP_SUMMARY only).
            boolean isDeliveryOrder = PKG_MEITUAN.equals(pkg)
                    || PKG_ELEME.equals(pkg) || PKG_AMAP.equals(pkg);
            int dropMask = hasProgress ? 512
                    : (isDeliveryOrder ? 576 : 578);
            // Music is exempt (1.x returns early for music before the
            // mask — a player notification is ongoing by definition).
            if (!isMusic && (n.flags & dropMask) != 0) {
                FileLog.i("Notification dropped by flags mask " + dropMask + ": " + pkg);
                // v372: a group summary dropped here is still a
                // visible notification (its children popped as
                // bubbles) — cancel it so it does not leak onto the
                // lock screen / shade.
                // v377: same for ONGOING / FOREGROUND_SERVICE.
                // BOSS 直聘 (and similar apps) post a real user
                // message — a job recommendation — with the
                // ongoing / foreground-service flag set, so it was
                // dropped here with no bubble AND no cancel, and
                // the card stayed on the lock screen (user
                // 2026-11-22 "这种还是没有屏蔽到"). A dropped
                // notification is a visible card either way, so
                // cancel it. Music / downloads are exempt (they
                // never reach this branch).
                if (!isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // Per-package fingerprint (1.x fixed28): identical
            // id:postTime:title/text:progress means the exact same
            // notification was re-delivered.
            if (isRepeatNotifFingerprint(pkg, sbn, title, text,
                    notifProgress, notifProgressMax)) {
                FileLog.i("Repeat notification fingerprint dropped: " + pkg);
                // v375: a re-delivered notification is a fresh
                // visible card — cancel it (no-op if gone).
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (isRepeatNotif(sbn.getKey(), title, text,
                    notifProgress, notifProgressMax)) {
                FileLog.i("Repeat notification key dropped: " + sbn.getKey());
                // v375: same — cancel the re-delivered card.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (isStalePostTime(sbn.getKey(), sbn.getPostTime())) {
                FileLog.i("Stale postTime dropped: " + sbn.getKey());
                // v375: same — cancel the stale card.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // Per-key message lifecycle (1.x fixed11/12): a re-post
            // of the same notification, or a summary that only repeats
            // already-seen messages, is redundant no matter which app
            // posted it.
            if (isRedundantMessage(sbn, n, extras, pkg, title, text)) {
                FileLog.i("Redundant message dropped (already seen): " + pkg);
                // v375: a re-posted message is a fresh visible
                // card — cancel it (no-op if gone).
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // Duplicate (1.x fixed135): skipped for progress
            // notifications — a progress update has the same title/text
            // as the last one, so it would be dropped as a duplicate
            // within its 15s window. isRepeatNotif is already
            // progress-aware and still catches exact duplicates.
            // Smart push-service filter (v144): the push-service
            // copy of a message the app already posted is a duplicate
            // — drop it. A push copy the app never posted (BOSS/鱼泡
            // in the background) is the only copy, so it is kept.
            if (isRedundantPushCopy(pkg, title, text)) {
                FileLog.i("Redundant push copy dropped: " + pkg);
                // v372: the push copy is a DISTINCT notification
                // (different key, posted by the push service) — the
                // real app's copy already popped a bubble and was
                // cancelled, but this copy stays visible unless it
                // is cancelled too. That is the "系统推送" leak.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (!hasProgress && isPkgFingerprintRepeat(pkg, title, text)) {
                FileLog.i("Duplicate notification dropped (pkg fp): " + pkg);
                // v375: a duplicate is a FRESH visible card (new
                // post, same content) — cancel it. This is the
                // test app's "再发一条相同内容" case.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (!hasProgress && isFingerprintRepeat(pkg, title, text)) {
                FileLog.i("Duplicate notification dropped (content fp): " + pkg);
                // v375: same — cancel the duplicate card.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (isRedundantGroupSummary(n, pkg)) {
                FileLog.i("Group summary dropped (child already shown): " + pkg);
                // v372: cancel the summary itself — its children
                // already popped as bubbles, so the summary card
                // must not stay on the lock screen / shade.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (isSummaryOf(pkg, title, text)) {
                FileLog.i("Summary dropped: " + pkg);
                // v372: cancel the summary itself (same leak as the
                // group summary — its content is already shown).
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (isAggregatedSummary(pkg, text)) {
                FileLog.i("Aggregated summary dropped: " + pkg);
                // v372: cancel the aggregated summary itself.
                if (!isMusic && !isDownloadType && FeatureFlags.notif) {
                    try {
                        cancelNotif(sbn);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            noteNotifGroup(n, pkg);
            recordNotif(sbn.getKey(), title, text, pkg, sbn.getPostTime(),
                    notifProgress, notifProgressMax);
            // v171 (1.x fixed150 + fixed17): a music re-post is a REAL
            // song change only if the song's own numbers confirm it.
            // QQ Music Flyme re-posts on every lyric line with the
            // same song title — same duration, mid-song position —
            // so it is a lyric refresh, NOT a new song: only the
            // lyric line is sent (ACTION_LYRIC) and nothing pops.
            // A credits/lyric line as the title is never a song.
            if (isMusic) {
                // v195: title/artist change is computed FIRST.
                // v173's same-key check below used to return lyric-
                // only for EVERY same-key re-post, so a real song
                // change (same key, new title) never reached the
                // capsule — it stayed on the first song's name.
                // A same-key re-post is a lyric refresh ONLY when
                // the title/artist did NOT change; a changed title
                // or artist is a new song and falls through.
                boolean titleIsCredit = isCreditOrLyricTitle(title);
                boolean titleIsLyric = title != null
                        && !title.isEmpty()
                        && title.equals(lyrics);
                boolean titleChanged = !titleIsCredit && !titleIsLyric
                        && title != null && !title.isEmpty()
                        && !title.equals(currentMusicTitle);
                boolean artistChanged = text != null && !text.isEmpty()
                        && !text.equals(currentMusicArtist);
                // v173: same notification key re-posted = same song
                // (1.x same key = same song; Flyme Island 0.119:
                // same key + new postTime = re-post, not a pop).
                // This matters for apps with NO MediaSession (QQ
                // Music Flyme), where the duration/position check
                // below has no data and its "no info = new song"
                // fallback misfires on every lyric-line title
                // change. Lyric refresh only — and only when the
                // song itself did not change (v195).
                if (currentMusicNotifKey != null
                        && currentMusicNotifKey.equals(sbn.getKey())
                        && !titleChanged && !artistChanged) {
                    try {
                        Intent li = new Intent(ACTION_LYRIC);
                        li.putExtra("key", sbn.getKey());
                        li.putExtra("lyrics", lyrics);
                        sendBroadcast(li);
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                long sessDur = -1;
                long sessPos = -1;
                try {
                    if (musicController != null) {
                        android.media.MediaMetadata md =
                                musicController.getMetadata();
                        if (md != null) {
                            sessDur = md.getLong(
                                    android.media.MediaMetadata
                                            .METADATA_KEY_DURATION);
                        }
                        android.media.session.PlaybackState st =
                                musicController.getPlaybackState();
                        if (st != null) {
                            sessPos = st.getPosition();
                        }
                    }
                } catch (Throwable ignored) {
                }
                // titleChanged / artistChanged were computed
                // above (v195), before the same-key check.
                boolean songChangeConfirmed;
                if (!titleChanged && !artistChanged) {
                    songChangeConfirmed = false;
                } else if (artistChanged) {
                    // v227: the notification TEXT changed. For
                    // Flyme the text is "song - artist - album"
                    // — it only changes on a real song change,
                    // never on a lyric-line title cycle. The
                    // session may still hold the old song at
                    // broadcast time (previous-tap: old position
                    // / duration), so do not require session
                    // confirmation when the text itself proves
                    // a new song.
                    songChangeConfirmed = true;
                } else if (currentMusicTitle == null
                        || currentMusicTitle.isEmpty()) {
                    songChangeConfirmed = true;
                } else if (sessDur > 0 && currentMusicDurationMs > 0
                        && sessDur != currentMusicDurationMs) {
                    songChangeConfirmed = true;
                } else if (sessPos >= 0 && sessPos < 5000) {
                    songChangeConfirmed = true;
                } else if (sessPos < 0 && sessDur <= 0) {
                    songChangeConfirmed = true;
                } else {
                    songChangeConfirmed = false;
                }
                if (!songChangeConfirmed) {
                    try {
                        Intent li = new Intent(ACTION_LYRIC);
                        li.putExtra("key", sbn.getKey());
                        li.putExtra("lyrics", lyrics);
                        sendBroadcast(li);
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                currentMusicTitle = title;
                currentMusicArtist = text;
                currentMusicNotifKey = sbn.getKey();
                if (sessDur > 0) {
                    currentMusicDurationMs = sessDur;
                }
            }
            // Hand the notification to the bubble window in SystemUI.
            Intent intent = new Intent(ACTION_NOTIFY);
            intent.putExtra("key", sbn.getKey());
            intent.putExtra("pkg", sbn.getPackageName());
            intent.putExtra("title", title);
            intent.putExtra("text", text);
            // v604: 完整正文（见上 bigTextRaw）——接收端
            // 填进 NotifData.bigText，识别走 fullText()。
            if (bigTextRaw != null) {
                intent.putExtra("bigText", bigTextRaw);
            }
            intent.putExtra("lyrics", lyrics);
            intent.putExtra("postTime", sbn.getPostTime());
            intent.putExtra("isMusic", isMusic);
            intent.putExtra("isDownload", isDownloadType);
            // v239: download percent for the capsule bar.
            if (isDownloadType) {
                intent.putExtra("download_pct", downloadPct);
            }
            // v143: the card's open buttons need the tap action.
            // PendingIntent is Parcelable, so it rides the broadcast
            // like 1.x (rn.intent = notification.contentIntent).
            try {
                android.app.Notification posted =
                        sbn.getNotification();
                if (posted != null && posted.contentIntent != null) {
                    intent.putExtra("content_intent",
                            posted.contentIntent);
                }
            } catch (Throwable ignored) {
            }
            // v171: music card disc — album art, downscaled to 128px
            // (64KB, far under the binder limit) so the card can show
            // the real cover instead of the app icon. Full-size
            // largeIcon is NOT passed (v24: binder transactions).
            if (isMusic) {
                try {
                    android.app.Notification posted =
                            sbn.getNotification();
                    android.graphics.Bitmap art = null;
                    if (posted != null && posted.extras != null) {
                        // v225: unwrap the Icon (see
                        // bitmapFromLargeIcon) — a straight
                        // Bitmap cast throws for every music
                        // app and the broadcast never carried
                        // art.
                        Object li = posted.extras.getParcelable(
                                android.app.Notification
                                        .EXTRA_LARGE_ICON);
                        art = bitmapFromLargeIcon(li);
                    }
                    if (art != null && (art.getWidth() > 128
                            || art.getHeight() > 128)) {
                        art = android.graphics.Bitmap
                                .createScaledBitmap(art, 128, 128,
                                        true);
                    }
                    if (art != null) {
                        intent.putExtra("album_art", art);
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
            // Cancel so the notification lives only in the bubble.
            // Music is NOT cancelled (1.x): the live lyric refresh
            // re-reads the active notification on every callback, and
            // cancelling it would leave nothing to re-read. Downloads
            // are NOT cancelled either (v95): the 1s tick re-reads the
            // live progress from the active notification.
            boolean isDownload = isDownloadType;
            // v564: 轻气泡「弹出」档 = 放行系统正常通知——这条
            // 不撤，让它按系统原样留在通知栏（BubbleWindow 侧
            // 同样不收不弹）。模式与规则都读 App 进程自己的
            // prefs（本进程的 FeatureFlags 静态值不保证新鲜）。
            boolean lightRelease = false;
            if (!isMusic && !isDownload) {
                try {
                    boolean lm = getSharedPreferences(
                            "flymebobo_features",
                            android.content.Context.MODE_PRIVATE)
                            .getBoolean("lightMode", false);
                    if (lm) {
                        AppRules.load(this);
                        lightRelease = AppRules.modeFor(
                                sbn.getPackageName())
                                == AppRules.POP;
                        if (lightRelease) {
                            FileLog.i("Light release keep shade"
                                    + " pkg="
                                    + sbn.getPackageName());
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (!isMusic && !isDownload && FeatureFlags.notif
                    && !lightRelease) {
                try {
                    cancelNotif(sbn);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            // v311: Amap navigation ended — its nav notification
            // (foot 99910002 / ride 99910003) was removed. Tell
            // SystemUI to drop the nav capsule for this type.
            if (sbn != null
                    && "com.autonavi.minimap".equals(sbn.getPackageName())) {
                String bizType = null;
                if (sbn.getId() == 99910002) {
                    bizType = "footnavi";
                } else if (sbn.getId() == 99910003) {
                    bizType = "ridenavi";
                }
                if (bizType != null) {
                    try {
                        android.content.Intent intent =
                                new android.content.Intent(ACTION_NAVI_END);
                        intent.putExtra("bizType", bizType);
                        sendBroadcast(intent);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (sbn != null && downloadKey != null
                    && downloadKey.equals(sbn.getKey())) {
                // v321: Coolapk removes its download notification
                // the instant the download finishes (never posts a
                // "下载完成" state) — the tick's gone path never got
                // to run because this stopped the tick first, so
                // the capsule was left stuck at the last progress.
                // Tell SystemUI to drop the capsule before stopping
                // the tick. (A download that completed via the
                // "下载完成" update already cleared downloadKey, so
                // this only fires for a download still in progress.)
                sendDownloadRemove(downloadKey);
                stopDownloadTick();
                downloadKey = null;
            }
            if (sbn != null && callKey != null
                    && callKey.equals(sbn.getKey())) {
                // v125: the call notification is no longer
                // self-cancelled, so its removal means the call
                // really ended (system removed it). Send IDLE.
                // v131: BUT the system re-posts the call
                // notification after answering with a DIFFERENT
                // key — it REMOVES the old one first. That removal
                // is not a hangup. If the telephony state still
                // says a call is up, keep the call state and send
                // nothing; the re-post that follows will update
                // the key. Only send IDLE when there is really
                // no call left.
                boolean callStillUp = false;
                try {
                    android.telephony.TelephonyManager tm =
                            (android.telephony.TelephonyManager)
                            getSystemService(
                                    android.content.Context
                                            .TELEPHONY_SERVICE);
                    if (tm != null
                            && tm.getCallState()
                            != android.telephony.TelephonyManager
                                    .CALL_STATE_IDLE) {
                        callStillUp = true;
                    }
                } catch (Throwable ignored) {
                }
                if (!callStillUp) {
                    callKey = null;
                    callNumber = null;
                    callName = null;
                    sendCallBroadcast(CALL_IDLE, null, null);
                }
            }
        } catch (Throwable ignored) {
        }
        // The bubble owns its own dismiss; nothing to do here — except
        // the lyric listener: if the music notification goes away,
        // the music stopped, so stop listening.
        if (sbn != null && musicKey != null
                && musicKey.equals(sbn.getKey())) {
            // v564: 告诉 SystemUI 音乐结束了（轻气泡面板的
            // 音乐卡据此撤下；经典路径不消费这条广播）。
            try {
                android.content.Intent gone =
                        new android.content.Intent(
                                ACTION_MUSIC_GONE);
                gone.putExtra("key", sbn.getKey());
                sendBroadcast(gone);
            } catch (Throwable ignored) {
            }
            musicListening = false;
            teardownMusicSession();
            musicToken = null;
            musicKey = null;
        }
    }
}
