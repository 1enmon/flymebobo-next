package com.chenan.flymebobo;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

/**
 * v647: 手动识别的外部入口（全程不进 App 界面）：
 * - 选中文字菜单（PROCESS_TEXT）「flymebobo 识别」
 * - 系统分享（ACTION_SEND text/plain）
 * - 桌面快捷方式「输入文字识别」（悬浮输入小窗）
 *
 * 本 Activity 是透明中转：文字类把文字经广播送
 * SystemUI（BubbleWindow.onScreenOcrText）识别并
 * 弹悬浮卡；输入类广播让 SystemUI 弹悬浮输入窗。
 * 自己立刻 finish，不留任何界面。
 */
public class RecognizeActivity extends Activity {

    public static final String ACTION_INPUT =
            "com.chenan.flymebobo.RECOGNIZE_INPUT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            SharedPreferences features =
                    getSharedPreferences(
                            "flymebobo_features", 0);
            if (!features.getBoolean(
                    "manualRecognize", true)) {
                Toast.makeText(this,
                        "手动识别入口已在设置里关闭",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Intent in = getIntent();
            String action = in == null
                    ? null : in.getAction();
            if (ACTION_INPUT.equals(action)) {
                sendBroadcast(new Intent(
                        NotificationService
                                .ACTION_MANUAL_SHOW_INPUT));
                return;
            }
            CharSequence cs = null;
            if (in != null) {
                cs = in.getCharSequenceExtra(
                        "android.intent.extra.PROCESS_TEXT");
                if (cs == null) {
                    cs = in.getCharSequenceExtra(
                            Intent.EXTRA_TEXT);
                }
            }
            String text = cs == null
                    ? "" : cs.toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "没有拿到文字",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (text.length() > 4000) {
                text = text.substring(0, 4000);
            }
            Intent it = new Intent(
                    NotificationService.ACTION_MANUAL_TEXT);
            it.putExtra("text", text);
            sendBroadcast(it);
        } catch (Throwable ignored) {
        } finally {
            finish();
        }
    }
}
