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
