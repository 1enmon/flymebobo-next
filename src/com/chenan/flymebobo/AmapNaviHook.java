package com.chenan.flymebobo;

import android.app.Application;
import android.content.Intent;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;

// v311: Amap live navigation (foot/ride only, user "不写驾车").
//
// The notification never carries turn data (user 2026-11-21,
// confirmed by logs: car "正在导航", ride "正在为您骑行导航>>"),
// so the turn instruction + distance must come from Amap's own
// Ajx bridge: ModuleRoute.notifyNaviInfo(JSONObject). The JS
// navigation engine pushes {bizType, title, content, status,
// icon} here; RouteNaviInfoController forwards it to the
// extra-screen notification (foot/ride only). We hook the same
// entry, take footnavi/ridenavi only, and broadcast the fields to
// SystemUI, where BubbleWindow shows them as a live nav capsule.
//
// The Amap process cannot write the module's FileLog (different
// uid, scoped storage), so this side logs to logcat; SystemUI
// FileLogs the receipt ("NaviInfo ...") in BubbleNotify.log.
public final class AmapNaviHook {
    private static final String TAG = "flymebobo";
    private final XposedInterface bridge;
    private final Application app;
    private boolean done;

    public AmapNaviHook(XposedInterface xposed, Application app) {
        this.bridge = xposed;
        this.app = app;
    }

    public void install() {
        if (done) {
            return;
        }
        try {
            Class<?> moduleRoute = Class.forName(
                    "com.autonavi.bundle.routecommon.ajx.ModuleRoute",
                    false, app.getClassLoader());
            Method notifyNaviInfo = moduleRoute.getDeclaredMethod(
                    "notifyNaviInfo", JSONObject.class);
            bridge.hook(notifyNaviInfo).intercept(
                    new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain)
                        throws Throwable {
                    Object result = chain.proceed();
                    try {
                        onNaviInfo((JSONObject) chain.getArg(0));
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            done = true;
            Log.i(TAG, "AmapNaviHook: installed (foot/ride notifyNaviInfo)");
            sendHookStatus("installed", null);
            installCloudFlagHook();
            installNotifyChangeHook();
            installFootRouteHook();
            installRouteJumpHook();
            installFootLabelHook();
        } catch (Throwable th) {
            Log.i(TAG, "AmapNaviHook install failed: " + th);
            sendHookStatus("failed", String.valueOf(th));
        }
    }

    // v318 (diagnostic): read the remaining nav data from the
    // engine's shared PerfOptimization store and broadcast it.
    // The car page reads PerfOptUtils.a("remainDistance") /
    // a("remainTime") — the question is whether the foot/ride
    // engines populate the same keys, and in what format. Called
    // from the notifyNaviInfo push (ride, ~1s) and from
    // onNotifyChange (foot/ride, notification frequency).
    private void broadcastRemain(String src) {
        try {
            Class<?> perf = Class.forName(
                    "com.amap.bundle.perfopt.util.PerfOptUtils",
                    false, app.getClassLoader());
            Method aMethod = perf.getDeclaredMethod("a", String.class);
            aMethod.setAccessible(true);
            String remainD = String.valueOf(
                    aMethod.invoke(null, "remainDistance"));
            String remainT = String.valueOf(
                    aMethod.invoke(null, "remainTime"));
            String dist = String.valueOf(
                    aMethod.invoke(null, "distance"));
            String costT = String.valueOf(
                    aMethod.invoke(null, "costTime"));
            Intent intent = new Intent(
                    NotificationService.ACTION_NAVI_REMAIN);
            intent.putExtra("remainDistance", remainD);
            intent.putExtra("remainTime", remainT);
            intent.putExtra("distance", dist);
            intent.putExtra("costTime", costT);
            intent.putExtra("src", src);
            app.sendBroadcast(intent);
            Log.i(TAG, "NaviRemain src=" + src + " remainD=" + remainD
                    + " remainT=" + remainT + " dist=" + dist
                    + " costT=" + costT);
        } catch (Throwable th) {
            Log.i(TAG, "NaviRemain read failed: " + th);
        }
    }

    // v318 (diagnostic): hook onNotifyChange in the foot/ride
    // pages. The JS calls it to update the navigation
    // notification; it fires for BOTH foot and ride (unlike
    // notifyNaviInfo, which only the ride JS calls). Use it as
    // the trigger to read the PerfOptimization remaining data.
    private void installNotifyChangeHook() {
        String[][] targets = {
            {"com.autonavi.minimap.route.foot.page.AjxFootNaviPage",
                    "foot"},
            {"com.autonavi.minimap.route.ride.dest.page.AjxRideNaviPageNew",
                    "ride"},
        };
        for (String[] t : targets) {
            final String src = "onNotifyChange-" + t[1];
            try {
                Class<?> page = Class.forName(t[0], false,
                        app.getClassLoader());
                Method m = page.getDeclaredMethod("onNotifyChange",
                        String.class, String.class, boolean.class);
                bridge.hook(m).intercept(
                        new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain)
                            throws Throwable {
                        Object r = chain.proceed();
                        broadcastRemain(src);
                        return r;
                    }
                });
                Log.i(TAG, "AmapNaviHook: onNotifyChange hooked (" + t[1] + ")");
            } catch (Throwable th) {
                Log.i(TAG, "AmapNaviHook onNotifyChange hook failed ("
                        + t[1] + "): " + th);
            }
        }
    }

    // v316: force the extra-screen cloud flag on. The precise
    // RouteNaviInfo push (icon=turn action, content=distance) is
    // gated by TripCloudUtils.e("ride_walk_ongoing",
    // "ongoing_switch", "0") — ExtraScreenNaviInfoLogic.b() is true
    // only when it returns "1", and the JS engine only pushes
    // notifyNaviInfo when the feature is on. Override just that
    // one key; every other cloud config passes through untouched.
    // If the engine starts pushing, the capsule gets app-level
    // accuracy ("直行 112米", real-time) instead of the coarse
    // notification title.
    private void installCloudFlagHook() {
        try {
            Class<?> tripCloudUtils = Class.forName(
                    "com.autonavi.minimap.TripCloudUtils",
                    false, app.getClassLoader());
            Method eMethod = tripCloudUtils.getDeclaredMethod(
                    "e", String.class, String.class, String.class);
            bridge.hook(eMethod).intercept(
                    new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain)
                        throws Throwable {
                    String s0 = (String) chain.getArg(0);
                    String s1 = (String) chain.getArg(1);
                    if ("ride_walk_ongoing".equals(s0)
                            && "ongoing_switch".equals(s1)) {
                        Log.i(TAG, "AmapNaviHook: cloud flag "
                                + "ride_walk_ongoing/ongoing_switch "
                                + "forced to 1");
                        return "1";
                    }
                    return chain.proceed();
                }
            });
            Log.i(TAG, "AmapNaviHook: cloud flag hook installed");
        } catch (Throwable th) {
            Log.i(TAG, "AmapNaviHook cloud flag hook failed: " + th);
        }
    }

    // v312: the Amap process cannot write the module's FileLog, so
    // report install success/failure to SystemUI by broadcast —
    // the receiver FileLogs it into BubbleNotify.log.
    private void sendHookStatus(String status, String error) {
        try {
            Intent intent = new Intent(
                    NotificationService.ACTION_NAVI_HOOK_STATUS);
            intent.putExtra("status", status);
            if (error != null) {
                intent.putExtra("error", error);
            }
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    // v322 (diagnostic + compute-it-yourself): hook
    // ModuleFoot.jumpFootNavi(String) — JS calls it to start foot
    // navigation, and the String is the route JSON that becomes
    // presenter.z. The Java OnFootNaviPath is a legacy empty shell
    // (mAllPoints is never populated anywhere, Java or native), so
    // this JSON is the only place a polyline could live. Capture
    // it, report its top-level keys + whether a polyline field
    // exists, and if a polyline is found broadcast it as
    // ACTION_FOOT_ROUTE so BubbleWindow can project the live
    // position onto it.
    // v323: the FootDiag never appeared even after a fresh nav
    // start, so hook startFootNaviPage() too (public, called by
    // jumpFootNavi and possibly other entries) and read the route
    // JSON from the mDestNaviParams field; also report the hook
    // install status (it used to fail silently to logcat only).
    // v324: FootHook=installed but STILL no FootDiag — and grep
    // shows jumpFootNavi/startFootNaviPage have NO Java callers,
    // so JS is not calling them for this nav start. Hook the
    // point that ALWAYS runs: AjxFootNaviPresenter.onPageCreated()
    // (public final, no args) reads presenter.z from
    // page.getArguments().getString(BUNDLE_KEY_OBJ_DATA) — the
    // route JSON, whatever entry started the page. Read z after
    // proceed().
    // v325: FootHook status is now granular — it reports WHICH
    // of the three hooks installed (jumpFootNavi / startFootNaviPage
    // / onPageCreated), because v324 said "installed" yet still no
    // FootDiag, and the old status could not tell whether
    // onPageCreated was the one that failed.
    private void installFootRouteHook() {
        int hooked = 0;
        StringBuilder detail = new StringBuilder();
        try {
            Class<?> moduleFoot = Class.forName(
                    "com.autonavi.bundle.footresult.ajx.ModuleFoot",
                    false, app.getClassLoader());
            try {
                Method jump = moduleFoot.getDeclaredMethod(
                        "jumpFootNavi", String.class);
                bridge.hook(jump).intercept(
                        new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(
                            XposedInterface.Chain chain)
                            throws Throwable {
                        Object result = chain.proceed();
                        try {
                            onFootRouteJson(
                                    (String) chain.getArg(0));
                        } catch (Throwable ignored) {
                        }
                        return result;
                    }
                });
                hooked++;
                if (detail.length() > 0) {
                    detail.append(",");
                }
                detail.append("jumpFootNavi");
                Log.i(TAG, "AmapNaviHook: foot route hook installed "
                        + "(ModuleFoot.jumpFootNavi)");
            } catch (Throwable th) {
                Log.i(TAG, "AmapNaviHook jumpFootNavi hook failed: "
                        + th);
            }
            try {
                Method start = moduleFoot.getDeclaredMethod(
                        "startFootNaviPage");
                bridge.hook(start).intercept(
                        new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(
                            XposedInterface.Chain chain)
                            throws Throwable {
                        Object result = chain.proceed();
                        try {
                            Object thiz = chain.getThisObject();
                            Field f = thiz.getClass()
                                    .getDeclaredField(
                                            "mDestNaviParams");
                            f.setAccessible(true);
                            Object v = f.get(thiz);
                            if (v instanceof String) {
                                onFootRouteJson((String) v);
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    }
                });
                hooked++;
                if (detail.length() > 0) {
                    detail.append(",");
                }
                detail.append("startFootNaviPage");
                Log.i(TAG, "AmapNaviHook: foot route hook installed "
                        + "(ModuleFoot.startFootNaviPage)");
            } catch (Throwable th) {
                Log.i(TAG, "AmapNaviHook startFootNaviPage hook "
                        + "failed: " + th);
            }
        } catch (Throwable th) {
            Log.i(TAG, "AmapNaviHook foot route hook failed: " + th);
        }
        // v324: the page presenter — always runs when the foot
        // nav page is created, whatever entry started it.
        try {
            Class<?> presenter = Class.forName(
                    "com.autonavi.minimap.route.foot.presenter"
                            + ".AjxFootNaviPresenter",
                    false, app.getClassLoader());
            Method created = presenter.getMethod("onPageCreated");
            bridge.hook(created).intercept(
                    new XposedInterface.Hooker() {
                @Override
                public Object intercept(
                        XposedInterface.Chain chain)
                        throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object thiz = chain.getThisObject();
                        Field f = thiz.getClass()
                                .getDeclaredField("z");
                        f.setAccessible(true);
                        Object v = f.get(thiz);
                        if (v instanceof String) {
                            onFootRouteJson((String) v);
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            hooked++;
            if (detail.length() > 0) {
                detail.append(",");
            }
            detail.append("onPageCreated");
            Log.i(TAG, "AmapNaviHook: foot route hook installed "
                    + "(AjxFootNaviPresenter.onPageCreated)");
        } catch (Throwable th) {
            Log.i(TAG, "AmapNaviHook AjxFootNaviPresenter "
                    + "onPageCreated hook failed: " + th);
        }
        sendFootHookStatus(hooked > 0 ? "installed" : "failed",
                hooked > 0 ? detail.toString()
                        : "no method hooked");
    }

    private void sendFootHookStatus(String status, String detail) {
        try {
            Intent intent = new Intent(
                    NotificationService.ACTION_FOOT_HOOK_STATUS);
            intent.putExtra("status", status);
            if (detail != null) {
                intent.putExtra("detail", detail);
            }
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    // v326 (diagnostic): hook the route details JSON — the data
    // JS renders as the route options + the "路线详情" step list
    // (126米后左转 / 37米后右转进三钢二路 / …). The Java
    // OnFootNaviPath is an empty shell, so the steps only exist
    // in this JSON. Two entry points, both carry the full route
    // JSON as a String:
    //   ModuleFoot.jump(String, String) / ModuleRide.jump(String,
    //   String) — str is the action ("footPreview"/"footNavi" /
    //   "ridePreview"/"rideNavi"), str2 is the route JSON.
    //   "footPreview" opens the details page the user
    //   screenshotted; "footNavi" is the nav entry (which never
    //   fired before — hooking jump itself shows which entry JS
    //   actually uses).
    //   ModuleFoot/ModuleRide.handleAjxMessage(int, String) —
    //   i==0 means JS sends the route result JSON back to Java.
    // Diagnostic only: log the JSON's top-level keys + whether a
    // steps-like array exists (ACTION_ROUTE_DIAG), so the exact
    // field names can be read from the log before parsing.
    // v327: report which route hooks installed, so a missing
    // RouteDiag can tell a failed install from a never-called
    // jump (v326 had no status for these hooks).
    private void installRouteJumpHook() {
        StringBuilder detail = new StringBuilder();
        int hooked = 0;
        if (hookJump(
                "com.autonavi.bundle.footresult.ajx.ModuleFoot",
                "foot")) {
            hooked++;
            detail.append("jumpFoot");
        }
        if (hookJump(
                "com.autonavi.bundle.rideresult.ajx.ModuleRide",
                "ride")) {
            hooked++;
            if (detail.length() > 0) {
                detail.append(",");
            }
            detail.append("jumpRide");
        }
        if (hookAjxMessage(
                "com.autonavi.bundle.footresult.ajx.ModuleFoot",
                "foot")) {
            hooked++;
            if (detail.length() > 0) {
                detail.append(",");
            }
            detail.append("ajxFoot");
        }
        if (hookAjxMessage(
                "com.autonavi.bundle.rideresult.ajx.ModuleRide",
                "ride")) {
            hooked++;
            if (detail.length() > 0) {
                detail.append(",");
            }
            detail.append("ajxRide");
        }
        try {
            Intent intent = new Intent(
                    NotificationService.ACTION_ROUTE_HOOK_STATUS);
            intent.putExtra("status",
                    hooked > 0 ? "installed" : "failed");
            intent.putExtra("detail", hooked > 0
                    ? detail.toString() : "no method hooked");
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private boolean hookJump(final String cls, final String mode) {
        try {
            Class<?> module = Class.forName(cls, false,
                    app.getClassLoader());
            Method jump = module.getDeclaredMethod("jump",
                    String.class, String.class);
            bridge.hook(jump).intercept(
                    new XposedInterface.Hooker() {
                @Override
                public Object intercept(
                        XposedInterface.Chain chain)
                        throws Throwable {
                    Object result = chain.proceed();
                    try {
                        String action = (String) chain.getArg(0);
                        String json = (String) chain.getArg(1);
                        onRouteJson(json, mode, action);
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            Log.i(TAG, "AmapNaviHook: route jump hook installed ("
                    + cls + ")");
            return true;
        } catch (Throwable th) {
            Log.i(TAG, "AmapNaviHook route jump hook failed (" + cls
                    + "): " + th);
            return false;
        }
    }

    private boolean hookAjxMessage(final String cls,
            final String mode) {
        try {
            Class<?> module = Class.forName(cls, false,
                    app.getClassLoader());
            Method msg = module.getDeclaredMethod(
                    "handleAjxMessage", int.class, String.class);
            bridge.hook(msg).intercept(
                    new XposedInterface.Hooker() {
                @Override
                public Object intercept(
                        XposedInterface.Chain chain)
                        throws Throwable {
                    Object result = chain.proceed();
                    try {
                        int key = (Integer) chain.getArg(0);
                        if (key == 0) {
                            String json =
                                    (String) chain.getArg(1);
                            onRouteJson(json, mode, "ajxMessage");
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            Log.i(TAG, "AmapNaviHook: route ajxMessage hook "
                    + "installed (" + cls + ")");
            return true;
        } catch (Throwable th) {
            Log.i(TAG, "AmapNaviHook route ajxMessage hook failed ("
                    + cls + "): " + th);
            return false;
        }
    }

    private void onRouteJson(String json, String mode,
            String action) {
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(json);
            java.util.Iterator<String> keys = obj.keys();
            StringBuilder kb = new StringBuilder();
            while (keys.hasNext()) {
                if (kb.length() > 0) {
                    kb.append(",");
                }
                kb.append(keys.next());
            }
            String keysStr = kb.toString();
            // A steps-like array under common field names.
            boolean hasSteps = false;
            String[] stepKeys = {"steps", "segments", "path",
                    "naviSteps", "routes", "paths", "legs",
                    "instructions", "maneuvers", "points",
                    "polyline", "coor", "coords"};
            for (String k : stepKeys) {
                if (obj.has(k)) {
                    hasSteps = true;
                    break;
                }
            }
            Log.i(TAG, "RouteJson mode=" + mode + " action="
                    + action + " len=" + json.length()
                    + " keys=" + keysStr + " hasSteps=" + hasSteps);
            Intent diag = new Intent(
                    NotificationService.ACTION_ROUTE_DIAG);
            diag.putExtra("mode", mode);
            diag.putExtra("action", action);
            diag.putExtra("jsonKeys", keysStr);
            diag.putExtra("hasSteps", hasSteps);
            diag.putExtra("jsonLen", json.length());
            app.sendBroadcast(diag);
        } catch (Throwable th) {
            Log.i(TAG, "RouteJson parse failed: " + th);
        }
    }

    private void onFootRouteJson(String json) {
        if (json == null) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(json);
            java.util.Iterator<String> keys = obj.keys();
            StringBuilder kb = new StringBuilder();
            while (keys.hasNext()) {
                if (kb.length() > 0) {
                    kb.append(",");
                }
                kb.append(keys.next());
            }
            String keysStr = kb.toString();
            Log.i(TAG, "FootRouteJson len=" + json.length()
                    + " keys=" + keysStr);
            // Try to find a polyline under common field names.
            String points = findPolyline(obj);
            boolean hasPolyline = points != null
                    && !points.isEmpty();
            int totalLength = obj.optInt("mPathlength", -1);
            if (totalLength < 0) {
                totalLength = obj.optInt("pathlength", -1);
            }
            if (totalLength < 0) {
                totalLength = obj.optInt("length", -1);
            }
            int totalTime = obj.optInt("mPathTime", -1);
            if (totalTime < 0) {
                totalTime = obj.optInt("pathtime", -1);
            }
            if (totalTime < 0) {
                totalTime = obj.optInt("time", -1);
            }
            Intent diag = new Intent(
                    NotificationService.ACTION_FOOT_DIAG);
            diag.putExtra("jsonKeys", keysStr);
            diag.putExtra("hasPolyline", hasPolyline);
            diag.putExtra("jsonLen", json.length());
            app.sendBroadcast(diag);
            if (hasPolyline) {
                Intent route = new Intent(
                        NotificationService.ACTION_FOOT_ROUTE);
                route.putExtra("totalLength", totalLength);
                route.putExtra("totalTime", totalTime);
                route.putExtra("points", points);
                app.sendBroadcast(route);
                Log.i(TAG, "FootRoute polyline found, totalLen="
                        + totalLength + " totalTime=" + totalTime);
            }
        } catch (Throwable th) {
            Log.i(TAG, "FootRouteJson parse failed: " + th);
        }
    }

    // Look for a polyline under common field names. Accepts:
    //  - a "lat,lng;lat,lng;..." string,
    //  - a JSON array of [lng,lat] or [lat,lng] pairs,
    //  - a JSON array of {"lat":..,"lng":..} / {"x":..,"y":..}.
    // Returns the compact "lat,lng;..." form, or null.
    private String findPolyline(JSONObject obj) {
        String[] names = {"polyline", "points", "coor", "coords",
                "path", "line", "route", "polyLine", "coordinates",
                "mAllPoints", "allPoints"};
        for (String name : names) {
            try {
                if (!obj.has(name)) {
                    continue;
                }
                Object v = obj.opt(name);
                if (v instanceof String) {
                    String s = (String) v;
                    if (s.contains(",") && s.length() > 10) {
                        return s;
                    }
                } else if (v instanceof org.json.JSONArray) {
                    org.json.JSONArray arr = (org.json.JSONArray) v;
                    String pts = polylineFromArray(arr);
                    if (pts != null) {
                        return pts;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String polylineFromArray(org.json.JSONArray arr) {
        try {
            if (arr == null || arr.length() < 2) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (int i = 0; i < arr.length(); i++) {
                Object el = arr.opt(i);
                double lat = 0;
                double lng = 0;
                boolean ok = false;
                if (el instanceof org.json.JSONArray) {
                    org.json.JSONArray pair =
                            (org.json.JSONArray) el;
                    if (pair.length() >= 2) {
                        double a = pair.optDouble(0, 0);
                        double b = pair.optDouble(1, 0);
                        // Pairs are usually [lng,lat]; detect by
                        // range (lat is -90..90, lng -180..180,
                        // and in China lng ~73..135, lat ~18..54).
                        if (Math.abs(a) <= 90
                                && Math.abs(b) <= 180
                                && Math.abs(b) > 90) {
                            lat = a;
                            lng = b;
                        } else {
                            lng = a;
                            lat = b;
                        }
                        ok = lat != 0 && lng != 0;
                    }
                } else if (el instanceof JSONObject) {
                    JSONObject o = (JSONObject) el;
                    if (o.has("lat") && o.has("lng")) {
                        lat = o.optDouble("lat", 0);
                        lng = o.optDouble("lng", 0);
                        ok = lat != 0 && lng != 0;
                    } else if (o.has("latitude")
                            && o.has("longitude")) {
                        lat = o.optDouble("latitude", 0);
                        lng = o.optDouble("longitude", 0);
                        ok = lat != 0 && lng != 0;
                    }
                }
                if (!ok) {
                    continue;
                }
                if (count > 0) {
                    sb.append(";");
                }
                sb.append(lat).append(",").append(lng);
                count++;
            }
            return count >= 2 ? sb.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // v512: foot navigation from AJX Label.setText clusters.
    //
    // The 2026-11-24 real walk (NaviLog v0.3, 3.5 km to Wuliudian)
    // proved foot has NO structured channel: FOOT-PRESENTER /
    // FOOT-PAGE / notifyNaviInfo / PerfOpt never fired once, while
    // Label.setText carried everything — turn direction, countdown
    // distance, remaining km/minutes, ETA, walked meters, elapsed
    // time. So parse the label stream here and broadcast it as the
    // same ACTION_NAVI_INFO (bizType=footnavi) the ride path uses;
    // BubbleWindow.onNaviInfo needs no change.
    //
    // What the nav screen looks like as labels (from that log):
    //   instruction cluster, re-rendered with fresh instances:
    //     "直行" + "128" + "米"        -> 直行 128米
    //     "128" + "米后" + "直行"      -> 128米后直行
    //     "18" + "米后" + "向左前方直行" + "进入" + "建新东路"
    //     "沿" + "建新东路" + "直行" + "188" + "米"
    //   stats, stable instances, updated in place:
    //     number + "公里" (remaining), number + "分钟" (remaining),
    //     "到达" + "HH:MM" (ETA clock time),
    //     "已行走(m)" + meters, "已用时" + "MM:SS" (ticks each second),
    //     "大卡" + kcal.
    //
    // Two traps the log shows: (1) the unit label ("米"/"米后")
    // only fires setText when it CHANGES, so a countdown number can
    // arrive with no unit beside it — and the instruction labels
    // are re-created with fresh instances on every render, so no
    // instance can be trusted to keep a role. The distance is
    // therefore set the moment a number is followed by 米/米后,
    // and bare numbers in between are accepted only while they
    // keep counting down (smaller than the last, drop <= 30 m);
    // the stats labels ARE stable instances, so their roles
    // (remainKm / remainMin) are learned once from the
    // number-followed-by-unit pair and stick. (2) The route-details
    // page shows the same direction+distance words ("50米后左转进
    // 入三钢二路") BEFORE navigation starts, so the parser stays
    // gated off until the nav screen proves itself: the
    // "已行走(m)"/"已用时" captions, or the elapsed "MM:SS"
    // ticking up by exactly 1s (the ETA "HH:MM" moves by minutes,
    // never +1s). The gate expires 5s after the last heartbeat,
    // so leaving the screen stops the capsule updates; NAVI_END
    // (notification removal) still drops the capsule itself.
    private static final String[] FOOT_DIRS = {
        "向左前方直行", "向右前方直行", "向左前方", "向右前方",
        "直行", "左转", "右转", "向左转", "向右转",
        "掉头", "上过街天桥", "下过街天桥", "靠左", "靠右",
    };
    private boolean footLabelInstalled;
    private boolean footNavActive;
    private long footNavLastTick;
    private int footElapsedLastSec = -1;
    private long footElapsedSeenAt;
    private long footEtaPendingUntil;
    // instance id -> role ("remainKm" / "remainMin"), learned
    // from number-immediately-followed-by-unit pairs. Only the
    // stats labels keep a stable instance; the instruction labels
    // are re-created every render, so they carry no role.
    private final java.util.HashMap<Integer, String> footLabelRoles =
            new java.util.HashMap<>();
    // Recent label events while the nav screen is up (pruned to
    // the last 1200 ms on every event).
    private static final class FootLabelEvent {
        final long time;
        final String text;
        FootLabelEvent(long t, String s) { time = t; text = s; }
    }
    private final java.util.ArrayList<FootLabelEvent> footLabelWindow =
            new java.util.ArrayList<>();
    private String footDir;
    private String footDistNum;
    private long footDistAt;
    private String footDistUnit = "米";
    private String footRoadPrefix;
    private String footRoadName;
    private long footRoadAt;
    private String footArriveName;
    private long footArriveAt;
    private String footRemainKm;
    private String footRemainMin;
    private String footEta;
    // v515: walked-meters capture for the route total. The
    // stats labels are value-first: the walked value fires
    // right BEFORE its "已行走(m)" caption, so the caption
    // learns the value's (stable) instance from the most
    // recent number token; later tokens from that instance
    // keep footWalkedMeters fresh.
    private int footWalkedMeters = -1;
    private int footWalkedId = -1;
    private int footLastNumId = -1;
    private String footLastNumText;
    private long footLastNumAt;
    // v515: most recent clock token (ETA look-back — in the
    // stats cluster the ETA clock fires BEFORE its "到达"
    // caption, so the v512 forward arm usually misses it) and
    // the elapsed-timer instance (learned from its +1s ticks;
    // its clocks must never be taken for the ETA).
    private String footLastClockText;
    private int footLastClockId = -1;
    private long footLastClockAt;
    private int footElapsedId = -1;
    // v520: ETA trust bookkeeping. Cold start bit hard here:
    // on the first navigation after the app process starts,
    // the elapsed timer is not learned yet, so the 到达
    // look-back grabbed the timer's own reading ("00:01") as
    // the ETA — and footEta then stuck for the whole trip
    // (user 2026-11-25: first nav ETA wrong until cancel +
    // restart, second nav fine because the learning survived
    // in-process). So: remember which instance the accepted
    // ETA came from (learning the timer retro-invalidates a
    // capture from it), keep a candidate record of the most
    // recent clock from a not-known-elapsed instance (the
    // look-back reads this, not the raw last clock, which is
    // usually the timer's latest tick), remember the timer's
    // last reading (an "ETA" equal to it is the timer), and
    // validate every capture against now + remaining (see
    // footEtaPlausible) — an ETA that stops agreeing is
    // dropped and re-captured instead of sticking.
    private int footEtaId = -1;
    private String footEtaCandText;
    private int footEtaCandId = -1;
    private long footEtaCandAt;
    private String footElapsedText;
    private String footLastBroadcast;
    private long footLastBroadcastAt;

    private void installFootLabelHook() {
        if (footLabelInstalled) {
            return;
        }
        try {
            Class<?> label = Class.forName(
                    "com.autonavi.minimap.ajx3.widget.view.Label",
                    false, app.getClassLoader());
            int n = 0;
            for (final Method m : label.getDeclaredMethods()) {
                if (!"setText".equals(m.getName())) {
                    continue;
                }
                try {
                    bridge.hook(m).intercept(
                            new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(
                                XposedInterface.Chain chain)
                                throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object arg = chain.getArgs() == null
                                        || chain.getArgs().isEmpty()
                                        ? null : chain.getArg(0);
                                if (arg != null) {
                                    int id = System
                                            .identityHashCode(
                                                    chain
                                                    .getThisObject());
                                    onFootLabel(id,
                                            String.valueOf(arg));
                                }
                            } catch (Throwable ignored) {
                            }
                            return r;
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            footLabelInstalled = true;
            Log.i(TAG, "AmapNaviHook: foot Label.setText hooks=" + n);
        } catch (Throwable th) {
            Log.i(TAG, "AmapNaviHook foot label hook failed: " + th);
        }
    }

    private static String stripLabelTags(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf('<') >= 0) {
            s = s.replaceAll("<[^>]*>", "");
        }
        if (s.indexOf('&') >= 0) {
            s = s.replace("&apos;", "'").replace("&nbsp;", " ")
                    .replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">");
        }
        return s.trim();
    }

    private static boolean isFootNumber(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int dots = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (++dots > 1) {
                    return false;
                }
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isFootClock(String s) {
        if (s == null || s.length() != 5 || s.charAt(2) != ':') {
            return false;
        }
        return isFootNumber(s.substring(0, 2))
                && isFootNumber(s.substring(3, 5));
    }

    private static int footClockSeconds(String s) {
        try {
            return Integer.parseInt(s.substring(0, 2)) * 60
                    + Integer.parseInt(s.substring(3, 5));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** v520: is this clock text a believable ETA? With the
     * remaining minutes known, the ETA must agree with
     * now + remaining (±5 min, circular on the day); without
     * them it must at least be a wall-clock time in the next
     * 12 hours and not the elapsed timer's current reading.
     * A cold-start capture of the timer ("00:01" at 16:04
     * with 57 min left, real ETA ~17:01) fails both forms. */
    private boolean footEtaPlausible(String eta) {
        int etaMin = footClockSeconds(eta);
        if (etaMin < 0) {
            return false;
        }
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + cal.get(java.util.Calendar.MINUTE);
        if (footRemainMin != null) {
            try {
                int remain = Integer.parseInt(footRemainMin.trim());
                int d = Math.abs(etaMin - (nowMin + remain)) % 1440;
                if (d > 720) {
                    d = 1440 - d;
                }
                return d <= 5;
            } catch (Throwable ignored) {
            }
        }
        int delta = (etaMin - nowMin) % 1440;
        if (delta < 0) {
            delta += 1440;
        }
        return delta <= 720 && !eta.equals(footElapsedText);
    }

    private static String footDirOf(String s) {
        for (String d : FOOT_DIRS) {
            if (d.equals(s)) {
                return d;
            }
        }
        return null;
    }

    private void onFootLabel(int id, String raw) {
        String text = stripLabelTags(raw);
        if (text.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        // Nav-screen captions: prove navigation is up.
        if ("已行走(m)".equals(text) || "已用时".equals(text)
                || "大卡".equals(text)) {
            footNavActive = true;
            footNavLastTick = now;
            // v515: learn the walked value's instance from the
            // number token that just preceded this caption
            // (value-first layout). A unit pairing later can
            // still reclaim the instance (see below) if this
            // guess was wrong.
            if ("已行走(m)".equals(text) && footWalkedId < 0
                    && footLastNumText != null
                    && now - footLastNumAt <= 400
                    && footLastNumText.indexOf('.') < 0
                    && footLabelRoles.get(footLastNumId) == null) {
                try {
                    footWalkedMeters = Integer.parseInt(
                            footLastNumText);
                    footWalkedId = footLastNumId;
                    footLabelRoles.put(footWalkedId, "walked");
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        // "到达" marks the NEXT clock token as the ETA, not the
        // elapsed timer; a following name token is the destination.
        if ("到达".equals(text)) {
            footEtaPendingUntil = now + 800;
            footArrivePendingUntil = now + 800;
            // v515: in the stats cluster the ETA clock fires
            // BEFORE this caption (value-first, like 已行走),
            // so take the most recent clock token as the ETA —
            // unless it came from the elapsed-timer instance.
            // v520: read the candidate record (most recent
            // clock from a not-known-elapsed instance) instead
            // of the raw last clock — the timer ticks every
            // second, so the raw last clock is almost always
            // the timer — and validate it before trusting it.
            if (footEtaCandText != null
                    && now - footEtaCandAt <= 1500
                    && footEtaPlausible(footEtaCandText)) {
                footEta = footEtaCandText;
                footEtaId = footEtaCandId;
            }
            return;
        }
        if (text.startsWith("到达") && text.length() > 2) {
            // Single label "到达 五里店(地铁站)". Only trusted
            // while the nav screen is already proven active —
            // the route-details page shows the same label before
            // navigation starts and must not activate the parser.
            if (footNavActive) {
                footArriveName = text.substring(2).trim();
                footArriveAt = now;
                footNavLastTick = now;
            }
            return;
        }
        // Clock tokens: ETA (after 到达) or the elapsed timer.
        if (isFootClock(text)) {
            // v515: remember every clock for the 到达 look-back.
            footLastClockText = text;
            footLastClockId = id;
            footLastClockAt = now;
            // v520: candidate record — the most recent clock
            // from an instance not (yet) known to be the
            // elapsed timer. If this instance is later learned
            // to BE the timer, the record is wiped there.
            if (id != footElapsedId) {
                footEtaCandText = text;
                footEtaCandId = id;
                footEtaCandAt = now;
            }
            if (now < footEtaPendingUntil) {
                footEtaPendingUntil = 0;
                // v520: validate before trusting — on a cold
                // start the clock right after 到达 can be the
                // elapsed timer ("00:01"), not the ETA. A
                // rejected token falls through so the tick
                // learner below can still learn from it.
                if (footEtaPlausible(text)) {
                    footEta = text;
                    footEtaId = id;
                    // The clock after 到达 is the ETA, not a name.
                    footArrivePendingUntil = 0;
                    return;
                }
            }
            int sec = footClockSeconds(text);
            if (sec >= 0) {
                if (footElapsedLastSec >= 0
                        && sec == footElapsedLastSec + 1
                        && now - footElapsedSeenAt <= 3000) {
                    // Elapsed timer ticking +1s: nav is live.
                    footNavActive = true;
                    footNavLastTick = now;
                    // v515: this instance is the elapsed timer;
                    // its clocks are never the ETA.
                    footElapsedId = id;
                    footElapsedText = text;
                    // v520: retro-invalidate — an ETA (or ETA
                    // candidate) sourced from this instance
                    // was the timer all along.
                    if (footEtaId == id) {
                        footEta = null;
                        footEtaId = -1;
                    }
                    if (footEtaCandId == id) {
                        footEtaCandText = null;
                        footEtaCandId = -1;
                    }
                    // v520: value-level guard — an accepted
                    // ETA string identical to the timer's
                    // current reading is the timer itself.
                    if (footEta != null && footEta.equals(text)) {
                        footEta = null;
                        footEtaId = -1;
                    }
                }
                if (sec != footElapsedLastSec) {
                    footElapsedLastSec = sec;
                    footElapsedSeenAt = now;
                }
            }
            // v520: continuous correction — once the timer
            // instance is known, a clock from any other
            // instance on the live nav screen is the ETA
            // clock; take it (validated), so a wrong early
            // ETA gets overwritten instead of sticking for
            // the whole trip.
            if (footNavActive && footElapsedId >= 0
                    && id != footElapsedId
                    && footEtaPlausible(text)) {
                footEta = text;
                footEtaId = id;
            }
            return;
        }
        // v515: record the latest number token even before the
        // gate opens — on the first stats render the walked
        // value fires BEFORE its 已行走(m) caption (which is
        // what opens the gate), and the caption's look-back
        // learn needs it. Recording alone changes nothing;
        // the caption (nav-screen only) is the learn trigger.
        if (isFootNumber(text)) {
            footLastNumId = id;
            footLastNumText = text;
            footLastNumAt = now;
        }
        if (!footNavActive) {
            return;
        }
        if (now - footNavLastTick > 5000) {
            footNavActive = false;
            footLabelRoles.clear();
            footLabelWindow.clear();
            // v520: a stale ETA must not leak into the next
            // trip; it is re-captured on the nav screen.
            footEta = null;
            footEtaId = -1;
            return;
        }
        // Learn roles from number-immediately-followed-by-unit
        // pairs, and keep the slots the capsule is built from.
        // The unit fires only when it changes, so a paired unit
        // also sets the distance right away; the number's instance
        // role only matters for the stats labels (stable) and for
        // bare countdown numbers (see below).
        String prevText = null;
        long prevAt = 0;
        int wsize = footLabelWindow.size();
        if (wsize > 0) {
            FootLabelEvent prev = footLabelWindow.get(wsize - 1);
            prevText = prev.text;
            prevAt = prev.time;
        }
        if (prevText != null && isFootNumber(prevText)
                && now - prevAt <= 400) {
            if ("米".equals(text) || "米后".equals(text)) {
                footDistNum = prevText;
                footDistAt = now;
                footDistUnit = text;
                // v515: a unit pairing proves this instance is
                // the distance, not the walked value — undo a
                // wrong walked learn.
                if (footWalkedId == footPendingNumId) {
                    footWalkedId = -1;
                    footWalkedMeters = -1;
                    footLabelRoles.remove(footPendingNumId);
                }
            } else if ("公里".equals(text)) {
                footLabelRoles.put(footPendingNumId, "remainKm");
                footRemainKm = prevText;
                if (footWalkedId == footPendingNumId) {
                    footWalkedId = -1;
                    footWalkedMeters = -1;
                }
            } else if ("分钟".equals(text)) {
                footLabelRoles.put(footPendingNumId, "remainMin");
                footRemainMin = prevText;
                if (footWalkedId == footPendingNumId) {
                    footWalkedId = -1;
                    footWalkedMeters = -1;
                }
            }
        }
        if (isFootNumber(text)) {
            footPendingNumId = id;
            // v515: most-recent number token (the 已行走(m)
            // caption learns its value's instance from this).
            footLastNumId = id;
            footLastNumText = text;
            footLastNumAt = now;
            String role = footLabelRoles.get(id);
            if ("walked".equals(role)) {
                try {
                    footWalkedMeters = Integer.parseInt(text);
                } catch (Throwable ignored) {
                }
            } else if ("remainKm".equals(role)) {
                footRemainKm = text;
            } else if ("remainMin".equals(role)) {
                footRemainMin = text;
            } else if (role == null && text.indexOf('.') < 0
                    && footDistNum != null
                    && now - footDistAt <= 5000) {
                // Bare countdown number: the unit label only
                // fires on change, so between full clusters the
                // distance arrives alone (128 -> 121 -> 118 ...).
                // The instruction instances are fresh every
                // render, so roles cannot identify it — accept it
                // only when it continues the countdown (smaller
                // than the last distance, drop <= 30 m/s). Walked
                // meters rise instead, and kcal drops far more.
                try {
                    int v = Integer.parseInt(text);
                    int last = Integer.parseInt(footDistNum);
                    if (v >= 0 && v < last && last - v <= 30) {
                        footDistNum = text;
                        footDistAt = now;
                    }
                } catch (Throwable ignored) {
                }
            }
        } else {
            String dir = footDirOf(text);
            if (dir != null) {
                footDir = dir;
            } else if ("进入".equals(text) || "沿".equals(text)) {
                footRoadPrefix = text;
                footRoadAt = now;
                footRoadName = null;
            } else if (now < footArrivePendingUntil
                    && !"米".equals(text) && !"米后".equals(text)
                    && !"公里".equals(text) && !"分钟".equals(text)) {
                // Name token right after a bare "到达".
                footArriveName = text;
                footArriveAt = now;
                footArrivePendingUntil = 0;
            } else if (footRoadPrefix != null
                    && footRoadName == null
                    && now - footRoadAt <= 800
                    && !"米".equals(text) && !"米后".equals(text)
                    && !"公里".equals(text) && !"分钟".equals(text)
                    && !"到达".equals(text)) {
                // Token right after 进入/沿 is the road name.
                footRoadName = text;
            }
        }
        // Keep a short window for the arrival branch ("到达" was
        // handled above, so this is only the name that follows
        // a road prefix or a bare arrival name).
        footLabelWindow.add(new FootLabelEvent(now, text));
        while (!footLabelWindow.isEmpty()
                && now - footLabelWindow.get(0).time > 1200) {
            footLabelWindow.remove(0);
        }
        if (footLabelWindow.size() > 64) {
            footLabelWindow.remove(0);
        }
        tryFootLabelBroadcast(now);
    }

    private int footPendingNumId;
    private long footArrivePendingUntil;

    private void tryFootLabelBroadcast(long now) {
        // Slots are long-lived while the nav screen is up: the
        // direction / road only change when the maneuver changes
        // (which re-renders the whole cluster), and the distance
        // counts down ~1/s — so only the distance needs a
        // freshness bound. Arrival wins over the instruction:
        // it means the trip is over.
        String title = null;
        if (footArriveName != null
                && now - footArriveAt <= 5000) {
            title = "到达" + footArriveName;
        } else if (footDir != null && footDistNum != null
                && now - footDistAt <= 5000) {
            String roadPart = "";
            boolean roadFresh = footRoadName != null
                    && now - footRoadAt <= 30_000;
            if (roadFresh) {
                roadPart = footRoadPrefix + footRoadName;
            }
            // The unit form ("米" vs "米后") only changes with
            // the maneuver, so the last-known form stays valid
            // until a new unit token arrives.
            if ("米后".equals(footDistUnit)) {
                title = footDistNum + "米后" + footDir + roadPart;
            } else if ("沿".equals(footRoadPrefix) && roadFresh) {
                title = "沿" + footRoadName + footDir
                        + footDistNum + "米";
            } else {
                title = footDir + footDistNum + "米" + roadPart;
            }
        }
        if (title == null) {
            return;
        }
        // v520: an accepted ETA must STAY plausible. As the
        // remaining minutes land (and then tick down with the
        // clock), a bogus cold-start capture stops agreeing
        // with now + remaining and is dropped here, so the
        // bottom line loses the wrong segment within seconds
        // instead of keeping it all trip; the genuine ETA
        // clock re-fills it via the capture paths above. A
        // real ETA stays plausible all trip: now + remaining
        // is anchored to it by construction.
        if (footEta != null && !footEtaPlausible(footEta)) {
            footEta = null;
            footEtaId = -1;
        }
        String content;
        if (footRemainKm != null && footRemainMin != null) {
            content = "剩" + footRemainKm + "公里 · "
                    + footRemainMin + "分钟";
        } else if (footRemainKm != null) {
            content = "剩" + footRemainKm + "公里";
        } else if (footRemainMin != null) {
            content = "剩" + footRemainMin + "分钟";
        } else {
            content = "步行导航";
        }
        if (footEta != null) {
            content = content + " · 预计" + footEta + "到达";
        }
        // v515: route total from the label stream itself —
        // walked + remaining (the v322 route hook's total is
        // usually silent). Both inputs required; the total
        // rides the broadcast so BubbleWindow can fill the
        // navi card's progress bar.
        int totalMeters = -1;
        if (footWalkedMeters >= 0 && footRemainKm != null) {
            try {
                totalMeters = footWalkedMeters
                        + (int) Math.round(Double.parseDouble(
                                footRemainKm) * 1000.0);
            } catch (Throwable ignored) {
            }
        }
        String shown = title + "|" + content + "|" + totalMeters;
        if (shown.equals(footLastBroadcast)) {
            return;
        }
        if (now - footLastBroadcastAt < 700) {
            return;
        }
        footLastBroadcast = shown;
        footLastBroadcastAt = now;
        footNavLastTick = now;
        try {
            Intent intent =
                    new Intent(NotificationService.ACTION_NAVI_INFO);
            intent.putExtra("bizType", "footnavi");
            intent.putExtra("title", title);
            intent.putExtra("content", content);
            intent.putExtra("status", 0);
            intent.putExtra("icon", 0);
            if (totalMeters > 0) {
                intent.putExtra("totalMeters", totalMeters);
            }
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "FootLabel \"" + title + "\" / \"" + content
                + "\" total=" + totalMeters);
    }

    private void onNaviInfo(JSONObject json) {
        if (json == null) {
            return;
        }
        // v314 (diagnostic): broadcast EVERY call, not just
        // footnavi/ridenavi — the capsule never updated while
        // mock-moving (user 2026-11-21 "胶囊没有变"), so we need to
        // see whether the engine calls this at all and with what
        // bizType. The capsule gate stays in BubbleWindow.
        String bizType = json.optString("bizType");
        String title = json.optString("title");
        String content = json.optString("content");
        int status = json.optInt("status");
        int icon = json.optInt("icon");
        try {
            Intent intent =
                    new Intent(NotificationService.ACTION_NAVI_INFO);
            intent.putExtra("bizType", bizType);
            intent.putExtra("title", title);
            intent.putExtra("content", content);
            intent.putExtra("status", status);
            intent.putExtra("icon", icon);
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
        // v318 (diagnostic): same remaining-data read on the
        // 1-second ride push, so the log shows whether the
        // values move as the ride progresses.
        broadcastRemain("notifyNaviInfo");
        Log.i(TAG, "AmapNaviInfo bizType=" + bizType
                + " title=" + title + " content=" + content
                + " status=" + status + " icon=" + icon);
    }
}
