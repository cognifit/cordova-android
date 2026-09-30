/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Host-side session and input-region owner. All state mutations run on the activity UI thread. */
public final class SecondaryWebViewIsolatedController {
    public interface Result { void success(JSONObject value); void error(JSONObject value); }
    private final CordovaActivity activity;
    private final FrameLayout root;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "SecondaryWebViewBinder"); thread.setDaemon(true); return thread; });
    private final ExecutorService eventReads = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "SecondaryEventPipe"); thread.setDaemon(true); return thread; });
    private final ExecutorService metricReads = Executors.newCachedThreadPool(r -> { Thread thread = new Thread(r, "SecondaryMetricsPipe"); thread.setDaemon(true); return thread; });
    private final List<Consumer<Boolean>> connectionWaiters = new ArrayList<>();
    private final Intent serviceIntent;
    private ISecondaryWebViewService service;
    private ServiceConnection connection;
    private IBinder.DeathRecipient serviceDeath;
    private boolean bound, connecting;
    private String sessionId;
    private SecondaryWebViewManager.Listener listener;
    private SurfaceView surface;
    private View mainView;
    private Drawable mainBackground;
    private String touchMode = "none";
    private JSONArray touchRects = new JSONArray();
    private Result pendingCreate;
    private Result pendingDestroy;
    private long startedAtNs;
    private boolean destroying;
    private boolean teardownUnbound;
    private boolean unsafeBelowApi33;

    SecondaryWebViewIsolatedController(CordovaActivity activity, FrameLayout root) {
        this.activity = activity; this.root = root;
        serviceIntent = new Intent(activity, SecondaryWebViewService.class);
    }
    public boolean hasSession() { return sessionId != null; }
    public boolean isDestroying() { return destroying; }
    private boolean mainWebViewDebuggingEnabled() {
        String inspectable = activity.appView.getPreferences().getString("InspectableWebview", null);
        if (inspectable != null) return "true".equals(inspectable);
        return (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
    private static JSONObject error(String code, String message) { JSONObject value = new JSONObject(); try { value.put("code", code); value.put("message", message); } catch (JSONException ignored) { } return value; }
    private void connect(boolean experimental, Consumer<Boolean> continuation) {
        if (Build.VERSION.SDK_INT < (experimental ? 30 : 33) || !activity.getWindow().getDecorView().isHardwareAccelerated()
            || (!experimental && activity.getWindow().getRootSurfaceControl() == null)) { continuation.accept(false); return; }
        try { activity.getPackageManager().getServiceInfo(new ComponentName(activity, SecondaryWebViewService.class), 0); }
        catch (PackageManager.NameNotFoundException e) { continuation.accept(false); return; }
        if (service != null) { continuation.accept(true); return; }
        connectionWaiters.add(continuation);
        if (connecting) return;
        connecting = true;
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                ISecondaryWebViewService candidate = ISecondaryWebViewService.Stub.asInterface(binder);
                ServiceConnection activeConnection = this;
                io.execute(() -> {
                    boolean reachable = false;
                    try { reachable = candidate.ping(); } catch (RemoteException ignored) { }
                    final boolean ok = reachable;
                    activity.runOnUiThread(() -> {
                        if (connection != activeConnection || !bound) return;
                        if (ok) {
                            service = candidate;
                            serviceDeath = () -> activity.runOnUiThread(() -> onServiceDeath());
                            try { binder.linkToDeath(serviceDeath, 0); } catch (RemoteException e) { onServiceDeath(); return; }
                        }
                        connecting = false;
                        List<Consumer<Boolean>> callbacks = new ArrayList<>(connectionWaiters); connectionWaiters.clear();
                        for (Consumer<Boolean> callback : callbacks) callback.accept(ok);
                        if (!ok) disconnect();
                    });
                });
            }
            @Override public void onServiceDisconnected(ComponentName name) { onServiceDeath(); }
        };
        bound = activity.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) { connecting = false; List<Consumer<Boolean>> callbacks = new ArrayList<>(connectionWaiters); connectionWaiters.clear(); for (Consumer<Boolean> callback : callbacks) callback.accept(false); }
        else activity.getWindow().getDecorView().postDelayed(() -> {
            if (!connecting) return;
            connecting = false; List<Consumer<Boolean>> callbacks = new ArrayList<>(connectionWaiters); connectionWaiters.clear();
            for (Consumer<Boolean> callback : callbacks) callback.accept(false);
            disconnect();
        }, 5000);
    }
    public void probe(Consumer<Boolean> result) { connect(false, ok -> { result.accept(ok); if (sessionId == null) disconnect(); }); }
    private void disconnect() {
        if (service != null && serviceDeath != null) service.asBinder().unlinkToDeath(serviceDeath, 0);
        service = null; serviceDeath = null;
        if (bound && connection != null) { activity.unbindService(connection); activity.stopService(serviceIntent); }
        bound = false; connection = null;
    }
    private void onServiceDeath() {
        if (service == null && !bound && !connecting) return;
        disconnect(); // Unbind immediately so Android cannot auto-restart a crashed bound service.
        if (destroying && teardownUnbound) completeDestroy();
        touchMode = "none"; applyTouchRegion();
        if (sessionId != null && listener != null) {
            JSONObject event = new JSONObject(); try { event.put("sessionId", sessionId); event.put("type", "terminated"); event.put("detail", JSONObject.NULL); } catch (JSONException ignored) { }
            listener.onEvent(event);
        }
        if (pendingCreate != null) failCreate("CREATE_FAILED", "Secondary process exited during create");
    }
    private final ISecondaryWebViewCallback events = new ISecondaryWebViewCallback.Stub() {
        @Override public void onEvent(String id, ParcelFileDescriptor event) {
            eventReads.execute(() -> {
                try {
                    JSONObject value = new JSONObject(SecondaryWebViewPipe.receive(event));
                    activity.runOnUiThread(() -> { if (id.equals(sessionId) && listener != null) listener.onEvent(value); });
                } catch (Exception e) { android.util.Log.w("SecondaryWebView", "Invalid remote event", e); }
            });
        }
    };
    private FrameLayout.LayoutParams frame(JSONObject config) throws JSONException {
        Object value = config.opt("frame");
        if (value == null || "fill".equals(value)) return new FrameLayout.LayoutParams(-1, -1);
        JSONObject rect = config.getJSONObject("frame"); float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round((float)rect.getDouble("width") * density), height = Math.round((float)rect.getDouble("height") * density);
        if (width < 1 || height < 1) throw new JSONException("Invalid frame size");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = Math.round((float)rect.optDouble("x", 0) * density); params.topMargin = Math.round((float)rect.optDouble("y", 0) * density);
        return params;
    }
    public void create(JSONObject config, SecondaryWebViewManager.Listener listener, Result result) {
        if (sessionId != null || destroying) { result.error(error("ALREADY_EXISTS", "Destroy the current secondary web view first")); return; }
        if (activity.secondaryWebViews().hasSession()) { result.error(error("ALREADY_EXISTS", "Destroy the shared secondary web view first")); return; }
        if (!"below".equals(config.optString("zOrder", "below"))) { result.error(error("UNSUPPORTED_MODE", "Isolated mode currently supports zOrder below only")); return; }
        if (!config.optBoolean("hardwareAccelerated", true)) { result.error(error("UNSUPPORTED_MODE", "Hardware acceleration is required")); return; }
        unsafeBelowApi33 = Build.VERSION.SDK_INT >= 30 && Build.VERSION.SDK_INT < 33 && config.optBoolean("unsafeAllowIsolatedBelowApi33", false);
        if (unsafeBelowApi33) {
            android.util.Log.w("SecondaryWebView", "UNSUPPORTED, UNSAFE isolated mode enabled below API 33 for performance measurement; touch semantics are not honoured");
            android.util.Log.w("SecondaryWebView", "Experimental embedded surface receives all touches within its frame");
            android.util.Log.w("SecondaryWebView", "Experimental setTouchRegions is a no-op and returns success");
        }
        FrameLayout.LayoutParams placement;
        try { placement = frame(config); } catch (JSONException e) { result.error(error("INVALID_CONFIG", e.toString())); return; }
        startedAtNs = SystemClock.elapsedRealtimeNanos();
        sessionId = UUID.randomUUID().toString(); this.listener = listener; pendingCreate = result;
        mainView = activity.appView.getView(); mainBackground = mainView.getBackground(); mainView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        surface = new SurfaceView(activity);
        surface.setZOrderOnTop(unsafeBelowApi33);
        surface.getHolder().setFormat(config.optBoolean("opaque", false) ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT);
        surface.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> applyTouchRegion());
        int index = root.indexOfChild(mainView);
        root.addView(surface, index, placement);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            boolean started;
            @Override public void surfaceCreated(SurfaceHolder holder) { if (!started) { started = true; connect(unsafeBelowApi33, ok -> { if (!ok) { failCreate("UNSUPPORTED_MODE", "Isolated service is unreachable"); return; } beginRemoteCreate(config); }); } }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                applyTouchRegion();
                ISecondaryWebViewService target = service; String id = sessionId;
                if (target != null && id != null) io.execute(() -> { try { target.resize(id, width, height); } catch (RemoteException ignored) { } });
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { }
        });
        applyTouchRegion();
    }
    private void beginRemoteCreate(JSONObject config) {
        if (surface == null || service == null || sessionId == null) return;
        if (config.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) { failCreate("INVALID_CONFIG", "Configuration exceeds 64 KiB"); return; }
        IBinder token = surface.getHostToken();
        if (token == null || surface.getDisplay() == null) { failCreate("UNSUPPORTED_MODE", "Surface host token or display is unavailable"); return; }
        Bundle request = new Bundle(); request.putBinder("hostToken", token); request.putInt("displayId", surface.getDisplay().getDisplayId());
        request.putInt("width", Math.max(1, surface.getWidth())); request.putInt("height", Math.max(1, surface.getHeight()));
        request.putString("sessionId", sessionId); request.putString("config", config.toString()); request.putLong("startedAtNs", startedAtNs);
        request.putBoolean("webContentsDebuggingEnabled", mainWebViewDebuggingEnabled());
        ISecondaryWebViewService target = service; String id = sessionId;
        io.execute(() -> {
            Bundle answer;
            try { answer = target.create(request, events); } catch (Exception e) { answer = errorBundle("CREATE_FAILED", e.toString()); }
            final Bundle response = answer;
            activity.runOnUiThread(() -> {
                if (!id.equals(sessionId)) return;
                if (response.containsKey("errorCode")) { failCreate(response.getString("errorCode"), response.getString("errorMessage")); return; }
                SurfaceControlViewHost.SurfacePackage surfacePackage = response.getParcelable("surfacePackage");
                if (surfacePackage == null) { failCreate("CREATE_FAILED", "Remote surface package is missing"); return; }
                surface.setChildSurfacePackage(surfacePackage);
                if (pendingCreate != null) { JSONObject value = new JSONObject(); try { value.put("sessionId", id); value.put("storageIsolation", "origin"); } catch (JSONException ignored) { } pendingCreate.success(value); pendingCreate = null; }
            });
        });
    }
    private static Bundle errorBundle(String code, String message) { Bundle result = new Bundle(); result.putString("errorCode", code); result.putString("errorMessage", message); return result; }
    private void failCreate(String code, String message) { Result pending = pendingCreate; pendingCreate = null; if (pending != null) pending.error(error(code, message)); destroy(); }
    private void applyTouchRegion() {
        if (Build.VERSION.SDK_INT < 33 || surface == null) return;
        android.view.AttachedSurfaceControl control = activity.getWindow().getRootSurfaceControl();
        if (control == null) return;
        View decor = activity.getWindow().getDecorView();
        Region touchable = new Region(0, 0, decor.getWidth(), decor.getHeight());
        int[] windowPoint = new int[2]; surface.getLocationInWindow(windowPoint);
        int[] rootPoint = new int[2]; root.getLocationInWindow(rootPoint);
        Rect bounds = new Rect(windowPoint[0], windowPoint[1], windowPoint[0] + surface.getWidth(), windowPoint[1] + surface.getHeight());
        if ("all".equals(touchMode)) touchable.op(bounds, Region.Op.DIFFERENCE);
        else if ("rects".equals(touchMode)) {
            float density = activity.getResources().getDisplayMetrics().density;
            for (int i = 0; i < touchRects.length(); i++) {
                JSONObject rect = touchRects.optJSONObject(i); if (rect == null) continue;
                Rect area = new Rect(rootPoint[0] + Math.round((float)rect.optDouble("x") * density), rootPoint[1] + Math.round((float)rect.optDouble("y") * density),
                    rootPoint[0] + Math.round((float)(rect.optDouble("x") + rect.optDouble("width")) * density), rootPoint[1] + Math.round((float)(rect.optDouble("y") + rect.optDouble("height")) * density));
                area.intersect(bounds); touchable.op(area, Region.Op.DIFFERENCE);
            }
        }
        control.setTouchableRegion(touchable);
    }
    public void setTouchRegions(JSONObject regions, Result result) {
        if (sessionId == null) { result.error(error("NOT_CREATED", "No secondary web view")); return; }
        if (unsafeBelowApi33) { result.success(new JSONObject()); return; }
        String mode = regions.optString("mode", "none");
        if (!"none".equals(mode) && !"all".equals(mode) && !"rects".equals(mode)) { result.error(error("INVALID_CONFIG", "Invalid touch mode")); return; }
        JSONArray rects = regions.optJSONArray("rects"); if (rects == null) rects = new JSONArray();
        for (int i = 0; i < rects.length(); i++) { JSONObject r = rects.optJSONObject(i); if (r == null || r.optDouble("width", -1) < 0 || r.optDouble("height", -1) < 0) { result.error(error("INVALID_CONFIG", "Invalid touch rectangle")); return; } }
        String id = sessionId; ISecondaryWebViewService target = service; JSONArray nextRects = rects;
        if (target == null) { result.error(error("TERMINATED", "Secondary process has exited")); return; }
        io.execute(() -> {
            Bundle answer;
            try { answer = target.setTouchRegions(id, regions.toString()); } catch (RemoteException e) { answer = errorBundle("TERMINATED", e.toString()); }
            Bundle response = answer;
            activity.runOnUiThread(() -> {
                if (!id.equals(sessionId)) { result.error(error("STALE_SESSION", "Session has ended")); return; }
                if (response.containsKey("errorCode")) result.error(error(response.getString("errorCode"), response.getString("errorMessage")));
                else { touchMode = mode; touchRects = nextRects; applyTouchRegion(); result.success(new JSONObject()); }
            });
        });
    }
    public void send(JSONObject envelope, Result result) {
        if (envelope == null) { result.error(error("INVALID_MESSAGE", "Envelope is required")); return; }
        if (sessionId == null || service == null) { result.error(error("TERMINATED", "Secondary process is unavailable")); return; }
        String id = sessionId; ISecondaryWebViewService target = service;
        io.execute(() -> {
            Bundle answer;
            try (ParcelFileDescriptor payload = SecondaryWebViewPipe.send(envelope.toString())) { answer = target.send(id, payload); }
            catch (Exception e) { String message = e.getMessage();
                String code = message != null && message.contains("INVALID_JSON") ? "INVALID_JSON"
                    : message != null && message.contains("MESSAGE_TOO_LARGE") ? "MESSAGE_TOO_LARGE" : "CHANNEL_ERROR";
                answer = errorBundle(code, e.toString()); }
            Bundle response = answer;
            activity.runOnUiThread(() -> { if (!id.equals(sessionId)) { result.error(error("STALE_SESSION", "Session has ended")); return; } if (response.containsKey("errorCode")) result.error(error(response.getString("errorCode"), response.getString("errorMessage"))); else result.success(new JSONObject()); });
        });
    }
    public void metrics(Result result) {
        if (sessionId == null || service == null) { result.error(error("TERMINATED", "Secondary process is unavailable")); return; }
        String id = sessionId; ISecondaryWebViewService target = service;
        io.execute(() -> {
            try {
                ParcelFileDescriptor payload = target.getMetrics(id);
                if (payload == null) throw new IOException("No metrics response");
                metricReads.execute(() -> {
                    try { JSONObject value = new JSONObject(SecondaryWebViewPipe.receive(payload)); activity.runOnUiThread(() -> { if (id.equals(sessionId)) result.success(value); else result.error(error("STALE_SESSION", "Session has ended")); }); }
                    catch (Exception e) { activity.runOnUiThread(() -> result.error(error("CHANNEL_ERROR", e.toString()))); }
                });
            } catch (Exception e) { activity.runOnUiThread(() -> result.error(error("CHANNEL_ERROR", e.toString()))); }
        });
    }
    public void onPause() { ISecondaryWebViewService target = service; String id = sessionId; if (target != null && id != null) io.execute(() -> { try { target.setBackgrounded(id, true); } catch (RemoteException ignored) { } }); }
    public void onResume() { ISecondaryWebViewService target = service; String id = sessionId; if (target != null && id != null) io.execute(() -> { try { target.setBackgrounded(id, false); } catch (RemoteException ignored) { } }); }
    public void onTrimMemory(int level) { ISecondaryWebViewService target = service; String id = sessionId; if (target != null && id != null) io.execute(() -> { try { target.trimMemory(id, level); } catch (RemoteException ignored) { } }); }
    public void destroy() { destroy(null); }
    private void completeDestroy() {
        Result done = pendingDestroy; pendingDestroy = null;
        destroying = false; teardownUnbound = false;
        if (done != null) done.success(new JSONObject());
    }
    public void destroy(Result result) {
        if (destroying) { if (result != null) result.error(error("ALREADY_EXISTS", "Secondary web view teardown is in progress")); return; }
        Result creating = pendingCreate; pendingCreate = null;
        if (creating != null) creating.error(error("DESTROYED", "Secondary web view destroyed during create"));
        String old = sessionId; sessionId = null;
        ISecondaryWebViewService target = service;
        if (old != null && target != null) {
            destroying = true;
            teardownUnbound = false;
            pendingDestroy = result;
            io.execute(() -> { try { target.destroy(old); } catch (RemoteException ignored) { }
                activity.runOnUiThread(() -> {
                    if (bound && connection != null) activity.unbindService(connection);
                    activity.stopService(serviceIntent);
                    bound = false; connection = null;
                    teardownUnbound = true;
                    if (service == null) { completeDestroy(); return; }
                    activity.getWindow().getDecorView().postDelayed(() -> {
                        if (!destroying) return;
                        Result waiting = pendingDestroy; pendingDestroy = null; destroying = false; teardownUnbound = false; disconnect();
                        if (waiting != null) waiting.error(error("TEARDOWN_FAILED", "Secondary process did not exit"));
                    }, 5000);
                }); });
        }
        if (Build.VERSION.SDK_INT >= 33) { android.view.AttachedSurfaceControl control = activity.getWindow().getRootSurfaceControl(); if (control != null) control.setTouchableRegion(null); }
        if (surface != null) { root.removeView(surface); surface = null; }
        if (mainView != null) { mainView.setBackground(mainBackground); mainView = null; mainBackground = null; }
        touchMode = "none"; touchRects = new JSONArray();
        unsafeBelowApi33 = false;
        if (target == null || old == null) { disconnect(); if (result != null) result.success(new JSONObject()); }
        if (listener != null && old != null) { JSONObject event = new JSONObject(); try { event.put("sessionId", old); event.put("type", "destroyed"); event.put("detail", JSONObject.NULL); } catch (JSONException ignored) { } listener.onEvent(event); }
        listener = null;
    }
}
