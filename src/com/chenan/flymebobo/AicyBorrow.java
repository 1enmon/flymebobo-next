package com.chenan.flymebobo;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Aicy 借道（装在超级 Aicy 进程 com.meizu.voiceassistant 里）。
 *
 * 反编译与实测结论（Aicy 13.0.82，日志 80–85 逐步验证）：
 * - LLM 客户端接口 a7.f：a(request) 流式 / c(request, cont) 一次性；
 *   c 的参数是具体类 ContinuationImpl 无法 Proxy，故走流式。
 * - 路由实现 com.flyme.agentos.llm.impl.routing.k implements a7.f，
 *   由绑定 l 创建（l.a = ModelFunction：CHAT/CONTEXT_SUMMARY/...）。
 *   钩 k 的构造截获实例，Aicy 启动即全量捕获。
 * - 请求类真实运行时名 d7.i（ChatRequest，@Serializable 序列化
 *   构造：mask bit 置 1 = 用传入值，messages bit0 必填）；从客户
 *   端 a 方法的参数类型取 Class，不写死类名。
 * - 消息类 d7.b0（Message：role/content/toolCalls/toolCallId/
 *   mediaInputs/reasoningContent，默认参数构造 mask 相反：
 *   bit 置 1 = 取默认）。
 * - 流式块 d7.f（ChatChunk）：字段 a = 正文增量，b = 思考增量
 *   （忽略），d = done。collect/emit 的 Continuation 均为接口，
 *   Proxy 可用（dex 实证）。日志 85 全链路跑通，摘要 JSON 正确。
 *
 * v626 起转服务模式：注册广播接收器，模块 App 进程发
 * ACTION_AICY_ASK（id/system/user），本类在后台线程跑完流式
 * 收集后以 ACTION_AICY_REPLY（id/text 或 err）回包；
 * ACTION_AICY_PING → ACTION_AICY_PONG（id/ok）供存活检测。
 * 日志经 ACTION_AICY_LOG 广播回 SystemUI 落 FileLog（本进程
 * FileLog 不落盘）。
 */
public class AicyBorrow {
    public static final String ACTION_AICY_LOG =
            "com.chenan.flymebobo.AICY_LOG";
    public static final String ACTION_AICY_ASK =
            "com.chenan.flymebobo.AICY_ASK";
    public static final String ACTION_AICY_REPLY =
            "com.chenan.flymebobo.AICY_REPLY";
    public static final String ACTION_AICY_PING =
            "com.chenan.flymebobo.AICY_PING";
    public static final String ACTION_AICY_PONG =
            "com.chenan.flymebobo.AICY_PONG";

    private static final String ROUTER_CLASS =
            "com.flyme.agentos.llm.impl.routing.k";

    private final XposedModule module;
    private final Application app;
    private final ClassLoader cl;
    private final Map<String, Object> clients =
            new ConcurrentHashMap<>();

    public AicyBorrow(XposedModule module, Application app,
            ClassLoader cl) {
        this.module = module;
        this.app = app;
        this.cl = cl;
    }

    private void log(String msg) {
        try {
            Intent it = new Intent(ACTION_AICY_LOG);
            it.putExtra("msg", msg);
            app.sendBroadcast(it);
        } catch (Throwable ignored) {
        }
        try {
            module.log(4, "flymebobo", "AicyBorrow: " + msg);
        } catch (Throwable ignored) {
        }
    }

    public void install() {
        try {
            Class<?> kClass = Class.forName(ROUTER_CLASS, false, cl);
            Constructor<?> ctor = kClass.getDeclaredConstructors()[0];
            module.hook(ctor).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain)
                        throws Throwable {
                    Object result = chain.proceed();
                    try {
                        capture(chain.getThisObject());
                    } catch (Throwable t) {
                        log("capture err " + t);
                    }
                    return result;
                }
            });
            log("hook installed on " + ROUTER_CLASS);
        } catch (Throwable t) {
            log("install failed " + t);
        }
        registerAskReceiver();
        // v648: OCR 探针钩子撤下（诊断已完结，
        // 不留无谓的拦截面）。
    }

    /** v646: 识屏 OCR 探针（验证包）——在 Aicy 进程
     * 里三处下网，截 ACTION_SEND_CAPTURE_CURRENT
     * _SCREEN 广播的发送/接收，把来源应用 + OCR
     * 全文长度 + 开头一截经日志通道递回 SystemUI：
     * ① ContextWrapper.sendBroadcast（两个重载）
     * ② ContextImpl.sendBroadcast
     * ③ 能力实现自带 receiver 的 onReceive。 */
    private static final String OCR_ACTION =
            "com.meizu.voiceassistant"
                    + ".ACTION_SEND_CAPTURE_CURRENT_SCREEN";

    private void probeLogOcr(String where,
            android.content.Intent it) {
        try {
            String appName = it.getStringExtra("KEY_APP_NAME");
            String ocr = it.getStringExtra("KEY_OCR_DATA");
            String head = "";
            if (ocr != null) {
                head = ocr.replace('\n', ' ')
                        .replace('\r', ' ');
                if (head.length() > 120) {
                    head = head.substring(0, 120);
                }
            }
            log("OcrHook " + where + " app=" + appName
                    + " len=" + (ocr == null ? -1
                            : ocr.length())
                    + " head=" + head);
        } catch (Throwable ignored) {
        }
    }

    private XposedInterface.Hooker ocrSendHooker() {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(
                    XposedInterface.Chain chain)
                    throws Throwable {
                try {
                    Object a0 = chain.getArg(0);
                    if (a0 instanceof android.content.Intent) {
                        android.content.Intent it =
                                (android.content.Intent) a0;
                        if (OCR_ACTION.equals(it.getAction())) {
                            probeLogOcr("send", it);
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            }
        };
    }

    private void installOcrProbe() {
        try {
            java.lang.reflect.Method sb =
                    android.content.ContextWrapper.class
                            .getDeclaredMethod("sendBroadcast",
                                    android.content.Intent.class);
            module.hook(sb).intercept(ocrSendHooker());
            java.lang.reflect.Method sb2 =
                    android.content.ContextWrapper.class
                            .getDeclaredMethod("sendBroadcast",
                                    android.content.Intent.class,
                                    String.class);
            module.hook(sb2).intercept(ocrSendHooker());
            log("OcrProbe send hook ok (ContextWrapper)");
        } catch (Throwable t) {
            log("OcrProbe send hook fail " + t);
        }
        try {
            Class<?> ci = Class.forName(
                    "android.app.ContextImpl", false, cl);
            java.lang.reflect.Method sb = ci
                    .getDeclaredMethod("sendBroadcast",
                            android.content.Intent.class);
            module.hook(sb).intercept(ocrSendHooker());
            log("OcrProbe send hook ok (ContextImpl)");
        } catch (Throwable t) {
            log("OcrProbe ContextImpl hook fail " + t);
        }
        try {
            Class<?> rc = Class.forName(
                    "com.meizu.picker.currentscreencontentability"
                            + ".CurrentScreenContentAbilityImpl"
                            + "$broadcastReceiver$1",
                    false, cl);
            java.lang.reflect.Method orc = rc
                    .getDeclaredMethod("onReceive",
                            android.content.Context.class,
                            android.content.Intent.class);
            module.hook(orc).intercept(
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(
                                XposedInterface.Chain chain)
                                throws Throwable {
                            try {
                                Object a1 = chain.getArg(1);
                                if (a1 instanceof
                                        android.content.Intent) {
                                    probeLogOcr("recv",
                                            (android.content
                                                    .Intent) a1);
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        }
                    });
            log("OcrProbe recv hook ok");
        } catch (Throwable t) {
            log("OcrProbe recv hook fail " + t);
        }
    }

    private void capture(Object client) {
        String fn = "?";
        try {
            Field fa = client.getClass().getDeclaredField("a");
            fa.setAccessible(true);
            Object binding = fa.get(client);
            if (binding != null) {
                Field ff = binding.getClass().getDeclaredField("a");
                ff.setAccessible(true);
                Object fnObj = ff.get(binding);
                if (fnObj instanceof Enum) {
                    fn = ((Enum<?>) fnObj).name();
                }
            }
        } catch (Throwable t) {
            log("fn read err " + t);
        }
        clients.put(fn, client);
        log("capture fn=" + fn + " total=" + clients.size());
    }

    // ---- 服务模式：ASK / PING 广播 ----

    private void registerAskReceiver() {
        try {
            IntentFilter filter = new IntentFilter(ACTION_AICY_ASK);
            filter.addAction(ACTION_AICY_PING);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    final String id = intent.getStringExtra("id");
                    if (ACTION_AICY_PING.equals(intent.getAction())) {
                        Intent pong = new Intent(ACTION_AICY_PONG);
                        pong.putExtra("id", id);
                        pong.putExtra("ok", !clients.isEmpty());
                        try {
                            app.sendBroadcast(pong);
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    final String system =
                            intent.getStringExtra("system");
                    final String user = intent.getStringExtra("user");
                    final boolean fast = intent.getBooleanExtra(
                            "fast", false);
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handleAsk(id, system, user, fast);
                        }
                    }, "aicy-ask").start();
                }
            };
            try {
                Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class,
                        int.class);
                // 2 = RECEIVER_EXPORTED
                m.invoke(app, receiver, filter, Integer.valueOf(2));
            } catch (Throwable t) {
                app.registerReceiver(receiver, filter);
            }
            log("ask receiver registered");
        } catch (Throwable t) {
            log("ask receiver failed " + t);
        }
    }

    private void reply(String id, String text, String err) {
        Intent it = new Intent(ACTION_AICY_REPLY);
        it.putExtra("id", id);
        if (text != null) {
            it.putExtra("text", text);
        }
        if (err != null) {
            it.putExtra("err", err);
        }
        try {
            app.sendBroadcast(it);
        } catch (Throwable ignored) {
        }
    }

    private void handleAsk(String id, String system, String user,
            boolean fast) {
        if (id == null) {
            return;
        }
        try {
            Object client = clients.get("CHAT");
            if (client == null && !clients.isEmpty()) {
                client = clients.values().iterator().next();
            }
            if (client == null) {
                reply(id, null, "no-client");
                return;
            }
            Method a = null;
            for (Method m : client.getClass().getDeclaredMethods()) {
                if ("a".equals(m.getName())
                        && m.getParameterTypes().length == 1) {
                    a = m;
                    break;
                }
            }
            if (a == null) {
                reply(id, null, "no-method-a");
                return;
            }
            a.setAccessible(true);
            Object request = buildRequest(system, user,
                    a.getParameterTypes()[0], fast);
            Object flow = a.invoke(client, request);
            final StringBuilder content = new StringBuilder();
            final int[] thinkChunks = new int[1];
            final CountDownLatch done = new CountDownLatch(1);
            final String[] error = new String[1];
            Object collector = makeCollector(content, thinkChunks);
            Object continuation =
                    makeContinuation(done, error);
            Method collect = null;
            for (Method m : flow.getClass().getMethods()) {
                if ("b".equals(m.getName())
                        && m.getParameterTypes().length == 2) {
                    collect = m;
                    break;
                }
            }
            if (collect == null) {
                reply(id, null, "no-collect");
                return;
            }
            collect.setAccessible(true);
            collect.invoke(flow, collector, continuation);
            boolean finished = done.await(120, TimeUnit.SECONDS);
            if (!finished) {
                reply(id, null, "timeout");
                return;
            }
            if (error[0] != null) {
                log("ask err " + error[0]);
                reply(id, null, error[0]);
                return;
            }
            log("ask ok len=" + content.length()
                    + " thinkChunks=" + thinkChunks[0]
                    + " fast=" + fast);
            reply(id, content.toString(), null);
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null
                    && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            log("ask fail " + cause);
            reply(id, null, String.valueOf(cause));
        }
    }

    // ---- Proxy collector / continuation ----

    private Object makeCollector(final StringBuilder content,
            final int[] thinkChunks) throws Exception {
        Class<?> collectorClass = Class.forName(
                "kotlinx.coroutines.flow.i", false, cl);
        Class<?> unitClass = Class.forName("kotlin.Unit", false, cl);
        final Object unit = unitClass.getField("INSTANCE").get(null);
        InvocationHandler handler = new InvocationHandler() {
            private Field contentField;
            private Field thinkField;

            @Override
            public Object invoke(Object proxy, Method method,
                    Object[] args) {
                if ("emit".equals(method.getName())
                        && args != null && args.length >= 1
                        && args[0] != null) {
                    try {
                        if (contentField == null) {
                            contentField = args[0].getClass()
                                    .getDeclaredField("a");
                            contentField.setAccessible(true);
                            thinkField = args[0].getClass()
                                    .getDeclaredField("b");
                            thinkField.setAccessible(true);
                        }
                        Object v = contentField.get(args[0]);
                        if (v instanceof String) {
                            content.append((String) v);
                        }
                        Object rv = thinkField.get(args[0]);
                        if (rv instanceof String
                                && !((String) rv).isEmpty()) {
                            thinkChunks[0]++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return unit;
            }
        };
        return Proxy.newProxyInstance(cl,
                new Class<?>[]{collectorClass}, handler);
    }

    private Object makeContinuation(final CountDownLatch done,
            final String[] error) throws Exception {
        Class<?> contClass = Class.forName(
                "kotlin.coroutines.Continuation", false, cl);
        Class<?> emptyClass = Class.forName(
                "kotlin.coroutines.EmptyCoroutineContext", false, cl);
        final Object emptyContext =
                emptyClass.getField("INSTANCE").get(null);
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method,
                    Object[] args) {
                String name = method.getName();
                if ("getContext".equals(name)) {
                    return emptyContext;
                }
                if ("resumeWith".equals(name)) {
                    Object r = args == null || args.length == 0
                            ? null : args[0];
                    if (r != null && r.getClass().getName().equals(
                            "kotlin.Result$Failure")) {
                        try {
                            Field fe = r.getClass()
                                    .getDeclaredField("exception");
                            fe.setAccessible(true);
                            error[0] = String.valueOf(fe.get(r));
                        } catch (Throwable t) {
                            error[0] = "failure-dump-err";
                        }
                    }
                    done.countDown();
                    return null;
                }
                return null;
            }
        };
        return Proxy.newProxyInstance(cl,
                new Class<?>[]{contClass}, handler);
    }

    private Object coroutineSuspended() {
        try {
            Class<?> kt = Class.forName(
                    "kotlin.coroutines.intrinsics.IntrinsicsKt", false,
                    cl);
            Method m = kt.getMethod("getCOROUTINE_SUSPENDED");
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 反射构造请求 ----

    private Object buildMessage(String roleName, String text)
            throws Exception {
        Class<?> roleClass = Class.forName(
                "com.flyme.agentos.llm.api.model.Message$Role", false,
                cl);
        Object role = null;
        for (Object e : roleClass.getEnumConstants()) {
            if (((Enum<?>) e).name().equals(roleName)) {
                role = e;
                break;
            }
        }
        Class<?> b0 = Class.forName("d7.b0", false, cl);
        // b0 合成构造 (Role, String, List, String, List, String,
        // int mask)：默认参数语义，mask 位 2..5 置 1 = 其余取默认，
        // 只给 role 与正文。
        Constructor<?> ctor = null;
        for (Constructor<?> c : b0.getDeclaredConstructors()) {
            Class<?>[] pt = c.getParameterTypes();
            if (pt.length >= 3 && pt[0] == roleClass
                    && pt[pt.length - 1] == int.class) {
                ctor = c;
                break;
            }
        }
        if (ctor == null) {
            throw new NoSuchMethodException("b0 synthetic ctor");
        }
        ctor.setAccessible(true);
        Class<?>[] pt = ctor.getParameterTypes();
        Object[] args = new Object[pt.length];
        args[0] = role;
        args[1] = text;
        for (int i = 2; i < pt.length - 1; i++) {
            args[i] = defaultFor(pt[i]);
        }
        args[pt.length - 1] = Integer.valueOf(0b111100);
        return ctor.newInstance(args);
    }

    private Object buildRequest(String system, String user,
            Class<?> reqClass, boolean fast) throws Exception {
        List<Object> messages = new ArrayList<>();
        if (system != null && !system.isEmpty()) {
            messages.add(buildMessage("SYSTEM", system));
        }
        messages.add(buildMessage("USER", user));
        // ChatRequest 合成构造 (int mask, 23 个真实参数)：
        // @Serializable 序列化语义，bit 置 1 = 用传入值，
        // messages(bit0) 必填。只给 messages 与 caller(bit5)。
        Constructor<?> ctor = null;
        for (Constructor<?> c : reqClass.getDeclaredConstructors()) {
            Class<?>[] pt = c.getParameterTypes();
            if (pt.length >= 2 && pt[0] == int.class) {
                ctor = c;
                break;
            }
        }
        if (ctor == null) {
            throw new NoSuchMethodException("request synthetic ctor");
        }
        ctor.setAccessible(true);
        Class<?>[] pt = ctor.getParameterTypes();
        Object[] args = new Object[pt.length];
        int mask = (1 << 0) | (1 << 5);
        if (fast) {
            // v627: 快速模式——真实参数 15 = ThinkingMode，
            // 置 bit15 并填 OFF，关掉思考过程提速。
            mask |= (1 << 15);
        }
        args[0] = Integer.valueOf(mask);
        for (int i = 1; i < pt.length; i++) {
            args[i] = defaultFor(pt[i]);
        }
        args[1] = messages;
        Class<?> callerClass = Class.forName(
                "com.flyme.agentos.llm.api.model.ChatCaller", false,
                cl);
        Object caller = null;
        for (Object e : callerClass.getEnumConstants()) {
            if (((Enum<?>) e).name().equals("QUICKPASS_SUMMARY")) {
                caller = e;
                break;
            }
        }
        args[6] = caller;
        if (fast) {
            Class<?> tmClass = Class.forName(
                    "com.flyme.agentos.llm.api.model.ThinkingMode",
                    false, cl);
            Object off = null;
            for (Object e : tmClass.getEnumConstants()) {
                if (((Enum<?>) e).name().equalsIgnoreCase("off")) {
                    off = e;
                    break;
                }
            }
            args[16] = off;
        }
        return ctor.newInstance(args);
    }

    private static Object defaultFor(Class<?> t) {
        if (t == int.class || t == Integer.class) {
            return Integer.valueOf(0);
        }
        if (t == long.class || t == Long.class) {
            return Long.valueOf(0);
        }
        if (t == float.class || t == Float.class) {
            return Float.valueOf(0f);
        }
        if (t == double.class || t == Double.class) {
            return Double.valueOf(0d);
        }
        if (t == boolean.class || t == Boolean.class) {
            return Boolean.FALSE;
        }
        return null;
    }
}
