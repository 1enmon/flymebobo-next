package com.chenan.flymebobo;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.icu.text.Transliterator;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.widget.RemoteViews;
import com.chenan.flymebobo.UpdateChecker;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: ns613.jar:com/chenan/flymebobo/NotificationService.class */
public class NotificationService extends NotificationListenerService {
    public static final String ACTION_NOTIFY = "com.chenan.flymebobo.NOTIFY";
    public static final String ACTION_CANCEL_DIAG = "com.chenan.flymebobo.CANCEL_DIAG";
    public static final String ACTION_LYRIC = "com.chenan.flymebobo.LYRIC";
    public static final String ACTION_DOWNLOAD = "com.chenan.flymebobo.DOWNLOAD";
    public static final String ACTION_DOWNLOAD_REMOVE = "com.chenan.flymebobo.DOWNLOAD_REMOVE";
    public static final String ACTION_CALL = "com.chenan.flymebobo.CALL";
    public static final String ACTION_CALL_CONTROL = "com.chenan.flymebobo.CALL_CONTROL";
    public static final String ACTION_DISMISS = "com.chenan.flymebobo.DISMISS";
    public static final String ACTION_CALL_RESULT = "com.chenan.flymebobo.CALL_RESULT";
    public static final String CALL_RINGING = "ringing";
    public static final String CALL_OFFHOOK = "offhook";
    public static final String CALL_IDLE = "idle";
    public static final String ACTION_LYRIC_LISTEN = "com.chenan.flymebobo.LYRIC_LISTEN";
    public static final String ACTION_AI_ANALYZE = "com.chenan.flymebobo.AI_ANALYZE";
    public static final String ACTION_AI_RESULT = "com.chenan.flymebobo.AI_RESULT";
    public static final String ACTION_AI_INFO_RESULT = "com.chenan.flymebobo.AI_INFO_RESULT";
    public static final String ACTION_INFO_REMIND = "com.chenan.flymebobo.INFO_REMIND";
    public static final String ACTION_INFO_REMIND_CANCEL = "com.chenan.flymebobo.INFO_REMIND_CANCEL";
    public static final String ACTION_MUSIC_CONTROL = "com.chenan.flymebobo.MUSIC_CONTROL";
    public static final String ACTION_MUSIC_PROGRESS = "com.chenan.flymebobo.MUSIC_PROGRESS";
    public static final String ACTION_FEATURE_TOGGLE = "com.chenan.flymebobo.FEATURE_TOGGLE";
    public static final String ACTION_APP_RULES = "com.chenan.flymebobo.APP_RULES";
    public static final String ACTION_SET_APP_RULE = "com.chenan.flymebobo.SET_APP_RULE";
    public static final String ACTION_SET_LIGHT_DOT_POS = "com.chenan.flymebobo.SET_LIGHT_DOT_POS";
    public static final String ACTION_LIGHT_OPEN = "com.chenan.flymebobo.LIGHT_OPEN";
    public static final String ACTION_LIGHT_RECALL_OPEN = "com.chenan.flymebobo.LIGHT_RECALL_OPEN";
    public static final String ACTION_MUSIC_GONE = "com.chenan.flymebobo.MUSIC_GONE";
    public static final String ACTION_SCENE_EXC = "com.chenan.flymebobo.SCENE_EXC";
    public static final String ACTION_SOUND_RULES = "com.chenan.flymebobo.SOUND_RULES";
    public static final String ACTION_GAME_LIST = "com.chenan.flymebobo.GAME_LIST";
    public static final String ACTION_APPEARANCE = "com.chenan.flymebobo.APPEARANCE";
    public static final String ACTION_RELOAD_SETTINGS = "com.chenan.flymebobo.RELOAD_SETTINGS";
    public static final String ACTION_TEMP_SILENCE = "com.chenan.flymebobo.TEMP_SILENCE";
    public static final String ACTION_TEMP_SILENCE_FLUSHED = "com.chenan.flymebobo.TEMP_SILENCE_FLUSHED";
    public static final String ACTION_SCENE_ANIM = "com.chenan.flymebobo.SCENE_ANIM";
    public static final String ACTION_NAVI_INFO = "com.chenan.flymebobo.NAVI_INFO";
    public static final String ACTION_NAVI_END = "com.chenan.flymebobo.NAVI_END";
    public static final String ACTION_NAVI_HOOK_STATUS = "com.chenan.flymebobo.NAVI_HOOK_STATUS";
    public static final String ACTION_NAVI_REMAIN = "com.chenan.flymebobo.NAVI_REMAIN";
    public static final String ACTION_BUS_INFO = "com.chenan.flymebobo.BUS_INFO";
    public static final String ACTION_BUS_ROUTE = "com.chenan.flymebobo.BUS_ROUTE";
    public static final String ACTION_BUS_HOOK_STATUS = "com.chenan.flymebobo.BUS_HOOK_STATUS";
    public static final String ACTION_FOOT_ROUTE = "com.chenan.flymebobo.FOOT_ROUTE";
    public static final String ACTION_FOOT_DIAG = "com.chenan.flymebobo.FOOT_DIAG";
    public static final String ACTION_FOOT_HOOK_STATUS = "com.chenan.flymebobo.FOOT_HOOK_STATUS";
    public static final String ACTION_ROUTE_DIAG = "com.chenan.flymebobo.ROUTE_DIAG";
    public static final String ACTION_ROUTE_HOOK_STATUS = "com.chenan.flymebobo.ROUTE_HOOK_STATUS";
    private static final long DEDUP_TTL_MS = 3600000;
    private static final int DEDUP_MAX_KEYS = 256;
    private static final long STALE_WINDOW_MS = 5000;
    private static final String PKG_MEITUAN = "com.sankuai.meituan";
    private static final String PKG_ELEME = "me.ele";
    private static final String PKG_AMAP = "com.autonavi.minimap";
    private static final long FINGERPRINT_WINDOW_MS = 15000;
    private static final long SUMMARY_WINDOW_MS = 60000;
    private static final long GROUP_WINDOW_MS = 10000;
    private MediaController musicController;
    private MediaController.Callback musicCallback;
    private MediaSession.Token musicToken;
    private String musicKey;
    private boolean musicListening;
    private String lastLyricSent;
    private String currentMusicTitle;
    private String lastBroadcastTitle;
    private String lastBroadcastArtist;
    private String lastBroadcastRawArtist;
    private String lastBroadcastArtTitle;
    private String currentMusicNotifKey;
    private String currentMusicArtist;
    private boolean lastBroadcastFavorite;
    private Boolean pendingFavorite;
    private long pendingFavoriteAt;
    private Handler lyricPollHandler;
    private Runnable lyricPollRunnable;
    private static final long LYRIC_POLL_MS = 500;
    private Handler updateCheckHandler;
    private Runnable updateCheckRunnable;
    private boolean updateCheckStarted;
    private static final long UPDATE_CHECK_FIRST_MS = 60000;
    private static final long UPDATE_CHECK_INTERVAL_MS = 21600000;
    private String netLyricSong;
    private String netLyricArtist;
    private long[] netLyricTimes;
    private String[] netLyricTexts;
    private boolean netLyricFetching;
    private String netLyricFetchingFor;
    private long netLyricStartTime;
    private String downloadKey;
    private String callKey;
    private PendingIntent callSpeakerOnIntent;
    private PendingIntent callSpeakerOffIntent;
    private String callActionsDesc;
    private String callNumber;
    private String callName;
    private Handler downloadTickHandler;
    private Runnable downloadTickRunnable;
    private String downloadTickKey;
    private static final long DOWNLOAD_TICK_MS = 500;
    private Handler musicTickHandler;
    private Runnable musicTickRunnable;
    private static final long MUSIC_TICK_MS = 1000;
    private boolean swappedLayout;
    private String swappedSongTitle;
    private String swappedArtist;
    private String lastSubtitle;
    private String lastRawTitle;
    private String lastArtistMeta;
    private String lastNotifTitle;
    private String currentNotifTitle;
    private String currentLyricTicker;
    private String currentLyricText;
    private String currentLyricBigText;
    private static Object s2sTransliterator;
    private static final Set<String> PUSH_SERVICE_PACKAGES = new HashSet(Arrays.asList("com.meizu.cloud", "com.meizu.cloud.pushservice", "com.meizu.push", "com.meizu.flyme.push", "com.meizu.cloud.pushsdk", "com.xiaomi.xmsf", "com.xiaomi.push", "com.xiaomi.mipush", "com.xiaomi.xmpush", "com.huawei.android.pushagent", "com.huawei.hms", "com.huawei.android.push", "com.hihonor.push", "com.honor.push", "com.hihonor.android.pushagent", "com.honor.android.pushagent", "com.coloros.mcs", "com.heytap.mcs", "com.heytap.msp", "com.oppo.push", "com.coloros.push", "com.heytap.push", "com.oneplus.push", "com.oneplus.mcs", "com.realme.push", "com.realme.mcs", "com.vivo.pushservice", "com.vivo.push", "com.vivo.push.sdk", "com.samsung.android.push", "com.samsung.android.mobileservice", "com.sec.android.push", "com.samsung.push", "com.google.android.gms", "com.google.android.gsf", "com.zte.push", "com.nubia.push", "com.lenovo.push", "com.motorola.push", "com.sony.nfx", "com.sonymobile.push", "com.lge.push", "com.htc.push", "com.asus.push", "com.sharp.push", "com.tcl.push", "com.hmdglobal.push", "com.baidu.push", "com.baidu.android.pushservice", "com.qihoo.push", "com.meitu.push", "com.gionee.push", "com.coolpad.push", "com.smartisan.push", "com.android.push"));
    private static final Set<String> VIDEO_APP_PACKAGES = new HashSet(Arrays.asList("tv.danmaku.bili", "com.bilibili.app.in", "tv.danmaku.bilibilihd", "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite", "com.smile.gifmaker", "com.kuaishou.nebula", "com.tencent.qqlive", "com.qiyi.video", "com.youku.phone", "com.ss.android.article.video", "com.hunantv.imgo.activity", "com.google.android.youtube", "com.netflix.mediaclient", "com.sohu.sohuvideo", "com.letv.android.client", "com.baidu.video", "com.baidu.haokan"));
    private final Map<String, Long> seenKeys = new HashMap();
    private final Map<String, String> seenContent = new HashMap();
    private final Map<String, Long> recentFingerprints = new HashMap();
    private final Map<String, String> recentContentPoster = new HashMap();
    private final Map<String, Long> recentPkgFingerprints = new HashMap();
    private final Map<String, Long> recentNotifGroups = new HashMap();
    private final LinkedHashMap<String, String> lastNotifFingerprintByPkg = new LinkedHashMap<String, String>(32, 0.75f, true) { // from class: com.chenan.flymebobo.NotificationService.1
        @Override // java.util.LinkedHashMap
        protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
            return size() > 32;
        }
    };
    private final Map<String, MsgState> msgStates = new HashMap();
    private final Map<String, String> lastMsgByConversation = new HashMap();
    private final Map<String, Long> lastPostTimeByKey = new HashMap();
    private final Map<String, String> lastTitleByPkg = new HashMap();
    private final Map<String, String> lastTextByPkg = new HashMap();
    private final Map<String, Long> lastShownAtByPkg = new HashMap();
    private final Map<String, Long> lastSummarySenderAt = new HashMap();
    private long lastBroadcastDuration = -1;
    private long currentMusicDurationMs = -1;
    private long lastBroadcastPositionMs = -1;
    private final Set<String> selfCancelledKeys = Collections.synchronizedSet(new HashSet());
    private int lastDownloadPct = -1;
    private long lastDownloadBytes = -1;
    private long lastDownloadBytesTime = 0;
    private long lastDownloadSpeed = -1;
    private long lastDownloadEta = -1;
    private int downloadGoneTicks = 0;
    private final BroadcastReceiver callControlReceiver = new BroadcastReceiver() { // from class: com.chenan.flymebobo.NotificationService.19
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            if (NotificationService.ACTION_SET_APP_RULE.equals(intent.getAction())) {
                String stringExtra = intent.getStringExtra("pkg");
                String stringExtra2 = intent.getStringExtra("mode");
                if (stringExtra != null) {
                    AppRules.setRule(context, stringExtra, AppRules.parse(stringExtra2));
                    FileLog.i("SetAppRule pkg=" + stringExtra + " mode=" + stringExtra2);
                    return;
                }
                return;
            }
            if (NotificationService.ACTION_SET_LIGHT_DOT_POS.equals(intent.getAction())) {
                String parseLightDotPos = FeatureFlags.parseLightDotPos(intent.getStringExtra("pos"));
                context.getSharedPreferences("flymebobo_features", 0).edit().putString("lightDotPos", parseLightDotPos).commit();
                SettingsStore.saveAll(context);
                Intent intent2 = new Intent(NotificationService.ACTION_FEATURE_TOGGLE);
                intent2.putExtra("key", "lightDotPos");
                intent2.putExtra("pos", parseLightDotPos);
                context.sendBroadcast(intent2);
                FileLog.i("SetLightDotPos pos=" + parseLightDotPos);
                return;
            }
            if (NotificationService.ACTION_MUSIC_CONTROL.equals(intent.getAction())) {
                NotificationService.this.handleMusicControl(intent.getStringExtra("command"), intent.getLongExtra("seek_position", -1L));
            } else {
                if (NotificationService.ACTION_DISMISS.equals(intent.getAction())) {
                    String stringExtra3 = intent.getStringExtra("key");
                    if (stringExtra3 != null) {
                        NotificationService.this.cancelByKey(stringExtra3, intent.getStringExtra("postPkg"));
                        return;
                    }
                    return;
                }
                String stringExtra4 = intent.getStringExtra("command");
                if (stringExtra4 == null) {
                    return;
                }
                NotificationService.this.fireCallAction(stringExtra4, intent.getStringExtra("call_key"));
            }
        }
    };

    /* loaded from: ns613.jar:com/chenan/flymebobo/NotificationService$MsgState.class */
    private static class MsgState {
        long postTime;
        final LinkedHashSet<String> seen = new LinkedHashSet<>();

        MsgState(long j) {
            this.postTime = j;
        }
    }

    private static String contentDigest(String str, String str2) {
        return contentDigest(str, str2, -1, -1);
    }

    private Bitmap bitmapFromLargeIcon(Object obj) {
        Drawable loadDrawable;
        if (obj == null) {
            return null;
        }
        try {
            if (obj instanceof Bitmap) {
                return (Bitmap) obj;
            }
            if (!(obj instanceof Icon) || (loadDrawable = ((Icon) obj).loadDrawable(this)) == null) {
                return null;
            }
            if (loadDrawable instanceof BitmapDrawable) {
                return ((BitmapDrawable) loadDrawable).getBitmap();
            }
            int intrinsicWidth = loadDrawable.getIntrinsicWidth();
            int intrinsicHeight = loadDrawable.getIntrinsicHeight();
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
                intrinsicWidth = 154;
                intrinsicHeight = 154;
            }
            Bitmap createBitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(createBitmap);
            loadDrawable.setBounds(0, 0, intrinsicWidth, intrinsicHeight);
            loadDrawable.draw(canvas);
            return createBitmap;
        } catch (Throwable th) {
            return null;
        }
    }

    private static String contentDigest(String str, String str2, int i, int i2) {
        return (str == null ? "" : str) + "\u0001" + (str2 == null ? "" : str2) + "\u0001" + i + "/" + i2;
    }

    private static String firstNonEmpty(String... strArr) {
        if (strArr == null) {
            return null;
        }
        for (String str : strArr) {
            if (str != null && !str.isEmpty()) {
                return str;
            }
        }
        return null;
    }

    private static String getText(Bundle bundle, String str) {
        if (bundle == null) {
            return null;
        }
        try {
            CharSequence charSequence = bundle.getCharSequence(str);
            if (charSequence != null) {
                return charSequence.toString();
            }
            return null;
        } catch (Throwable th) {
            return null;
        }
    }

    private static String getLastTextLine(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        try {
            CharSequence[] charSequenceArray = bundle.getCharSequenceArray("android.textLines");
            if (charSequenceArray != null) {
                for (int length = charSequenceArray.length - 1; length >= 0; length--) {
                    if (charSequenceArray[length] != null && !charSequenceArray[length].toString().trim().isEmpty()) {
                        return charSequenceArray[length].toString();
                    }
                }
            }
            return null;
        } catch (Throwable th) {
            return null;
        }
    }

    private static String getLatestMessageText(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        try {
            Parcelable[] parcelableArray = bundle.getParcelableArray("android.messages");
            if (parcelableArray != null) {
                for (int length = parcelableArray.length - 1; length >= 0; length--) {
                    Parcelable parcelable = parcelableArray[length];
                    if (parcelable != null && "android.app.Notification$MessagingStyle$Message".equals(parcelable.getClass().getName())) {
                        try {
                            Object invoke = parcelable.getClass().getMethod("getText", new Class[0]).invoke(parcelable, new Object[0]);
                            if ((invoke instanceof CharSequence) && !invoke.toString().trim().isEmpty()) {
                                return invoke.toString();
                            }
                        } catch (Throwable th) {
                        }
                    }
                }
            }
            return null;
        } catch (Throwable th2) {
            return null;
        }
    }

    private boolean isRepeatNotif(String str, String str2, String str3) {
        return isRepeatNotif(str, str2, str3, -1, -1);
    }

    private boolean isRepeatNotif(String str, String str2, String str3, int i, int i2) {
        if (str == null) {
            return false;
        }
        try {
            String contentDigest = contentDigest(str2, str3, i, i2);
            long uptimeMillis = SystemClock.uptimeMillis();
            synchronized (this.seenKeys) {
                Long l = this.seenKeys.get(str);
                String str4 = this.seenContent.get(str);
                if (l != null && uptimeMillis - l.longValue() < DEDUP_TTL_MS && contentDigest.equals(str4)) {
                    return true;
                }
                this.seenKeys.put(str, Long.valueOf(uptimeMillis));
                this.seenContent.put(str, contentDigest);
                if (this.seenKeys.size() > 128) {
                    Iterator<Map.Entry<String, Long>> it = this.seenKeys.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, Long> next = it.next();
                        if (uptimeMillis - next.getValue().longValue() > DEDUP_TTL_MS) {
                            it.remove();
                            this.seenContent.remove(next.getKey());
                        }
                    }
                }
                return false;
            }
        } catch (Throwable th) {
            return false;
        }
    }

    private boolean isRepeatNotifFingerprint(String str, StatusBarNotification statusBarNotification, String str2, String str3, int i, int i2) {
        if (str == null || statusBarNotification == null) {
            return false;
        }
        try {
            int id = statusBarNotification.getId();
            String str4 = id + ":" + statusBarNotification.getPostTime() + ":" + id + "/" + (str2 == null ? "" : str2) + ":" + (str3 == null ? "" : str3) + "/" + i;
            synchronized (this.lastNotifFingerprintByPkg) {
                if (str4.equals(this.lastNotifFingerprintByPkg.get(str))) {
                    return true;
                }
                this.lastNotifFingerprintByPkg.put(str, str4);
                return false;
            }
        } catch (Throwable th) {
            return false;
        }
    }

    private boolean isRedundantMessage(StatusBarNotification statusBarNotification, Notification notification, Bundle bundle, String str, String str2, String str3) {
        CharSequence[] charSequenceArray;
        Bundle bundle2;
        CharSequence charSequence;
        if (statusBarNotification == null || statusBarNotification.getKey() == null) {
            return false;
        }
        try {
            ArrayList arrayList = new ArrayList();
            if (bundle != null) {
                Parcelable[] parcelableArray = bundle.getParcelableArray("android.messages");
                if (parcelableArray != null) {
                    for (int max = Math.max(0, parcelableArray.length - 25); max < parcelableArray.length; max++) {
                        if ((parcelableArray[max] instanceof Bundle) && (charSequence = (bundle2 = (Bundle) parcelableArray[max]).getCharSequence("text")) != null && charSequence.length() > 0) {
                            CharSequence charSequence2 = bundle2.getCharSequence("sender");
                            arrayList.add((charSequence2 == null ? "" : charSequence2.toString()) + "\u0001" + charSequence.toString());
                        }
                    }
                }
                if (arrayList.isEmpty() && (charSequenceArray = bundle.getCharSequenceArray("android.textLines")) != null) {
                    for (int max2 = Math.max(0, charSequenceArray.length - 25); max2 < charSequenceArray.length; max2++) {
                        if (charSequenceArray[max2] != null && charSequenceArray[max2].length() > 0) {
                            arrayList.add(charSequenceArray[max2].toString());
                        }
                    }
                }
            }
            if (arrayList.isEmpty()) {
                String str4 = (str2 == null ? "" : str2) + "\u0001" + (str3 == null ? "" : str3);
                if (!str4.trim().isEmpty()) {
                    arrayList.add(str4);
                }
            }
            if (arrayList.isEmpty()) {
                return false;
            }
            String str5 = null;
            if (bundle != null) {
                CharSequence charSequence3 = bundle.getCharSequence("android.conversationTitle");
                String charSequence4 = charSequence3 == null ? "" : charSequence3.toString();
                if (charSequence4.isEmpty() && notification != null && notification.getShortcutId() != null) {
                    charSequence4 = notification.getShortcutId();
                }
                if (charSequence4.isEmpty() && str2 != null && !str2.isEmpty() && !"微信".equals(str2) && !"QQ".equals(str2) && !str2.contains("条新消息") && !str2.contains("新消息")) {
                    charSequence4 = str2;
                }
                if (!charSequence4.isEmpty()) {
                    str5 = str + "\u0001" + charSequence4;
                }
            }
            String str6 = (String) arrayList.get(arrayList.size() - 1);
            String key = statusBarNotification.getKey();
            long postTime = statusBarNotification.getPostTime();
            synchronized (this.msgStates) {
                MsgState msgState = this.msgStates.get(key);
                if (msgState != null && postTime < msgState.postTime) {
                    return true;
                }
                if (msgState == null) {
                    msgState = new MsgState(postTime);
                    this.msgStates.put(key, msgState);
                    if (this.msgStates.size() > DEDUP_MAX_KEYS) {
                        Iterator<String> it = this.msgStates.keySet().iterator();
                        if (it.hasNext()) {
                            it.next();
                            it.remove();
                        }
                    }
                } else {
                    msgState.postTime = postTime;
                }
                boolean z = true;
                Iterator it2 = arrayList.iterator();
                while (true) {
                    if (!it2.hasNext()) {
                        break;
                    }
                    if (!msgState.seen.contains((String) it2.next())) {
                        z = false;
                        break;
                    }
                }
                if (z) {
                    return true;
                }
                if (str5 != null) {
                    synchronized (this.lastMsgByConversation) {
                        String str7 = this.lastMsgByConversation.get(str5);
                        if (str7 != null && str7.equals(str6)) {
                            Iterator it3 = arrayList.iterator();
                            while (it3.hasNext()) {
                                msgState.seen.add((String) it3.next());
                                if (msgState.seen.size() > 64) {
                                    msgState.seen.remove(msgState.seen.iterator().next());
                                }
                            }
                            return true;
                        }
                    }
                }
                Iterator it4 = arrayList.iterator();
                while (it4.hasNext()) {
                    msgState.seen.add((String) it4.next());
                    if (msgState.seen.size() > 64) {
                        msgState.seen.remove(msgState.seen.iterator().next());
                    }
                }
                if (str5 != null) {
                    synchronized (this.lastMsgByConversation) {
                        this.lastMsgByConversation.put(str5, str6);
                        if (this.lastMsgByConversation.size() > DEDUP_MAX_KEYS) {
                            Iterator<String> it5 = this.lastMsgByConversation.keySet().iterator();
                            if (it5.hasNext()) {
                                it5.next();
                                it5.remove();
                            }
                        }
                    }
                }
                return false;
            }
        } catch (Throwable th) {
            return false;
        }
    }

    private boolean isStalePostTime(String str, long j) {
        Long l;
        return (str == null || (l = this.lastPostTimeByKey.get(str)) == null || j >= l.longValue()) ? false : true;
    }

    private boolean isPkgFingerprintRepeat(String str, String str2, String str3) {
        String str4 = (str == null ? "" : str) + "\u0001" + contentDigest(str2, str3);
        long uptimeMillis = SystemClock.uptimeMillis();
        Long l = this.recentPkgFingerprints.get(str4);
        boolean z = l != null && uptimeMillis - l.longValue() < FINGERPRINT_WINDOW_MS;
        this.recentPkgFingerprints.put(str4, Long.valueOf(uptimeMillis));
        if (this.recentPkgFingerprints.size() > 64) {
            Iterator<Map.Entry<String, Long>> it = this.recentPkgFingerprints.entrySet().iterator();
            while (it.hasNext()) {
                if (uptimeMillis - it.next().getValue().longValue() > FINGERPRINT_WINDOW_MS) {
                    it.remove();
                }
            }
        }
        return z;
    }

    private boolean isPushService(String str) {
        if (str == null) {
            return false;
        }
        if (PUSH_SERVICE_PACKAGES.contains(str)) {
            return true;
        }
        try {
            CharSequence applicationLabel = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(str, 0));
            if (applicationLabel != null) {
                return "推送服务".equals(applicationLabel.toString());
            }
            return false;
        } catch (Throwable th) {
            return false;
        }
    }

    private void cancelNotif(StatusBarNotification statusBarNotification) {
        if (statusBarNotification == null) {
            return;
        }
        final String key = statusBarNotification.getKey();
        String keyPackage = keyPackage(key);
        final String packageName = (keyPackage == null || keyPackage.isEmpty()) ? statusBarNotification.getPackageName() : keyPackage;
        final String tag = statusBarNotification.getTag();
        final int id = statusBarNotification.getId();
        FileLog.i("cancelNotif key=" + key + " keyPkg=" + keyPackage + " pkg=" + packageName + " postPkg=" + statusBarNotification.getPackageName() + " tag=" + tag + " id=" + id);
        try {
            cancelNotification(key);
        } catch (Throwable th) {
            FileLog.i("cancelNotif key-cancel threw: " + String.valueOf(th));
        }
        try {
            cancelNotification(packageName, tag, id);
        } catch (Throwable th2) {
            FileLog.i("cancelNotif 3-arg cancel threw: " + String.valueOf(th2));
        }
        String packageName2 = statusBarNotification.getPackageName();
        if (packageName2 != null && !packageName2.equals(packageName)) {
            try {
                cancelNotification(packageName2, tag, id);
            } catch (Throwable th3) {
                FileLog.i("cancelNotif postPkg cancel threw: " + String.valueOf(th3));
            }
        }
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { // from class: com.chenan.flymebobo.NotificationService.2
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        boolean z = false;
                        StatusBarNotification[] activeNotifications = NotificationService.this.getActiveNotifications();
                        if (activeNotifications != null) {
                            int length = activeNotifications.length;
                            int i = 0;
                            while (true) {
                                if (i < length) {
                                    StatusBarNotification statusBarNotification2 = activeNotifications[i];
                                    if (statusBarNotification2 != null && key.equals(statusBarNotification2.getKey())) {
                                        z = true;
                                        break;
                                    }
                                    i++;
                                } else {
                                    break;
                                }
                            }
                        }
                        FileLog.i("cancelNotif stillActive=" + z + " key=" + key);
                        try {
                            Intent intent = new Intent(NotificationService.ACTION_CANCEL_DIAG);
                            intent.putExtra("key", key);
                            intent.putExtra("stillActive", z);
                            intent.putExtra("pkg", packageName);
                            intent.putExtra("tag", tag);
                            intent.putExtra("id", id);
                            NotificationService.this.sendBroadcast(intent);
                        } catch (Throwable th4) {
                        }
                    } catch (Throwable th5) {
                    }
                }
            }, 500L);
        } catch (Throwable th4) {
        }
    }

    private void cancelByKey(final String str, String str2) {
        if (str == null) {
            return;
        }
        String keyPackage = keyPackage(str);
        String str3 = null;
        int i = 0;
        boolean z = false;
        try {
            String[] split = str.split("\\|");
            if (split.length >= 4) {
                i = Integer.parseInt(split[2]);
                String str4 = split[3];
                str3 = "null".equals(str4) ? null : str4;
                z = true;
            }
        } catch (Throwable th) {
        }
        FileLog.i("cancelByKey key=" + str + " keyPkg=" + keyPackage + " postPkg=" + str2 + " tag=" + str3 + " id=" + i);
        try {
            cancelNotification(str);
        } catch (Throwable th2) {
            FileLog.i("cancelByKey key-cancel threw: " + String.valueOf(th2));
        }
        if (z && keyPackage != null && !keyPackage.isEmpty()) {
            try {
                cancelNotification(keyPackage, str3, i);
            } catch (Throwable th3) {
                FileLog.i("cancelByKey keyPkg cancel threw: " + String.valueOf(th3));
            }
        }
        if (z && str2 != null && !str2.isEmpty() && !str2.equals(keyPackage)) {
            try {
                cancelNotification(str2, str3, i);
            } catch (Throwable th4) {
                FileLog.i("cancelByKey postPkg cancel threw: " + String.valueOf(th4));
            }
        }
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { // from class: com.chenan.flymebobo.NotificationService.3
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        boolean z2 = false;
                        StatusBarNotification[] activeNotifications = NotificationService.this.getActiveNotifications();
                        if (activeNotifications != null) {
                            int length = activeNotifications.length;
                            int i2 = 0;
                            while (true) {
                                if (i2 < length) {
                                    StatusBarNotification statusBarNotification = activeNotifications[i2];
                                    if (statusBarNotification != null && str.equals(statusBarNotification.getKey())) {
                                        z2 = true;
                                        break;
                                    }
                                    i2++;
                                } else {
                                    break;
                                }
                            }
                        }
                        FileLog.i("cancelByKey stillActive=" + z2 + " key=" + str);
                    } catch (Throwable th5) {
                    }
                }
            }, 500L);
        } catch (Throwable th5) {
        }
    }

    private static String keyPackage(String str) {
        if (str == null) {
            return null;
        }
        String[] split = str.split("\\|");
        if (split.length >= 2) {
            return split[1];
        }
        return null;
    }

    private boolean isRedundantPushCopy(String str, String str2, String str3) {
        if (!isPushService(str)) {
            return false;
        }
        String contentDigest = contentDigest(str2, str3);
        Long l = this.recentFingerprints.get(contentDigest);
        String str4 = this.recentContentPoster.get(contentDigest);
        return (l == null || str4 == null || isPushService(str4) || SystemClock.uptimeMillis() - l.longValue() >= FINGERPRINT_WINDOW_MS) ? false : true;
    }

    private boolean isFingerprintRepeat(String str, String str2, String str3) {
        String contentDigest = contentDigest(str2, str3);
        long uptimeMillis = SystemClock.uptimeMillis();
        Long l = this.recentFingerprints.get(contentDigest);
        boolean z = l != null && uptimeMillis - l.longValue() < FINGERPRINT_WINDOW_MS;
        this.recentFingerprints.put(contentDigest, Long.valueOf(uptimeMillis));
        this.recentContentPoster.put(contentDigest, str == null ? "" : str);
        if (this.recentFingerprints.size() > 64) {
            Iterator<Map.Entry<String, Long>> it = this.recentFingerprints.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> next = it.next();
                if (uptimeMillis - next.getValue().longValue() > FINGERPRINT_WINDOW_MS) {
                    it.remove();
                    this.recentContentPoster.remove(next.getKey());
                }
            }
        }
        return z;
    }

    private boolean isRedundantGroupSummary(Notification notification, String str) {
        String group;
        Long l;
        try {
            if ((notification.flags & 512) != 0 && (group = notification.getGroup()) != null && str != null && (l = this.recentNotifGroups.get(str + "\u0001" + group)) != null) {
                if (SystemClock.uptimeMillis() - l.longValue() < GROUP_WINDOW_MS) {
                    return true;
                }
            }
            return false;
        } catch (Throwable th) {
            return false;
        }
    }

    private void noteNotifGroup(Notification notification, String str) {
        String group;
        try {
            if ((notification.flags & 512) != 0 || (group = notification.getGroup()) == null || str == null) {
                return;
            }
            this.recentNotifGroups.put(str + "\u0001" + group, Long.valueOf(SystemClock.uptimeMillis()));
            if (this.recentNotifGroups.size() > 64) {
                this.recentNotifGroups.clear();
            }
        } catch (Throwable th) {
        }
    }

    private boolean isSummaryOf(String str, String str2, String str3) {
        if (str == null || str3 == null || str3.isEmpty()) {
            return false;
        }
        String str4 = this.lastTitleByPkg.get(str);
        String str5 = this.lastTextByPkg.get(str);
        Long l = this.lastShownAtByPkg.get(str);
        if (str5 == null || str5.isEmpty() || l == null || SystemClock.uptimeMillis() - l.longValue() > 60000) {
            return false;
        }
        String str6 = str2 == null ? "" : str2;
        String str7 = str4 == null ? "" : str4;
        return (str3.contains(str5) || str5.contains(str3)) && ((!str6.isEmpty() && (str3.contains(str6) || str5.contains(str6))) || (!str7.isEmpty() && (str3.contains(str7) || str5.contains(str7))));
    }

    private static String parseSummarySender(String str) {
        int indexOf;
        if (str == null) {
            return null;
        }
        int indexOf2 = str.indexOf(91);
        int indexOf3 = indexOf2 >= 0 ? str.indexOf(93, indexOf2) : -1;
        if (indexOf2 < 0 || indexOf3 < 0 || indexOf3 + 1 >= str.length() || !str.substring(indexOf2 + 1, indexOf3).endsWith("条") || (indexOf = str.indexOf(58, indexOf3)) < 0 || indexOf + 1 >= str.length()) {
            return null;
        }
        String trim = str.substring(indexOf3 + 1, indexOf).trim();
        if (trim.isEmpty()) {
            return null;
        }
        return trim;
    }

    private boolean isAggregatedSummary(String str, String str2) {
        String parseSummarySender;
        Long l;
        return (str == null || (parseSummarySender = parseSummarySender(str2)) == null || (l = this.lastSummarySenderAt.get(str + "\u0001" + parseSummarySender)) == null || SystemClock.uptimeMillis() - l.longValue() >= 60000) ? false : true;
    }

    private void recordNotif(String str, String str2, String str3, String str4, long j) {
        recordNotif(str, str2, str3, str4, j, -1, -1);
    }

    private void recordNotif(String str, String str2, String str3, String str4, long j, int i, int i2) {
        if (str == null) {
            return;
        }
        if (this.seenKeys.size() >= DEDUP_MAX_KEYS) {
            long uptimeMillis = SystemClock.uptimeMillis();
            Iterator<Map.Entry<String, Long>> it = this.seenKeys.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> next = it.next();
                if (uptimeMillis - next.getValue().longValue() > DEDUP_TTL_MS) {
                    it.remove();
                    this.seenContent.remove(next.getKey());
                }
            }
        }
        this.seenKeys.put(str, Long.valueOf(SystemClock.uptimeMillis()));
        this.seenContent.put(str, contentDigest(str2, str3, i, i2));
        Long l = this.lastPostTimeByKey.get(str);
        if (l == null || j > l.longValue()) {
            this.lastPostTimeByKey.put(str, Long.valueOf(j));
        }
        if (str4 != null) {
            this.lastTitleByPkg.put(str4, str2 == null ? "" : str2);
            this.lastTextByPkg.put(str4, str3 == null ? "" : str3);
            this.lastShownAtByPkg.put(str4, Long.valueOf(SystemClock.uptimeMillis()));
            String parseSummarySender = parseSummarySender(str3);
            if (parseSummarySender != null) {
                this.lastSummarySenderAt.put(str4 + "\u0001" + parseSummarySender, Long.valueOf(SystemClock.uptimeMillis()));
            }
        }
    }

    private static boolean isSongRecognition(Notification notification) {
        if (notification == null || notification.extras == null) {
            return false;
        }
        CharSequence charSequence = notification.extras.getCharSequence("android.title");
        if (charSequence != null) {
            String charSequence2 = charSequence.toString();
            if (charSequence2.contains("听歌识曲") || charSequence2.contains("可识别")) {
                return true;
            }
        }
        CharSequence charSequence3 = notification.extras.getCharSequence("android.text");
        if (charSequence3 == null) {
            return false;
        }
        String charSequence4 = charSequence3.toString();
        return charSequence4.contains("听歌识曲") || charSequence4.contains("可识别");
    }

    private static boolean isKnownMusicApp(String str, Notification notification) {
        if (str == null || notification == null) {
            return false;
        }
        if (((notification.flags & 2) == 0 && (notification.flags & 64) == 0) ? false : true) {
            return "com.tencent.qqmusic".equals(str) || "com.netease.cloudmusic".equals(str) || "com.kugou.android".equals(str) || "cn.kuwo.player".equals(str) || "cmccwm.mobilemusic".equals(str) || "com.ximalaya.ting.android".equals(str) || "com.luna.music".equals(str) || "com.meizu.media.music".equals(str) || str.contains("qqmusic") || str.contains("kugou") || str.contains("kuwo");
        }
        return false;
    }

    private static boolean isVideoApp(String str) {
        return str != null && VIDEO_APP_PACKAGES.contains(str);
    }

    private void ensureMusicController() {
        if (this.musicToken == null) {
            return;
        }
        try {
            if (this.musicController == null) {
                this.musicController = new MediaController(this, this.musicToken);
            }
            startMusicTick();
        } catch (Throwable th) {
        }
    }

    private void setupMusicListener() {
        teardownMusicListener();
        if (this.musicToken == null || this.musicKey == null) {
            return;
        }
        try {
            ensureMusicController();
            this.lastLyricSent = null;
            MediaController.Callback callback = new MediaController.Callback() { // from class: com.chenan.flymebobo.NotificationService.4
                @Override // android.media.session.MediaController.Callback
                public void onMetadataChanged(MediaMetadata mediaMetadata) {
                    NotificationService.this.refreshLyricFromActive();
                    NotificationService.this.broadcastMusicProgress();
                }

                @Override // android.media.session.MediaController.Callback
                public void onPlaybackStateChanged(PlaybackState playbackState) {
                    NotificationService.this.refreshLyricFromActive();
                    NotificationService.this.broadcastMusicProgress();
                }
            };
            this.musicCallback = callback;
            this.musicController.registerCallback(callback);
            refreshLyricFromActive();
            startLyricPoll();
        } catch (Throwable th) {
            this.musicController = null;
            this.musicCallback = null;
        }
    }

    private void startLyricPoll() {
        try {
            stopLyricPoll();
            if (this.lyricPollHandler == null) {
                this.lyricPollHandler = new Handler(Looper.getMainLooper());
            }
            this.lyricPollRunnable = new Runnable() { // from class: com.chenan.flymebobo.NotificationService.5
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        if (!NotificationService.this.musicListening) {
                            try {
                                if (NotificationService.this.musicListening && NotificationService.this.lyricPollHandler != null && NotificationService.this.lyricPollRunnable != null) {
                                    NotificationService.this.lyricPollHandler.postDelayed(NotificationService.this.lyricPollRunnable, 500L);
                                }
                                return;
                            } catch (Throwable th) {
                                return;
                            }
                        }
                        NotificationService.this.refreshLyricFromActive();
                        try {
                            if (NotificationService.this.musicListening && NotificationService.this.lyricPollHandler != null && NotificationService.this.lyricPollRunnable != null) {
                                NotificationService.this.lyricPollHandler.postDelayed(NotificationService.this.lyricPollRunnable, 500L);
                            }
                        } catch (Throwable th2) {
                        }
                    } catch (Throwable th3) {
                        try {
                            if (NotificationService.this.musicListening && NotificationService.this.lyricPollHandler != null && NotificationService.this.lyricPollRunnable != null) {
                                NotificationService.this.lyricPollHandler.postDelayed(NotificationService.this.lyricPollRunnable, 500L);
                            }
                        } catch (Throwable th4) {
                        }
                        throw th3;
                    }
                }
            };
            this.lyricPollHandler.postDelayed(this.lyricPollRunnable, 500L);
        } catch (Throwable th) {
        }
    }

    private void stopLyricPoll() {
        try {
            if (this.lyricPollHandler != null && this.lyricPollRunnable != null) {
                this.lyricPollHandler.removeCallbacks(this.lyricPollRunnable);
            }
        } catch (Throwable th) {
        }
        this.lyricPollRunnable = null;
    }

    private void startUpdateCheck() {
        if (this.updateCheckStarted) {
            return;
        }
        this.updateCheckStarted = true;
        try {
            if (this.updateCheckHandler == null) {
                this.updateCheckHandler = new Handler(Looper.getMainLooper());
            }
            this.updateCheckRunnable = new Runnable() { // from class: com.chenan.flymebobo.NotificationService.6
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        GamePackages.fetchIfStale(NotificationService.this);
                        UpdateChecker.checkAsync(NotificationService.this, new UpdateChecker.Callback() { // from class: com.chenan.flymebobo.NotificationService.6.1
                            public void onResult(UpdateChecker.UpdateInfo updateInfo) {
                                if (updateInfo == null || !updateInfo.available) {
                                    return;
                                }
                                UpdateChecker.notifyUpdate(NotificationService.this, updateInfo);
                            }
                        });
                    } catch (Throwable th) {
                    }
                    try {
                        if (NotificationService.this.updateCheckHandler != null && NotificationService.this.updateCheckRunnable != null) {
                            NotificationService.this.updateCheckHandler.postDelayed(NotificationService.this.updateCheckRunnable, NotificationService.UPDATE_CHECK_INTERVAL_MS);
                        }
                    } catch (Throwable th2) {
                    }
                }
            };
            this.updateCheckHandler.postDelayed(this.updateCheckRunnable, 60000L);
        } catch (Throwable th) {
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:31:0x00a7, code lost:
    
        r0 = r0.getNotification().extras;
     */
    /* JADX WARN: Code restructure failed: missing block: B:32:0x00b3, code lost:
    
        if (r0 == null) goto L33;
     */
    /* JADX WARN: Code restructure failed: missing block: B:33:0x00b6, code lost:
    
        r0 = r0.getCharSequence("android.title");
     */
    /* JADX WARN: Code restructure failed: missing block: B:34:0x00c2, code lost:
    
        if (r0 == null) goto L31;
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x00cf, code lost:
    
        if (r0.toString().isEmpty() != false) goto L31;
     */
    /* JADX WARN: Code restructure failed: missing block: B:37:0x00d2, code lost:
    
        r8 = r0.toString();
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void refreshLyricFromActive() {
        if (this.musicKey == null) {
            return;
        }
        try {
            String notifTitle = null;
            String artist = null;
            String subtitle = null;
            String albumArtist = null;
            String title = null;
            long duration = -1;
            if (this.musicController != null) {
                try {
                    MediaMetadata metadata =
                            this.musicController.getMetadata();
                    if (metadata != null) {
                        title = metadata.getString(
                                "android.media.metadata.TITLE");
                        duration = metadata.getLong(
                                "android.media.metadata.DURATION");
                        artist = metadata.getString(
                                "android.media.metadata.ARTIST");
                        subtitle = metadata.getString(
                                "android.media.metadata.DISPLAY_SUBTITLE");
                        albumArtist = metadata.getString(
                                "android.media.metadata.ALBUM_ARTIST");
                    }
                } catch (Throwable th) {
                }
            }
            try {
                StatusBarNotification[] activeNotifications =
                        getActiveNotifications();
                if (activeNotifications != null) {
                    for (StatusBarNotification sbn
                            : activeNotifications) {
                        if (sbn != null
                                && sbn.getNotification() != null
                                && this.musicKey.equals(
                                        sbn.getKey())) {
                            try {
                                Bundle extras =
                                        sbn.getNotification()
                                                .extras;
                                if (extras != null) {
                                    CharSequence cs =
                                            extras.getCharSequence(
                                                    "android.title");
                                    if (cs != null
                                            && !cs.toString()
                                                    .isEmpty()) {
                                        notifTitle =
                                                cs.toString();
                                    }
                                }
                            } catch (Throwable th) {
                            }
                            break;
                        }
                    }
                }
            } catch (Throwable th) {
            }
            refreshLyricCandidatesFromActive();
            String lyric = resolveMusicLyric(title, subtitle,
                    artist, albumArtist, notifTitle);
            if (lyric == null || lyric.isEmpty()) {
                lyric = netLyricForCurrentPosition(notifTitle,
                        artist);
                if ((lyric == null || lyric.isEmpty())
                        && notifTitle != null
                        && !notifTitle.isEmpty()) {
                    maybeFetchNetworkLyrics(notifTitle, artist,
                            duration);
                }
            }
            if ((lyric == null || lyric.isEmpty())
                    && notifTitle != null
                    && !notifTitle.isEmpty()
                    && !this.netLyricFetching
                    && this.netLyricFetchingFor != null
                    && this.netLyricFetchingFor.equals(
                            notifTitle + "\u0000" + (artist == null
                                    ? "" : artist))) {
                lyric = "找不到当前歌词";
            }
            if (lyric == null || lyric.isEmpty()
                    || lyric.equals(this.lastLyricSent)) {
                return;
            }
            this.lastLyricSent = lyric;
            Intent intent = new Intent(ACTION_LYRIC);
            intent.putExtra("key", this.musicKey);
            intent.putExtra("lyrics", lyric);
            try {
                sendBroadcast(intent);
            } catch (Throwable th) {
            }
        } catch (Throwable th) {
        }
    }

    private static boolean isLyricCandidate(String str, String str2, String str3, String str4) {
        if (str == null) {
            return false;
        }
        try {
            String trim = str.trim();
            if (trim.isEmpty() || isCreditOrLyricTitle(trim) || isStatusMessage(trim) || isCopyrightNotice(trim)) {
                return false;
            }
            if (str2 != null && !str2.isEmpty() && (trim.equals(str2) || trim.startsWith(str2 + " - ") || trim.endsWith(" - " + str2) || trim.startsWith(str2 + "：") || trim.endsWith("：" + str2) || trim.startsWith(str2 + " – ") || trim.endsWith(" – " + str2))) {
                return false;
            }
            if (str3 != null && !str3.isEmpty() && (trim.equals(str3) || trim.equals(str2 + " - " + str3) || trim.equals(str3 + " - " + str2) || trim.equals(str2 + "：" + str3) || trim.equals(str3 + "：" + str2) || trim.equals(str2 + " – " + str3) || trim.equals(str3 + " – " + str2) || trim.startsWith(str3 + " - ") || trim.startsWith(str3 + " – ") || trim.startsWith(str3 + "："))) {
                return false;
            }
            if (str4 != null && !str4.isEmpty() && trim.equals(str4)) {
                return false;
            }
            if (str2 == null || str2.isEmpty()) {
                return true;
            }
            String replaceAll = trim.replaceAll("\\s+", "");
            String replaceAll2 = str2.replaceAll("\\s+", "");
            if (!replaceAll2.isEmpty() && (replaceAll.equals(replaceAll2) || replaceAll.startsWith(replaceAll2 + "-") || replaceAll.startsWith(replaceAll2 + "："))) {
                return false;
            }
            if (str3 == null || str3.isEmpty()) {
                return true;
            }
            String replaceAll3 = str3.replaceAll("\\s+", "");
            if (replaceAll3.isEmpty() || !replaceAll.contains(replaceAll2)) {
                return true;
            }
            return !replaceAll.contains(replaceAll3);
        } catch (Throwable th) {
            return false;
        }
    }

    private static boolean isCreditOrLyricTitle(String str) {
        if (str == null) {
            return false;
        }
        String trim = str.trim();
        for (String str2 : new String[]{"词：", "曲：", "词:", "曲:", "作词", "作曲", "编曲", "演唱", "原唱", "词曲", "作词：", "作曲：", "编曲：", "演唱：", "原唱：", "制作", "混音", "母带", "监制", "出品", "发行", "制作：", "混音：", "母带：", "监制：", "出品：", "发行："}) {
            if (trim.startsWith(str2)) {
                return true;
            }
        }
        String lowerCase = trim.toLowerCase(Locale.ROOT);
        for (String str3 : new String[]{"lyrics by", "lyric by", "lyrics:", "lyric:", "written by", "composed by", "music by", "words by", "arranged by", "produced by", "mixed by", "mastered by"}) {
            if (lowerCase.startsWith(str3)) {
                return true;
            }
        }
        return false;
    }

    private void updateLyricCandidates(Notification notification, boolean z) {
        String str = null;
        String str2 = null;
        String str3 = null;
        if (notification != null) {
            try {
                if (notification.extras != null) {
                    CharSequence charSequence = notification.extras.getCharSequence("android.text");
                    if (charSequence != null) {
                        str = charSequence.toString().trim();
                    }
                    CharSequence charSequence2 = notification.extras.getCharSequence("android.bigText");
                    if (charSequence2 != null) {
                        str2 = charSequence2.toString().trim();
                    }
                }
            } catch (Throwable th) {
                return;
            }
        }
        if (notification != null && notification.tickerText != null) {
            str3 = notification.tickerText.toString().trim();
        }
        if (z) {
            if (str != null && !str.isEmpty()) {
                this.currentLyricText = str;
            }
            if (str2 != null && !str2.isEmpty()) {
                this.currentLyricBigText = str2;
            }
            if (str3 != null && !str3.isEmpty()) {
                this.currentLyricTicker = str3;
            }
        } else {
            this.currentLyricText = str;
            this.currentLyricBigText = str2;
            this.currentLyricTicker = str3;
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:22:0x0045, code lost:
    
        updateLyricCandidates(r0.getNotification(), true);
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void refreshLyricCandidatesFromActive() {
        try {
            if (this.musicKey == null) {
                return;
            }
            StatusBarNotification[] activeNotifications =
                    getActiveNotifications();
            if (activeNotifications == null) {
                return;
            }
            for (StatusBarNotification sbn
                    : activeNotifications) {
                if (sbn != null && sbn.getNotification() != null
                        && this.musicKey.equals(sbn.getKey())) {
                    updateLyricCandidates(
                            sbn.getNotification(), true);
                    break;
                }
            }
        } catch (Throwable th) {
        }
    }

    private static boolean isCopyrightNotice(String str) {
        if (str == null) {
            return false;
        }
        String trim = str.trim();
        if (trim.isEmpty()) {
            return false;
        }
        return trim.contains("©") || trim.contains("℗") || trim.contains("版权") || trim.toLowerCase(Locale.ROOT).contains("copyright") || trim.startsWith("(P)") || trim.startsWith("(C)") || trim.startsWith("(p)") || trim.startsWith("(c)");
    }

    private static boolean isStatusMessage(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return str.contains("列表为空") || str.contains("点击选择") || str.contains("点击播放") || str.contains("暂无") || str.contains("未播放") || str.contains("没有正在播放") || str.contains("音乐正在播放");
    }

    private static String[] parseArtistSong(String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        int indexOf = str.indexOf(8211);
        if (indexOf < 0) {
            indexOf = str.indexOf(45);
        }
        if (indexOf < 0) {
            indexOf = str.indexOf(183);
        }
        if (indexOf <= 0 || indexOf >= str.length() - 1) {
            return null;
        }
        String trim = str.substring(0, indexOf).trim();
        String trim2 = str.substring(indexOf + 1).trim();
        if (trim.isEmpty() || trim2.isEmpty()) {
            return null;
        }
        return new String[]{trim, trim2};
    }

    private String resolveMusicLyric(String str, String str2, String str3, String str4, String str5) {
        String str6 = this.currentLyricTicker;
        String str7 = this.currentLyricText;
        String str8 = this.currentLyricBigText;
        if (str5 != null && !str5.isEmpty()) {
            this.currentNotifTitle = str5;
        }
        String str9 = null;
        String str10 = null;
        boolean z = false;
        if (str2 != null && !str2.isEmpty() && str2.contains("-")) {
            int indexOf = str2.indexOf(45);
            String trim = str2.substring(0, indexOf).trim();
            String trim2 = str2.substring(indexOf + 1).trim();
            if (!trim.isEmpty() && !trim2.isEmpty()) {
                str9 = trim;
                str10 = trim2;
            }
        }
        if (str9 == null && str3 != null && !str3.isEmpty() && str3.contains("-")) {
            int indexOf2 = str3.indexOf(45);
            String trim3 = str3.substring(0, indexOf2).trim();
            String trim4 = str3.substring(indexOf2 + 1).trim();
            if (!trim3.isEmpty() && !trim4.isEmpty()) {
                str9 = trim3;
                str10 = trim4;
                z = true;
            }
        }
        if (str9 != null && str != null && !str.isEmpty() && !str9.equals(str) && (str3 == null || !str9.equals(str3))) {
            if (str9.equals(this.currentNotifTitle) || (z ? this.lastArtistMeta != null && this.lastArtistMeta.equals(str3) && this.lastRawTitle != null && !this.lastRawTitle.equals(str) : this.lastSubtitle != null && this.lastSubtitle.equals(str2) && this.lastRawTitle != null && !this.lastRawTitle.equals(str)) || (!z && str.length() > str9.length()) || isCreditOrLyricTitle(str) || str.startsWith("女：") || str.startsWith("男：") || str.startsWith("合：") || str.startsWith("女:") || str.startsWith("男:") || (this.lastNotifTitle != null && this.lastNotifTitle.equals(this.currentNotifTitle) && this.lastRawTitle != null && !this.lastRawTitle.equals(str) && !str.equals(this.currentNotifTitle))) {
                this.swappedLayout = true;
                this.swappedSongTitle = str9;
                this.swappedArtist = str10;
            }
        }
        if (this.swappedLayout && str9 != null && this.swappedSongTitle != null && !str9.equals(this.swappedSongTitle) && str != null && str.equals(str9)) {
            this.swappedLayout = false;
            this.swappedSongTitle = null;
            this.swappedArtist = null;
        }
        this.lastSubtitle = str2;
        this.lastRawTitle = str;
        this.lastArtistMeta = str3;
        this.lastNotifTitle = this.currentNotifTitle;
        String str11 = str9 != null ? str9 : (str == null || str.isEmpty()) ? str5 : str;
        String str12 = str10 != null ? str10 : str3;
        String str13 = null;
        if (this.swappedLayout && this.swappedSongTitle != null && str != null && !str.isEmpty()) {
            str13 = str;
            str11 = this.swappedSongTitle;
            str12 = this.swappedArtist;
        }
        if (str13 != null && isLyricCandidate(str13, str11, str12, str4)) {
            return str13.trim();
        }
        if (isLyricCandidate(str6, str11, str12, str4)) {
            return str6.trim();
        }
        if (isLyricCandidate(str7, str11, str12, str4)) {
            return str7.trim();
        }
        if (isLyricCandidate(str8, str11, str12, str4)) {
            return str8.trim();
        }
        if (isLyricCandidate(str2, str11, str12, str4)) {
            return str2.trim();
        }
        if (isLyricCandidate(str, str11, str12, str4)) {
            return str.trim();
        }
        return null;
    }

    /* JADX WARN: Code restructure failed: missing block: B:28:0x005d, code lost:
    
        if (r12.equals(r10.netLyricArtist) != false) goto L24;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void maybeFetchNetworkLyrics(final String song,
            final String artist, final long durationMs) {
        try {
            if (!getSharedPreferences("flymebobo_features", 0)
                    .getBoolean("lyricNet", true)) {
                return;
            }
            if (this.netLyricFetching) {
                return;
            }
            String fetchKey = song + "\u0000" + (artist == null
                    ? "" : artist);
            if (fetchKey.equals(this.netLyricFetchingFor)) {
                return;
            }
            if (song.equals(this.netLyricSong)) {
                boolean sameArtist = (artist == null)
                        ? (this.netLyricArtist == null)
                        : artist.equals(this.netLyricArtist);
                if (sameArtist && this.netLyricTimes != null
                        && this.netLyricTimes.length > 0) {
                    return;
                }
            }
            this.netLyricFetching = true;
            this.netLyricFetchingFor = fetchKey;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        NotificationService.this
                                .fetchNetworkLyrics(song, artist,
                                        durationMs);
                    } catch (Throwable th) {
                    } finally {
                        NotificationService.this
                                .netLyricFetching = false;
                    }
                }
            }).start();
        } catch (Throwable th) {
        }
    }

    private static String toSimplified(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        for (int i = 0; i < str.length(); i++) {
            char charAt = str.charAt(i);
            if (charAt >= 12352 && charAt <= 12543) {
                return str;
            }
        }
        try {
            if (s2sTransliterator == null) {
                s2sTransliterator = Transliterator.getInstance("Traditional-Simplified");
            }
            return ((Transliterator) s2sTransliterator).transliterate(str);
        } catch (Throwable th) {
            return str;
        }
    }

    private File lyricCacheFile(String str, String str2) {
        try {
            File file = new File(getFilesDir(), "lyrics");
            if (!file.exists()) {
                file.mkdirs();
            }
            byte[] digest = MessageDigest.getInstance("MD5").digest((str + "��" + (str2 == null ? "" : str2)).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", Byte.valueOf(b)));
            }
            return new File(file, String.valueOf(sb) + ".lrc");
        } catch (Throwable th) {
            return null;
        }
    }

    private String readLyricCache(String str, String str2) {
        try {
            File lyricCacheFile = lyricCacheFile(str, str2);
            if (lyricCacheFile == null || !lyricCacheFile.exists() || lyricCacheFile.length() == 0) {
                return null;
            }
            lyricCacheFile.setLastModified(System.currentTimeMillis());
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(lyricCacheFile), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            while (true) {
                String readLine = bufferedReader.readLine();
                if (readLine == null) {
                    bufferedReader.close();
                    return sb.toString();
                }
                sb.append(readLine).append('\n');
            }
        } catch (Throwable th) {
            return null;
        }
    }

    private void writeLyricCache(String str, String str2, String str3) {
        try {
            File lyricCacheFile = lyricCacheFile(str, str2);
            if (lyricCacheFile == null) {
                return;
            }
            FileOutputStream fileOutputStream = new FileOutputStream(lyricCacheFile);
            fileOutputStream.write(str3.getBytes("UTF-8"));
            fileOutputStream.close();
            File[] listFiles = lyricCacheFile.getParentFile().listFiles();
            if (listFiles != null && listFiles.length > 200) {
                Arrays.sort(listFiles, new Comparator<File>() { // from class: com.chenan.flymebobo.NotificationService.8
                    @Override // java.util.Comparator
                    public int compare(File file, File file2) {
                        return Long.compare(file.lastModified(), file2.lastModified());
                    }
                });
                for (int i = 0; i < listFiles.length - 200; i++) {
                    try {
                        listFiles[i].delete();
                    } catch (Throwable th) {
                    }
                }
            }
        } catch (Throwable th2) {
        }
    }

    private void applyCachedLyrics(String str, String str2, String str3) {
        int indexOf;
        try {
            ArrayList arrayList = new ArrayList();
            ArrayList arrayList2 = new ArrayList();
            for (String str4 : str.split("\n")) {
                String trim = str4.trim();
                if (trim.startsWith("[") && (indexOf = trim.indexOf(93)) >= 0) {
                    String trim2 = trim.substring(1, indexOf).trim();
                    String trim3 = trim.substring(indexOf + 1).trim();
                    if (!trim3.isEmpty() && !isCreditOrLyricTitle(trim3) && !isStatusMessage(trim3) && !isCopyrightNotice(trim3)) {
                        try {
                            String[] split = trim2.split(":");
                            if (split.length == 2) {
                                arrayList.add(Long.valueOf((long) ((Long.parseLong(split[0]) * MUSIC_TICK_MS) + (Double.parseDouble(split[1]) * 1000.0d))));
                                arrayList2.add(toSimplified(trim3));
                            }
                        } catch (Throwable th) {
                        }
                    }
                }
            }
            if (arrayList.isEmpty()) {
                return;
            }
            long[] jArr = new long[arrayList.size()];
            String[] strArr = new String[arrayList2.size()];
            for (int i = 0; i < arrayList.size(); i++) {
                jArr[i] = ((Long) arrayList.get(i)).longValue();
                strArr[i] = (String) arrayList2.get(i);
            }
            this.netLyricSong = str2;
            this.netLyricArtist = str3;
            this.netLyricTimes = jArr;
            this.netLyricTexts = strArr;
            this.netLyricStartTime = System.currentTimeMillis();
            refreshLyricFromActive();
        } catch (Throwable th2) {
        }
    }

    private void fetchNetworkLyrics(String str, String str2, long j) {
        String readLyricCache = readLyricCache(str, str2);
        if (readLyricCache != null && !readLyricCache.isEmpty()) {
            applyCachedLyrics(readLyricCache, str, str2);
        } else {
            if (tryLrclib(str, str2, j) || tryNeteaseLyrics(str, str2, j) || tryQqLyrics(str, str2, j)) {
                return;
            }
            tryAiLyrics(str, str2, j);
        }
    }

    private static String httpGetText(String str, String str2) {
        HttpURLConnection httpURLConnection = null;
        try {
            httpURLConnection = (HttpURLConnection) new URL(str).openConnection();
            httpURLConnection.setConnectTimeout(5000);
            httpURLConnection.setReadTimeout(5000);
            httpURLConnection.setRequestMethod("GET");
            httpURLConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)");
            if (str2 != null) {
                httpURLConnection.setRequestProperty("Referer", str2);
            }
            if (httpURLConnection.getResponseCode() != 200) {
                if (httpURLConnection != null) {
                    try {
                        httpURLConnection.disconnect();
                    } catch (Throwable th) {
                    }
                }
                return null;
            }
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(httpURLConnection.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            while (true) {
                String readLine = bufferedReader.readLine();
                if (readLine == null) {
                    break;
                }
                sb.append(readLine);
            }
            bufferedReader.close();
            String sb2 = sb.toString();
            if (httpURLConnection != null) {
                try {
                    httpURLConnection.disconnect();
                } catch (Throwable th2) {
                }
            }
            return sb2;
        } catch (Throwable th3) {
            if (httpURLConnection != null) {
                try {
                    httpURLConnection.disconnect();
                } catch (Throwable th4) {
                }
            }
            return null;
        }
    }

    private boolean applySyncedLrc(String str, long j, String str2, String str3, long j2, boolean z) {
        int indexOf;
        if (str == null) {
            return false;
        }
        try {
            if (str.isEmpty()) {
                return false;
            }
            if (j2 > 0 && j > 0 && Math.abs(j - j2) > GROUP_WINDOW_MS) {
                return false;
            }
            ArrayList arrayList = new ArrayList();
            ArrayList arrayList2 = new ArrayList();
            for (String str4 : str.split("\n")) {
                String trim = str4.trim();
                if (trim.startsWith("[") && (indexOf = trim.indexOf(93)) >= 0) {
                    String trim2 = trim.substring(1, indexOf).trim();
                    String trim3 = trim.substring(indexOf + 1).trim();
                    if (!trim3.isEmpty() && !isCreditOrLyricTitle(trim3) && !isStatusMessage(trim3) && !isCopyrightNotice(trim3)) {
                        try {
                            String[] split = trim2.split(":");
                            if (split.length == 2) {
                                arrayList.add(Long.valueOf((long) ((Long.parseLong(split[0]) * MUSIC_TICK_MS) + (Double.parseDouble(split[1]) * 1000.0d))));
                                arrayList2.add(toSimplified(trim3));
                            }
                        } catch (Throwable th) {
                        }
                    }
                }
            }
            if (arrayList.isEmpty()) {
                return false;
            }
            if (z) {
                writeLyricCache(str2, str3, str);
            }
            long[] jArr = new long[arrayList.size()];
            String[] strArr = new String[arrayList2.size()];
            for (int i = 0; i < arrayList.size(); i++) {
                jArr[i] = ((Long) arrayList.get(i)).longValue();
                strArr[i] = (String) arrayList2.get(i);
            }
            this.netLyricSong = str2;
            this.netLyricArtist = str3;
            this.netLyricTimes = jArr;
            this.netLyricTexts = strArr;
            this.netLyricStartTime = System.currentTimeMillis();
            refreshLyricFromActive();
            return true;
        } catch (Throwable th2) {
            return false;
        }
    }

    private boolean tryLrclib(String str, String str2, long j) {
        String extractJsonString;
        try {
            String str3 = "https://lrclib.net/api/get?track_name=" + URLEncoder.encode(str, "UTF-8") + "&artist_name=" + URLEncoder.encode(str2 == null ? "" : str2, "UTF-8");
            if (j > 0) {
                str3 = str3 + "&duration=" + (j / MUSIC_TICK_MS);
            }
            String httpGetText = httpGetText(str3, null);
            if (httpGetText == null || (extractJsonString = extractJsonString(httpGetText, "syncedLyrics")) == null || extractJsonString.isEmpty()) {
                return false;
            }
            return applySyncedLrc(extractJsonString, extractJsonDurationMs(httpGetText), str, str2, j, true);
        } catch (Throwable th) {
            return false;
        }
    }

    private boolean tryNeteaseLyrics(String str, String str2, long j) {
        String httpGetText;
        JSONObject optJSONObject;
        try {
            String httpGetText2 = httpGetText("https://music.163.com/api/search/get/web?type=1&limit=5&s=" + URLEncoder.encode(str + " " + (str2 == null ? "" : str2), "UTF-8"), "https://music.163.com");
            if (httpGetText2 == null) {
                return false;
            }
            JSONObject optJSONObject2 = new JSONObject(httpGetText2).optJSONObject("result");
            JSONArray optJSONArray = optJSONObject2 == null ? null : optJSONObject2.optJSONArray("songs");
            if (optJSONArray == null) {
                return false;
            }
            long j2 = -1;
            long j3 = Long.MAX_VALUE;
            for (int i = 0; i < optJSONArray.length(); i++) {
                JSONObject jSONObject = optJSONArray.getJSONObject(i);
                String optString = jSONObject.optString("name", "");
                if (optString.isEmpty() || str.contains(optString) || optString.contains(str)) {
                    long optLong = jSONObject.optLong("duration", 0L);
                    long abs = (j <= 0 || optLong <= 0) ? 0L : Math.abs(optLong - j);
                    if ((j <= 0 || optLong <= 0 || abs <= GROUP_WINDOW_MS) && abs < j3) {
                        j3 = abs;
                        j2 = jSONObject.optLong("id", -1L);
                    }
                }
            }
            if (j2 < 0 || (httpGetText = httpGetText("https://music.163.com/api/song/lyric?id=" + j2 + "&lv=1&tv=1", "https://music.163.com")) == null || (optJSONObject = new JSONObject(httpGetText).optJSONObject("lrc")) == null) {
                return false;
            }
            String optString2 = optJSONObject.optString("lyric", "");
            if (optString2.isEmpty() || optString2.contains("纯音乐，请欣赏")) {
                return false;
            }
            return applySyncedLrc(optString2, 0L, str, str2, j, true);
        } catch (Throwable th) {
            return false;
        }
    }

    private boolean tryQqLyrics(String str, String str2, long j) {
        String httpGetText;
        try {
            String httpGetText2 = httpGetText("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=5&w=" + URLEncoder.encode(str + " " + (str2 == null ? "" : str2), "UTF-8"), "https://y.qq.com");
            if (httpGetText2 == null) {
                return false;
            }
            JSONObject optJSONObject = new JSONObject(httpGetText2).optJSONObject("data");
            JSONObject optJSONObject2 = optJSONObject == null ? null : optJSONObject.optJSONObject("song");
            JSONArray optJSONArray = optJSONObject2 == null ? null : optJSONObject2.optJSONArray("list");
            if (optJSONArray == null) {
                return false;
            }
            String str3 = null;
            long j2 = Long.MAX_VALUE;
            for (int i = 0; i < optJSONArray.length(); i++) {
                JSONObject jSONObject = optJSONArray.getJSONObject(i);
                String optString = jSONObject.optString("songname", "");
                if (optString.isEmpty() || str.contains(optString) || optString.contains(str)) {
                    long optLong = jSONObject.optLong("interval", 0L) * MUSIC_TICK_MS;
                    long abs = (j <= 0 || optLong <= 0) ? 0L : Math.abs(optLong - j);
                    if ((j <= 0 || optLong <= 0 || abs <= GROUP_WINDOW_MS) && abs < j2) {
                        j2 = abs;
                        str3 = jSONObject.optString("songmid", "");
                    }
                }
            }
            if (str3 == null || str3.isEmpty() || (httpGetText = httpGetText("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid=" + str3, "https://y.qq.com")) == null) {
                return false;
            }
            String optString2 = new JSONObject(httpGetText).optString("lyric", "");
            if (optString2.isEmpty()) {
                return false;
            }
            return applySyncedLrc(optString2.replace("&apos;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"), 0L, str, str2, j, true);
        } catch (Throwable th) {
            return false;
        }
    }

    private void tryAiLyrics(String str, String str2, long j) {
        try {
            SharedPreferences sharedPreferences = getSharedPreferences("flymebobo_ai", 0);
            String decrypt = AiKey.decrypt(sharedPreferences.getString("aiKey", ""));
            if (decrypt == null || decrypt.isEmpty()) {
                return;
            }
            String string = sharedPreferences.getString("aiBase", "https://api.deepseek.com");
            Object string2 = sharedPreferences.getString("aiModel", "deepseek-chat");
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("model", string2);
            JSONArray jSONArray = new JSONArray();
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("role", "system");
            jSONObject2.put("content", "你是歌词库，只输出歌词原文。");
            jSONArray.put(jSONObject2);
            JSONObject jSONObject3 = new JSONObject();
            jSONObject3.put("role", "user");
            jSONObject3.put("content", "给出歌曲《" + str + "》（歌手：" + (str2 == null ? "" : str2) + "）的完整歌词原文。只输出歌词，每行一句，不要解释、不要标题、不要标注主歌/副歌等段落名。不确定或不知道这首歌就只回复：不知道");
            jSONArray.put(jSONObject3);
            jSONObject.put("messages", jSONArray);
            jSONObject.put("temperature", 0.2d);
            HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(string + "/chat/completions").openConnection();
            httpURLConnection.setRequestMethod("POST");
            httpURLConnection.setConnectTimeout(15000);
            httpURLConnection.setReadTimeout(30000);
            httpURLConnection.setRequestProperty("Content-Type", "application/json");
            httpURLConnection.setRequestProperty("Authorization", "Bearer " + decrypt);
            httpURLConnection.setDoOutput(true);
            OutputStream outputStream = httpURLConnection.getOutputStream();
            outputStream.write(jSONObject.toString().getBytes(StandardCharsets.UTF_8));
            outputStream.close();
            if (httpURLConnection.getResponseCode() != 200) {
                return;
            }
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(httpURLConnection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            while (true) {
                String readLine = bufferedReader.readLine();
                if (readLine == null) {
                    break;
                } else {
                    sb.append(readLine);
                }
            }
            bufferedReader.close();
            String string3 = new JSONObject(sb.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            if (string3 == null || string3.contains("不知道")) {
                return;
            }
            ArrayList arrayList = new ArrayList();
            for (String str3 : string3.split("\n")) {
                String trim = str3.trim();
                if (!trim.isEmpty() && !trim.startsWith("[") && !trim.startsWith("#")) {
                    arrayList.add(trim);
                }
            }
            if (arrayList.size() < 4) {
                return;
            }
            long j2 = j > 0 ? j : 240000L;
            long j3 = (j2 * 3) / 100;
            long j4 = (j2 * 94) / 100;
            StringBuilder sb2 = new StringBuilder();
            for (int i = 0; i < arrayList.size(); i++) {
                long j5 = j3 + ((j4 * i) / arrayList.size());
                long size = j5 / 60000;
                double d = (j5 % 60000) / 1000.0d;
                sb2.append('[').append(size < 10 ? "0" : "").append(size).append(':').append(d < 10.0d ? "0" : "").append(String.format(Locale.US, "%.2f", Double.valueOf(d))).append(']').append((String) arrayList.get(i)).append('\n');
            }
            if (applySyncedLrc(sb2.toString(), 0L, str, str2, 0L, true)) {
                FileLog.i("Lyric ai fallback song=" + str);
            }
        } catch (Throwable th) {
        }
    }

    private static long extractJsonDurationMs(String str) {
        char charAt;
        try {
            int indexOf = str.indexOf("\"duration\":");
            if (indexOf < 0) {
                return -1L;
            }
            int length = indexOf + "\"duration\":".length();
            int i = length;
            while (i < str.length() && (((charAt = str.charAt(i)) >= '0' && charAt <= '9') || charAt == '.' || charAt == '-')) {
                i++;
            }
            if (i <= length) {
                return -1L;
            }
            double parseDouble = Double.parseDouble(str.substring(length, i));
            if (parseDouble <= 0.0d) {
                return -1L;
            }
            return (long) (parseDouble * 1000.0d);
        } catch (Throwable th) {
            return -1L;
        }
    }

    private static String extractJsonString(String str, String str2) {
        try {
            String str3 = "\"" + str2 + "\":\"";
            int indexOf = str.indexOf(str3);
            if (indexOf < 0) {
                return null;
            }
            int length = indexOf + str3.length();
            StringBuilder sb = new StringBuilder();
            int i = length;
            while (i < str.length()) {
                char charAt = str.charAt(i);
                if (charAt == '\\' && i + 1 < str.length()) {
                    char charAt2 = str.charAt(i + 1);
                    if (charAt2 == 'n') {
                        sb.append('\n');
                    } else if (charAt2 == 't') {
                        sb.append('\t');
                    } else if (charAt2 == 'r') {
                        sb.append('\r');
                    } else if (charAt2 != 'u' || i + 5 >= str.length()) {
                        sb.append(charAt2);
                    } else {
                        sb.append((char) Integer.parseInt(str.substring(i + 2, i + 6), 16));
                        i += 4;
                    }
                    i++;
                } else {
                    if (charAt == '\"') {
                        return sb.toString();
                    }
                    sb.append(charAt);
                }
                i++;
            }
            return null;
        } catch (Throwable th) {
            return null;
        }
    }

    private String netLyricForCurrentPosition(String str, String str2) {
        try {
            if (this.netLyricTimes == null || this.netLyricTexts == null || this.netLyricTimes.length == 0 || str == null || !str.equals(this.netLyricSong)) {
                return null;
            }
            long j = -1;
            if (this.musicController != null) {
                try {
                    PlaybackState playbackState = this.musicController.getPlaybackState();
                    if (playbackState != null) {
                        j = playbackState.getPosition();
                    }
                } catch (Throwable th) {
                }
            }
            if (j < 0) {
                if (this.netLyricStartTime <= 0) {
                    return this.netLyricTexts[0];
                }
                j = System.currentTimeMillis() - this.netLyricStartTime;
            }
            String str3 = null;
            for (int i = 0; i < this.netLyricTimes.length && this.netLyricTimes[i] <= j; i++) {
                str3 = this.netLyricTexts[i];
            }
            return str3 != null ? str3 : this.netLyricTexts[0];
        } catch (Throwable th2) {
            return null;
        }
    }

    private static int[] readProgressFromRemoteViews(Notification notification) {
        try {
            RemoteViews remoteViews = notification.contentView;
            if (remoteViews == null) {
                remoteViews = notification.bigContentView;
            }
            if (remoteViews == null) {
                return null;
            }
            Field declaredField = RemoteViews.class.getDeclaredField("mActions");
            declaredField.setAccessible(true);
            ArrayList arrayList = (ArrayList) declaredField.get(remoteViews);
            if (arrayList == null) {
                return null;
            }
            int i = -1;
            int i2 = -1;
            Iterator it = arrayList.iterator();
            while (it.hasNext()) {
                Object next = it.next();
                if (next != null) {
                    Class<?> cls = next.getClass();
                    if (cls.getSimpleName().equals("ReflectionAction")) {
                        Field declaredField2 = cls.getDeclaredField("methodName");
                        declaredField2.setAccessible(true);
                        String str = (String) declaredField2.get(next);
                        Field declaredField3 = cls.getDeclaredField("value");
                        declaredField3.setAccessible(true);
                        Object obj = declaredField3.get(next);
                        if ("setMax".equals(str) && (obj instanceof Number)) {
                            i = ((Number) obj).intValue();
                        } else if ("setProgress".equals(str) && (obj instanceof Number)) {
                            i2 = ((Number) obj).intValue();
                        }
                    }
                }
            }
            if (i <= 0 || i2 < 0) {
                return null;
            }
            return new int[]{i2, i};
        } catch (Throwable th) {
            return null;
        }
    }

    private static int parseProgressPercent(String str) {
        if (str == null || str.isEmpty()) {
            return -1;
        }
        try {
            Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(str);
            if (matcher.find()) {
                float parseFloat = Float.parseFloat(matcher.group(1));
                if (parseFloat >= 0.0f && parseFloat <= 100.0f) {
                    return Math.round(parseFloat);
                }
            }
            Matcher matcher2 = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:MB|KB|GB|B)?\\s*/\\s*(\\d+(?:\\.\\d+)?)\\s*(?:MB|KB|GB|B)?").matcher(str);
            if (!matcher2.find()) {
                return -1;
            }
            float parseFloat2 = Float.parseFloat(matcher2.group(1));
            float parseFloat3 = Float.parseFloat(matcher2.group(2));
            if (parseFloat3 <= 0.0f || parseFloat2 < 0.0f || parseFloat2 > parseFloat3) {
                return -1;
            }
            return Math.round((parseFloat2 * 100.0f) / parseFloat3);
        } catch (Throwable th) {
            return -1;
        }
    }

    private static String stripPercent(String str) {
        return str == null ? "" : str.replaceAll("\\s*\\d+(?:\\.\\d+)?\\s*%", "").trim();
    }

    private static long[] readDownloadBytes(Notification notification) {
        if (notification == null) {
            return null;
        }
        Bundle bundle = notification.extras;
        if (bundle != null) {
            long j = bundle.getInt("android.progress", 0);
            long j2 = bundle.getInt("android.progressMax", 0);
            if (j2 > 100 && j >= 0) {
                return new long[]{j, j2};
            }
        }
        int[] readProgressFromRemoteViews = readProgressFromRemoteViews(notification);
        if (readProgressFromRemoteViews == null || readProgressFromRemoteViews[1] <= 100 || readProgressFromRemoteViews[0] < 0) {
            return null;
        }
        return new long[]{readProgressFromRemoteViews[0], readProgressFromRemoteViews[1]};
    }

    private static String formatBytes(long j) {
        if (j < 0) {
            return "?";
        }
        if (j < 1024) {
            return j + "B";
        }
        Object obj = "KB";
        double d = j / 1024.0d;
        if (d >= 1024.0d) {
            d /= 1024.0d;
            obj = "MB";
        }
        if (d >= 1024.0d) {
            d /= 1024.0d;
            obj = "GB";
        }
        String format = String.format(Locale.US, "%.1f", Double.valueOf(d));
        if (format.endsWith(".0")) {
            format = format.substring(0, format.length() - 2);
        }
        return format + obj;
    }

    private static String formatSpeed(long j) {
        return j < 0 ? "" : formatBytes(j) + "/s";
    }

    private long trackDownloadSpeed(long j) {
        long uptimeMillis = SystemClock.uptimeMillis();
        long j2 = -1;
        if (j >= 0 && this.lastDownloadBytes >= 0 && j >= this.lastDownloadBytes && uptimeMillis > this.lastDownloadBytesTime) {
            long j3 = uptimeMillis - this.lastDownloadBytesTime;
            if (j3 > 0) {
                j2 = ((j - this.lastDownloadBytes) * MUSIC_TICK_MS) / j3;
            }
        }
        if (j >= 0) {
            this.lastDownloadBytes = j;
            this.lastDownloadBytesTime = uptimeMillis;
        }
        return j2;
    }

    private static long parseSizeToBytes(String str) {
        if (str == null) {
            return -1L;
        }
        String upperCase = str.trim().toUpperCase(Locale.US);
        if (upperCase.isEmpty()) {
            return -1L;
        }
        long j = 1;
        if (upperCase.endsWith("GB") || upperCase.endsWith("G")) {
            j = 1073741824;
            upperCase = upperCase.replaceAll("(GB|G)$", "").trim();
        } else if (upperCase.endsWith("MB") || upperCase.endsWith("M")) {
            j = 1048576;
            upperCase = upperCase.replaceAll("(MB|M)$", "").trim();
        } else if (upperCase.endsWith("KB") || upperCase.endsWith("K")) {
            j = 1024;
            upperCase = upperCase.replaceAll("(KB|K)$", "").trim();
        } else if (upperCase.endsWith("B")) {
            upperCase = upperCase.substring(0, upperCase.length() - 1).trim();
        }
        try {
            double parseDouble = Double.parseDouble(upperCase);
            if (parseDouble < 0.0d) {
                return -1L;
            }
            return (long) (parseDouble * j);
        } catch (Throwable th) {
            return -1L;
        }
    }

    private static long[] parseDownloadedTotal(String str, String str2) {
        String trim;
        int indexOf;
        if (str2 != null && (indexOf = (trim = str2.trim()).indexOf(47)) > 0 && indexOf < trim.length() - 1) {
            long parseSizeToBytes = parseSizeToBytes(trim.substring(0, indexOf).trim());
            long parseSizeToBytes2 = parseSizeToBytes(trim.substring(indexOf + 1).trim());
            if (parseSizeToBytes >= 0 && parseSizeToBytes2 > 0) {
                return new long[]{parseSizeToBytes, parseSizeToBytes2};
            }
        }
        if (str != null) {
            String trim2 = str.trim();
            int indexOf2 = trim2.indexOf("已下载");
            int indexOf3 = trim2.indexOf("，共", indexOf2 >= 0 ? indexOf2 : 0);
            if (indexOf3 < 0) {
                indexOf3 = trim2.indexOf(",共", indexOf2 >= 0 ? indexOf2 : 0);
            }
            if (indexOf2 >= 0 && indexOf3 > indexOf2) {
                long parseSizeToBytes3 = parseSizeToBytes(trim2.substring(indexOf2 + 3, indexOf3).trim());
                long parseSizeToBytes4 = parseSizeToBytes(trim2.substring(indexOf3 + 2).trim());
                if (parseSizeToBytes3 >= 0 && parseSizeToBytes4 > 0) {
                    return new long[]{parseSizeToBytes3, parseSizeToBytes4};
                }
            }
        }
        if (str == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?\\s*[KMGT]B?)\\s*/\\s*([0-9]+(?:\\.[0-9]+)?\\s*[KMGT]B?)", 2).matcher(str);
        if (!matcher.find()) {
            return null;
        }
        long parseSizeToBytes5 = parseSizeToBytes(matcher.group(1));
        long parseSizeToBytes6 = parseSizeToBytes(matcher.group(2));
        if (parseSizeToBytes5 < 0 || parseSizeToBytes6 <= 0) {
            return null;
        }
        return new long[]{parseSizeToBytes5, parseSizeToBytes6};
    }

    private static int parseSizePairPct(String str) {
        if (str == null) {
            return -1;
        }
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?\\s*[KMGT]B?)\\s*/\\s*([0-9]+(?:\\.[0-9]+)?\\s*[KMGT]B?)", 2).matcher(str);
        if (!matcher.find()) {
            return -1;
        }
        long parseSizeToBytes = parseSizeToBytes(matcher.group(1));
        long parseSizeToBytes2 = parseSizeToBytes(matcher.group(2));
        if (parseSizeToBytes < 0 || parseSizeToBytes2 <= 0) {
            return -1;
        }
        int i = (int) ((parseSizeToBytes * 100) / parseSizeToBytes2);
        if (i > 100) {
            return 100;
        }
        return i;
    }

    private static long parseSpeedFromText(String str) {
        if (str == null) {
            return -1L;
        }
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?B)/s", 2).matcher(str);
        if (!matcher.find()) {
            return -1L;
        }
        try {
            double parseDouble = Double.parseDouble(matcher.group(1));
            String upperCase = matcher.group(2).toUpperCase(Locale.US);
            long j = 1;
            if ("KB".equals(upperCase)) {
                j = 1024;
            } else if ("MB".equals(upperCase)) {
                j = 1048576;
            } else if ("GB".equals(upperCase)) {
                j = 1073741824;
            } else if ("TB".equals(upperCase)) {
                j = 1099511627776L;
            }
            if (parseDouble < 0.0d) {
                return -1L;
            }
            return (long) (parseDouble * j);
        } catch (Throwable th) {
            return -1L;
        }
    }

    private static long parseEtaSeconds(String str, String str2) {
        for (String str3 : new String[]{str, str2}) {
            if (str3 != null) {
                Matcher matcher = Pattern.compile("(?:剩余时间|还剩)\\s*([0-9]+)\\s*秒").matcher(str3);
                if (matcher.find()) {
                    try {
                        long parseLong = Long.parseLong(matcher.group(1));
                        if (parseLong >= 0) {
                            return parseLong;
                        }
                    } catch (Throwable th) {
                    }
                } else {
                    continue;
                }
            }
        }
        return -1L;
    }

    private static String[] normalizeDownload(String str, String str2, int i, long j, long j2, long j3, long j4) {
        String str3 = str == null ? "" : str;
        String str4 = str2 == null ? "" : str2;
        String str5 = str3.contains("安装") ? "正在安装" : "正在下载";
        String str6 = null;
        String[] strArr = {"正在下载", "下载", "正在安装", "安装"};
        int length = strArr.length;
        int i2 = 0;
        while (true) {
            if (i2 >= length) {
                break;
            }
            String str7 = strArr[i2];
            if (str3.startsWith(str7)) {
                String trim = str3.substring(str7.length()).trim();
                if (!trim.isEmpty()) {
                    str6 = trim;
                }
            } else {
                i2++;
            }
        }
        if (str6 == null) {
            str6 = (str4.isEmpty() || str4.contains("下载") || str4.contains("准备") || str4.contains("安装")) ? (str3.isEmpty() || str3.equals("正在下载") || str3.equals("下载") || str3.equals("正在安装") || str3.equals("安装")) ? str4 : str3 : str4;
        }
        if (str6 == null || str6.isEmpty()) {
            str6 = str5;
        }
        String trim2 = str6.replaceAll("\\s+[0-9]+%$", "").trim();
        if (trim2.isEmpty()) {
            trim2 = str5;
        }
        StringBuilder sb = new StringBuilder(str5);
        if (i >= 0) {
            sb.append(" ").append(i).append("%");
        }
        if (j >= 0 && j2 > 0) {
            sb.append(" · ").append(formatBytes(j)).append("/").append(formatBytes(j2));
        }
        if (j3 >= 0) {
            sb.append(" · ").append(formatSpeed(j3));
        }
        if (j4 >= 0) {
            sb.append(" · 还剩").append(j4).append("秒");
        }
        return new String[]{trim2, sb.toString()};
    }

    private static int readDownloadPct(Notification notification, String str, String str2) {
        int i;
        if (notification == null) {
            return -1;
        }
        int i2 = -1;
        Bundle bundle = notification.extras;
        if (bundle != null) {
            int i3 = bundle.getInt("android.progress", 0);
            int i4 = bundle.getInt("android.progressMax", 0);
            if (i4 > 0) {
                i2 = (i3 * 100) / i4;
            }
        }
        int[] readProgressFromRemoteViews = readProgressFromRemoteViews(notification);
        if (readProgressFromRemoteViews != null && readProgressFromRemoteViews[1] > 0 && (i = (readProgressFromRemoteViews[0] * 100) / readProgressFromRemoteViews[1]) > i2) {
            i2 = i;
        }
        int parseProgressPercent = parseProgressPercent(str2);
        if (parseProgressPercent < 0) {
            parseProgressPercent = parseProgressPercent(str);
        }
        if (parseProgressPercent > i2) {
            i2 = parseProgressPercent;
        }
        if (i2 < 0) {
            i2 = parseSizePairPct(str);
        }
        if (i2 < 0) {
            i2 = parseSizePairPct(str2);
        }
        if (i2 < 0) {
            return -1;
        }
        if (i2 > 100) {
            i2 = 100;
        }
        return i2;
    }

    private static boolean hasDownloadSignal(String str, String str2, String str3, String str4) {
        if (containsAny(str3, "上传", "备份", "同步") || containsAny(str4, "上传", "备份", "同步")) {
            return false;
        }
        if (containsAny(str3, "下载") || containsAny(str4, "下载")) {
            return true;
        }
        if (str2 == null || !str2.toLowerCase(Locale.US).contains("download")) {
            return str != null && isDownloaderPkg(str);
        }
        return true;
    }

    private static boolean containsAny(String str, String... strArr) {
        if (str == null) {
            return false;
        }
        for (String str2 : strArr) {
            if (str2 != null && str.contains(str2)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDownloaderPkg(String str) {
        return str.equals("com.meizu.mstore") || str.equals("com.xiaomi.market") || str.equals("com.huawei.appmarket") || str.equals("com.oppo.market") || str.equals("com.vivo.appstore") || str.equals("com.bbk.appstore") || str.equals("com.tencent.android.qqdownloader") || str.equals("com.android.vending") || str.equals("com.android.browser") || str.equals("com.UCMobile") || str.equals("com.tencent.mtt") || str.equals("com.baidu.searchbox") || str.equals("org.mozilla.firefox") || str.equals("com.microsoft.emmx") || str.equals("com.quark.browser") || str.equals("com.qihoo.browser") || str.equals("com.opera.browser") || str.equals("com.android.providers.downloads") || str.equals("com.dv.adm");
    }

    private void startDownloadTick() {
        try {
            if (this.downloadTickRunnable == null || this.downloadKey == null || !this.downloadKey.equals(this.downloadTickKey)) {
                stopDownloadTick();
                this.downloadTickKey = this.downloadKey;
                if (this.downloadTickHandler == null) {
                    this.downloadTickHandler = new Handler(Looper.getMainLooper());
                }
                this.downloadTickRunnable = new Runnable() { // from class: com.chenan.flymebobo.NotificationService.9
                    @Override // java.lang.Runnable
                    public void run() {
                        try {
                            if (NotificationService.this.downloadKey == null) {
                                try {
                                    if (NotificationService.this.downloadKey != null && NotificationService.this.downloadTickHandler != null && NotificationService.this.downloadTickRunnable != null) {
                                        NotificationService.this.downloadTickHandler.postDelayed(NotificationService.this.downloadTickRunnable, 500L);
                                    }
                                    return;
                                } catch (Throwable th) {
                                    return;
                                }
                            }
                            NotificationService.this.refreshDownloadProgress();
                            try {
                                if (NotificationService.this.downloadKey != null && NotificationService.this.downloadTickHandler != null && NotificationService.this.downloadTickRunnable != null) {
                                    NotificationService.this.downloadTickHandler.postDelayed(NotificationService.this.downloadTickRunnable, 500L);
                                }
                            } catch (Throwable th2) {
                            }
                        } catch (Throwable th3) {
                            try {
                                if (NotificationService.this.downloadKey != null && NotificationService.this.downloadTickHandler != null && NotificationService.this.downloadTickRunnable != null) {
                                    NotificationService.this.downloadTickHandler.postDelayed(NotificationService.this.downloadTickRunnable, 500L);
                                }
                            } catch (Throwable th4) {
                            }
                            throw th3;
                        }
                    }
                };
                this.downloadTickHandler.postDelayed(this.downloadTickRunnable, 500L);
            }
        } catch (Throwable th) {
        }
    }

    private void stopDownloadTick() {
        try {
            if (this.downloadTickHandler != null && this.downloadTickRunnable != null) {
                this.downloadTickHandler.removeCallbacks(this.downloadTickRunnable);
            }
        } catch (Throwable th) {
        }
        this.downloadTickRunnable = null;
        this.downloadTickKey = null;
    }

    private static boolean isDownloadComplete(String str, String str2) {
        return (str != null && (str.contains("下载完成") || str.contains("安装完成") || str.contains("已完成") || str.contains("下载完毕") || str.contains("已下载") || str.contains("下载成功") || str.toLowerCase(Locale.ROOT).contains("download complete"))) || (str2 != null && (str2.contains("下载完成") || str2.contains("安装完成") || str2.contains("已完成") || str2.contains("下载完毕") || str2.contains("已下载") || str2.contains("下载成功") || str2.toLowerCase(Locale.ROOT).contains("download complete")));
    }

    private void sendDownloadRemove(String str) {
        if (str == null) {
            return;
        }
        try {
            Intent intent = new Intent(ACTION_DOWNLOAD_REMOVE);
            intent.putExtra("key", str);
            sendBroadcast(intent);
        } catch (Throwable th) {
        }
    }

    private void refreshDownloadProgress() {
        if (this.downloadKey == null) {
            return;
        }
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications == null) {
                return;
            }
            for (StatusBarNotification statusBarNotification : activeNotifications) {
                if (statusBarNotification != null && statusBarNotification.getNotification() != null && this.downloadKey.equals(statusBarNotification.getKey())) {
                    Notification notification = statusBarNotification.getNotification();
                    Bundle bundle = notification.extras;
                    CharSequence charSequence = bundle != null ? bundle.getCharSequence("android.title") : null;
                    CharSequence charSequence2 = bundle != null ? bundle.getCharSequence("android.text") : null;
                    int readDownloadPct = readDownloadPct(notification, charSequence != null ? charSequence.toString() : null, charSequence2 != null ? charSequence2.toString() : null);
                    if (readDownloadPct < 0) {
                        if (!isDownloadComplete(charSequence != null ? charSequence.toString() : null, charSequence2 != null ? charSequence2.toString() : null)) {
                            int i = this.downloadGoneTicks + 1;
                            this.downloadGoneTicks = i;
                            if (i >= 2) {
                                sendDownloadRemove(this.downloadKey);
                                stopDownloadTick();
                                this.downloadKey = null;
                                return;
                            }
                            return;
                        }
                        readDownloadPct = 100;
                    }
                    this.downloadGoneTicks = 0;
                    String charSequence3 = charSequence != null ? charSequence.toString() : null;
                    String charSequence4 = charSequence2 != null ? charSequence2.toString() : null;
                    String text = bundle != null ? getText(bundle, "android.infoText") : null;
                    String text2 = bundle != null ? getText(bundle, "android.subText") : null;
                    long[] parseDownloadedTotal = parseDownloadedTotal(charSequence4, text);
                    long[] readDownloadBytes = parseDownloadedTotal != null ? parseDownloadedTotal : readDownloadBytes(notification);
                    long j = readDownloadBytes != null ? readDownloadBytes[0] : -1L;
                    long j2 = readDownloadBytes != null ? readDownloadBytes[1] : -1L;
                    long j3 = this.lastDownloadBytes;
                    long parseSpeedFromText = parseSpeedFromText(charSequence4);
                    if (parseSpeedFromText < 0 && j >= 0) {
                        parseSpeedFromText = trackDownloadSpeed(j);
                    }
                    long parseEtaSeconds = parseEtaSeconds(charSequence4, text2);
                    if (readDownloadPct >= 100) {
                        String[] normalizeDownload = normalizeDownload(charSequence != null ? charSequence.toString() : null, charSequence2 != null ? charSequence2.toString() : null, 100, -1L, -1L, -1L, -1L);
                        String str = (charSequence == null || !charSequence.toString().contains("安装")) ? "下载完成" : "安装完成";
                        Intent intent = new Intent(ACTION_DOWNLOAD);
                        intent.putExtra("key", this.downloadKey);
                        intent.putExtra("title", normalizeDownload[0]);
                        intent.putExtra("text", str);
                        intent.putExtra("pct", 100);
                        intent.putExtra("completed", true);
                        try {
                            sendBroadcast(intent);
                        } catch (Throwable th) {
                        }
                        stopDownloadTick();
                        this.downloadKey = null;
                        return;
                    }
                    if (readDownloadPct == this.lastDownloadPct && j == j3 && parseSpeedFromText == this.lastDownloadSpeed && parseEtaSeconds == this.lastDownloadEta) {
                        return;
                    }
                    this.lastDownloadPct = readDownloadPct;
                    this.lastDownloadSpeed = parseSpeedFromText;
                    this.lastDownloadEta = parseEtaSeconds;
                    CharSequence charSequence5 = bundle.getCharSequence("android.title");
                    CharSequence charSequence6 = bundle.getCharSequence("android.text");
                    String[] normalizeDownload2 = normalizeDownload(charSequence5 != null ? charSequence5.toString() : null, charSequence6 != null ? charSequence6.toString() : null, readDownloadPct, j, j2, parseSpeedFromText, parseEtaSeconds);
                    Intent intent2 = new Intent(ACTION_DOWNLOAD);
                    intent2.putExtra("key", this.downloadKey);
                    intent2.putExtra("title", normalizeDownload2[0]);
                    intent2.putExtra("text", normalizeDownload2[1]);
                    intent2.putExtra("pct", readDownloadPct);
                    try {
                        sendBroadcast(intent2);
                        return;
                    } catch (Throwable th2) {
                        return;
                    }
                }
            }
            int i2 = this.downloadGoneTicks + 1;
            this.downloadGoneTicks = i2;
            if (i2 >= 2) {
                sendDownloadRemove(this.downloadKey);
                stopDownloadTick();
                this.downloadKey = null;
            }
        } catch (Throwable th3) {
        }
    }

    private void teardownMusicListener() {
        stopLyricPoll();
        if (this.musicController != null && this.musicCallback != null) {
            try {
                this.musicController.unregisterCallback(this.musicCallback);
            } catch (Throwable th) {
            }
        }
        this.musicCallback = null;
        this.lastLyricSent = null;
    }

    private void teardownMusicSession() {
        teardownMusicListener();
        stopMusicTick();
        this.musicController = null;
        this.currentMusicTitle = null;
        this.currentMusicArtist = null;
        this.currentMusicNotifKey = null;
        this.currentMusicDurationMs = -1L;
        this.lastBroadcastPositionMs = -1L;
        this.lastBroadcastTitle = null;
        this.lastBroadcastArtist = null;
        this.lastBroadcastRawArtist = null;
        this.lastBroadcastArtTitle = null;
        this.lastBroadcastDuration = -1L;
    }

    private void handleMusicControl(String str, long j) {
        if (str == null || this.musicController == null) {
            return;
        }
        try {
            MediaController.TransportControls transportControls = this.musicController.getTransportControls();
            if (transportControls == null) {
                return;
            }
            if ("toggle".equals(str)) {
                PlaybackState playbackState = this.musicController.getPlaybackState();
                if (playbackState != null && playbackState.getState() == 3) {
                    transportControls.pause();
                } else {
                    transportControls.play();
                }
            } else if ("next".equals(str)) {
                transportControls.skipToNext();
            } else if ("prev".equals(str)) {
                transportControls.skipToPrevious();
            } else if ("favorite".equals(str)) {
                try {
                    boolean readHeartRating = readHeartRating();
                    transportControls.setRating(Rating.newHeartRating(!readHeartRating));
                    this.pendingFavorite = Boolean.valueOf(!readHeartRating);
                    this.pendingFavoriteAt = System.currentTimeMillis();
                } catch (Throwable th) {
                }
            } else if ("seek".equals(str) && j >= 0) {
                transportControls.seekTo(j);
            }
            broadcastMusicProgress();
        } catch (Throwable th2) {
        }
    }

    private void startMusicTick() {
        try {
            stopMusicTick();
            if (this.musicTickHandler == null) {
                this.musicTickHandler = new Handler(Looper.getMainLooper());
            }
            this.musicTickRunnable = new Runnable() { // from class: com.chenan.flymebobo.NotificationService.10
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        NotificationService.this.broadcastMusicProgress();
                    } catch (Throwable th) {
                    }
                    try {
                        if (NotificationService.this.musicController != null && NotificationService.this.musicTickHandler != null && NotificationService.this.musicTickRunnable != null) {
                            NotificationService.this.musicTickHandler.postDelayed(NotificationService.this.musicTickRunnable, NotificationService.MUSIC_TICK_MS);
                        }
                    } catch (Throwable th2) {
                    }
                }
            };
            this.musicTickHandler.postDelayed(this.musicTickRunnable, MUSIC_TICK_MS);
        } catch (Throwable th) {
        }
    }

    private void stopMusicTick() {
        try {
            if (this.musicTickHandler != null && this.musicTickRunnable != null) {
                this.musicTickHandler.removeCallbacks(this.musicTickRunnable);
            }
        } catch (Throwable th) {
        }
        this.musicTickRunnable = null;
    }

    private boolean readHeartRating() {
        MediaMetadata metadata;
        Rating rating;
        if (this.pendingFavorite != null && System.currentTimeMillis() - this.pendingFavoriteAt < 3000) {
            return this.pendingFavorite.booleanValue();
        }
        try {
            if (this.musicController != null && (metadata = this.musicController.getMetadata()) != null && (rating = metadata.getRating("android.media.metadata.RATING")) != null && rating.isRated()) {
                if (rating.hasHeart()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable th) {
            return false;
        }
    }

    private static boolean isFlymeMusicKey(String str) {
        if (str == null) {
            return false;
        }
        try {
            String[] split = str.split("\\|");
            if (split.length > 1) {
                if ("com.meizu.media.music".equals(split[1])) {
                    return true;
                }
            }
            return false;
        } catch (Throwable th) {
            return false;
        }
    }

    private static String[] resolveFlymeSessionSongArtist(String str, String str2, String str3) {
        String[] parseArtistSong;
        String str4 = null;
        String str5 = null;
        if (str != null && !str.isEmpty() && !isStatusMessage(str) && !isCreditOrLyricTitle(str)) {
            String[] parseArtistSong2 = parseArtistSong(str);
            if (parseArtistSong2 != null) {
                str4 = parseArtistSong2[0];
                str5 = parseArtistSong2[1];
            } else {
                str5 = str;
            }
        }
        if (str4 == null && str2 != null && !str2.isEmpty() && !isStatusMessage(str2) && (parseArtistSong = parseArtistSong(str2)) != null) {
            str4 = parseArtistSong[0];
            if (str5 == null) {
                String str6 = parseArtistSong[1];
                int indexOf = str6.indexOf(" - ");
                str5 = indexOf > 0 ? str6.substring(0, indexOf).trim() : str6.trim();
            }
        }
        if (str4 == null && str5 == null) {
            return null;
        }
        return new String[]{str4, str5};
    }

    private void broadcastMusicProgress() {
        String[] resolveFlymeSessionSongArtist;
        int indexOf;
        try {
            if (this.musicController == null) {
                return;
            }
            PlaybackState playbackState = this.musicController.getPlaybackState();
            MediaMetadata metadata = this.musicController.getMetadata();
            long position = playbackState != null ? playbackState.getPosition() : 0L;
            long j = -1;
            if (metadata != null) {
                j = metadata.getLong("android.media.metadata.DURATION");
            }
            boolean z = playbackState != null && playbackState.getState() == 3;
            boolean z2 = false;
            boolean z3 = false;
            if (this.pendingFavorite == null || System.currentTimeMillis() - this.pendingFavoriteAt >= 3000) {
                this.pendingFavorite = null;
                if (metadata != null) {
                    try {
                        Rating rating = metadata.getRating("android.media.metadata.RATING");
                        if (rating != null && rating.isRated()) {
                            z3 = true;
                            z2 = rating.hasHeart();
                        }
                    } catch (Throwable th) {
                    }
                }
            } else {
                z3 = true;
                z2 = this.pendingFavorite.booleanValue();
            }
            String str = null;
            String str2 = null;
            String str3 = null;
            String str4 = null;
            if (metadata != null) {
                str = metadata.getString("android.media.metadata.TITLE");
                str2 = metadata.getString("android.media.metadata.ARTIST");
                str3 = metadata.getString("android.media.metadata.DISPLAY_SUBTITLE");
                str4 = metadata.getString("android.media.metadata.ALBUM");
            }
            String str5 = null;
            String str6 = null;
            if (str3 != null && !str3.isEmpty() && (indexOf = str3.indexOf(8211)) > 0 && indexOf < str3.length() - 1) {
                String trim = str3.substring(0, indexOf).trim();
                String trim2 = str3.substring(indexOf + 1).trim();
                if (!trim.isEmpty() && !trim2.isEmpty()) {
                    str5 = trim;
                    str6 = trim2;
                }
            }
            String str7 = null;
            String str8 = null;
            if (isFlymeMusicKey(this.musicKey) && (resolveFlymeSessionSongArtist = resolveFlymeSessionSongArtist(str3, str2, str4)) != null) {
                str7 = resolveFlymeSessionSongArtist[0];
                str8 = resolveFlymeSessionSongArtist[1];
            }
            String str9 = null;
            if (str7 != null) {
                str9 = str7;
            } else if (str5 != null) {
                str9 = str5;
            } else if (isFlymeMusicKey(this.musicKey)) {
                str9 = this.lastBroadcastTitle;
            } else if (this.currentNotifTitle != null && !this.currentNotifTitle.isEmpty() && !isCreditOrLyricTitle(this.currentNotifTitle)) {
                str9 = this.currentNotifTitle;
            } else if (str != null && !str.isEmpty() && !isCreditOrLyricTitle(str)) {
                str9 = str;
            }
            if (str9 != null && this.lastBroadcastTitle != null && !str9.equals(this.lastBroadcastTitle)) {
                boolean z4 = false;
                if (str2 != null && this.lastBroadcastRawArtist != null && !str2.equals(this.lastBroadcastRawArtist)) {
                    z4 = true;
                } else if (j > 0 && this.lastBroadcastDuration > 0 && j != this.lastBroadcastDuration) {
                    z4 = true;
                } else if (position >= 0 && this.lastBroadcastPositionMs > 0 && position + 2000 < this.lastBroadcastPositionMs) {
                    z4 = true;
                }
                if (!z4 && !isFlymeMusicKey(this.musicKey) && this.currentNotifTitle != null && str9 != null && str9.equals(this.currentNotifTitle)) {
                    z4 = true;
                }
                if (!z4) {
                    str9 = this.lastBroadcastTitle;
                }
            }
            String str10 = str8 != null ? str8 : str6 != null ? str6 : (str2 == null || str2.isEmpty()) ? null : str2;
            Bitmap bitmap = null;
            boolean z5 = false;
            if (metadata != null) {
                try {
                    Bitmap bitmap2 = metadata.getBitmap("android.media.metadata.ALBUM_ART");
                    if (bitmap2 != null && (bitmap2.getWidth() > 128 || bitmap2.getHeight() > 128)) {
                        bitmap2 = Bitmap.createScaledBitmap(bitmap2, 128, 128, true);
                    }
                    bitmap = bitmap2;
                } catch (Throwable th2) {
                    bitmap = null;
                }
            }
            if (bitmap != null) {
                if (!(str9 != null ? str9 : "").equals(this.lastBroadcastArtTitle)) {
                    z5 = true;
                }
            }
            boolean z6 = (str9 == null || str9.equals(this.lastBroadcastTitle)) ? false : true;
            boolean z7 = (str10 == null || str10.equals(this.lastBroadcastArtist)) ? false : true;
            if (position != this.lastBroadcastPositionMs || z || ((z3 && z2 != this.lastBroadcastFavorite) || z6 || z7 || z5)) {
                if (z6 || position >= this.lastBroadcastPositionMs) {
                    this.lastBroadcastPositionMs = position;
                }
                if (z3) {
                    this.lastBroadcastFavorite = z2;
                }
                if (str9 != null) {
                    this.lastBroadcastTitle = str9;
                }
                if (str10 != null) {
                    this.lastBroadcastArtist = str10;
                }
                if (str2 != null) {
                    this.lastBroadcastRawArtist = str2;
                }
                if (z5) {
                    this.lastBroadcastArtTitle = str9 != null ? str9 : "";
                }
                if (j > 0) {
                    this.lastBroadcastDuration = j;
                }
                Intent intent = new Intent(ACTION_MUSIC_PROGRESS);
                intent.putExtra("position", position);
                intent.putExtra("duration", j);
                intent.putExtra("playing", z);
                if (str9 != null) {
                    intent.putExtra("title", str9);
                }
                if (str10 != null) {
                    intent.putExtra("artist", str10);
                }
                if (z3) {
                    intent.putExtra("favorite", z2);
                }
                if (z5 && bitmap != null) {
                    intent.putExtra("album_art", bitmap);
                }
                if (this.musicKey != null) {
                    intent.putExtra("key", this.musicKey);
                }
                sendBroadcast(intent);
            }
        } catch (Throwable th3) {
        }
    }

    private String appVersion() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName + " (" + packageInfo.versionCode + ")";
        } catch (Throwable th) {
            return "unknown";
        }
    }

    @Override // android.service.notification.NotificationListenerService
    public void onListenerConnected() {
        FileLog.i("Listener connected, FileLog ready");
        FileLog.i("Version: " + appVersion());
        startUpdateCheck();
        try {
            Method method = Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, Integer.TYPE);
            IntentFilter intentFilter = new IntentFilter(ACTION_CALL_CONTROL);
            intentFilter.addAction(ACTION_DISMISS);
            intentFilter.addAction(ACTION_MUSIC_CONTROL);
            intentFilter.addAction(ACTION_SET_APP_RULE);
            intentFilter.addAction(ACTION_SET_LIGHT_DOT_POS);
            method.invoke(this, this.callControlReceiver, intentFilter, 2);
        } catch (Throwable th) {
            try {
                IntentFilter intentFilter2 = new IntentFilter(ACTION_CALL_CONTROL);
                intentFilter2.addAction(ACTION_DISMISS);
                intentFilter2.addAction(ACTION_MUSIC_CONTROL);
                intentFilter2.addAction(ACTION_SET_APP_RULE);
                intentFilter2.addAction(ACTION_SET_LIGHT_DOT_POS);
                registerReceiver(this.callControlReceiver, intentFilter2);
            } catch (Throwable th2) {
            }
        }
        FeatureFlags.load(this);
        try {
            Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, Integer.TYPE).invoke(this, new BroadcastReceiver() { // from class: com.chenan.flymebobo.NotificationService.11
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    FeatureFlags.set(intent.getStringExtra("key"), intent.getBooleanExtra("enabled", true));
                }
            }, new IntentFilter(ACTION_FEATURE_TOGGLE), 2);
        } catch (Throwable th3) {
            try {
                registerReceiver(new BroadcastReceiver() { // from class: com.chenan.flymebobo.NotificationService.12
                    @Override // android.content.BroadcastReceiver
                    public void onReceive(Context context, Intent intent) {
                        if (intent == null) {
                            return;
                        }
                        FeatureFlags.set(intent.getStringExtra("key"), intent.getBooleanExtra("enabled", true));
                    }
                }, new IntentFilter(ACTION_FEATURE_TOGGLE));
            } catch (Throwable th4) {
            }
        }
        try {
            registerReceiver(new BroadcastReceiver() { // from class: com.chenan.flymebobo.NotificationService.13
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    TempSilence.onFilterBroadcast(NotificationService.this);
                }
            }, new IntentFilter("android.app.action.INTERRUPTION_FILTER_CHANGED"));
        } catch (Throwable th5) {
        }
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications != null) {
                for (StatusBarNotification statusBarNotification : activeNotifications) {
                    if (statusBarNotification != null && statusBarNotification.getKey() != null && statusBarNotification.getNotification() != null) {
                        Bundle bundle = statusBarNotification.getNotification().extras;
                        String string = bundle != null ? bundle.getString("android.title") : null;
                        CharSequence charSequence = bundle != null ? bundle.getCharSequence("android.text") : null;
                        recordNotif(statusBarNotification.getKey(), string, charSequence != null ? charSequence.toString() : null, statusBarNotification.getPackageName(), statusBarNotification.getPostTime(), bundle != null ? bundle.getInt("android.progress", 0) : 0, bundle != null ? bundle.getInt("android.progressMax", 0) : 0);
                    }
                }
            }
        } catch (Throwable th6) {
        }
        try {
            IntentFilter intentFilter3 = new IntentFilter(ACTION_LYRIC_LISTEN);
            BroadcastReceiver broadcastReceiver = new BroadcastReceiver() { // from class: com.chenan.flymebobo.NotificationService.14
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    boolean booleanExtra = intent.getBooleanExtra("listen", false);
                    String stringExtra = intent.getStringExtra("key");
                    if (!booleanExtra) {
                        NotificationService.this.musicListening = false;
                        NotificationService.this.teardownMusicListener();
                    } else {
                        NotificationService.this.musicListening = true;
                        if (stringExtra != null) {
                            NotificationService.this.musicKey = stringExtra;
                        }
                        NotificationService.this.setupMusicListener();
                    }
                }
            };
            try {
                Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, Integer.TYPE).invoke(this, broadcastReceiver, intentFilter3, 2);
            } catch (Throwable th7) {
                try {
                    registerReceiver(broadcastReceiver, intentFilter3);
                } catch (Throwable th8) {
                }
            }
        } catch (Throwable th9) {
        }
        try {
            IntentFilter intentFilter4 = new IntentFilter(ACTION_AI_ANALYZE);
            BroadcastReceiver broadcastReceiver2 = new BroadcastReceiver() { // from class: com.chenan.flymebobo.NotificationService.15
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    if (intent != null) {
                        if (intent.hasExtra("infoBlob")) {
                            NotificationService.this.handleAiInfoCheck(intent);
                        } else {
                            NotificationService.this.handleAiAnalyze(intent);
                        }
                    }
                }
            };
            try {
                Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, Integer.TYPE).invoke(this, broadcastReceiver2, intentFilter4, 2);
            } catch (Throwable th10) {
                try {
                    registerReceiver(broadcastReceiver2, intentFilter4);
                } catch (Throwable th11) {
                }
            }
        } catch (Throwable th12) {
        }
        try {
            IntentFilter intentFilter5 = new IntentFilter();
            intentFilter5.addAction(ACTION_INFO_REMIND);
            intentFilter5.addAction(ACTION_INFO_REMIND_CANCEL);
            BroadcastReceiver broadcastReceiver3 = new BroadcastReceiver() { // from class: com.chenan.flymebobo.NotificationService.16
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    String stringExtra;
                    if (intent == null || (stringExtra = intent.getStringExtra("id")) == null) {
                        return;
                    }
                    if (NotificationService.ACTION_INFO_REMIND_CANCEL.equals(intent.getAction())) {
                        InfoRemindReceiver.cancel(NotificationService.this, stringExtra);
                    } else {
                        InfoRemindReceiver.schedule(NotificationService.this, stringExtra, intent.getStringExtra("title"), intent.getStringExtra("sub"), intent.getStringExtra("kind"), intent.getLongExtra("event", 0L));
                    }
                }
            };
            try {
                Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, Integer.TYPE).invoke(this, broadcastReceiver3, intentFilter5, 2);
            } catch (Throwable th13) {
                try {
                    registerReceiver(broadcastReceiver3, intentFilter5);
                } catch (Throwable th14) {
                }
            }
        } catch (Throwable th15) {
        }
    }

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

    private void handleAiInfoCheck(Intent intent) {
        final String stringExtra = intent.getStringExtra("infoBlob");
        if (stringExtra == null || stringExtra.isEmpty()) {
            return;
        }
        SharedPreferences sharedPreferences = getSharedPreferences("flymebobo_ai", 0);
        final String decrypt = AiKey.decrypt(sharedPreferences.getString("aiKey", ""));
        if (decrypt == null || decrypt.isEmpty()) {
            FileLog.i("Light info ai skip: no key");
            return;
        }
        final String string = sharedPreferences.getString("aiBase", "https://api.deepseek.com");
        final String string2 = sharedPreferences.getString("aiModel", "deepseek-chat");
        new Thread(new Runnable() { // from class: com.chenan.flymebobo.NotificationService.18
            @Override // java.lang.Runnable
            public void run() {
                try {
                    String[] split = stringExtra.split("\n");
                    ArrayList arrayList = new ArrayList();
                    ArrayList arrayList2 = new ArrayList();
                    StringBuilder sb = new StringBuilder();
                    sb.append("下面是手机通知收纳盒里的").append(split.length).append("条通知，逐条处理后只输出一个 JSON 数组，不要解释。\n");
                    for (String str : split) {
                        String[] split2 = str.split("\u0001", -1);
                        if (split2.length >= 4) {
                            arrayList.add(split2[0]);
                            arrayList2.add(split2[1]);
                            sb.append("[").append(arrayList.size() - 1).append("]");
                            if (!split2[1].isEmpty()) {
                                sb.append("(本地已识别为 ").append(split2[1]).append("，请核对)");
                            }
                            sb.append(" 标题：").append(split2[2]).append(" 正文：").append(split2[3]).append("\n");
                        }
                    }
                    if (arrayList.isEmpty()) {
                        return;
                    }
                    sb.append("每条输出 {\"i\":序号,\"kind\":\"train/flight/courier/bill/none\",\"title\":\"...\",\"sub\":\"...\",\"event\":\"yyyy-MM-dd HH:mm 或空字符串\",\"copy\":\"快递单号或空字符串\"}。kind 定义：train=火车票/高铁票，flight=机票/航班，courier=快递物流，bill=信用卡账单，都不是填 none。title 格式：火车票「车次 次 · 出发站 → 到达站」；机票「航班号 · 出发 → 到达」；快递「公司 · 单号 xxx」；账单「信用卡账单 ¥金额」。sub 放日期时间、座位、检票口等要点（40 字内）。event 是发车/起飞/账单到期时间，没有填空字符串。带核对标记的：本地识别正确就保持 kind 并补全字段；明显不对就改成正确 kind 或 none。");
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("model", string2);
                    JSONArray jSONArray = new JSONArray();
                    JSONObject jSONObject2 = new JSONObject();
                    jSONObject2.put("role", "system");
                    jSONObject2.put("content", "你是通知信息提取器，只输出 JSON 数组。");
                    jSONArray.put(jSONObject2);
                    JSONObject jSONObject3 = new JSONObject();
                    jSONObject3.put("role", "user");
                    jSONObject3.put("content", sb.toString());
                    jSONArray.put(jSONObject3);
                    jSONObject.put("messages", jSONArray);
                    jSONObject.put("temperature", 0.1d);
                    HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(string + "/chat/completions").openConnection();
                    httpURLConnection.setRequestMethod("POST");
                    httpURLConnection.setConnectTimeout(15000);
                    httpURLConnection.setReadTimeout(30000);
                    httpURLConnection.setRequestProperty("Content-Type", "application/json");
                    httpURLConnection.setRequestProperty("Authorization", "Bearer " + decrypt);
                    httpURLConnection.setDoOutput(true);
                    OutputStream outputStream = httpURLConnection.getOutputStream();
                    outputStream.write(jSONObject.toString().getBytes(StandardCharsets.UTF_8));
                    outputStream.close();
                    int responseCode = httpURLConnection.getResponseCode();
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader((responseCode < 200 || responseCode >= 300) ? httpURLConnection.getErrorStream() : httpURLConnection.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb2 = new StringBuilder();
                    while (true) {
                        String readLine = bufferedReader.readLine();
                        if (readLine == null) {
                            break;
                        } else {
                            sb2.append(readLine);
                        }
                    }
                    bufferedReader.close();
                    if (responseCode < 200 || responseCode >= 300) {
                        FileLog.i("Light info ai http " + responseCode);
                        return;
                    }
                    String string3 = new JSONObject(sb2.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                    int indexOf = string3.indexOf(91);
                    int lastIndexOf = string3.lastIndexOf(93);
                    if (indexOf < 0 || lastIndexOf <= indexOf) {
                        FileLog.i("Light info ai bad json");
                        return;
                    }
                    JSONArray jSONArray2 = new JSONArray(string3.substring(indexOf, lastIndexOf + 1));
                    SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
                    simpleDateFormat.setLenient(false);
                    StringBuilder sb3 = new StringBuilder();
                    for (int i = 0; i < jSONArray2.length(); i++) {
                        JSONObject jSONObject4 = jSONArray2.getJSONObject(i);
                        int optInt = jSONObject4.optInt("i", -1);
                        if (optInt >= 0 && optInt < arrayList.size()) {
                            String optString = jSONObject4.optString("kind", "none");
                            String optString2 = jSONObject4.optString("event", "");
                            long r33 = 0L;
                            if (!optString2.isEmpty()) {
                                try {
                                    Date parse = simpleDateFormat.parse(optString2);
                                    r33 = parse != null ? parse.getTime() : 0L;
                                } catch (Throwable th) {
                                }
                            }
                            sb3.append((String) arrayList.get(optInt)).append("\u0001").append(optString).append("\u0001").append(jSONObject4.optString("title", "")).append("\u0001").append(jSONObject4.optString("sub", "")).append("\u0001").append(r33).append("\u0001").append(jSONObject4.optString("copy", "")).append("\n");
                        }
                    }
                    Intent intent2 = new Intent(NotificationService.ACTION_AI_INFO_RESULT);
                    intent2.putExtra("infoResult", sb3.toString());
                    NotificationService.this.sendBroadcast(intent2);
                    FileLog.i("Light info ai result n=" + jSONArray2.length());
                } catch (Throwable th2) {
                    FileLog.i("Light info ai fail: " + th2.getMessage());
                }
            }
        }).start();
    }

    private static boolean isPhoneNumber(String str) {
        if (str == null) {
            return false;
        }
        String replace = str.trim().replace(" ", "").replace("-", "");
        if (replace.startsWith("+")) {
            replace = replace.substring(1);
        }
        if (replace.length() < 7) {
            return false;
        }
        for (int i = 0; i < replace.length(); i++) {
            if (!Character.isDigit(replace.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String digitsOnly(String str) {
        if (str == null) {
            return null;
        }
        String replace = str.trim().replace(" ", "").replace("-", "");
        if (replace.startsWith("+")) {
            replace = replace.substring(1);
        }
        return replace;
    }

    private static String formatNumber(String str) {
        if (str == null) {
            return null;
        }
        String trim = str.trim();
        if (trim.isEmpty()) {
            return trim;
        }
        String digitsOnly = digitsOnly(trim);
        return (digitsOnly == null || digitsOnly.length() != 11 || trim.contains(" ")) ? trim : digitsOnly.substring(0, 3) + " " + digitsOnly.substring(3, 7) + " " + digitsOnly.substring(7);
    }

    private boolean isCallNotification(StatusBarNotification statusBarNotification, Notification notification) {
        if (notification == null) {
            return false;
        }
        if ((statusBarNotification != null && "com.android.incallui".equals(statusBarNotification.getPackageName())) || "call".equals(notification.category)) {
            return true;
        }
        if (notification.fullScreenIntent == null) {
            return false;
        }
        String packageName = statusBarNotification != null ? statusBarNotification.getPackageName() : null;
        if (packageName != null) {
            String lowerCase = packageName.toLowerCase();
            if (lowerCase.contains("dialer") || lowerCase.contains("phone") || lowerCase.contains("telecom") || lowerCase.contains("incallui") || lowerCase.contains("call")) {
                return true;
            }
        }
        try {
            Bundle bundle = notification.extras;
            String string = bundle != null ? bundle.getString("android.title") : null;
            if (string == null) {
                return false;
            }
            if (string.replaceAll("[^0-9]", "").length() >= 7 || string.contains("来电") || string.contains("呼入")) {
                return true;
            }
            return string.contains("正在呼叫");
        } catch (Throwable th) {
            return false;
        }
    }

    private boolean fireCallAction(String str, String str2) {
        if (fireCallActionPass(str, str2, true)) {
            reportCallResult("切换: 已点扬声器");
            return true;
        }
        if (fireCallActionPass(str, str2, false)) {
            reportCallResult("切换: 已点扬声器(按包)");
            return true;
        }
        reportCallResult(describeCallActions());
        return false;
    }

    private void reportCallResult(String str) {
        try {
            Intent intent = new Intent(ACTION_CALL_RESULT);
            intent.putExtra("call_result", str);
            sendBroadcast(intent);
        } catch (Throwable th) {
        }
    }

    private String describeCallActions() {
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications == null) {
                return "切换查找: 无通知列表";
            }
            for (StatusBarNotification statusBarNotification : activeNotifications) {
                if (statusBarNotification != null && "com.android.incallui".equals(statusBarNotification.getPackageName())) {
                    Notification notification = statusBarNotification.getNotification();
                    if (notification == null || notification.actions == null) {
                        return "切换查找: 通话通知无按钮";
                    }
                    StringBuilder sb = new StringBuilder("切换查找:");
                    for (Notification.Action action : notification.actions) {
                        if (action != null && action.title != null) {
                            sb.append(" [").append(action.title.toString()).append("]");
                        }
                    }
                    sb.append(" 无扬声器");
                    return sb.toString();
                }
            }
            return "切换查找: 无通话通知";
        } catch (Throwable th) {
            return "切换查找失败: " + String.valueOf(th);
        }
    }

    private boolean fireCallActionPass(String str, String str2, boolean z) {
        int classifyCallAction;
        Notification notification;
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications == null || (classifyCallAction = classifyCallAction(str)) < 0) {
                return false;
            }
            for (StatusBarNotification statusBarNotification : activeNotifications) {
                if (statusBarNotification != null && "com.android.incallui".equals(statusBarNotification.getPackageName()) && ((!z || str2 == null || str2.isEmpty() || str2.equals(statusBarNotification.getKey())) && (notification = statusBarNotification.getNotification()) != null && notification.actions != null)) {
                    for (Notification.Action action : notification.actions) {
                        if (action != null && action.actionIntent != null && action.title != null) {
                            int classifyCallAction2 = classifyCallAction(action.title.toString());
                            boolean z2 = classifyCallAction == 3 || classifyCallAction == 4;
                            boolean z3 = classifyCallAction2 == 3 || classifyCallAction2 == 4;
                            if (z2) {
                                if (!z3) {
                                }
                            } else if (classifyCallAction2 != classifyCallAction) {
                            }
                            try {
                                if ("com.android.incallui".equals(action.actionIntent.getCreatorPackage())) {
                                    action.actionIntent.send();
                                    return true;
                                }
                            } catch (Throwable th) {
                            }
                        }
                    }
                }
            }
            return false;
        } catch (Throwable th2) {
            return false;
        }
    }

    private int classifyCallAction(String str) {
        if (str == null) {
            return -1;
        }
        String lowerCase = str.trim().toLowerCase(Locale.ROOT);
        if (lowerCase.equals("接听") || lowerCase.equals("接聽") || lowerCase.equals("answer") || lowerCase.equals("accept")) {
            return 0;
        }
        if (lowerCase.equals("拒接") || lowerCase.equals("拒绝") || lowerCase.equals("拒絕") || lowerCase.equals("decline") || lowerCase.equals("reject")) {
            return 1;
        }
        if (lowerCase.equals("挂断") || lowerCase.equals("掛斷") || lowerCase.equals("hang up")) {
            return 2;
        }
        return (lowerCase.contains("扬声器") || lowerCase.contains("揚聲器") || lowerCase.contains("speaker") || lowerCase.contains("免提")) ? (lowerCase.contains("关") || lowerCase.contains("關") || lowerCase.contains("off")) ? 4 : 3 : (lowerCase.equals("静音") || lowerCase.equals("靜音") || lowerCase.equals("silence")) ? 5 : -1;
    }

    private void extractCallSpeakerIntents(Notification notification) {
        this.callSpeakerOnIntent = null;
        this.callSpeakerOffIntent = null;
        if (notification == null || notification.actions == null) {
            return;
        }
        for (Notification.Action action : notification.actions) {
            if (action != null && action.actionIntent != null && action.title != null) {
                int classifyCallAction = classifyCallAction(action.title.toString());
                if (classifyCallAction == 3) {
                    this.callSpeakerOnIntent = action.actionIntent;
                } else if (classifyCallAction == 4) {
                    this.callSpeakerOffIntent = action.actionIntent;
                }
            }
        }
    }

    private void sendCallBroadcast(String str, String str2, String str3) {
        try {
            Intent intent = new Intent(ACTION_CALL);
            intent.putExtra("call_state", str);
            intent.putExtra("call_name", str2);
            intent.putExtra("call_number", str3);
            if (this.callKey != null) {
                intent.putExtra("call_key", this.callKey);
            }
            if (this.callSpeakerOnIntent != null) {
                intent.putExtra("speaker_on_intent", this.callSpeakerOnIntent);
            }
            if (this.callSpeakerOffIntent != null) {
                intent.putExtra("speaker_off_intent", this.callSpeakerOffIntent);
            }
            sendBroadcast(intent);
        } catch (Throwable th) {
        }
    }

    private static boolean isMeituanLiveNotif(StatusBarNotification statusBarNotification, Notification notification) {
        if (statusBarNotification == null || notification == null) {
            return false;
        }
        try {
            if (notification.extras == null) {
                return false;
            }
            if (!(notification.extras.getBoolean("is_live", false) || notification.extras.getBundle("notification.live.capsule") != null)) {
                return false;
            }
            if (!PKG_MEITUAN.equals(notification.extras.getString("notification.live.app.pkg.delegated"))) {
                if (!PKG_MEITUAN.equals(statusBarNotification.getPackageName())) {
                    return false;
                }
            }
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    @Override // android.service.notification.NotificationListenerService
    public void onNotificationPosted(StatusBarNotification statusBarNotification) {
        CharSequence charSequence;
        TelephonyManager telephonyManager = null;
        boolean z;
        if (statusBarNotification == null || statusBarNotification.getNotification() == null) {
            return;
        }
        try {
            Notification notification = statusBarNotification.getNotification();
            FileLog.i("Posted pkg=" + statusBarNotification.getPackageName() + " key=" + statusBarNotification.getKey() + " flags=" + notification.flags);
            if (isCallNotification(statusBarNotification, notification)) {
                Bundle bundle = notification.extras;
                String string = bundle != null ? bundle.getString("android.title") : null;
                String string2 = bundle != null ? bundle.getString("android.text") : null;
                String string3 = bundle != null ? bundle.getString("notification.live.titleOverlay") : null;
                String string4 = bundle != null ? bundle.getString("notification.live.contentOverlay") : null;
                String str = null;
                if (isPhoneNumber(string3)) {
                    str = formatNumber(string3);
                } else if (isPhoneNumber(string2)) {
                    str = formatNumber(string2);
                } else if (isPhoneNumber(string4)) {
                    str = formatNumber(string4);
                } else if (isPhoneNumber(string)) {
                    str = formatNumber(string);
                }
                String str2 = null;
                if (string != null && !string.trim().isEmpty() && !isPhoneNumber(string)) {
                    str2 = string.trim();
                } else if (string3 != null && !string3.trim().isEmpty() && !isPhoneNumber(string3)) {
                    str2 = string3.trim();
                }
                if (this.callKey != null && this.callKey.equals(statusBarNotification.getKey())) {
                    boolean z2 = (this.callName == null || this.callName.trim().isEmpty() || isPhoneNumber(this.callName)) ? false : true;
                    if (str2 == null || z2) {
                        return;
                    }
                    this.callName = str2;
                    if (str != null) {
                        this.callNumber = digitsOnly(str);
                    }
                    extractCallSpeakerIntents(notification);
                    sendCallBroadcast(CALL_RINGING, str2, str);
                    return;
                }
                boolean z3 = (this.callNumber == null || str == null || !this.callNumber.equals(digitsOnly(str))) ? false : true;
                boolean z4 = (this.callName == null || str2 == null || !this.callName.equals(str2)) ? false : true;
                boolean z5 = false;
                try {
                    telephonyManager = (TelephonyManager) getSystemService("phone");
                } catch (Throwable th) {
                }
                if (telephonyManager != null) {
                    if (telephonyManager.getCallState() == 2) {
                        z = true;
                        z5 = z;
                        if ((z3 && !z4) || !z5) {
                            this.callKey = statusBarNotification.getKey();
                            this.callNumber = digitsOnly(str);
                            this.callName = str2;
                            extractCallSpeakerIntents(notification);
                            sendCallBroadcast(CALL_RINGING, str2, str);
                            return;
                        }
                        if (statusBarNotification.getKey() != null || statusBarNotification.getKey().equals(this.callKey)) {
                            return;
                        }
                        this.callKey = statusBarNotification.getKey();
                        extractCallSpeakerIntents(notification);
                        sendCallBroadcast(CALL_OFFHOOK, str2, str);
                        return;
                    }
                }
                z = false;
                z5 = z;
                if (z3) {
                }
                if (statusBarNotification.getKey() != null) {
                    return;
                } else {
                    return;
                }
            }
            MediaSession.Token token = null;
            Bundle bundle2 = notification.extras;
            if (bundle2 != null) {
                try {
                    Parcelable parcelable = bundle2.getParcelable("android.mediaSession");
                    if (parcelable instanceof MediaSession.Token) {
                        token = (MediaSession.Token) parcelable;
                    }
                } catch (Throwable th2) {
                }
            }
            boolean z6 = !isSongRecognition(notification) && ("transport".equals(notification.category) || token != null || isKnownMusicApp(statusBarNotification.getPackageName(), notification));
            if (z6 && !FeatureFlags.musicVideo && isVideoApp(statusBarNotification.getPackageName())) {
                z6 = false;
            }
            if (isMeituanLiveNotif(statusBarNotification, notification)) {
                FileLog.i("LivePass meituan key=" + statusBarNotification.getKey());
                return;
            }
            boolean z7 = ((notification.flags & 2) == 0 && (notification.flags & 64) == 0) ? false : true;
            Bundle bundle3 = notification.extras;
            int i = bundle3 != null ? bundle3.getInt("android.progress", 0) : 0;
            int i2 = bundle3 != null ? bundle3.getInt("android.progressMax", 0) : 0;
            boolean z8 = bundle3 != null && bundle3.getBoolean("android.progressIndeterminate", false);
            int[] readProgressFromRemoteViews = readProgressFromRemoteViews(notification);
            if (readProgressFromRemoteViews != null && readProgressFromRemoteViews[1] > 0) {
                if ((readProgressFromRemoteViews[0] * 100) / readProgressFromRemoteViews[1] > (i2 > 0 ? (i * 100) / i2 : -1)) {
                    i = readProgressFromRemoteViews[0];
                    i2 = readProgressFromRemoteViews[1];
                }
            }
            boolean z9 = i2 > 0 || z8;
            if (!z7 || z9 || z6) {
                String text = getText(bundle3, "android.title");
                String text2 = getText(bundle3, "android.text");
                String text3 = getText(bundle3, "android.bigText");
                if (text2 == null || text2.isEmpty()) {
                    text2 = getText(bundle3, "android.bigText");
                }
                if (text2 == null || text2.isEmpty()) {
                    text2 = getLastTextLine(bundle3);
                }
                if (text2 == null || text2.isEmpty()) {
                    text2 = getLatestMessageText(bundle3);
                }
                if ((text2 == null || text2.isEmpty()) && (charSequence = notification.tickerText) != null && !charSequence.toString().trim().isEmpty()) {
                    text2 = charSequence.toString();
                }
                if (text2 == null || text2.isEmpty()) {
                    text2 = getText(bundle3, "android.subText");
                }
                int i3 = -1;
                boolean z10 = false;
                if (!z6) {
                    i3 = readDownloadPct(notification, text, text2);
                    z10 = i3 >= 0 && hasDownloadSignal(statusBarNotification.getPackageName(), notification.getChannelId(), text, text2);
                    if (z10) {
                        String text4 = bundle3 != null ? getText(bundle3, "android.infoText") : null;
                        String text5 = bundle3 != null ? getText(bundle3, "android.subText") : null;
                        long[] parseDownloadedTotal = parseDownloadedTotal(text2, text4);
                        if (parseDownloadedTotal == null) {
                            parseDownloadedTotal = parseDownloadedTotal(text, null);
                        }
                        long[] readDownloadBytes = parseDownloadedTotal != null ? parseDownloadedTotal : readDownloadBytes(notification);
                        long j = readDownloadBytes != null ? readDownloadBytes[0] : -1L;
                        long j2 = readDownloadBytes != null ? readDownloadBytes[1] : -1L;
                        long parseSpeedFromText = parseSpeedFromText(text2);
                        long parseEtaSeconds = parseEtaSeconds(text2, text5);
                        this.lastDownloadBytes = j;
                        this.lastDownloadBytesTime = SystemClock.uptimeMillis();
                        String[] normalizeDownload = normalizeDownload(text, text2, i3, j, j2, parseSpeedFromText, parseEtaSeconds);
                        text = normalizeDownload[0];
                        text2 = normalizeDownload[1];
                        this.downloadKey = statusBarNotification.getKey();
                        this.lastDownloadPct = i3;
                        this.lastDownloadSpeed = -1L;
                        this.lastDownloadEta = -1L;
                        startDownloadTick();
                    } else if (i3 < 0 && hasDownloadSignal(statusBarNotification.getPackageName(), notification.getChannelId(), text, text2) && isDownloadComplete(text, text2)) {
                        this.downloadKey = statusBarNotification.getKey();
                        this.lastDownloadPct = 100;
                        this.lastDownloadSpeed = -1L;
                        this.lastDownloadEta = -1L;
                        startDownloadTick();
                    }
                }
                String str3 = null;
                if (z6 && bundle3 != null) {
                    String str4 = this.musicKey;
                    String str5 = null;
                    String str6 = null;
                    String str7 = null;
                    String str8 = null;
                    MediaSession.Token token2 = token;
                    if (token2 != null) {
                        try {
                            if (this.musicToken != null && !this.musicToken.equals(token2)) {
                                if (this.musicController != null) {
                                    if (this.musicCallback != null) {
                                        try {
                                            this.musicController.unregisterCallback(this.musicCallback);
                                        } catch (Throwable th3) {
                                        }
                                    }
                                    this.musicController = null;
                                }
                                this.musicCallback = null;
                                this.lastLyricSent = null;
                            }
                            this.musicToken = token2;
                            this.musicKey = statusBarNotification.getKey();
                            ensureMusicController();
                            if (this.musicListening) {
                                setupMusicListener();
                            }
                            MediaMetadata metadata = new MediaController(this, token2).getMetadata();
                            if (metadata != null) {
                                str5 = metadata.getString("android.media.metadata.TITLE");
                                str7 = metadata.getString("android.media.metadata.ARTIST");
                                str6 = metadata.getString("android.media.metadata.DISPLAY_SUBTITLE");
                                str8 = metadata.getString("android.media.metadata.ALBUM_ARTIST");
                            }
                        } catch (Throwable th4) {
                        }
                    }
                    updateLyricCandidates(notification, str4 != null && str4.equals(statusBarNotification.getKey()));
                    if (text != null && !text.isEmpty()) {
                        String str9 = null;
                        String str10 = null;
                        try {
                            CharSequence applicationLabel = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(statusBarNotification.getPackageName(), 0));
                            if (applicationLabel != null && applicationLabel.toString().equals(text)) {
                                String str11 = this.currentLyricTicker;
                                if (str11 == null || str11.isEmpty()) {
                                    str11 = this.currentLyricBigText;
                                }
                                if (str11 == null || str11.isEmpty()) {
                                    str11 = this.currentLyricText;
                                }
                                String[] parseArtistSong = parseArtistSong(str11);
                                if (parseArtistSong != null) {
                                    str10 = parseArtistSong[0];
                                    str9 = parseArtistSong[1];
                                }
                                if (str9 == null && str5 != null && !str5.isEmpty() && !isCreditOrLyricTitle(str5) && !str5.equals(text)) {
                                    str9 = str5;
                                    if (str7 != null && !str7.isEmpty() && !isCreditOrLyricTitle(str7)) {
                                        str10 = str7;
                                    }
                                }
                                if (str9 == null && str11 != null && !str11.isEmpty() && str11.length() <= 30 && !isCreditOrLyricTitle(str11) && !isStatusMessage(str11)) {
                                    str9 = str11.trim();
                                }
                            }
                        } catch (Throwable th5) {
                        }
                        if (str9 != null) {
                            text = str9;
                            if (str10 != null) {
                                text2 = str10;
                            }
                        }
                    }
                    str3 = resolveMusicLyric(str5, str6, str7, str8, text);
                    if ((text2 == null || text2.isEmpty()) && str7 != null && !str7.isEmpty() && !isCreditOrLyricTitle(str7)) {
                        text2 = str7;
                    }
                }
                if ((text == null || text.isEmpty()) && (text2 == null || text2.isEmpty())) {
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th6) {
                        return;
                    }
                }
                String packageName = statusBarNotification.getPackageName();
                if (i3 >= 0 && i2 <= 0) {
                    i = i3;
                    i2 = 100;
                }
                int parseProgressPercent = parseProgressPercent(text2);
                if (parseProgressPercent < 0) {
                    parseProgressPercent = parseProgressPercent(text);
                }
                if (parseProgressPercent >= 0) {
                    if (parseProgressPercent > (i2 > 0 ? (i * 100) / i2 : -1)) {
                        if (i2 > 0) {
                            i = (parseProgressPercent * i2) / 100;
                        } else {
                            i2 = 100;
                            i = parseProgressPercent;
                        }
                        z9 = true;
                    }
                }
                if (!z9 && !z6 && System.currentTimeMillis() - statusBarNotification.getPostTime() > STALE_WINDOW_MS) {
                    FileLog.i("Stale notification dropped (>5s old): " + packageName);
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th7) {
                        return;
                    }
                }
                int i4 = z9 ? 512 : PKG_MEITUAN.equals(packageName) || PKG_ELEME.equals(packageName) || PKG_AMAP.equals(packageName) ? 576 : 578;
                if (!z6 && (notification.flags & i4) != 0) {
                    FileLog.i("Notification dropped by flags mask " + i4 + ": " + packageName);
                    if (z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th8) {
                        return;
                    }
                }
                if (isRepeatNotifFingerprint(packageName, statusBarNotification, text, text2, i, i2)) {
                    FileLog.i("Repeat notification fingerprint dropped: " + packageName);
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th9) {
                        return;
                    }
                }
                if (isRepeatNotif(statusBarNotification.getKey(), text, text2, i, i2)) {
                    FileLog.i("Repeat notification key dropped: " + statusBarNotification.getKey());
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th10) {
                        return;
                    }
                }
                if (isStalePostTime(statusBarNotification.getKey(), statusBarNotification.getPostTime())) {
                    FileLog.i("Stale postTime dropped: " + statusBarNotification.getKey());
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th11) {
                        return;
                    }
                }
                if (isRedundantMessage(statusBarNotification, notification, bundle3, packageName, text, text2)) {
                    FileLog.i("Redundant message dropped (already seen): " + packageName);
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th12) {
                        return;
                    }
                }
                if (isRedundantPushCopy(packageName, text, text2)) {
                    FileLog.i("Redundant push copy dropped: " + packageName);
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th13) {
                        return;
                    }
                }
                if (!z9 && isPkgFingerprintRepeat(packageName, text, text2)) {
                    FileLog.i("Duplicate notification dropped (pkg fp): " + packageName);
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th14) {
                        return;
                    }
                }
                if (!z9 && isFingerprintRepeat(packageName, text, text2)) {
                    FileLog.i("Duplicate notification dropped (content fp): " + packageName);
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th15) {
                        return;
                    }
                }
                if (isRedundantGroupSummary(notification, packageName)) {
                    FileLog.i("Group summary dropped (child already shown): " + packageName);
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th16) {
                        return;
                    }
                }
                if (isSummaryOf(packageName, text, text2)) {
                    FileLog.i("Summary dropped: " + packageName);
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th17) {
                        return;
                    }
                }
                if (isAggregatedSummary(packageName, text2)) {
                    FileLog.i("Aggregated summary dropped: " + packageName);
                    if (z6 || z10 || !FeatureFlags.notif) {
                        return;
                    }
                    try {
                        cancelNotif(statusBarNotification);
                        return;
                    } catch (Throwable th18) {
                        return;
                    }
                }
                noteNotifGroup(notification, packageName);
                recordNotif(statusBarNotification.getKey(), text, text2, packageName, statusBarNotification.getPostTime(), i, i2);
                if (z6) {
                    boolean z11 = (isCreditOrLyricTitle(text) || (text != null && !text.isEmpty() && text.equals(str3)) || text == null || text.isEmpty() || text.equals(this.currentMusicTitle)) ? false : true;
                    boolean z12 = (text2 == null || text2.isEmpty() || text2.equals(this.currentMusicArtist)) ? false : true;
                    if (this.currentMusicNotifKey != null && this.currentMusicNotifKey.equals(statusBarNotification.getKey()) && !z11 && !z12) {
                        try {
                            Intent intent = new Intent(ACTION_LYRIC);
                            intent.putExtra("key", statusBarNotification.getKey());
                            intent.putExtra("lyrics", str3);
                            sendBroadcast(intent);
                            return;
                        } catch (Throwable th19) {
                            return;
                        }
                    }
                    long j3 = -1;
                    long j4 = -1;
                    try {
                        if (this.musicController != null) {
                            MediaMetadata metadata2 = this.musicController.getMetadata();
                            if (metadata2 != null) {
                                j3 = metadata2.getLong("android.media.metadata.DURATION");
                            }
                            PlaybackState playbackState = this.musicController.getPlaybackState();
                            if (playbackState != null) {
                                j4 = playbackState.getPosition();
                            }
                        }
                    } catch (Throwable th20) {
                    }
                    if (!((z11 || z12) ? z12 ? true : (this.currentMusicTitle == null || this.currentMusicTitle.isEmpty()) ? true : (j3 <= 0 || this.currentMusicDurationMs <= 0 || j3 == this.currentMusicDurationMs) ? (j4 < 0 || j4 >= STALE_WINDOW_MS) ? j4 < 0 && j3 <= 0 : true : true : false)) {
                        try {
                            Intent intent2 = new Intent(ACTION_LYRIC);
                            intent2.putExtra("key", statusBarNotification.getKey());
                            intent2.putExtra("lyrics", str3);
                            sendBroadcast(intent2);
                            return;
                        } catch (Throwable th21) {
                            return;
                        }
                    }
                    this.currentMusicTitle = text;
                    this.currentMusicArtist = text2;
                    this.currentMusicNotifKey = statusBarNotification.getKey();
                    if (j3 > 0) {
                        this.currentMusicDurationMs = j3;
                    }
                }
                Intent intent3 = new Intent(ACTION_NOTIFY);
                intent3.putExtra("key", statusBarNotification.getKey());
                intent3.putExtra("pkg", statusBarNotification.getPackageName());
                intent3.putExtra("title", text);
                intent3.putExtra("text", text2);
                if (text3 != null) {
                    intent3.putExtra("bigText", text3);
                }
                intent3.putExtra("lyrics", str3);
                intent3.putExtra("postTime", statusBarNotification.getPostTime());
                intent3.putExtra("isMusic", z6);
                intent3.putExtra("isDownload", z10);
                if (z10) {
                    intent3.putExtra("download_pct", i3);
                }
                try {
                    Notification notification2 = statusBarNotification.getNotification();
                    if (notification2 != null && notification2.contentIntent != null) {
                        intent3.putExtra("content_intent", notification2.contentIntent);
                    }
                } catch (Throwable th22) {
                }
                if (z6) {
                    try {
                        Notification notification3 = statusBarNotification.getNotification();
                        Bitmap bitmap = null;
                        if (notification3 != null && notification3.extras != null) {
                            bitmap = bitmapFromLargeIcon(notification3.extras.getParcelable("android.largeIcon"));
                        }
                        if (bitmap != null && (bitmap.getWidth() > 128 || bitmap.getHeight() > 128)) {
                            bitmap = Bitmap.createScaledBitmap(bitmap, 128, 128, true);
                        }
                        if (bitmap != null) {
                            intent3.putExtra("album_art", bitmap);
                        }
                    } catch (Throwable th23) {
                    }
                }
                try {
                    sendBroadcast(intent3);
                } catch (Throwable th24) {
                }
                boolean z13 = z10;
                boolean z14 = false;
                if (!z6 && !z13) {
                    try {
                        if (getSharedPreferences("flymebobo_features", 0).getBoolean("lightMode", false)) {
                            AppRules.load(this);
                            z14 = AppRules.modeFor(statusBarNotification.getPackageName()) == 3;
                            if (z14) {
                                FileLog.i("Light release keep shade pkg=" + statusBarNotification.getPackageName());
                            }
                        }
                    } catch (Throwable th25) {
                    }
                }
                if (!z6 && !z13 && FeatureFlags.notif && !z14) {
                    try {
                        cancelNotif(statusBarNotification);
                    } catch (Throwable th26) {
                    }
                }
            }
        } catch (Throwable th27) {
        }
    }

    @Override // android.service.notification.NotificationListenerService
    public void onNotificationRemoved(StatusBarNotification statusBarNotification) {
        if (statusBarNotification != null) {
            try {
                if (PKG_AMAP.equals(statusBarNotification.getPackageName())) {
                    String str = null;
                    if (statusBarNotification.getId() == 99910002) {
                        str = "footnavi";
                    } else if (statusBarNotification.getId() == 99910003) {
                        str = "ridenavi";
                    }
                    if (str != null) {
                        try {
                            Intent intent = new Intent(ACTION_NAVI_END);
                            intent.putExtra("bizType", str);
                            sendBroadcast(intent);
                        } catch (Throwable th) {
                        }
                    }
                }
            } catch (Throwable th2) {
            }
        }
        if (statusBarNotification != null && this.downloadKey != null && this.downloadKey.equals(statusBarNotification.getKey())) {
            sendDownloadRemove(this.downloadKey);
            stopDownloadTick();
            this.downloadKey = null;
        }
        if (statusBarNotification != null && this.callKey != null && this.callKey.equals(statusBarNotification.getKey())) {
            boolean z = false;
            try {
                TelephonyManager telephonyManager = (TelephonyManager) getSystemService("phone");
                if (telephonyManager != null) {
                    if (telephonyManager.getCallState() != 0) {
                        z = true;
                    }
                }
            } catch (Throwable th3) {
            }
            if (!z) {
                this.callKey = null;
                this.callNumber = null;
                this.callName = null;
                sendCallBroadcast(CALL_IDLE, null, null);
            }
        }
        if (statusBarNotification == null || this.musicKey == null || !this.musicKey.equals(statusBarNotification.getKey())) {
            return;
        }
        try {
            Intent intent2 = new Intent(ACTION_MUSIC_GONE);
            intent2.putExtra("key", statusBarNotification.getKey());
            sendBroadcast(intent2);
        } catch (Throwable th4) {
        }
        this.musicListening = false;
        teardownMusicSession();
        this.musicToken = null;
        this.musicKey = null;
    }
}
