package com.chenan.flymebobo;

import android.app.Application;
import android.content.Intent;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

// v320: Amap bus live navigation (user 2026-11-21 "公交语音实况导航",
// then "不要语音播报" — visual live capsule only, no TTS).
//
// Amap has NO bus navigation engine (no AjxBusNaviPage, no bus
// notifyNaviInfo, no bus ongoing notification). What exists:
//  - Real-time arrival BEFORE boarding: BusRealTimeResponse
//    (line + station + per-bus arrival seconds / station_left /
//    x,y), polled by BusCommuteRealTimeRefreshManager (30s, 10s
//    when close) and delivered to BusCommuteMainPageImpl
//    .onRequestCallback(i==3) via the BusCommuteResult singleton.
//  - Static route station list: BusPath.segmentlist -> BusSegment
//    (busname / startname / endname / passdepotname space-separated
//    / passdepotcoord comma-separated lng,lat pairs).
//
// This hook reads both from the singleton when onRequestCallback
// fires with i==3 and broadcasts them to SystemUI. BubbleWindow
// shows mode A (waiting: "xx路 还有N站，约M分钟到") and, once
// boarding is detected (user position + bus position converge and
// move along the route at bus speed), mode B (next stop /
// remaining stations from the station list + live position).
//
// The Amap process cannot write the module's FileLog, so this
// side logs to logcat; SystemUI FileLogs the receipt.
public final class BusNaviHook {
    private static final String TAG = "flymebobo";
    private final XposedInterface bridge;
    private final Application app;
    private boolean done;
    private String lastRouteKey;

    public BusNaviHook(XposedInterface xposed, Application app) {
        this.bridge = xposed;
        this.app = app;
    }

    public void install() {
        if (done) {
            return;
        }
        try {
            Class<?> page = Class.forName(
                    "com.autonavi.bundle.routecommute.bus.details"
                            + ".BusCommuteMainPageImpl",
                    false, app.getClassLoader());
            Method cb = page.getDeclaredMethod("onRequestCallback",
                    int.class, int.class, String.class);
            bridge.hook(cb).intercept(
                    new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain)
                        throws Throwable {
                    Object result = chain.proceed();
                    try {
                        int i = (Integer) chain.getArg(0);
                        if (i == 3) {
                            onBusData();
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            done = true;
            Log.i(TAG, "BusNaviHook: installed (onRequestCallback)");
            sendHookStatus("installed", null);
        } catch (Throwable th) {
            Log.i(TAG, "BusNaviHook install failed: " + th);
            sendHookStatus("failed", String.valueOf(th));
        }
    }

    private void sendHookStatus(String status, String error) {
        try {
            Intent intent = new Intent(
                    NotificationService.ACTION_BUS_HOOK_STATUS);
            intent.putExtra("status", status);
            if (error != null) {
                intent.putExtra("error", error);
            }
            app.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    // Read the BusCommuteResult singleton and broadcast the live
    // bus data (mode A) and the route station list (mode B).
    private void onBusData() {
        try {
            Class<?> resultCls = Class.forName(
                    "com.autonavi.bundle.routecommute.bus.manager"
                            + ".BusCommuteResult",
                    false, app.getClassLoader());
            Method aMethod = resultCls.getDeclaredMethod("a");
            aMethod.setAccessible(true);
            Object result = aMethod.invoke(null);
            if (result == null) {
                return;
            }
            broadcastRoute(result, resultCls);
            broadcastRealtime(result, resultCls);
        } catch (Throwable th) {
            Log.i(TAG, "BusNaviHook onBusData failed: " + th);
        }
    }

    // Mode B station list: BusPath.segmentlist -> first segment
    // with a busname. Stations = startname + passdepotname (space
    // separated) + endname; coords = passdepotcoord (comma
    // separated lng,lat pairs, one pair per intermediate station).
    // Sent once per distinct route (busname+start+end).
    private void broadcastRoute(Object result, Class<?> resultCls) {
        try {
            Method bMethod = resultCls.getDeclaredMethod("b");
            bMethod.setAccessible(true);
            Object busPath = bMethod.invoke(result);
            if (busPath == null) {
                return;
            }
            Field segField = busPath.getClass()
                    .getDeclaredField("segmentlist");
            segField.setAccessible(true);
            Object segList = segField.get(busPath);
            if (!(segList instanceof ArrayList)) {
                return;
            }
            ArrayList<?> segments = (ArrayList<?>) segList;
            for (Object seg : segments) {
                if (seg == null) {
                    continue;
                }
                String busname = strField(seg, "busname");
                if (busname == null || busname.isEmpty()) {
                    continue;
                }
                String startName = strField(seg, "startname");
                String endName = strField(seg, "endname");
                String direction = strField(seg, "directionName");
                String passNames = strField(seg, "passdepotname");
                String passCoords = strField(seg, "passdepotcoord");
                String routeKey = busname + "|" + startName + "|"
                        + endName;
                if (routeKey.equals(lastRouteKey)) {
                    return;
                }
                lastRouteKey = routeKey;
                Intent intent = new Intent(
                        NotificationService.ACTION_BUS_ROUTE);
                intent.putExtra("busname", busname);
                intent.putExtra("direction", direction);
                intent.putExtra("startName", startName);
                intent.putExtra("endName", endName);
                intent.putExtra("passNames", passNames);
                intent.putExtra("passCoords", passCoords);
                app.sendBroadcast(intent);
                Log.i(TAG, "BusRoute bus=" + busname + " dir="
                        + direction + " " + startName + "->" + endName
                        + " passNames=" + passNames);
                return;
            }
        } catch (Throwable th) {
            Log.i(TAG, "BusNaviHook broadcastRoute failed: " + th);
        }
    }

    // Mode A real-time arrival: BusRealTimeResponse.buses -> each
    // line's nearest bus (trip[0]): station_left (stations left),
    // arrival (seconds), x/y (bus position). One broadcast per
    // line; BubbleWindow keeps a capsule per line key.
    private void broadcastRealtime(Object result, Class<?> resultCls) {
        try {
            Field dField = resultCls.getDeclaredField("d");
            dField.setAccessible(true);
            Object rt = dField.get(result);
            if (rt == null) {
                return;
            }
            Field busesField = rt.getClass()
                    .getDeclaredField("buses");
            busesField.setAccessible(true);
            Object buses = busesField.get(rt);
            if (!(buses instanceof ArrayList)) {
                return;
            }
            ArrayList<?> lines = (ArrayList<?>) buses;
            for (Object lineInfo : lines) {
                if (lineInfo == null) {
                    continue;
                }
                String line = strField(lineInfo, "line");
                String station = strField(lineInfo, "station");
                Field tripField = lineInfo.getClass()
                        .getDeclaredField("trip");
                tripField.setAccessible(true);
                Object trip = tripField.get(lineInfo);
                if (!(trip instanceof ArrayList)) {
                    continue;
                }
                ArrayList<?> trips = (ArrayList<?>) trip;
                if (trips.isEmpty() || trips.get(0) == null) {
                    continue;
                }
                Object bus = trips.get(0);
                String stationLeft = strField(bus, "station_left");
                String arrival = strField(bus, "arrival");
                String busX = strField(bus, "x");
                String busY = strField(bus, "y");
                Intent intent = new Intent(
                        NotificationService.ACTION_BUS_INFO);
                intent.putExtra("line", line);
                intent.putExtra("station", station);
                intent.putExtra("stationLeft", stationLeft);
                intent.putExtra("arrival", arrival);
                intent.putExtra("busX", busX);
                intent.putExtra("busY", busY);
                app.sendBroadcast(intent);
                Log.i(TAG, "BusInfo line=" + line + " station="
                        + station + " left=" + stationLeft
                        + " arrival=" + arrival + " bus=(" + busX
                        + "," + busY + ")");
            }
        } catch (Throwable th) {
            Log.i(TAG, "BusNaviHook broadcastRealtime failed: " + th);
        }
    }

    private static String strField(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(obj);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
