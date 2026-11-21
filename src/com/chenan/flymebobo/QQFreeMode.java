package com.chenan.flymebobo;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;

/**
 * QQ音乐 Flyme 版免费听（v334，集成自测试模块 qqfreemode v11）。
 *
 * v345: 开关已去掉，免费听永远打开。
 * 不支持主动领取——没有领取按钮，不收领取广播，只在打开 QQ 音乐时自动补时长。
 *
 * 行为：每次打开 QQ 音乐 Flyme 版（进程启动），5 秒后查一次剩余免费时长；
 * 低于 2 小时才领，补到 4 小时；两次领取间隔不得低于 4 小时。
 * 领满弹“已免费领取4小时听歌时长”；本来就 ≥2 小时弹“大于2小时无法领取”。
 * 打开一次只检测一次，不反复检测、不监听 Activity 回到前台。
 * 点免费页的“看视频”不打开广告，直接领（同样受开关管）。
 *
 * 敏感字符串（反射目标类名/方法名）用 XOR 加密存放，运行时解密，
 * dex 里搜不到明文。
 */
public final class QQFreeMode {
    private static final String TAG = "QQFreeMode";
    private static final String LOG_PATH = "/data/local/tmp/QQFreeMode.log";
    // v337: 跟 standalone 一样多写一份到 QQ 音乐自己的 files 目录，文件管理器不用 root 就能看到。
    private static final String LOG_PATH_ALT2 = "/sdcard/Android/data/com.meizu.media.music/files/QQFreeMode.log";
    // v345: 共享文件已去掉（开关没了，永远打开，不用跨进程同步状态）。

    // v341: 低于 2 小时才触发（TRIGGER），补到 4 小时（TARGET）；
    // 两次领取间隔不得低于 4 小时（COOLDOWN）。每次领 20 分钟，无日上限。
    private static final long TRIGGER_SECONDS = 7200L;
    private static final long TARGET_SECONDS = 14400L;
    private static final long COOLDOWN_MILLIS = 4L * 3600L * 1000L;
    private static final String LAST_CLAIM_PATH = "/data/local/tmp/flymebobo_qqfree_last";
    private static final int MAX_ATTEMPTS = 12;

    private final XposedInterface bridge;
    private final Application app;
    private final ClassLoader appLoader;
    private boolean done;

    private Context appContext;
    private Object brObj;
    private boolean receiverRegistered;
    // v338: 前台会话计数（0->1 = 进前台，1->0 = 切后台）。
    private int startedActivities = 0;
    private boolean topUpScheduled;
    private boolean topUpActive;
    private int claimIndex;
    private long remainSec = -1L;
    private int retryCount;
    private int zeroStreak;
    private int attempts;
    // v341: 上次成功领取的 wall-clock 毫秒（存文件，跨进程/重启有效）。
    private long lastClaimTimeMillis = 0L;

    // ---- 字符串加密：XOR 0x5A，运行时 d() 解密 ----
    private static String d(String hex) {
        int n = hex.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    + Character.digit(hex.charAt(i * 2 + 1), 16));
            b[i] ^= 0x5A;
        }
        return new String(b);
    }

    private static final String CLS_COMPONENTS = "393537742e3f34393f342e742b2b372f29333936332e3f743e3b3d3d3f28741935372a35343f342e29";
    private static final String CLS_BR = "393537742e3f34393f342e742b2b372f29333936332e3f743c283f3f37353e3f743e3b2e3b74283f37352e3f74183f343f3c332e29083f2b2f3f292e3f28";
    private static final String CLS_STATE_REQ = "393537742e3f34393f342e742b2b372f29333936332e3f743c283f3f37353e3f743e3b2e3b74283f37352e3f74092e3b2e3f083f2b2f3f292e3f28";
    private static final String CLS_REWARD_FROM = "393537742e3f34393f342e742b2b372f29333936332e3f743c283f3f37353e3f743e3b2e3b743f342f372974083f2d3b283e1b3e1c283537";
    private static final String CLS_EM_TYPE = "393537742e3f34393f342e742b2b372f29333936332e3f743c283f3f37353e3f743e3b2e3b743f342f3729741f37123b343e293f360e232a3f";
    private static final String CLS_SCENE = "393537742e3f34393f342e742b2b372f29333936332e3f743c283f3f37353e3f743e3b2e3b74283f37352e3f74183f343f3c332e29083f2b2f3f292e3f287e09393f343f0e232a3f";
    private static final String CLS_BR_LISTENER = "393537742e3f34393f342e742b2b372f29333936332e3f743c283f3f37353e3f743e3b2e3b74283f37352e3f743633292e3f343f2874183f343f3c332e291633292e3f343f28";
    private static final String CLS_STATE_LISTENER = "393537742e3f34393f342e742b2b372f29333936332e3f743c283f3f37353e3f743e3b2e3b74283f37352e3f743633292e3f343f2874092e3b2e3f1633292e3f343f28";
    private static final String CLS_EXTENSIONS = "393537742e3f34393f342e742b2b372f29333936332e3f743c283b3d373f342e743e3f2e3b3336741f222e3f342933353429112e";
    private static final String CLS_AD_CONFIG = "393537742e3f34393f342e742b2b372f29333936332e3f743c283f3f37353e3f743e3b2e3b743f342f3729741b3e1935343c333d131e";
    private static final String CLS_REWARD_AD_TYPE = "393537742e3f34393f342e742b2b372f29333936332e3f743c283f3f37353e3f743e3b2e3b743f342f372974083f2d3b283e1b3e0e232a3f";
    private static final String M_GET_BR = "3d3f2e1c283f3f17353e3f183f343f3c332e29";
    private static final String M_GET_STATE_REQ = "3d3f2e1c283f3f17353e3f092e3b2e3f083f2b2f3f292e";
    private static final String M_CLAIM = "283f2b2f3f292e183f343f3c332e290d332e32192f292e35370a3b283b3729";
    private static final String M_REQ_STATE = "283f2b2f3f292e092e3b2e3f";
    private static final String M_TO_AD = "2e35083f2d3b283e1b3e1b392e332c332e23";
    private static final String M_GET_REMAIN = "3d3f2e0c332a083f373b33340e33373f";
    private static final String M_GET_HANDSEL = "3d3f2e123b343e293f360e33373f";
    private static final String M_GET_END = "3d3f2e0c332a1f343e0e33373f";
    private static final String M_GET_ALREADY = "3d3f2e1b36283f3b3e23093f343e19352f342e";
    private static final String M_GET_MAX = "3d3f2e173b22093f343e19352f342e";
    private static final String E_VIDEO = "123b343e293f360e232a3f0c333e3f35";
    private static final String E_WEB = "123b343e293f360e232a3f0d3f38";
    private static final String E_SIGN = "123b343e293f360e232a3f09333d34";
    private static final String E_GUIDE = "1d0f131e1f051e131b16151d";
    private static final String E_NORMAL = "141508171b1605081f0d1b081e";
    private static final String E_FTV = "1c081f1f050e1b18050c131e1f15";
    private static final String E_FTW = "1c081f1f050e1b18050d1f18";
    private static final String E_ENTRANCE = "1b170905081f0d1b081e051b1e051f140e081b14191f";

    private static final String[] CLAIM_TYPES = new String[]{E_VIDEO, E_WEB, E_SIGN};

    public QQFreeMode(XposedInterface xposed, Application app, ClassLoader loader) {
        this.bridge = xposed;
        this.app = app;
        this.appLoader = loader != null ? loader : app.getClassLoader();
    }

    public void install() {
        if (done) {
            return;
        }
        done = true;
        appContext = app;
        // v337: 先写一行 log——就算读 prefs 出问题，也能证明模块进了 QQ 音乐。
        // v345: 开关已去掉，免费听永远打开，不再读开关状态/共享文件。
        log(4, "installing QQFreeMode...");
        FeatureFlags.load(app);
        readLastClaimTime();
        log(4, "installed, qqFree always on");
        registerToggleReceiver();
        registerLifecycleCallbacks();
        tryGetBR();
        hookToRewardAdActivity();
        scheduleAutoTopUp();
    }

    /** v341: 读上次成功领取时间（文件不存在即 0，不过冷却）。 */
    private void readLastClaimTime() {
        try {
            java.io.File f = new java.io.File(LAST_CLAIM_PATH);
            if (!f.exists()) {
                lastClaimTimeMillis = 0L;
                return;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String s = r.readLine();
            r.close();
            if (s != null) {
                lastClaimTimeMillis = Long.parseLong(s.trim());
            }
            log(4, "last claim time=" + lastClaimTimeMillis);
        } catch (Throwable t) {
            lastClaimTimeMillis = 0L;
            log(6, "read last claim time failed", t);
        }
    }

    /** v341: 成功领到后更新上次领取时间并落盘。 */
    private void saveLastClaimTime(long now) {
        lastClaimTimeMillis = now;
        try {
            java.io.FileWriter w = new java.io.FileWriter(LAST_CLAIM_PATH, false);
            w.write(Long.toString(now));
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 开关实时生效：APP 拨动开关发广播，这里更新旗标。 */
    private void registerToggleReceiver() {
        if (receiverRegistered || appContext == null) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(NotificationService.ACTION_FEATURE_TOGGLE);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    if (!NotificationService.ACTION_FEATURE_TOGGLE.equals(intent.getAction())) {
                        return;
                    }
                    String key = intent.getStringExtra("key");
                    boolean enabled = intent.getBooleanExtra("enabled", true);
                    FeatureFlags.set(key, enabled);
                    log(4, "toggle: " + key + "=" + enabled);
                    // v344: 开关只是开关——拨动只改旗标，不触发领取。
                    // 领取只由“打开 QQ 音乐”（进程启动 / 进前台）触发一次。
                }
            };
            try {
                Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class,
                        String.class, Handler.class, int.class);
                m.invoke(appContext, receiver, filter, null, null, 2);
            } catch (NoSuchMethodException e) {
                appContext.registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable t) {
            log(6, "registerToggleReceiver failed", t);
        }
    }

    /** v338: 按“用户可见的打开”检测一次。QQ 音乐关了再开，进程常在后台没死，
     * 进程启动的检测不会再跑；这里跟前台会话走：0->1（冷启动 / 后台切回前台）
     * 排一次检测，切后台时把 topUpScheduled 清掉，下次进前台重新排。
     * App 内切 Activity（计数一直 >=1）不会重复触发。 */
    private void registerLifecycleCallbacks() {
        try {
            if (!isMainProcess()) {
                return;
            }
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {
                    if (startedActivities == 0) {
                        onAppForeground();
                    }
                    startedActivities++;
                }
                @Override public void onActivityResumed(Activity a) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {
                    startedActivities--;
                    if (startedActivities <= 0) {
                        startedActivities = 0;
                        topUpScheduled = false;
                        topUpActive = false;
                        retryCount = 0;
                    }
                }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {}
            });
            log(4, "lifecycle callbacks registered");
        } catch (Throwable t) {
            log(6, "registerLifecycleCallbacks failed", t);
        }
    }

    private void onAppForeground() {
        // v342: 每次进前台都重读一次上次领取时间。
        // v345: 开关已去掉，免费听永远打开，不再读开关状态。
        readLastClaimTime();
        log(4, "app foreground, scheduling check");
        scheduleAutoTopUp();
    }

    /** v338: 每次前台会话排一次（进程启动 / 后台切回前台），5 秒后查一次剩余时长。
     * 切后台时 topUpScheduled 清掉，下次进前台重新排；App 内切 Activity 不重复触发。 */
    private void scheduleAutoTopUp() {
        if (topUpScheduled) {
            return;
        }
        topUpScheduled = true;
        // v345: 开关已去掉，免费听永远打开，不再拦。
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    runAutoTopUp();
                }
            }, 5000);
        } catch (Throwable t) {
            log(6, "scheduleAutoTopUp failed", t);
        }
    }

    private void runAutoTopUp() {
        try {
            // v345: 开关已去掉，免费听永远打开。
            if (!isMainProcess()) {
                return;
            }
            if (topUpActive) {
                return;
            }
            tryGetBR();
            if (brObj == null) {
                if (retryCount < 2) {
                    retryCount++;
                    log(4, "autoTopUp: not ready, retry " + retryCount + " in 10s");
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            runAutoTopUp();
                        }
                    }, 10000);
                } else {
                    log(4, "autoTopUp: still null, give up this launch");
                }
                return;
            }
            queryRemainAndTopUp();
        } catch (Throwable t) {
            log(6, "runAutoTopUp failed", t);
        }
    }

    private void queryRemainAndTopUp() {
        try {
            Class<?> compClass = Class.forName(d(CLS_COMPONENTS), false, appLoader);
            Object instance = compClass.getField("INSTANCE").get(null);
            Object stateRequester = compClass.getMethod(d(M_GET_STATE_REQ)).invoke(instance);
            if (stateRequester == null) {
                log(4, "autoTopUp: SR is null");
                return;
            }
            Class<?> listenerClass = Class.forName(d(CLS_STATE_LISTENER), false, appLoader);
            Object listener = Proxy.newProxyInstance(appLoader,
                    new Class<?>[]{listenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if ("onResponse".equals(method.getName()) && args != null && args.length > 0) {
                                onStateResponse(args[0]);
                            }
                            return null;
                        }
                    });
            Method reqMethod = stateRequester.getClass().getMethod(d(M_REQ_STATE), listenerClass);
            log(4, "autoTopUp: querying remain time...");
            reqMethod.invoke(stateRequester, listener);
        } catch (Throwable t) {
            log(6, "autoTopUp query failed", t);
        }
    }

    private void onStateResponse(Object wrapper) {
        try {
            Object exception = wrapper.getClass().getMethod("getException").invoke(wrapper);
            if (exception != null) {
                log(4, "autoTopUp: state query exception: " + exception);
                return;
            }
            Object resp = wrapper.getClass().getMethod("getState").invoke(wrapper);
            if (resp == null) {
                log(4, "autoTopUp: state response null");
                return;
            }
            Object req = resp.getClass().getMethod("getRequest").invoke(resp);
            if (req == null) {
                log(4, "autoTopUp: state request null");
                return;
            }
            Object state = req.getClass().getMethod("getData").invoke(req);
            if (state == null) {
                log(4, "autoTopUp: state data null");
                return;
            }
            long remain = (Long) state.getClass().getMethod(d(M_GET_REMAIN)).invoke(state);
            log(4, "autoTopUp: remain=" + remain + "s trigger=" + TRIGGER_SECONDS + "s target=" + TARGET_SECONDS + "s");
            if (remain >= TRIGGER_SECONDS) {
                log(4, "autoTopUp: already >= 2h, nothing to claim");
                toast("当前可用免费听歌时长大于2小时无法领取");
                return;
            }
            // v341: 两次领取间隔不得低于 4 小时。
            long sinceLast = System.currentTimeMillis() - lastClaimTimeMillis;
            if (sinceLast < COOLDOWN_MILLIS) {
                log(4, "autoTopUp: cooldown, sinceLast=" + (sinceLast / 60000) + "min < 4h, skip");
                toast("距离上次领取不足4小时，暂不领取");
                return;
            }
            topUpActive = true;
            claimIndex = 0;
            zeroStreak = 0;
            attempts = 0;
            remainSec = remain;
            autoClaimNext(remain);
        } catch (Throwable t) {
            log(6, "autoTopUp: onStateResponse failed", t);
        }
    }

    /** 补时链：循环领直到 4 小时。停：补满 / 连续 3 次领到 0 / 尝试 12 次。 */
    private void autoClaimNext(long remain) {
        remainSec = remain;
        if (remain >= TARGET_SECONDS) {
            log(4, "autoTopUp DONE: remain=" + remain + "s (>= 4h)");
            toast("已免费领取4小时听歌时长");
            topUpActive = false;
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            log(4, "autoTopUp DONE: attempt cap, remain=" + remain + "s");
            toast("剩余" + (remain / 60) + "分钟，未能补满4小时");
            topUpActive = false;
            return;
        }
        if (zeroStreak >= 3) {
            log(4, "autoTopUp DONE: all gave 0, remain=" + remain + "s");
            toast("剩余" + (remain / 60) + "分钟，未能补满4小时");
            topUpActive = false;
            return;
        }
        String type = CLAIM_TYPES[claimIndex % CLAIM_TYPES.length];
        claimIndex++;
        attempts++;
        log(4, "autoTopUp: remain=" + remain + "s < 2h, claiming " + d(type) + " (attempt " + attempts + ")");
        claimAuto(type);
    }

    private void claimAuto(final String typeEnc) {
        claimInternal(typeEnc, true);
    }

    private void claimInternal(final String typeEnc, final boolean auto) {
        try {
            tryGetBR();
            if (brObj == null) {
                log(4, "claim FAILED: BR is null");
                if (auto) {
                    zeroStreak++;
                    autoClaimNext(remainSec);
                }
                return;
            }
            Class<?> fromClass = Class.forName(d(CLS_REWARD_FROM), false, appLoader);
            Class<?> sceneClass = Class.forName(d(CLS_SCENE), false, appLoader);
            Class<?> typeClass = Class.forName(d(CLS_EM_TYPE), false, appLoader);
            Class<?> listenerClass = Class.forName(d(CLS_BR_LISTENER), false, appLoader);

            Object from = Enum.valueOf((Class<? extends Enum>) fromClass, d(E_GUIDE));
            Object scene = Enum.valueOf((Class<? extends Enum>) sceneClass, d(E_NORMAL));
            Object type = Enum.valueOf((Class<? extends Enum>) typeClass, d(typeEnc));

            final String typeName = d(typeEnc);
            Object listener = Proxy.newProxyInstance(appLoader,
                    new Class<?>[]{listenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if ("onResponse".equals(method.getName()) && args != null && args.length > 0) {
                                onClaimResponse(typeName, args[0], auto);
                            }
                            return null;
                        }
                    });

            Method claimMethod = brObj.getClass().getMethod(
                    d(M_CLAIM),
                    fromClass, int.class, sceneClass, typeClass,
                    boolean.class, boolean.class, listenerClass);
            log(4, "calling claim type=" + typeName + " count=1");
            claimMethod.invoke(brObj, from, 1, scene, type, true, true, listener);
        } catch (Throwable t) {
            log(6, "claim threw", t);
            if (auto) {
                zeroStreak++;
                autoClaimNext(remainSec);
            }
        }
    }

    private void onClaimResponse(String typeName, Object wrapper, boolean auto) {
        try {
            Object exception = wrapper.getClass().getMethod("getException").invoke(wrapper);
            if (exception != null) {
                log(4, "claim " + typeName + " RESPONSE EXCEPTION: " + exception);
                if (auto) {
                    zeroStreak++;
                    autoClaimNext(remainSec);
                }
                return;
            }
            Object info = wrapper.getClass().getMethod("getInfo").invoke(wrapper);
            if (info == null) {
                log(4, "claim " + typeName + " RESPONSE: info is null");
                if (auto) {
                    zeroStreak++;
                    autoClaimNext(remainSec);
                }
                return;
            }
            Object handselTime = info.getClass().getMethod(d(M_GET_HANDSEL)).invoke(info);
            Object remain = info.getClass().getMethod(d(M_GET_REMAIN)).invoke(info);
            Object end = info.getClass().getMethod(d(M_GET_END)).invoke(info);
            Object already = info.getClass().getMethod(d(M_GET_ALREADY)).invoke(info);
            Object max = info.getClass().getMethod(d(M_GET_MAX)).invoke(info);
            String msg = "claim " + typeName + " OK: handselTime=" + handselTime
                    + " vipRemainTime=" + remain + " vipEndTime=" + end
                    + " already=" + already + "/" + max;
            log(4, msg);
            if (auto) {
                long remainS = remain instanceof Number ? ((Number) remain).longValue() : remainSec;
                long got = handselTime instanceof Number ? ((Number) handselTime).longValue() : 0;
                if (got > 0) {
                    zeroStreak = 0;
                    // v341: 成功领到，记下这次领取时间（冷却计时起点）。
                    saveLastClaimTime(System.currentTimeMillis());
                } else {
                    zeroStreak++;
                }
                autoClaimNext(remainS);
            }
        } catch (Throwable t) {
            log(6, "onClaimResponse failed", t);
            if (auto) {
                zeroStreak++;
                autoClaimNext(remainSec);
            }
        }
    }

    /** 免费页点“看视频”不打开广告，直接领。只在开关开着时拦截。 */
    private void hookToRewardAdActivity() {
        try {
            Class<?> extClass = Class.forName(d(CLS_EXTENSIONS), false, appLoader);
            Class<?> adConfigClass = Class.forName(d(CLS_AD_CONFIG), false, appLoader);
            Class<?> fromClass = Class.forName(d(CLS_REWARD_FROM), false, appLoader);
            Class<?> rewardAdTypeClass = Class.forName(d(CLS_REWARD_AD_TYPE), false, appLoader);
            Method adMethod = extClass.getDeclaredMethod(d(M_TO_AD),
                    Context.class, adConfigClass, fromClass,
                    String.class, String.class, rewardAdTypeClass);
            adMethod.setAccessible(true);
            bridge.hook(adMethod).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // v345: 开关已去掉，免费听永远打开，广告入口恒拦截。
                    java.util.List<Object> args = chain.getArgs();
                    Object fromTag = args.get(2);
                    String fromName = fromTag == null ? "null" : ((Enum<?>) fromTag).name();
                    Object amsConfig = args.get(1);
                    String amsName = amsConfig == null ? "null" : ((Enum<?>) amsConfig).name();
                    log(4, "adEntry: amsConfig=" + amsName + " fromTag=" + fromName);
                    if (d(E_FTV).equals(fromName)) {
                        log(4, "intercept tab-video: claim directly");
                        claimInternal(E_VIDEO, false);
                        return null;
                    }
                    if (d(E_FTW).equals(fromName)) {
                        log(4, "intercept tab-web: claim directly");
                        claimInternal(E_WEB, false);
                        return null;
                    }
                    if (d(E_GUIDE).equals(fromName) && d(E_ENTRANCE).equals(amsName)) {
                        log(4, "intercept guide+entrance: claim video directly");
                        claimInternal(E_VIDEO, false);
                        return null;
                    }
                    return chain.proceed();
                }
            });
            log(4, "hooked ad entry");
        } catch (Throwable t) {
            log(6, "hook ad entry failed", t);
        }
    }

    private void tryGetBR() {
        if (brObj != null) {
            return;
        }
        try {
            Class<?> compClass = Class.forName(d(CLS_COMPONENTS), false, appLoader);
            Object instance = compClass.getField("INSTANCE").get(null);
            brObj = compClass.getMethod(d(M_GET_BR)).invoke(instance);
            log(4, "got BR: " + brObj);
        } catch (Throwable t) {
            log(6, "get BR failed", t);
        }
    }

    private boolean isMainProcess() {
        try {
            String proc = null;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                proc = Application.getProcessName();
            }
            if (proc == null) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.FileReader("/proc/self/cmdline"));
                proc = r.readLine();
                r.close();
                if (proc != null) {
                    proc = proc.trim();
                }
            }
            String pkg = appContext == null ? null : appContext.getPackageName();
            return pkg == null || pkg.equals(proc);
        } catch (Throwable t) {
            return true;
        }
    }

    private void toast(final String text) {
        try {
            if (appContext == null) {
                return;
            }
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(appContext, text, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void writeLogFile(String path, String line) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            FileWriter w = new FileWriter(f, true);
            w.write(line);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private void log(int level, String msg) {
        log(level, msg, null);
    }

    private void log(int level, String msg, Throwable t) {
        try {
            if (t != null) {
                bridge.log(level, TAG, msg, t);
            } else {
                bridge.log(level, TAG, msg);
            }
        } catch (Throwable ignored) {
        }
        try {
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String line = "[" + stamp + "] " + msg + (t != null ? " | " + t : "") + "\n";
            writeLogFile(LOG_PATH, line);
            writeLogFile(LOG_PATH_ALT2, line);
        } catch (Throwable ignored) {
        }
    }
}
