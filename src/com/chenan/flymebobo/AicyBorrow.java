package com.chenan.flymebobo;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
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

/**
 * v620 stage 1: Aicy 借道侦察钩子（装在超级 Aicy 进程
 * com.meizu.voiceassistant 里）。
 *
 * 反编译结论（Aicy 13.0.82, classes3.dex）：
 * - LLM 客户端接口 a7.f：a(request) 流式 / c(request, cont) 一次性。
 * - 路由实现 com.flyme.agentos.llm.impl.routing.k implements a7.f，
 *   构造时由绑定 l 创建（l.a = ModelFunction 枚举 CHAT /
 *   CONTEXT_SUMMARY / ...）。钩 k 的构造即可截获实例。
 * - 请求对象 d7.C2117i（消息列表 + ChatCaller 场景标签 + 若干参数，
 *   无档位字段——档位由路由层按场景与 Aicy 自身模式决定）。
 * - 消息对象 d7.b0（Role + 正文 + ...，kotlin 默认参数合成构造）。
 *
 * 本阶段只做两件事：截获实例并回报；在截获后 20 秒自动发一条
 * 测试请求（模块摘要提示词 + 四条样例），把回包对象的类名与
 * 字段反射倾倒进日志，验证「构造请求 → 调用 → 拿回文本」整条
 * 链路。日志经广播送到 SystemUI 的 FileLog（本进程 FileLog
 * 不落盘，见 v616/v617 的教训）。
 */
public class AicyBorrow {
    public static final String ACTION_AICY_LOG =
            "com.chenan.flymebobo.AICY_LOG";

    private static final String ROUTER_CLASS =
            "com.flyme.agentos.llm.impl.routing.k";

    private final XposedModule module;
    private final Application app;
    private final ClassLoader cl;
    private final Map<String, Object> clients =
            new ConcurrentHashMap<>();
    private volatile boolean testFired = false;

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
        if (!testFired) {
            testFired = true;
            new Handler(Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    testAsk();
                                }
                            }, "aicy-test").start();
                        }
                    }, 20000);
        }
    }

    // ---- 测试请求：模块摘要提示词 + wei 手测过的四条样例 ----

    private void testAsk() {
        try {
            Object client = clients.get("CHAT");
            if (client == null && !clients.isEmpty()) {
                client = clients.values().iterator().next();
            }
            if (client == null) {
                log("test ask: no client captured");
                return;
            }
            String system = "你是通知整理助手，只输出 JSON。";
            String user = "下面是手机通知收纳盒里的4条通知，请处理后只输出一个 JSON 对象，不要解释。\n"
                    + "[0] 标题：物业通知 正文：今晚22点停水，请提前储水\n"
                    + "[1] 标题：顺丰速运 正文：您的快递已到菜鸟驿站，取件码 5-2-3106\n"
                    + "[2] 标题：95588 正文：您的账户涉嫌洗钱，请点击链接验证身份\n"
                    + "[3] 标题：购物App 正文：大促最后一天，全场五折\n"
                    + "输出格式：{\"summary\":\"一句话总结这些通知（中文，30字以内，没有值得总结的就空字符串）\"}";
            Method c = findChatMethod(client.getClass());
            // v622: 请求类的真实运行时名是 d7.i（jadx 因与 d7.I
            // 大小写冲突改的名 C2117i/C6336i 在真机不存在）——
            // 直接从客户端 c 方法的参数类型取 Class，不再写死类名。
            Object request = buildRequest(system, user,
                    c.getParameterTypes()[0]);
            log("test ask sending caller=QUICKPASS_SUMMARY");
            Object cont = makeContinuation();
            Object ret = c.invoke(client, request, cont);
            Object suspended = coroutineSuspended();
            if (ret != null && ret != suspended) {
                dumpResult("immediate", ret);
            } else {
                log("test ask suspended, waiting resume");
            }
        } catch (Throwable t) {
            log("test ask err " + t);
        }
    }

    private Method findChatMethod(Class<?> kClass) throws Exception {
        for (Method m : kClass.getDeclaredMethods()) {
            if ("c".equals(m.getName())
                    && m.getParameterTypes().length == 2) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodException("c/2");
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

    private Object makeContinuation() throws Exception {
        Class<?> contClass = Class.forName(
                "kotlin.coroutines.Continuation", false, cl);
        Class<?> emptyClass = Class.forName(
                "kotlin.coroutines.EmptyCoroutineContext", false, cl);
        Field inst = emptyClass.getField("INSTANCE");
        final Object emptyContext = inst.get(null);
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
                            log("test reply FAILURE " + fe.get(r));
                        } catch (Throwable t) {
                            log("test reply FAILURE (dump err) " + t);
                        }
                    } else {
                        dumpResult("resumed", r);
                    }
                    return null;
                }
                return null;
            }
        };
        return Proxy.newProxyInstance(cl,
                new Class<?>[]{contClass}, handler);
    }

    private void dumpResult(String how, Object result) {
        if (result == null) {
            log("test reply (" + how + ") null");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("test reply (").append(how).append(") class=")
                .append(result.getClass().getName());
        Class<?> c = result.getClass();
        while (c != null && !c.getName().startsWith("java.lang.Object")) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(result);
                    if (v instanceof String) {
                        String s = (String) v;
                        if (s.length() > 400) {
                            s = s.substring(0, 400) + "…";
                        }
                        sb.append(" | ").append(f.getName()).append("=")
                                .append(s);
                    } else if (v != null
                            && (v instanceof Number
                            || v instanceof Boolean
                            || v instanceof Enum)) {
                        sb.append(" | ").append(f.getName()).append("=")
                                .append(v);
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        try {
            String ts = String.valueOf(result);
            if (ts.length() > 600) {
                ts = ts.substring(0, 600) + "…";
            }
            sb.append(" | toString=").append(ts);
        } catch (Throwable ignored) {
        }
        log(sb.toString());
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
        // v621: Aicy 的 kotlin 合成构造没有 DefaultConstructorMarker
        // （日志 80 实证 NoSuchMethodException）——b0 的合成构造是
        // (Role, String, List, String, List, String, int mask)。
        // 按「首参是 Role、末参是 int」定位，mask 位 2..5 置 1
        //（只给 role 与正文，其余取默认）。
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
            throw new NoSuchMethodException("b0 synthetic ctor v621");
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
        Object msg = ctor.newInstance(args);
        log("built message role=" + roleName + " -> " + msg);
        return msg;
    }

    private Object buildRequest(String system, String user,
            Class<?> reqClass) throws Exception {
        List<Object> messages = new ArrayList<>();
        messages.add(buildMessage("SYSTEM", system));
        messages.add(buildMessage("USER", user));
        // v621: 合成构造为 (int mask, 23 个真实参数)，无 marker。
        // 真实参数序：0=messages 1=tools 2..4=String 5=ChatCaller
        // 其余全部默认。mask 只清 bit0 与 bit5，其余置 1。
        Constructor<?> ctor = null;
        for (Constructor<?> c : reqClass.getDeclaredConstructors()) {
            Class<?>[] pt = c.getParameterTypes();
            if (pt.length >= 2 && pt[0] == int.class) {
                ctor = c;
                break;
            }
        }
        if (ctor == null) {
            throw new NoSuchMethodException("request synthetic ctor v621");
        }
        ctor.setAccessible(true);
        Class<?>[] pt = ctor.getParameterTypes();
        Object[] args = new Object[pt.length];
        int mask = 0x7FFFFF & ~(1 << 0) & ~(1 << 5);
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
        Object req = ctor.newInstance(args);
        log("built request -> " + req);
        return req;
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
