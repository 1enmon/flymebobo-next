package com.chenan.flymebobo;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * v647: 识屏取字中继（跑在 com.meizu.voiceassistant
 * 进程里，Flyme 专属通道）。
 *
 * SystemUI 点了磁贴要识别当前屏 → 广播
 * ACTION_OCR_REQUEST 过来 → 本类自己注册
 * ACTION_SEND_CAPTURE_CURRENT_SCREEN 的接收者，
 * 再 startService 触发 AIBarEntryService 截屏
 * OCR；广播回来后把全文（KEY_OCR_DATA）+ 来源
 * 应用（KEY_APP_NAME）经 ACTION_OCR_TEXT 转发
 * 回 SystemUI。12 秒没回音转发空文本兜底。
 *
 * 为什么要绕这一道：v645 实测那份 OCR 广播从
 * 外部进程收不到（疑似定向投递），只有在 Aicy
 * 自己的进程里才接得住。
 */
public class ScreenOcrRelay {

    public static final String ACTION_OCR_REQUEST =
            "com.chenan.flymebobo.OCR_REQUEST";
    public static final String ACTION_OCR_TEXT =
            "com.chenan.flymebobo.OCR_TEXT";

    private static final String OCR_ACTION =
            "com.meizu.voiceassistant"
                    + ".ACTION_SEND_CAPTURE_CURRENT_SCREEN";
    private static final String TRIGGER_ACTION =
            "com.meizu.voiceassistant"
                    + ".ACTION_TRIGGER_CAPTURE_CURRENT_SCREEN";

    private final Application app;
    private volatile long armedUntil;
    private volatile boolean installed;

    public ScreenOcrRelay(Application app) {
        this.app = app;
    }

    private void log(String msg) {
        try {
            Intent it = new Intent(AicyBorrow.ACTION_AICY_LOG);
            it.putExtra("msg", "OcrRelay " + msg);
            app.sendBroadcast(it);
        } catch (Throwable ignored) {
        }
    }

    private void register(BroadcastReceiver receiver,
            IntentFilter filter) {
        try {
            Method m = Context.class.getMethod(
                    "registerReceiver",
                    BroadcastReceiver.class,
                    IntentFilter.class, int.class);
            m.invoke(app, receiver, filter, 2);
        } catch (Throwable t) {
            app.registerReceiver(receiver, filter);
        }
    }

    public void install() {
        if (installed) {
            return;
        }
        installed = true;
        // 请求接收：SystemUI 磁贴流程发来。
        register(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context,
                    Intent intent) {
                if (intent != null
                        && ACTION_OCR_REQUEST.equals(
                                intent.getAction())) {
                    armAndTrigger();
                }
            }
        }, new IntentFilter(ACTION_OCR_REQUEST));
        // OCR 结果接收：只在 arm 窗口内转发。
        register(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context,
                    Intent intent) {
                if (intent == null
                        || !OCR_ACTION.equals(
                                intent.getAction())) {
                    return;
                }
                if (System.currentTimeMillis()
                        > armedUntil) {
                    return;
                }
                armedUntil = 0;
                forward(intent.getStringExtra("KEY_APP_NAME"),
                        intent.getStringExtra("KEY_OCR_DATA"));
            }
        }, new IntentFilter(OCR_ACTION));
        log("installed");
    }

    private void armAndTrigger() {
        armedUntil = System.currentTimeMillis() + 12000;
        try {
            Intent it = new Intent(TRIGGER_ACTION);
            it.setPackage("com.meizu.voiceassistant");
            app.startService(it);
            log("capture triggered");
        } catch (Throwable t) {
            log("trigger fail " + t);
            armedUntil = 0;
            forward(null, null);
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (armedUntil > 0
                                && System.currentTimeMillis()
                                        >= armedUntil) {
                            armedUntil = 0;
                            forward(null, null);
                            log("timeout, forwarded empty");
                        }
                    }
                }, 12500);
    }

    private void forward(String appName, String text) {
        try {
            Intent it = new Intent(ACTION_OCR_TEXT);
            it.putExtra("app", appName == null ? "" : appName);
            it.putExtra("text", text == null ? "" : text);
            app.sendBroadcast(it);
            log("forwarded len="
                    + (text == null ? -1 : text.length()));
        } catch (Throwable t) {
            log("forward fail " + t);
        }
    }
}
