/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.RectF;
import android.content.Context;
import android.net.Uri;
import android.os.IBinder;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewRenderProcess;
import androidx.webkit.WebViewRenderProcessClient;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.ScriptHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import android.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One optional, plugin-free web view owned by the main Cordova activity. All mutations run on the UI thread. */
public final class SecondaryWebViewManager {
    private static final String TAG = "SecondaryWebView";
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    private static final int PIPE_ASSET_THRESHOLD = 1024 * 1024;
    private static final int PROTOCOL_VERSION = 1;
    private static final String CONTENT_POLICY = "default-src 'self' data: blob: 'unsafe-inline' 'unsafe-eval'; connect-src 'self'; frame-src 'self'; form-action 'self'; object-src 'none'";
    private static final long[] HIST_LIMITS_NS = {1000000L, 2000000L, 4000000L, 8000000L, 16000000L, 33000000L, 100000000L};
    private static volatile Semaphore processReadPermits;
    private static int processReadLimit;
    private static int processReadUsers;
    private boolean ownsProcessLimit;
    private static volatile long processReadTimeoutMs = 5000;
    private static volatile long processReadWatchdogMs = 60000;
    private static final ScheduledExecutorService readWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "SecondaryAssetReadWatchdog");
        thread.setDaemon(true);
        return thread;
    });

    public interface Listener { void onEvent(JSONObject event); }
    interface SubscriptionObserver { void changed(java.util.Set<String> names); }
    public static final class Failure extends Exception {
        public final String code;
        Failure(String code, String message) { super(message); this.code = code; }
        public JSONObject json() { JSONObject j = new JSONObject(); try { j.put("code", code); j.put("message", getMessage()); } catch (JSONException ignored) { } return j; }
    }
    private static final class Root {
        final File path;
        final String kind;
        Root(File path, String kind) { this.path = path; this.kind = kind; }
    }
    private final CordovaActivity activity;
    private final Context context;
    private final boolean isolatedRuntime;
    private final FrameLayout rootLayout;
    private SurfaceControlViewHost surfaceHost;
    private IBinder surfaceToken;
    private Display surfaceDisplay;
    private int surfaceWidth, surfaceHeight;
    private String requestedSessionId;
    private long isolatedStartNs;
    private TouchContainer container;
    private volatile WebView webView;
    private View mainView;
    private ViewGroup.LayoutParams mainParams;
    private int mainIndex;
    private volatile String sessionId;
    private volatile String originHost;
    private Listener listener;
    private final List<Root> roots = new ArrayList<>();
    private volatile List<Root> rootsSnapshot = java.util.Collections.emptyList();
    private volatile Semaphore readPermits;
    private volatile long readTimeoutMs;
    private volatile long readWatchdogMs;
    private volatile int assetCacheMaxAgeSeconds;
    private long createdAt, lastPong;
    private int heartbeatMs, version, missedHeartbeats;
    private volatile boolean backgrounded;
    private volatile boolean perRequest, countersEnabled = true, followSymlinks, assetErrorsEnabled;
    private volatile String entryUrl;
    private final java.util.concurrent.atomic.AtomicBoolean entryFailureEmitted = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.Set<String> assetErrorKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong assetErrorWindow = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger assetErrorCount = new java.util.concurrent.atomic.AtomicInteger();
    private boolean ready, nativeHangDetection;
    private volatile boolean dead;
    private boolean frameScheduled, unresponsive;
    private ScriptHandler documentScript;
    private final java.util.concurrent.atomic.AtomicLongArray metrics = new java.util.concurrent.atomic.AtomicLongArray(15);
    private final java.util.concurrent.atomic.AtomicLongArray[] histograms = new java.util.concurrent.atomic.AtomicLongArray[5];
    private final String[] pendingIds = new String[64];
    private final long[] pendingTimes = new long[64];
    private int pendingCursor;
    private static final int MAX_TRACES = 256;
    private final java.util.Queue<JSONObject> traces = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger traceCount = new java.util.concurrent.atomic.AtomicInteger();
    private int touchMode;
    private List<RectF> touchRects = new ArrayList<>();
    private final Map<String, Subscription> subscriptions = new HashMap<>();
    private final Object sampleLock = new Object();
    private final Map<String, Object> queuedLatest = new HashMap<>();
    private final Map<String, List<Object>> queuedBatch = new HashMap<>();
    private boolean sampleDrainPosted;
    private volatile java.util.Set<String> subscribedStreams = java.util.Collections.emptySet();
    private volatile java.util.Set<String> batchStreams = java.util.Collections.emptySet();
    private SubscriptionObserver subscriptionObserver;
    private final java.util.Set<InputStream> openStreams = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable heartbeat;

    private static final class TrackedWebView extends WebView {
        private MotionEvent lastTouch;
        TrackedWebView(Context context) { super(context); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (lastTouch != null) lastTouch.recycle();
            lastTouch = MotionEvent.obtain(event);
            boolean handled = super.dispatchTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) { lastTouch.recycle(); lastTouch = null; }
            return handled;
        }
        void cancelActiveGesture() {
            if (lastTouch == null) return;
            MotionEvent cancel = MotionEvent.obtain(lastTouch); cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel); cancel.recycle(); lastTouch.recycle(); lastTouch = null;
        }
    }

    SecondaryWebViewManager(CordovaActivity activity, FrameLayout rootLayout) {
        this.activity = activity; this.context = activity; this.rootLayout = rootLayout; this.isolatedRuntime = false;
        for (int i = 0; i < histograms.length; i++) histograms[i] = new java.util.concurrent.atomic.AtomicLongArray(8);
        SecondaryWebViewStreams.register(this);
    }
    SecondaryWebViewManager(Context context) {
        this.activity = null; this.context = context; this.rootLayout = null; this.isolatedRuntime = true;
        for (int i = 0; i < histograms.length; i++) histograms[i] = new java.util.concurrent.atomic.AtomicLongArray(8);
        SecondaryWebViewStreams.register(this);
    }
    public boolean hasSession() { return sessionId != null; }
    boolean hasStreamSubscriber(String streamName) { return webView != null && !backgrounded && !dead && subscribedStreams.contains(streamName); }
    void rejectStreamSample(String streamName) { handler.post(() -> { if (hasStreamSubscriber(streamName)) emit("channelError", "INVALID_JSON"); }); }
    void setSubscriptionObserver(SubscriptionObserver observer) { subscriptionObserver = observer; observer.changed(subscribedStreams); }
    public String createIsolated(JSONObject config, Listener listener, IBinder token, Display display, int width, int height, String id, long startedAtNs) throws Failure {
        if (!isolatedRuntime || Build.VERSION.SDK_INT < 30 || token == null || display == null || width < 1 || height < 1) throw new Failure("UNSUPPORTED_MODE", "Remote surface is unavailable");
        surfaceToken = token; surfaceDisplay = display; surfaceWidth = width; surfaceHeight = height; requestedSessionId = id; isolatedStartNs = startedAtNs;
        return create(config, listener);
    }
    public SurfaceControlViewHost.SurfacePackage surfacePackage() { return surfaceHost == null ? null : surfaceHost.getSurfacePackage(); }
    public void resizeIsolated(int width, int height) { if (surfaceHost != null && width > 0 && height > 0) surfaceHost.relayout(width, height); }
    public void navigate(String url) throws Failure {
        if (webView == null) throw new Failure("TERMINATED", "Secondary renderer has terminated");
        Uri target = Uri.parse(url);
        if (!allowedURL(target)) throw new Failure("PATH_DENIED", "Navigation outside allowedRoots");
        webView.loadUrl(url);
    }
    public JSONObject capabilities() {
        JSONObject o = new JSONObject();
        try {
            // A separate renderer requires a separate process and SurfaceControlViewHost embedding.
            o.put("processIsolation", false);
            o.put("storageIsolation", "origin");
            o.put("nativeHangDetection", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE));
            o.put("binaryMessages", binaryAvailable());
            o.put("assetReadLimiter", true);
            o.put("maxProtocolVersion", PROTOCOL_VERSION);
        } catch (JSONException ignored) { }
        return o;
    }
    private boolean binaryAvailable() { return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER) && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE); }
    public String create(JSONObject config, Listener listener) throws Failure {
        if (sessionId != null) throw new Failure("ALREADY_EXISTS", "Destroy the current secondary web view first");
        String url = config.optString("url", "");
        if (url.isEmpty()) throw new Failure("INVALID_CONFIG", "url is required");
        String processMode = config.optString("processIsolation", "shared");
        if (isolatedRuntime ? !"isolated".equals(processMode) : !"shared".equals(processMode)) throw new Failure("UNSUPPORTED_MODE", "Requested process mode is unavailable in this backend");
        String storage = config.optString("storageIsolation", "origin");
        if ("none".equals(storage)) throw new Failure("UNSUPPORTED_MODE", "Secondary content always uses a distinct origin");
        if (!"origin".equals(storage) && !"ephemeral".equals(storage)) throw new Failure("INVALID_CONFIG", "storageIsolation must be origin or ephemeral");
        if (!config.optBoolean("hardwareAccelerated", true) || (!isolatedRuntime && !activity.getWindow().getDecorView().isHardwareAccelerated())) throw new Failure("UNSUPPORTED_MODE", "Hardware acceleration is required");
        if (!"above".equals(config.optString("zOrder", "below")) && !"below".equals(config.optString("zOrder", "below"))) throw new Failure("INVALID_CONFIG", "Invalid zOrder");
        JSONArray offered = config.optJSONArray("protocolVersions");
        if (config.has("protocolVersions") && offered == null) throw new Failure("INVALID_CONFIG", "protocolVersions must be an array");
        if (offered != null) { boolean found = false; for (int i = 0; i < offered.length(); i++) found |= offered.optInt(i) == PROTOCOL_VERSION; if (!found) throw new Failure("PROTOCOL_MISMATCH", "No common protocol version"); }
        readTimeoutMs = config.optLong("assetReadTimeoutMs", 5000);
        if (readTimeoutMs < 1 || readTimeoutMs > 60000) throw new Failure("INVALID_CONFIG", "assetReadTimeoutMs must be between 1 and 60000");
        readWatchdogMs = config.optLong("assetReadWatchdogMs", 60000);
        if (readWatchdogMs < 1000 || readWatchdogMs > 300000) throw new Failure("INVALID_CONFIG", "assetReadWatchdogMs must be between 1000 and 300000");
        assetCacheMaxAgeSeconds = config.optInt("assetCacheMaxAgeSeconds", 3600);
        if (assetCacheMaxAgeSeconds < 0 || assetCacheMaxAgeSeconds > 31536000) throw new Failure("INVALID_CONFIG", "assetCacheMaxAgeSeconds must be between 0 and 31536000");
        heartbeatMs = config.optInt("heartbeatMs", 2000);
        if (heartbeatMs < 0) throw new Failure("INVALID_CONFIG", "heartbeatMs must be nonnegative");
        JSONObject telemetry = config.optJSONObject("telemetry");
        if (config.has("telemetry") && telemetry == null) throw new Failure("INVALID_CONFIG", "telemetry must be an object");
        perRequest = telemetry != null && telemetry.optBoolean("perRequest", false);
        assetErrorsEnabled = telemetry != null && telemetry.optBoolean("assetErrors", false);
        countersEnabled = telemetry == null || telemetry.optBoolean("counters", true);
        followSymlinks = config.optBoolean("followSymlinks", false);
        rootsSnapshot = java.util.Collections.emptyList();
        roots.clear();
        File entry = parseEntry(url);
        JSONArray declared = config.optJSONArray("allowedRoots");
        if (config.has("allowedRoots") && declared == null) throw new Failure("INVALID_CONFIG", "allowedRoots must be an array");
        if (declared == null) {
            String kind = url.startsWith("file:///android_asset/") || url.startsWith("/android_asset/") || !url.startsWith("file:") && !new File(url).isAbsolute() ? "bundle" : "file";
            roots.add(new Root(canonical(entry.getParentFile()), kind));
        } else {
            for (int i = 0; i < declared.length(); i++) {
                JSONObject r = declared.optJSONObject(i);
                if (r == null || !"bundle".equals(r.optString("kind")) && !"file".equals(r.optString("kind"))) throw new Failure("INVALID_CONFIG", "Each allowed root needs path and kind");
                String declaredPath = r.optString("path");
                if ("bundle".equals(r.optString("kind")) && !declaredPath.startsWith("/android_asset/")) declaredPath = "/android_asset/" + declaredPath.replaceFirst("^/+", "");
                if ("file".equals(r.optString("kind")) && !new File(declaredPath).isAbsolute()) throw new Failure("INVALID_CONFIG", "File roots must be absolute");
                File root = canonical(new File(declaredPath));
                if (!config.optBoolean("followSymlinks", false) && !root.equals(new File(declaredPath).getAbsoluteFile().toPath().normalize().toFile())) throw new Failure("INVALID_CONFIG", "Root is a symlink");
                roots.add(new Root(root, r.optString("kind")));
            }
        }
        if (roots.isEmpty()) throw new Failure("INVALID_CONFIG", "allowedRoots cannot be empty");
        rootsSnapshot = java.util.Collections.unmodifiableList(new ArrayList<>(roots));
        int entryRoot = -1;
        for (int i = 0; i < roots.size(); i++) if (within(entry, roots.get(i).path)) { entryRoot = i; break; }
        if (entryRoot < 0) throw new Failure("PATH_DENIED", "Entry document is outside allowedRoots");
        FrameLayout.LayoutParams frame = frame(config.opt("frame"));
        int color = parseColor(config.optString("backgroundColor", "transparent"));
        if (config.optBoolean("opaque", false) && Color.alpha(color) != 255) throw new Failure("INVALID_CONFIG", "opaque requires an opaque backgroundColor");
        int readLimit = parseReadLimit(config, "maxParallelAssetReads");
        readPermits = readLimit == 0 ? null : new Semaphore(readLimit, true);
        int processLimit = parseReadLimit(config, "processWideMaxParallelAssetReads");
        synchronized (SecondaryWebViewManager.class) {
            if (processLimit > 0 && processReadPermits != null && (processReadLimit != processLimit || processReadTimeoutMs != readTimeoutMs || processReadWatchdogMs != readWatchdogMs)) throw new Failure("INVALID_CONFIG", "Process-wide read limit or timeout is already set");
            if (processLimit > 0 && processReadPermits == null) { processReadLimit = processLimit; processReadTimeoutMs = readTimeoutMs; processReadWatchdogMs = readWatchdogMs; processReadPermits = new Semaphore(processLimit, true); }
            if (processLimit > 0) { processReadUsers++; ownsProcessLimit = true; }
        }
        this.listener = listener;
        this.sessionId = isolatedRuntime ? requestedSessionId : UUID.randomUUID().toString();
        Root originRoot = roots.get(entryRoot);
        this.originHost = UUID.nameUUIDFromBytes((originRoot.kind + ":" + originRoot.path).getBytes(StandardCharsets.UTF_8)) + ".secondary.local";
        this.createdAt = isolatedRuntime ? isolatedStartNs : SystemClock.elapsedRealtimeNanos();
        this.backgrounded = false; this.ready = false; this.dead = false; this.version = 0;
        this.entryFailureEmitted.set(false);
        assetErrorKeys.clear(); assetErrorWindow.set(0); assetErrorCount.set(0);
        for (int i = 0; i < metrics.length(); i++) metrics.set(i, 0);
        for (java.util.concurrent.atomic.AtomicLongArray histogram : histograms) for (int i = 0; i < histogram.length(); i++) histogram.set(i, 0);
        java.util.Arrays.fill(pendingIds, null); pendingCursor = 0;
        traces.clear(); traceCount.set(0);
        this.touchMode = 0; this.touchRects = new ArrayList<>();
        try {
        if (!isolatedRuntime) {
            mainView = activity.appView.getView();
            mainParams = mainView.getLayoutParams();
            mainIndex = rootLayout.indexOfChild(mainView);
            rootLayout.removeView(mainView);
            container = new TouchContainer(activity);
            rootLayout.addView(container, mainIndex, new FrameLayout.LayoutParams(-1, -1));
        }
        webView = isolatedRuntime ? new TrackedWebView(context) : new WebView(context);
        configureWebView();
        webView.setBackgroundColor(color);
        if (isolatedRuntime) { surfaceHost = new SurfaceControlViewHost(context, surfaceDisplay, surfaceToken); surfaceHost.setView(webView, surfaceWidth, surfaceHeight); }
        else if ("below".equals(config.optString("zOrder", "below"))) { container.addView(webView, frame); container.addView(mainView, mainParams); }
        else { container.addView(mainView, mainParams); container.addView(webView, frame); }
        webView.addJavascriptInterface(new Bridge(sessionId), "_secondaryNative");
        if (binaryAvailable()) {
            final String id = sessionId;
            WebViewCompat.addWebMessageListener(webView, "_secondaryBinary", java.util.Collections.singleton("https://" + originHost),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame || !id.equals(sessionId) || message.getType() != WebMessageCompat.TYPE_ARRAY_BUFFER) return;
                    byte[] packet = message.getArrayBuffer();
                    handler.post(() -> inboundPacket(id, packet));
                });
        }
        webView.setWebViewClient(new Client(sessionId));
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) documentScript = WebViewCompat.addDocumentStartJavaScript(webView, script(sessionId), java.util.Collections.singleton("https://" + originHost));
        nativeHangDetection = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE);
        if (nativeHangDetection) WebViewCompat.setWebViewRenderProcessClient(webView, new WebViewRenderProcessClient() {
            @Override public void onRenderProcessUnresponsive(WebView view, WebViewRenderProcess renderer) { if (webView == view && !unresponsive) { unresponsive = true; addMetric(11, 1); emit("unresponsive", null); } }
            @Override public void onRenderProcessResponsive(WebView view, WebViewRenderProcess renderer) { if (webView == view && unresponsive) { unresponsive = false; emit("responsive", null); } }
        });
        String relative = roots.get(entryRoot).path.toPath().relativize(entry.toPath()).toString().replace(File.separatorChar, '/');
        if (isolatedRuntime) setMetric(14, SystemClock.elapsedRealtimeNanos() - isolatedStartNs);
        entryUrl = "https://" + originHost + "/r/" + entryRoot + "/" + Uri.encode(relative, "/");
        webView.loadUrl(entryUrl);
        startHeartbeat();
        return sessionId;
        } catch (RuntimeException e) {
            this.listener = null;
            destroy();
            throw new Failure("CREATE_FAILED", e.toString());
        }
    }
    private int parseReadLimit(JSONObject config, String key) throws Failure {
        Object value = config.opt(key);
        if (value == null || value == JSONObject.NULL || "unbounded".equals(value)) return 0;
        if (!(value instanceof Number) || ((Number)value).doubleValue() < 1 || ((Number)value).doubleValue() > Integer.MAX_VALUE || ((Number)value).doubleValue() % 1 != 0) throw new Failure("INVALID_CONFIG", key + " must be a positive integer or unbounded");
        return ((Number)value).intValue();
    }
    private File parseEntry(String url) throws Failure {
        if (url.contains("://") && !url.startsWith("file://")) throw new Failure("INVALID_CONFIG", "url must be a local document");
        String value = url;
        if (url.startsWith("file:///android_asset/")) value = "/android_asset/" + url.substring("file:///android_asset/".length());
        else if (url.startsWith("file://")) value = Uri.parse(url).getPath();
        else if (!new File(url).isAbsolute()) value = "/android_asset/" + url.replaceFirst("^/+", "");
        File raw = new File(value);
        File file = canonical(raw);
        if (!followSymlinks && !file.equals(raw.getAbsoluteFile().toPath().normalize().toFile())) throw new Failure("PATH_DENIED", "Entry document is a symlink");
        if (file.getName().isEmpty()) throw new Failure("INVALID_CONFIG", "url must name a document");
        return file;
    }
    private File canonical(File file) throws Failure { try { return file.getCanonicalFile(); } catch (IOException e) { throw new Failure("INVALID_CONFIG", e.toString()); } }
    private static boolean within(File file, File root) { return file.equals(root) || file.toPath().startsWith(root.toPath()); }
    private int parseColor(String value) throws Failure {
        if ("transparent".equals(value)) return Color.TRANSPARENT;
        if (!value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) throw new Failure("INVALID_CONFIG", "backgroundColor must be transparent, #RRGGBB or #RRGGBBAA");
        long hex = Long.parseLong(value.substring(1), 16);
        if (value.length() == 7) return Color.rgb((int)(hex >> 16) & 255, (int)(hex >> 8) & 255, (int)hex & 255);
        return Color.argb((int)hex & 255, (int)(hex >> 24) & 255, (int)(hex >> 16) & 255, (int)(hex >> 8) & 255);
    }
    private FrameLayout.LayoutParams frame(Object value) throws Failure {
        if (value == null || "fill".equals(value)) return new FrameLayout.LayoutParams(-1, -1);
        if (!(value instanceof JSONObject)) throw new Failure("INVALID_CONFIG", "Invalid frame");
        JSONObject f = (JSONObject)value;
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.round((float)f.optDouble("width", -1) * density), h = Math.round((float)f.optDouble("height", -1) * density);
        if (w < 0 || h < 0) throw new Failure("INVALID_CONFIG", "Invalid frame size");
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.leftMargin = Math.round((float)f.optDouble("x", 0) * density); p.topMargin = Math.round((float)f.optDouble("y", 0) * density);
        return p;
    }
    @SuppressLint("SetJavaScriptEnabled") private void configureWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }
    public void setTouchRegions(JSONObject value) throws Failure {
        if (sessionId == null) throw new Failure("NOT_CREATED", "No secondary web view");
        if (webView == null) throw new Failure("TERMINATED", "Secondary renderer has terminated");
        String mode = value.optString("mode", "none");
        int nextMode = "none".equals(mode) ? 0 : "rects".equals(mode) ? 1 : "all".equals(mode) ? 2 : -1;
        if (nextMode < 0) throw new Failure("INVALID_CONFIG", "Invalid touch mode");
        List<RectF> next = new ArrayList<>(); JSONArray rects = value.optJSONArray("rects");
        if (nextMode == 1 && rects != null) for (int i = 0; i < rects.length(); i++) {
            JSONObject r = rects.optJSONObject(i); if (r == null) throw new Failure("INVALID_CONFIG", "Invalid rect");
            float x = (float)r.optDouble("x"), y = (float)r.optDouble("y"), w = (float)r.optDouble("width"), h = (float)r.optDouble("height");
            if (w < 0 || h < 0) throw new Failure("INVALID_CONFIG", "Invalid rect size");
            next.add(new RectF(x, y, x + w, y + h));
        }
        if (container != null) container.cancelActiveGesture();
        if (isolatedRuntime && webView instanceof TrackedWebView) ((TrackedWebView)webView).cancelActiveGesture();
        touchRects = next; touchMode = nextMode;
    }
    private final class TouchContainer extends FrameLayout {
        private final android.util.SparseArray<View> pointerTargets = new android.util.SparseArray<>();
        private MotionEvent lastEvent;
        private long mainDownTime, secondaryDownTime;
        TouchContainer(CordovaActivity context) { super(context); }
        void cancelActiveGesture() {
            if (lastEvent != null) {
                sendToTarget(lastEvent, mainView, MotionEvent.ACTION_CANCEL);
                sendToTarget(lastEvent, webView, MotionEvent.ACTION_CANCEL);
                lastEvent.recycle(); lastEvent = null;
            }
            pointerTargets.clear();
            mainDownTime = secondaryDownTime = 0;
        }
        private View targetAt(MotionEvent event, int index) {
            float density = getResources().getDisplayMetrics().density;
            float x = event.getX(index) / density, y = event.getY(index) / density;
            boolean hit = touchMode == 2;
            if (touchMode == 1) for (RectF rect : touchRects) if (rect.contains(x, y)) { hit = true; break; }
            return hit && webView != null ? webView : mainView;
        }
        private boolean sendToTarget(MotionEvent source, View target, int overrideAction) {
            if (target == null) return false;
            int count = 0, changedIndex = -1;
            int changedPointer = source.getPointerId(source.getActionIndex());
            for (int i = 0; i < source.getPointerCount(); i++) if (pointerTargets.get(source.getPointerId(i)) == target) count++;
            if (count == 0) return false;
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
            MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
            int next = 0;
            for (int i = 0; i < source.getPointerCount(); i++) {
                int pointerId = source.getPointerId(i);
                if (pointerTargets.get(pointerId) != target) continue;
                properties[next] = new MotionEvent.PointerProperties(); source.getPointerProperties(i, properties[next]);
                coordinates[next] = new MotionEvent.PointerCoords(); source.getPointerCoords(i, coordinates[next]);
                if (pointerId == changedPointer) changedIndex = next;
                next++;
            }
            int action = overrideAction >= 0 ? overrideAction : source.getActionMasked();
            if (overrideAction < 0 && (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)) {
                if (changedIndex < 0) action = MotionEvent.ACTION_MOVE;
                else if (count == 1) action = action == MotionEvent.ACTION_POINTER_DOWN ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP;
                else action |= changedIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
            }
            long downTime = target == mainView ? mainDownTime : secondaryDownTime;
            MotionEvent shifted = MotionEvent.obtain(downTime == 0 ? source.getDownTime() : downTime,
                source.getEventTime(), action, count, properties, coordinates, source.getMetaState(),
                source.getButtonState(), source.getXPrecision(), source.getYPrecision(),
                source.getDeviceId(), source.getEdgeFlags(), source.getSource(), source.getFlags());
            shifted.offsetLocation(-target.getLeft(), -target.getTop());
            boolean handled = target.dispatchTouchEvent(shifted);
            shifted.recycle();
            return handled;
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                cancelActiveGesture();
                View target = targetAt(event, 0);
                pointerTargets.put(event.getPointerId(0), target);
                if (target == mainView) mainDownTime = event.getEventTime(); else secondaryDownTime = event.getEventTime();
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                int index = event.getActionIndex();
                View target = targetAt(event, index);
                boolean firstForTarget = true;
                for (int i = 0; i < pointerTargets.size(); i++) if (pointerTargets.valueAt(i) == target) { firstForTarget = false; break; }
                pointerTargets.put(event.getPointerId(index), target);
                if (firstForTarget) { if (target == mainView) mainDownTime = event.getEventTime(); else secondaryDownTime = event.getEventTime(); }
            }
            if (lastEvent != null) lastEvent.recycle();
            lastEvent = MotionEvent.obtain(event);
            boolean handled = sendToTarget(event, mainView, -1) | sendToTarget(event, webView, -1);
            if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
                View target = pointerTargets.get(event.getPointerId(event.getActionIndex()));
                pointerTargets.remove(event.getPointerId(event.getActionIndex()));
                boolean any = false;
                for (int i = 0; i < pointerTargets.size(); i++) if (pointerTargets.valueAt(i) == target) { any = true; break; }
                if (!any) { if (target == mainView) mainDownTime = 0; else secondaryDownTime = 0; }
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { pointerTargets.clear(); lastEvent.recycle(); lastEvent = null; }
            return handled;
        }
    }
    private final class Client extends WebViewClient {
        final String id;
        Client(String id) { this.id = id; }
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return denyNavigation(request.getUrl()); }
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return denyNavigation(Uri.parse(url)); }
        private boolean denyNavigation(Uri url) { if (allowedURL(url)) return false; addMetric(5, 1); Log.w(TAG, "Denied secondary navigation: " + url); return true; }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try { return read(request.getUrl()); }
            catch (RuntimeException | OutOfMemoryError e) {
                Log.e(TAG, "Secondary asset interceptor failed", e);
                return failureResponse(String.valueOf(request.getUrl()), 500, "Internal Server Error");
            }
        }
        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { if (id.equals(sessionId)) ready = false; }
        @Override public void onPageFinished(WebView view, String url) { if (id.equals(sessionId) && documentScript == null && allowedURL(Uri.parse(url))) view.evaluateJavascript(script(id), null); }
        @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) { if (id.equals(sessionId) && request.isForMainFrame() && request.getUrl().toString().equals(entryUrl)) reportEntryFailure(id, error.toString()); }
        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) { if (id.equals(sessionId) && request.isForMainFrame() && request.getUrl().toString().equals(entryUrl) && response.getStatusCode() >= 400) reportEntryFailure(id, response.getReasonPhrase()); }
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) { if (id.equals(sessionId)) { dead = true; subscriptions.clear(); refreshStreamNames(); clearQueuedSamples(); addMetric(8, 1); emit("terminated", null); closeOpenStreams(); if (container != null) { container.cancelActiveGesture(); container.removeView(view); } if (surfaceHost != null) { surfaceHost.release(); surfaceHost = null; } if (documentScript != null) { documentScript.remove(); documentScript = null; } if (binaryAvailable()) WebViewCompat.removeWebMessageListener(view, "_secondaryBinary"); if (nativeHangDetection) WebViewCompat.setWebViewRenderProcessClient(view, null); view.removeJavascriptInterface("_secondaryNative"); view.setWebViewClient(null); view.destroy(); webView = null; } return true; }
    }
    private boolean allowedURL(Uri url) {
        final String currentSession = sessionId, currentHost = originHost;
        return allowedURL(url, currentSession, currentHost);
    }
    private boolean allowedURL(Uri url, String currentSession, String currentHost) {
        if (url == null || !"https".equals(url.getScheme()) || currentSession == null || currentHost == null || !currentHost.equals(url.getHost())) return false;
        String path = url.getPath(); if (path == null) return false;
        String[] parts = path.split("/", 4); if (parts.length < 4 || !"r".equals(parts[1])) return false;
        int index; try { index = Integer.parseInt(parts[2]); } catch (NumberFormatException e) { return false; }
        List<Root> currentRoots = rootsSnapshot;
        if (index < 0 || index >= currentRoots.size()) return false;
        Root selected = currentRoots.get(index);
        try {
            File root = selected.path;
            File lexical = new File(root, parts[3]).getAbsoluteFile().toPath().normalize().toFile();
            File canonical = lexical.getCanonicalFile();
            return within(lexical, root) && within(canonical, root) && (followSymlinks || lexical.equals(canonical));
        } catch (IOException e) { return false; }
    }
    private static String relativeAssetPath(String requested) {
        String path = Uri.parse(requested).getPath();
        if (path == null) return requested;
        String[] parts = path.split("/", 4);
        return parts.length == 4 && "r".equals(parts[1]) ? parts[3] : path;
    }
    private WebResourceResponse denied(String requested) { addMetric(5, 1); trace(requested, 403, 0); Log.w(TAG, "Denied secondary asset (path: " + relativeAssetPath(requested) + "): " + requested); return failureResponse(requested, 403, "Forbidden"); }
    private void reportEntryFailure(String id, String reason) {
        if (id != null && id.equals(sessionId) && entryFailureEmitted.compareAndSet(false, true)) handler.post(() -> { if (id.equals(sessionId)) emit("loadFailed", reason); });
    }
    private void reportAssetError(String requested, int status, String reason) {
        if (!assetErrorsEnabled) return;
        String rawPath = Uri.parse(requested).getPath();
        String[] parts = rawPath == null ? new String[0] : rawPath.split("/", 4);
        int rootIndex = -1;
        if (parts.length == 4 && "r".equals(parts[1])) try { rootIndex = Integer.parseInt(parts[2]); } catch (NumberFormatException ignored) { }
        String path = relativeAssetPath(requested);
        String key = rootIndex + "\u0000" + path + "\u0000" + status + "\u0000" + reason;
        long now = SystemClock.elapsedRealtime();
        long window = assetErrorWindow.get();
        if (now - window >= 1000 && assetErrorWindow.compareAndSet(window, now)) {
            assetErrorKeys.clear(); assetErrorCount.set(0);
        }
        if (!assetErrorKeys.add(key)) return;
        if (assetErrorCount.incrementAndGet() > 10) { assetErrorKeys.remove(key); return; }
        String id = sessionId;
        final int index = rootIndex;
        handler.post(() -> {
            if (id == null || !id.equals(sessionId)) return;
            JSONObject detail = new JSONObject();
            try { detail.put("sessionId", id); detail.put("path", path); detail.put("rootIndex", index); detail.put("status", status); detail.put("reason", reason); } catch (JSONException ignored) { }
            emit("assetError", detail);
        });
    }
    private WebResourceResponse failureResponse(String requested, int status, String reason) {
        reportAssetError(requested, status, reason);
        String path = Uri.parse(requested).getPath();
        if (path != null && entryUrl != null && path.equals(Uri.parse(entryUrl).getPath())) reportEntryFailure(sessionId, reason);
        return response("text/plain", status, reason, null);
    }
    private WebResourceResponse response(String mime, int status, String reason, InputStream stream) {
        String encoding = mime.startsWith("text/") || "application/javascript".equals(mime) || "application/json".equals(mime) || "image/svg+xml".equals(mime) ? "UTF-8" : null;
        java.util.Map<String, String> headers = new java.util.HashMap<>(); headers.put("Cache-Control", status == 200 ? "private, max-age=" + assetCacheMaxAgeSeconds : "no-store"); headers.put("Content-Security-Policy", CONTENT_POLICY);
        headers.put("Content-Type", encoding == null ? mime : mime + "; charset=utf-8");
        if (stream == null) {
            byte[] body = status == 200 ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
            headers.put("Content-Length", Integer.toString(body.length));
            stream = new java.io.ByteArrayInputStream(body);
        }
        return new WebResourceResponse(mime, encoding, status, reason, headers, stream);
    }
    private InputStream pipeCompletedAsset(File temporary) throws IOException {
        android.os.ParcelFileDescriptor[] pair = android.os.ParcelFileDescriptor.createPipe();
        try {
            Thread writer = new Thread(() -> {
                try (InputStream source = new FileInputStream(temporary);
                     OutputStream sink = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(pair[1])) {
                    byte[] chunk = new byte[64 * 1024]; int length;
                    while ((length = source.read(chunk)) != -1) sink.write(chunk, 0, length);
                } catch (IOException e) { Log.w(TAG, "Large asset pipe closed", e); }
                finally { temporary.delete(); }
            }, "SecondaryAssetPipe");
            writer.setDaemon(true); writer.start();
            return new android.os.ParcelFileDescriptor.AutoCloseInputStream(pair[0]);
        } catch (RuntimeException e) {
            try { pair[0].close(); pair[1].close(); } catch (IOException ignored) { }
            temporary.delete(); throw new IOException("Could not start large asset pipe", e);
        }
    }
    private WebResourceResponse read(Uri uri) {
        final String readSession = sessionId, readHost = originHost;
        if (readSession == null || !allowedURL(uri, readSession, readHost)) return denied(String.valueOf(uri));
        String path = uri.getPath(); String[] parts = path.split("/", 4);
        if (parts.length < 4) return denied(path);
        int index; try { index = Integer.parseInt(parts[2]); } catch (NumberFormatException e) { return denied(path); }
        List<Root> currentRoots = rootsSnapshot;
        if (index < 0 || index >= currentRoots.size()) return denied(path);
        Root root = currentRoots.get(index);
        File candidate;
        try {
            candidate = new File(root.path, parts[3]).getCanonicalFile();
            File lexical = new File(root.path, parts[3]).getAbsoluteFile().toPath().normalize().toFile();
            if (!within(candidate, root.path) || !within(lexical, root.path) || (!followSymlinks && !candidate.equals(lexical))) return denied(path);
        } catch (IOException e) { return denied(path); }
        long waitStart = SystemClock.elapsedRealtimeNanos();
        boolean local = false, process = false;
        final Semaphore localSemaphore = readPermits, processSemaphore = processReadPermits;
        try {
            if (localSemaphore != null) { local = localSemaphore.tryAcquire(); if (!local) return timeout(path); }
            if (processSemaphore != null) { process = processSemaphore.tryAcquire(); if (!process) return timeout(path); }
            if (!readSession.equals(sessionId) || dead) return response("text/plain", 410, "Gone", null);
            addMetric(3, SystemClock.elapsedRealtimeNanos() - waitStart); sampleHistogram(3, SystemClock.elapsedRealtimeNanos() - waitStart);
            InputStream input;
            if ("bundle".equals(root.kind)) {
                String assetPath = candidate.getPath().replaceFirst("^/android_asset/", "");
                if (assetPath.startsWith("/")) return denied(path);
                input = context.getAssets().open(assetPath);
            } else input = new FileInputStream(candidate);
            long opened = SystemClock.elapsedRealtimeNanos();
            final boolean releaseLocal = local, releaseProcess = process;
            local = process = false;
            final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
            final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            final java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
            final AtomicReference<ScheduledFuture<?>> watchdog = new AtomicReference<>();
            InputStream counted = new FilterInputStream(input) {
                private IOException watchdogFailure() { return new IOException("Secondary asset read watchdog expired: " + path); }
                private IOException watchdogFailure(Throwable cause) { return new IOException("Secondary asset read watchdog expired: " + path, cause); }
                @Override public int read() throws IOException {
                    if (expired.get()) throw watchdogFailure();
                    if (closed.get()) return -1;
                    try {
                        int n = super.read();
                        if (expired.get()) throw watchdogFailure();
                        if (n < 0) close();
                        return n;
                    } catch (IllegalStateException e) {
                        if (expired.get()) throw watchdogFailure(e);
                        if (closed.get()) return -1;
                        throw new IOException(e);
                    } catch (IOException e) {
                        if (expired.get()) throw watchdogFailure(e);
                        throw e;
                    }
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (expired.get()) throw watchdogFailure();
                    if (len == 0) return 0;
                    if (closed.get()) return -1;
                    try {
                        int n = super.read(b, off, len);
                        if (expired.get()) throw watchdogFailure();
                        if (n < 0) close();
                        return n;
                    } catch (IllegalStateException e) {
                        if (expired.get()) throw watchdogFailure(e);
                        if (closed.get()) return -1;
                        throw new IOException(e);
                    } catch (IOException e) {
                        if (expired.get()) throw watchdogFailure(e);
                        throw e;
                    }
                }
                @Override public int available() throws IOException {
                    if (expired.get()) throw watchdogFailure();
                    if (closed.get()) return 0;
                    int count;
                    try {
                        count = super.available();
                    } catch (IOException e) {
                        if (expired.get()) throw watchdogFailure(e);
                        throw e;
                    }
                    if (expired.get()) throw watchdogFailure();
                    return count;
                }
                @Override public void close() throws IOException {
                    if (!closed.compareAndSet(false, true)) return;
                    // Claim the read before super.close() can block while the watchdog fires.
                    boolean ours = released.compareAndSet(false, true);
                    ScheduledFuture<?> scheduled = watchdog.getAndSet(null);
                    if (scheduled != null) scheduled.cancel(false);
                    try { super.close(); } finally {
                        long duration = SystemClock.elapsedRealtimeNanos() - opened;
                        addMetric(2, duration); sampleHistogram(2, duration); addMetric(4, 1); openStreams.remove(this);
                        if (ours) { trace(path, 200, duration); if (releaseLocal) localSemaphore.release(); if (releaseProcess) processSemaphore.release(); }
                        if (perRequest) Log.d(TAG, "Asset read " + path);
                    }
                }
            };
            openStreams.add(counted);
            if (!readSession.equals(sessionId) || dead) {
                counted.close(); return response("text/plain", 410, "Gone", null);
            }
            Runnable expireRead = () -> {
                if (released.compareAndSet(false, true)) {
                    expired.set(true);
                    Log.e(TAG, "Secondary asset read watchdog expired (path: " + relativeAssetPath(path) + "): " + path);
                    addMetric(6, 1); trace(path, 504, SystemClock.elapsedRealtimeNanos() - opened);
                    if (releaseLocal) localSemaphore.release();
                    if (releaseProcess) processSemaphore.release();
                    android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> { try { counted.close(); } catch (IOException e) { Log.w(TAG, "Closing stalled asset read", e); } });
                }
            };
            watchdog.set(readWatchdog.schedule(expireRead, readWatchdogMs, TimeUnit.MILLISECONDS));
            if (closed.get()) {
                ScheduledFuture<?> scheduled = watchdog.getAndSet(null);
                if (scheduled != null) scheduled.cancel(false);
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            File temporary = null;
            OutputStream spool = null;
            boolean completed = false;
            try (InputStream stream = counted) {
                byte[] chunk = new byte[64 * 1024];
                int length;
                while ((length = stream.read(chunk)) != -1) {
                    if (spool == null && body.size() + length > PIPE_ASSET_THRESHOLD) {
                        temporary = File.createTempFile("secondary-asset-", ".tmp", context.getCacheDir());
                        spool = new FileOutputStream(temporary);
                        body.writeTo(spool);
                        body = null;
                    }
                    if (spool == null) body.write(chunk, 0, length);
                    else spool.write(chunk, 0, length);
                }
                if (spool != null) { spool.close(); spool = null; }
                completed = true;
            } catch (IOException e) {
                if (expired.get()) return failureResponse(path, 504, "Gateway Timeout");
                throw e;
            } finally {
                if (spool != null) try { spool.close(); } catch (IOException e) { Log.w(TAG, "Closing large asset spool", e); }
                if (!completed && temporary != null) temporary.delete();
            }
            if (!readSession.equals(sessionId) || dead) { if (temporary != null) temporary.delete(); return response("text/plain", 410, "Gone", null); }
            return response(mime(candidate.getName()), 200, "OK", temporary == null
                ? new java.io.ByteArrayInputStream(body.toByteArray()) : pipeCompletedAsset(temporary));
        } catch (IOException e) { int status = e instanceof java.io.FileNotFoundException ? 404 : 500; String reason = status == 404 ? "Not Found" : "Internal Server Error"; Log.e(TAG, "Asset read failed (path: " + relativeAssetPath(path) + "): " + path, e); addMetric(6, 1); trace(path, status, 0); return failureResponse(path, status, reason); }
        finally { if (local) localSemaphore.release(); if (process) processSemaphore.release(); }
    }
    private WebResourceResponse timeout(String path) { addMetric(7, 1); trace(path, 503, 0); Log.e(TAG, "Asset read permit unavailable (path: " + relativeAssetPath(path) + "): " + path); return failureResponse(path, 503, "Service Unavailable"); }
    private String mime(String name) {
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript";
        if (name.endsWith(".wasm")) return "application/wasm";
        String extension = MimeTypeMap.getFileExtensionFromUrl(name);
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? "application/octet-stream" : type;
    }
    private final class Bridge {
        final String id;
        Bridge(String id) { this.id = id; }
        @JavascriptInterface public void post(String json) {
            if (json == null) { handler.post(() -> emit("channelError", "INVALID_MESSAGE")); return; }
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
                String code;
                try { code = validEnvelope(new JSONObject(json)) ? "MESSAGE_TOO_LARGE" : "INVALID_MESSAGE"; }
                catch (JSONException | RuntimeException e) { code = "INVALID_MESSAGE"; }
                String result = code;
                handler.post(() -> emit("channelError", result));
                return;
            }
            handler.post(() -> inbound(id, json));
        }
    }
    private void inbound(String id, String json) { inbound(id, json, json.getBytes(StandardCharsets.UTF_8).length); }
    private void inbound(String id, String json, int wireBytes) {
        if (!id.equals(sessionId) || dead || backgrounded) return;
        try {
            SecondaryWebViewPipe.checkJsonDepth(json);
            JSONObject envelope = new JSONObject(json);
            if (!id.equals(envelope.optString("sessionId"))) return;
            if ("handshake".equals(envelope.optString("name"))) {
                if (ready) return;
                JSONArray offered = envelope.optJSONArray("payload"); boolean compatible = false;
                if (offered != null) for (int i = 0; i < offered.length(); i++) compatible |= offered.optInt(i) == PROTOCOL_VERSION;
                if (!compatible || !"req".equals(envelope.optString("kind")) || envelope.optString("id").isEmpty()) { emit("loadFailed", "PROTOCOL_MISMATCH"); webView.setVisibility(View.INVISIBLE); return; }
                version = PROTOCOL_VERSION; ready = true; setMetric(0, SystemClock.elapsedRealtimeNanos() - createdAt); sampleHistogram(0, SystemClock.elapsedRealtimeNanos() - createdAt);
                JSONObject answer = new JSONObject(); answer.put("v", version); answer.put("id", envelope.optString("id")); answer.put("kind", "res"); answer.put("name", "handshake"); answer.put("payload", version);
                try { send(answer); } catch (Failure ignored) { } emit("ready", null); webView.postVisualStateCallback(1, new WebView.VisualStateCallback() { @Override public void onComplete(long requestId) { if (id.equals(sessionId) && !dead) { setMetric(1, SystemClock.elapsedRealtimeNanos() - createdAt); sampleHistogram(1, SystemClock.elapsedRealtimeNanos() - createdAt); emit("firstPaint", null); } } });
                return;
            }
            Object wireVersion = envelope.opt("v");
            if (!ready || !(wireVersion instanceof Number) || ((Number)wireVersion).doubleValue() != version) return;
            if (!validEnvelope(envelope)) { emit("channelError", "INVALID_MESSAGE"); return; }
            if ("__secondaryChannelError".equals(envelope.optString("name"))) {
                String code = envelope.optString("payload");
                if ("evt".equals(envelope.optString("kind")) && "0".equals(envelope.optString("id")) && ("INVALID_JSON".equals(code) || "MESSAGE_TOO_LARGE".equals(code) || "INVALID_MESSAGE".equals(code))) emit("channelError", code);
                else emit("channelError", "INVALID_MESSAGE");
                return;
            }
            if ("res".equals(envelope.optString("kind"))) {
                String responseId = envelope.optString("id");
                for (int i = 0; i < pendingIds.length; i++) if (responseId.equals(pendingIds[i])) {
                    long duration = SystemClock.elapsedRealtimeNanos() - pendingTimes[i]; pendingIds[i] = null;
                    addMetric(12, duration); addMetric(13, 1); sampleHistogram(4, duration); break;
                }
            }
            if ("subscribe".equals(envelope.optString("name")) && "req".equals(envelope.optString("kind"))) {
                JSONObject options = envelope.optJSONObject("payload");
                Object rawRate = options == null ? null : options.opt("rateHz");
                boolean validRate = rawRate == null || rawRate instanceof Number && ((Number)rawRate).doubleValue() >= 1 && ((Number)rawRate).doubleValue() <= 60 && ((Number)rawRate).doubleValue() == Math.floor(((Number)rawRate).doubleValue());
                int rate = rawRate instanceof Number ? ((Number)rawRate).intValue() : 60;
                String coalesce = options == null ? "latest" : options.optString("coalesce", "latest");
                JSONObject answer = new JSONObject(); answer.put("kind", "res"); answer.put("id", envelope.optString("id")); answer.put("name", "subscribe");
                if (options == null || options.optString("streamName").isEmpty() || !validRate || rate < 1 || rate > 60 || !("latest".equals(coalesce) || "batch".equals(coalesce))) answer.put("err", new JSONObject().put("code", "INVALID_SUBSCRIPTION"));
                else { String subscriptionId = UUID.randomUUID().toString(); Subscription sub = new Subscription(); sub.streamName = options.optString("streamName"); sub.rateHz = rate; sub.batch = "batch".equals(coalesce); subscriptions.put(subscriptionId, sub); refreshStreamNames(); answer.put("payload", subscriptionId); }
                try { send(answer); } catch (Failure ignored) { } return;
            }
            if ("unsubscribe".equals(envelope.optString("name"))) { subscriptions.remove(envelope.optString("payload")); refreshStreamNames(); return; }
            if ("heartbeat".equals(envelope.optString("name"))) { lastPong = SystemClock.elapsedRealtime(); if (missedHeartbeats > 0) { missedHeartbeats = 0; emit("responsive", null); } return; }
            addMetric(9, 1); addMetric(10, wireBytes);
            JSONObject event = new JSONObject(); event.put("sessionId", id); event.put("type", "message"); event.put("detail", envelope); if (listener != null) listener.onEvent(event);
        } catch (JSONException | IOException e) { emit("channelError", "INVALID_MESSAGE"); }
    }
    private void inboundPacket(String id, byte[] packet) {
        if (!id.equals(sessionId)) return;
        if (packet == null || packet.length < 4) { emit("channelError", "INVALID_MESSAGE"); return; }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(packet);
            int length = buffer.getInt();
            if (length < 2 || length > buffer.remaining()) { emit("channelError", "INVALID_MESSAGE"); return; }
            byte[] header = new byte[length]; buffer.get(header);
            String headerText = new String(header, StandardCharsets.UTF_8);
            if (packet.length > MAX_MESSAGE_BYTES) {
                try { emit("channelError", validEnvelope(new JSONObject(headerText)) ? "MESSAGE_TOO_LARGE" : "INVALID_MESSAGE"); }
                catch (JSONException | RuntimeException e) { emit("channelError", "INVALID_MESSAGE"); }
                return;
            }
            SecondaryWebViewPipe.checkJsonDepth(headerText);
            JSONObject envelope = new JSONObject(headerText);
            if (envelope.optBoolean("binary")) {
                byte[] payload = new byte[buffer.remaining()]; buffer.get(payload);
                envelope.remove("binary");
                envelope.put("payload", new JSONObject().put("__secondaryArrayBuffer", Base64.encodeToString(payload, Base64.NO_WRAP)));
            }
            inbound(id, envelope.toString(), packet.length);
        } catch (JSONException | IOException e) { emit("channelError", "INVALID_MESSAGE"); }
    }
    private int postPacket(JSONObject envelope, byte[] payload) throws Failure { return postPacket(envelope, payload, false); }
    private int postPacket(JSONObject envelope, byte[] payload, boolean frame) throws Failure {
        JSONObject header;
        try { header = new JSONObject(envelope.toString()); } catch (JSONException e) { throw new Failure("INVALID_MESSAGE", e.toString()); }
        if (payload != null) { if (!frame) header.remove("payload"); try { header.put(frame ? "binaryFrame" : "binary", true); } catch (JSONException ignored) { } }
        byte[] json = header.toString().getBytes(StandardCharsets.UTF_8);
        int size = 4 + json.length + (payload == null ? 0 : payload.length);
        if (size > MAX_MESSAGE_BYTES) throw new Failure("MESSAGE_TOO_LARGE", "Channel message exceeds 1 MiB");
        ByteBuffer packet = ByteBuffer.allocate(size); packet.putInt(json.length); packet.put(json); if (payload != null) packet.put(payload);
        WebViewCompat.postWebMessage(webView, new WebMessageCompat(packet.array()), Uri.parse("https://" + originHost));
        return size;
    }
    private boolean validEnvelope(JSONObject envelope) {
        String kind = envelope.optString("kind");
        return ("req".equals(kind) || "res".equals(kind) || "evt".equals(kind))
            && !envelope.optString("id").isEmpty() && !envelope.optString("name").isEmpty()
            && (envelope.has("payload") || "res".equals(kind) && envelope.has("err"));
    }
    public void send(JSONObject envelope) throws Failure {
        if (envelope == null) throw new Failure("INVALID_MESSAGE", "Envelope is required");
        if (sessionId == null) throw new Failure("NOT_CREATED", "No secondary web view");
        if (webView == null) throw new Failure("TERMINATED", "Secondary renderer has terminated");
        if (envelope.has("sessionId") && !sessionId.equals(envelope.optString("sessionId"))) return;
        if (!validEnvelope(envelope)) throw new Failure("INVALID_MESSAGE", "Envelope requires id, kind, name and payload or err");
        if ("__secondaryChannelError".equals(envelope.optString("name"))) throw new Failure("INVALID_MESSAGE", "Reserved channel message name");
        if (envelope.toString().getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) throw new Failure("MESSAGE_TOO_LARGE", "Channel message exceeds 1 MiB");
        try { envelope.put("v", PROTOCOL_VERSION); envelope.put("sessionId", sessionId); } catch (JSONException ignored) { }
        JSONObject binaryTag = envelope.optJSONObject("payload");
        byte[] binary = null;
        if (binaryTag != null && binaryTag.has("__secondaryArrayBuffer")) {
            try { binary = Base64.decode(binaryTag.optString("__secondaryArrayBuffer"), Base64.DEFAULT); } catch (IllegalArgumentException e) { throw new Failure("INVALID_MESSAGE", "Invalid ArrayBuffer encoding"); }
        }
        if ("req".equals(envelope.optString("kind"))) { int slot = pendingCursor++ % pendingIds.length; pendingIds[slot] = envelope.optString("id"); pendingTimes[slot] = SystemClock.elapsedRealtimeNanos(); }
        int bytes;
        if (binaryAvailable()) bytes = postPacket(envelope, binary);
        else { webView.evaluateJavascript("window.secondaryWebView&&window.secondaryWebView._receive(" + envelope + ")", null); bytes = envelope.toString().getBytes(StandardCharsets.UTF_8).length; }
        addMetric(9, 1); addMetric(10, bytes);
    }
    private String script(String id) {
        return "(function(){const s=" + JSONObject.quote(id) + ";let seq=0;const pending=new Map(),listeners=new Map();"
            + "const b64=a=>{let out='';new Uint8Array(a).forEach(x=>out+=String.fromCharCode(x));return btoa(out);};"
            + "const unb64=x=>Uint8Array.from(atob(x),c=>c.charCodeAt(0)).buffer;"
            + "function revive(x,raw){if(!x||typeof x!=='object')return x;if(x.__secondaryArrayBuffer)return unb64(x.__secondaryArrayBuffer);if(raw&&x.__secondaryBinaryOffset!==undefined)return raw.slice(x.__secondaryBinaryOffset,x.__secondaryBinaryOffset+x.length).buffer;if(Array.isArray(x))return x.map(v=>revive(v,raw));Object.keys(x).forEach(k=>x[k]=revive(x[k],raw));return x;}"
            + "const valid=(v,seen)=>{if(v===null||typeof v==='string'||typeof v==='boolean')return true;if(typeof v==='number')return Number.isFinite(v);if(typeof v!=='object'||seen.has(v))return false;let p=Object.getPrototypeOf(v);if(!Array.isArray(v)&&p!==null&&Object.getPrototypeOf(p)!==null||Object.getOwnPropertySymbols(v).length)return false;seen.add(v);let keys=Object.keys(v);if(Array.isArray(v)&&keys.length!==v.length)return false;for(let k of keys)if(!valid(v[k],seen))return false;seen.delete(v);return true;};"
            + "const failure=c=>({code:c,message:c==='INVALID_JSON'?'Channel message is not valid JSON':c==='MESSAGE_TOO_LARGE'?'Channel message exceeds 1 MiB':'Reserved channel message name'});"
            + "const report=c=>{post({v:1,id:'0',kind:'evt',name:'__secondaryChannelError',payload:c},true).catch(()=>{});};"
            + "function post(e,internal){e.sessionId=s;let payload,packet,json,binary=!!window._secondaryBinary;try{payload=e.payload instanceof ArrayBuffer?e.payload:null;if(!internal&&e.name==='__secondaryChannelError')throw failure('INVALID_MESSAGE');"
            + "if(binary){if(!valid(payload?Object.assign({},e,{payload:null}):e,new Set()))throw failure('INVALID_JSON');"
            + "if(payload){delete e.payload;e.binary=true;}let h=new TextEncoder().encode(JSON.stringify(e)),p=payload?new Uint8Array(payload):new Uint8Array(0);"
            + "if(4+h.length+p.length>1048576)throw failure('MESSAGE_TOO_LARGE');let a=new Uint8Array(4+h.length+p.length),v=new DataView(a.buffer);v.setUint32(0,h.length);a.set(h,4);a.set(p,4+h.length);packet=a.buffer;}"
            + "else{if(payload)e.payload={__secondaryArrayBuffer:b64(payload)};if(!valid(e,new Set()))throw failure('INVALID_JSON');json=JSON.stringify(e);"
            + "if(new TextEncoder().encode(json).length>1048576)throw failure('MESSAGE_TOO_LARGE');}}"
            + "catch(x){let err=x&&x.code?x:failure('INVALID_JSON');console.warn('[SecondaryWebView] '+err.code+': '+err.message);if(!internal)report(err.code);return Promise.reject(err);}"
            + "try{if(binary)_secondaryBinary.postMessage(packet);else _secondaryNative.post(json);}catch(_){return Promise.resolve(false);}return Promise.resolve(true);}"
            + "const api={request(name,payload){let value=payload===undefined?null:payload;return new Promise((resolve,reject)=>{let id=String(++seq);pending.set(id,{resolve,reject});post({v:1,id,kind:'req',name,payload:value}).catch(err=>{pending.delete(id);reject(err);});});},"
            + "post(name,payload){post({v:1,id:String(++seq),kind:'evt',name,payload:payload===undefined?null:payload}).catch(()=>{});},"
            + "subscribe(name,options){return api.request('subscribe',{streamName:name,...options});},"
            + "unsubscribe(id){post({v:1,id:String(++seq),kind:'evt',name:'unsubscribe',payload:id}).catch(()=>{});},"
            + "onMessage:null,_receive(e){if(e.sessionId!==s||e.v!==1)return;e.payload=revive(e.payload);"
            + "if(e.kind==='res'){let p=pending.get(e.id);if(p){pending.delete(e.id);e.err?p.reject(e.err):p.resolve(e.payload);}}"
            + "else{if(api.onMessage)api.onMessage(e);let set=listeners.get(e.name);if(set)set.forEach(f=>f(e.payload));"
            + "if(e.name==='streams')Object.keys(e.payload).forEach(id=>{let item=e.payload[id],f=listeners.get(item.streamName);if(f)f.forEach(fn=>fn(item.data,id));});}},"
            + "on(name,fn){let set=listeners.get(name)||new Set();set.add(fn);listeners.set(name,set);return()=>set.delete(fn);}};"
            + "Object.defineProperty(window,'secondaryWebView',{value:api});"
            + "if(window._secondaryBinary)window.addEventListener('message',ev=>{if(!(ev.data instanceof ArrayBuffer))return;let a=new Uint8Array(ev.data),v=new DataView(ev.data),n=v.getUint32(0),h=JSON.parse(new TextDecoder().decode(a.slice(4,4+n)));if(h.binary){h.payload=a.slice(4+n).buffer;delete h.binary;}if(h.binaryFrame){h.payload=revive(h.payload,a.slice(4+n));delete h.binaryFrame;}api._receive(h);});"
            + "const hello=()=>post({v:1,id:String(++seq),kind:'req',name:'handshake',payload:[1]}).catch(()=>{});if(document.readyState==='complete')hello();else window.addEventListener('load',hello,{once:true});"
            + (heartbeatMs > 0 ? "setInterval(()=>{post({v:1,id:'0',kind:'evt',name:'heartbeat',payload:null}).catch(()=>{});}," + Math.max(500, heartbeatMs) + ");" : "")
            + "})();";
    }
    private void addMetric(int index, long amount) { if (countersEnabled) metrics.addAndGet(index, amount); }
    private void setMetric(int index, long value) { if (countersEnabled) metrics.set(index, value); }
    private void sampleHistogram(int index, long nanoseconds) {
        if (!countersEnabled) return;
        int bucket = 0; while (bucket < HIST_LIMITS_NS.length && nanoseconds >= HIST_LIMITS_NS[bucket]) bucket++;
        histograms[index].incrementAndGet(bucket);
    }
    private void trace(String path, int status, long durationNs) {
        if (!perRequest) return;
        JSONObject row = new JSONObject(); try { row.put("path", path); row.put("status", status); row.put("durationNs", durationNs); } catch (JSONException ignored) { }
        traces.add(row);
        if (traceCount.incrementAndGet() > MAX_TRACES) { if (traces.poll() != null) traceCount.decrementAndGet(); }
    }
    private void emit(String type, Object detail) {
        if (listener == null || sessionId == null) return;
        JSONObject event = new JSONObject();
        try { event.put("sessionId", sessionId); event.put("type", type); event.put("detail", detail == null ? JSONObject.NULL : detail); } catch (JSONException ignored) { }
        listener.onEvent(event);
    }
    private void startHeartbeat() {
        if (heartbeatMs == 0 || nativeHangDetection) return;
        lastPong = SystemClock.elapsedRealtime();
        heartbeat = new Runnable() { @Override public void run() {
            if (webView == null || backgrounded || dead) return;
            if (ready && SystemClock.elapsedRealtime() - lastPong > heartbeatMs * 3L) { if (missedHeartbeats++ == 0) { addMetric(11, 1); emit("unresponsive", null); } }
            handler.postDelayed(this, heartbeatMs);
        }};
        handler.postDelayed(heartbeat, heartbeatMs);
    }
    public JSONObject metrics() {
        JSONObject o = new JSONObject(); String[] names = {"createToReadyNs","createToFirstPaintNs","assetReadDurationNs","assetReadQueueWaitNs","assetReads","deniedPaths","assetReadErrors","permitTimeouts","rendererDeaths","channelMessages","channelBytes","hangs","ipcRoundTripDurationNs","ipcRoundTrips","processStartToReadyNs"};
        try {
            for (int i = 0; i < names.length; i++) o.put(names[i], metrics.get(i));
            String[] histNames = {"createToReadyHistogram", "createToFirstPaintHistogram", "assetReadDurationHistogram", "assetReadQueueWaitHistogram", "ipcRoundTripHistogram"};
            for (int h = 0; h < histNames.length; h++) { JSONArray bins = new JSONArray(); for (int i = 0; i < histograms[h].length(); i++) bins.put(histograms[h].get(i)); o.put(histNames[h], bins); }
            double seconds = Math.max(0.001, (SystemClock.elapsedRealtimeNanos() - createdAt) / 1000000000.0);
            o.put("channelMessagesPerSecond", metrics.get(9) / seconds); o.put("channelBytesPerSecond", metrics.get(10) / seconds);
            if (perRequest) { JSONArray events = new JSONArray(); for (JSONObject trace : traces) events.put(trace); o.put("assetReadTraces", events); }
        } catch (JSONException ignored) { }
        return o;
    }
    public void onPause() { backgrounded = true; subscriptions.clear(); refreshStreamNames(); clearQueuedSamples(); if (webView != null) webView.onPause(); if (heartbeat != null) handler.removeCallbacks(heartbeat); }
    public void onResume() { backgrounded = false; refreshStreamNames(); if (webView != null) webView.onResume(); if (webView != null) startHeartbeat(); }
    public void onTrimMemory(int level) { if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && webView != null) { webView.freeMemory(); emit("memoryPressure", level); } }
    public void onMainRendererGone() { if (sessionId != null && !dead) { dead = true; addMetric(8, 1); emit("terminated", null); } destroy(); }
    private void closeOpenStreams() {
        java.util.List<InputStream> closing = new ArrayList<>(openStreams); openStreams.clear();
        android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> { for (InputStream stream : closing) try { stream.close(); } catch (IOException e) { Log.w(TAG, "Closing asset stream", e); } });
    }
    public void destroy() {
        String old = sessionId; sessionId = null; originHost = null; ready = false; backgrounded = true; subscriptions.clear(); refreshStreamNames(); clearQueuedSamples();
        if (heartbeat != null) handler.removeCallbacks(heartbeat); heartbeat = null;
        if (webView == null && documentScript != null) { documentScript.remove(); documentScript = null; }
        if (webView != null) { if (documentScript != null) { documentScript.remove(); documentScript = null; } if (binaryAvailable()) WebViewCompat.removeWebMessageListener(webView, "_secondaryBinary"); if (nativeHangDetection) WebViewCompat.setWebViewRenderProcessClient(webView, null); webView.stopLoading(); webView.removeJavascriptInterface("_secondaryNative"); webView.setWebViewClient(null); if (container != null) container.removeView(webView); if (surfaceHost != null) { surfaceHost.release(); surfaceHost = null; } webView.destroy(); webView = null; }
        if (container != null) { container.cancelActiveGesture(); ViewGroup.LayoutParams restoreParams = mainView == null ? null : mainView.getLayoutParams(); if (mainView != null && mainView.getParent() == container) container.removeView(mainView); rootLayout.removeView(container); if (mainView != null && mainView.getParent() == null) rootLayout.addView(mainView, mainIndex, restoreParams == null ? mainParams : restoreParams); container = null; }
        closeOpenStreams();
        rootsSnapshot = java.util.Collections.emptyList(); roots.clear(); traces.clear(); traceCount.set(0); readPermits = null; touchRects.clear(); frameScheduled = false;
        if (ownsProcessLimit) { synchronized (SecondaryWebViewManager.class) { if (--processReadUsers == 0) { processReadPermits = null; processReadLimit = 0; } } ownsProcessLimit = false; }
        if (listener != null && old != null) { JSONObject event = new JSONObject(); try { event.put("sessionId", old); event.put("type", "destroyed"); event.put("detail", JSONObject.NULL); } catch (JSONException ignored) { } listener.onEvent(event); }
        listener = null; mainView = null;
    }
    private void refreshStreamNames() {
        java.util.Set<String> all = new java.util.HashSet<>(), batches = new java.util.HashSet<>();
        for (Subscription sub : subscriptions.values()) { all.add(sub.streamName); if (sub.batch) batches.add(sub.streamName); }
        subscribedStreams = java.util.Collections.unmodifiableSet(all); batchStreams = java.util.Collections.unmodifiableSet(batches);
        if (subscriptionObserver != null) subscriptionObserver.changed(subscribedStreams);
    }
    private void clearQueuedSamples() { synchronized (sampleLock) { queuedLatest.clear(); queuedBatch.clear(); } }
    /** Existing instance entry point; new producers use SecondaryWebViewStreams. */
    public void pushSample(String streamName, Object sample) {
        if (!hasStreamSubscriber(streamName)) return;
        Object snapshot;
        try { snapshot = SecondaryWebViewStreams.snapshot(sample); }
        catch (JSONException | RuntimeException e) { rejectStreamSample(streamName); return; }
        enqueueStreamSample(streamName, snapshot);
    }
    void enqueueStreamSample(String streamName, Object sample) {
        if (!hasStreamSubscriber(streamName)) return;
        if (sample == null) sample = JSONObject.NULL;
        boolean schedule = false;
        synchronized (sampleLock) {
            if (batchStreams.contains(streamName)) {
                List<Object> values = queuedBatch.get(streamName);
                if (values == null) { values = new ArrayList<>(); queuedBatch.put(streamName, values); }
                values.add(sample);
            } else queuedLatest.put(streamName, sample);
            if (!sampleDrainPosted) { sampleDrainPosted = true; schedule = true; }
        }
        if (schedule) handler.post(this::drainSamples);
    }
    void acceptSamples(JSONObject frame) {
        if (frame == null || webView == null || backgrounded || dead) return;
        java.util.Iterator<String> names = frame.keys();
        while (names.hasNext()) {
            String name = names.next(); Object value = frame.opt(name);
            if (!(value instanceof JSONArray)) continue;
            JSONArray batch = (JSONArray)value;
            for (int i = 0; i < batch.length(); i++) enqueueStreamSample(name, batch.opt(i));
        }
    }
    private void drainSamples() {
        Map<String, Object> latest;
        Map<String, List<Object>> batches;
        synchronized (sampleLock) {
            latest = new HashMap<>(queuedLatest); batches = new HashMap<>(queuedBatch);
            queuedLatest.clear(); queuedBatch.clear(); sampleDrainPosted = false;
        }
        if (webView == null || backgrounded || !ready) return;
        for (Subscription sub : subscriptions.values()) {
            List<Object> values = batches.get(sub.streamName);
            Object value = latest.get(sub.streamName);
            if (sub.batch) { if (values != null) for (Object item : values) sub.samples.put(item); else if (value != null) sub.samples.put(value); }
            else if (values != null && !values.isEmpty()) sub.latest = values.get(values.size() - 1);
            else if (value != null) sub.latest = value;
        }
        if (!frameScheduled && !subscriptions.isEmpty()) {
            frameScheduled = true;
            Choreographer.getInstance().postFrameCallback(frameTimeNanos -> flushFrame());
        }
    }
    private Object encodeStreamValue(Object value, ByteArrayOutputStream raw) throws JSONException {
        if (value instanceof byte[]) {
            byte[] bytes = (byte[])value;
            if (binaryAvailable()) { JSONObject ref = new JSONObject(); ref.put("__secondaryBinaryOffset", raw.size()); ref.put("length", bytes.length); raw.write(bytes, 0, bytes.length); return ref; }
            return new JSONObject().put("__secondaryArrayBuffer", Base64.encodeToString(bytes, Base64.NO_WRAP));
        }
        if (value instanceof JSONArray) { JSONArray encoded = new JSONArray(); JSONArray array = (JSONArray)value; for (int i = 0; i < array.length(); i++) encoded.put(encodeStreamValue(array.opt(i), raw)); return encoded; }
        return value;
    }
    private void flushFrame() {
        frameScheduled = false;
        if (webView == null || backgrounded || !ready) return;
        long now = SystemClock.elapsedRealtimeNanos();
        boolean pending = false;
        JSONObject frame = new JSONObject();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (Map.Entry<String, Subscription> row : subscriptions.entrySet()) {
            Subscription sub = row.getValue();
            if (sub.latest == null && sub.samples.length() == 0) continue;
            if (now - sub.lastDeliveryNs < 1000000000L / sub.rateHz) { pending = true; continue; }
            JSONObject payload = new JSONObject();
            try {
                payload.put("streamName", sub.streamName);
                payload.put("data", encodeStreamValue(sub.batch ? sub.samples : sub.latest, raw));
                frame.put(row.getKey(), payload);
            } catch (JSONException e) { Log.e(TAG, "Frame delivery failed", e); emit("channelError", "INVALID_JSON"); }
            sub.latest = null; sub.samples = new JSONArray(); sub.lastDeliveryNs = now;
        }
        if (frame.length() > 0) { JSONObject envelope = new JSONObject(); try {
            envelope.put("kind", "evt"); envelope.put("id", "0"); envelope.put("name", "streams"); envelope.put("payload", frame);
            if (binaryAvailable() && raw.size() > 0) {
                envelope.put("v", PROTOCOL_VERSION); envelope.put("sessionId", sessionId);
                int bytes = postPacket(envelope, raw.toByteArray(), true);
                addMetric(9, 1); addMetric(10, bytes);
            } else send(envelope);
        } catch (JSONException | Failure e) { Log.e(TAG, "Frame delivery failed", e); emit("channelError", e instanceof Failure ? ((Failure)e).code : "INVALID_JSON"); } }
        if (pending) { frameScheduled = true; Choreographer.getInstance().postFrameCallback(frameTimeNanos -> flushFrame()); }
    }
    private static final class Subscription {
        String streamName; int rateHz; boolean batch; Object latest; JSONArray samples = new JSONArray(); long lastDeliveryNs;
    }
}
