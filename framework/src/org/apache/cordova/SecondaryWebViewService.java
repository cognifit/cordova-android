/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;

import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Owns the isolated WebView, asset server, subscriptions and metrics in :secondarywebview. */
public final class SecondaryWebViewService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService eventDelivery = Executors.newSingleThreadExecutor(r -> new Thread(r, "SecondaryEventDelivery"));
    private final ExecutorService sampleReads = Executors.newSingleThreadExecutor(r -> new Thread(r, "SecondarySamplePipe"));
    private SecondaryWebViewManager manager;
    private ISecondaryWebViewCallback callback;
    private String sessionId;
    private IBinder.DeathRecipient hostDeath;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT < 30 || !SecondaryWebViewProcessInit.configured || !(getPackageName() + ":secondarywebview").equals(Application.getProcessName())) {
            throw new IllegalStateException("Secondary WebView data-directory suffix was not installed before WebView startup");
        }
        manager = new SecondaryWebViewManager(this);
    }
    // Value-returning Binder calls may wait for main, but main only queues host events.
    // No host callback or pipe write may run synchronously from their main-thread work.
    private <T> T onMain(Callable<T> call) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return call.call();
        FutureTask<T> task = new FutureTask<>(call); main.post(task); return task.get(30, TimeUnit.SECONDS);
    }
    private static Bundle error(String code, String message) { Bundle result = new Bundle(); result.putString("errorCode", code); result.putString("errorMessage", message); return result; }
    private static Bundle ok() { return new Bundle(); }
    private void emit(JSONObject event) {
        ISecondaryWebViewCallback target = callback;
        if (target == null || sessionId == null || !sessionId.equals(event.optString("sessionId"))) return;
        String id = sessionId;
        String json = event.toString();
        eventDelivery.execute(() -> {
            try (ParcelFileDescriptor payload = SecondaryWebViewPipe.send(json)) { target.onEvent(id, payload); }
            catch (IOException | RemoteException e) { android.util.Log.w("SecondaryWebView", "Host event delivery failed", e); }
        });
    }
    private void finishSession() {
        if (manager != null) manager.destroy();
        if (callback != null && hostDeath != null) callback.asBinder().unlinkToDeath(hostDeath, 0);
        callback = null; hostDeath = null; sessionId = null;
        // Drain queued oneway events before process teardown. Never wait on this from main.
        eventDelivery.execute(() -> main.post(this::stopSelf));
    }
    private final ISecondaryWebViewService.Stub binder = new ISecondaryWebViewService.Stub() {
        @Override public boolean ping() { return SecondaryWebViewProcessInit.configured; }
        @Override public Bundle create(Bundle request, ISecondaryWebViewCallback events) {
            try {
                return onMain(() -> {
                    if (sessionId != null) return error("ALREADY_EXISTS", "Destroy the current secondary web view first");
                    if (events == null) return error("INVALID_CONFIG", "Host callback is required");
                    String id = request.getString("sessionId");
                    if (id == null || id.isEmpty()) return error("INVALID_CONFIG", "sessionId is required");
                    Display display = ((DisplayManager)getSystemService(DISPLAY_SERVICE)).getDisplay(request.getInt("displayId"));
                    callback = events; sessionId = id;
                    manager.setSubscriptionObserver(streams -> {
                        ISecondaryWebViewCallback target = callback;
                        if (target == null || !id.equals(sessionId)) return;
                        String[] names = new String[streams.size()]; int[] rates = new int[streams.size()], batches = new int[streams.size()];
                        int index = 0;
                        for (java.util.Map.Entry<String, SecondaryWebViewStreams.StreamInfo> entry : streams.entrySet()) {
                            names[index] = entry.getKey(); rates[index] = entry.getValue().rateHz; batches[index] = entry.getValue().batch ? 1 : 0; index++;
                        }
                        eventDelivery.execute(() -> { try { target.onSubscriptions(id, names, rates, batches); }
                            catch (RemoteException e) { android.util.Log.w("SecondaryWebView", "Subscriber mirror failed", e); } });
                    });
                    hostDeath = () -> main.post(SecondaryWebViewService.this::finishSession);
                    events.asBinder().linkToDeath(hostDeath, 0);
                    try {
                        if (request.getBoolean("webContentsDebuggingEnabled")) WebView.setWebContentsDebuggingEnabled(true);
                        String configText = request.getString("config");
                        SecondaryWebViewPipe.checkJsonDepth(configText);
                        manager.createIsolated(new JSONObject(configText), SecondaryWebViewService.this::emit,
                            request.getBinder("hostToken"), display, request.getInt("width"), request.getInt("height"), id, request.getLong("startedAtNs"));
                        SurfaceControlViewHost.SurfacePackage surface = manager.surfacePackage();
                        if (surface == null) throw new IllegalStateException("Surface package unavailable");
                        Bundle result = ok(); result.putParcelable("surfacePackage", surface); result.putString("sessionId", id); result.putString("storageIsolation", "origin"); return result;
                    } catch (Exception e) { finishSession(); if (e instanceof SecondaryWebViewManager.Failure) { SecondaryWebViewManager.Failure failure = (SecondaryWebViewManager.Failure)e; return error(failure.code, failure.getMessage()); } return error("CREATE_FAILED", e.toString()); }
                });
            } catch (Exception e) { return error("CREATE_FAILED", e.toString()); }
        }
        @Override public void destroy(String id) { main.post(() -> { if (id != null && id.equals(sessionId)) finishSession(); }); }
        @Override public Bundle navigate(String id, String url) {
            try { return onMain(() -> { if (!id.equals(sessionId)) return error("STALE_SESSION", "Session has ended"); try { manager.navigate(url); return ok(); } catch (SecondaryWebViewManager.Failure e) { return error(e.code, e.getMessage()); } }); }
            catch (Exception e) { return error("INTERNAL_ERROR", e.toString()); }
        }
        @Override public Bundle send(String id, ParcelFileDescriptor payload) {
            try {
                String text = SecondaryWebViewPipe.receive(payload);
                return onMain(() -> { if (!id.equals(sessionId)) return error("STALE_SESSION", "Session has ended"); try { manager.send(new JSONObject(text)); return ok(); } catch (SecondaryWebViewManager.Failure e) { return error(e.code, e.getMessage()); } catch (Exception e) { return error("INVALID_MESSAGE", e.toString()); } });
            } catch (Exception e) {
                String message = e.getMessage();
                String code = message != null && message.contains("INVALID_JSON") ? "INVALID_JSON"
                    : message != null && message.contains("MESSAGE_TOO_LARGE") ? "MESSAGE_TOO_LARGE"
                    : message != null && (message.contains("INVALID_MESSAGE") || message.contains("JSON_DEPTH_EXCEEDED")) ? "INVALID_MESSAGE" : "CHANNEL_ERROR";
                return error(code, e.toString());
            }
        }
        @Override public Bundle setTouchRegions(String id, String regions) {
            try { return onMain(() -> { if (!id.equals(sessionId)) return error("STALE_SESSION", "Session has ended"); try { SecondaryWebViewPipe.checkJsonDepth(regions); manager.setTouchRegions(new JSONObject(regions)); return ok(); } catch (SecondaryWebViewManager.Failure e) { return error(e.code, e.getMessage()); } catch (Exception e) { return error("INVALID_CONFIG", e.toString()); } }); }
            catch (Exception e) { return error("INTERNAL_ERROR", e.toString()); }
        }
        @Override public Bundle subscriptionControl(String id, ParcelFileDescriptor payload) { return send(id, payload); }
        @Override public ParcelFileDescriptor getMetrics(String id) {
            try { return onMain(() -> id.equals(sessionId) ? SecondaryWebViewPipe.send(manager.metrics().toString()) : null); }
            catch (Exception e) { return null; }
        }
        @Override public void resize(String id, int width, int height) { main.post(() -> { if (id.equals(sessionId)) manager.resizeIsolated(width, height); }); }
        @Override public void setBackgrounded(String id, boolean backgrounded) { main.post(() -> { if (id.equals(sessionId)) { if (backgrounded) manager.onPause(); else manager.onResume(); } }); }
        @Override public void pushSamples(String id, ParcelFileDescriptor payload) {
            if (payload == null) return;
            sampleReads.execute(() -> {
                try {
                    JSONObject frame = new JSONObject(SecondaryWebViewPipe.receive(payload));
                    main.post(() -> { if (id.equals(sessionId)) manager.acceptSamples(frame); });
                } catch (Exception e) { android.util.Log.w("SecondaryWebView", "Sample delivery failed", e); }
            });
        }
        @Override public void trimMemory(String id, int level) { main.post(() -> { if (id.equals(sessionId)) manager.onTrimMemory(level); }); }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        if (sessionId != null) finishSession();
        super.onDestroy();
        // This process is reserved for one incarnation. A later create starts with no retained WebView state.
        Process.killProcess(Process.myPid());
    }
}
