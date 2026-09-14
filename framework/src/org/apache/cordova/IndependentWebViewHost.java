/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. */
package org.apache.cordova;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.apache.cordova.engine.SystemWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns optional, non-Cordova WebViews for {@link CordovaActivity}.  This class
 * intentionally creates no CordovaWebView, engine or PluginManager for games.
 */
public final class IndependentWebViewHost {
    public interface EventListener { void onEvent(String type, JSONObject detail); }
    public static final class WebApp {
        public final String root;
        public final String entryPage;
        public WebApp(String root, String entryPage) { this.root = root; this.entryPage = entryPage; }
    }

    private final CordovaActivity activity;
    private final FrameLayout root;
    private final Map<String, WebApp> apps = new LinkedHashMap<String, WebApp>();
    private final Map<String, String> routes = new HashMap<String, String>();
    private String activeId, pendingId, selectionKey = "CDVMainWebApp";
    private JSONObject context;
    private WebView game, loading;
    private ProgressBar spinner;
    private String gameSession;
    private EventListener eventListener;

    IndependentWebViewHost(CordovaActivity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
    }

    public void setEventListener(EventListener listener) { eventListener = listener; }
    public String getActiveWebAppId() { return activeId; }
    public JSONObject getWebAppContext() { return context; }
    public void setSelectionKey(String key) { if (key == null || key.length() == 0) throw new IllegalArgumentException("key"); selectionKey = key; }

    public void registerWebApps(Map<String, WebApp> definitions, String defaultId) {
        if (definitions == null || definitions.isEmpty() || !definitions.containsKey(defaultId)) throw new IllegalArgumentException("A default registered app is required");
        apps.clear(); apps.putAll(definitions);
        String remembered = activity.getSharedPreferences("Cordova", 0).getString(selectionKey, null);
        activeId = apps.containsKey(remembered) ? remembered : defaultId;
    }

    public boolean switchToWebApp(String id, boolean remember, JSONObject handoff) {
        WebApp app = apps.get(id);
        if (app == null) return false;
        destroyGame(); // The active main app is the only plugin owner.
        showLoading(null);
        routes.clear();
        activeId = id;
        pendingId = remember ? id : null;
        context = copyJson(handoff);
        activity.replaceMainWebView(toUrl(app.root, app.entryPage), handoffScript(id, context));
        return true;
    }

    public void confirmWebAppReady() {
        if (pendingId != null) activity.getSharedPreferences("Cordova", 0).edit().putString(selectionKey, pendingId).apply();
        pendingId = null;
        hideLoading();
    }
    public void clearRememberedWebApp() { activity.getSharedPreferences("Cordova", 0).edit().remove(selectionKey).apply(); }

    public boolean createGame(String contentRoot, String entryPage) {
        if (game != null || contentRoot == null || contentRoot.length() == 0) return false;
        activity.installGameResultInterceptor();
        gameSession = UUID.randomUUID().toString();
        game = new WebView(activity);
        configureVanilla(game);
        game.addJavascriptInterface(new HostJsBridge(gameSession), "_cordovaGameHost");
        game.setWebViewClient(new GameClient(gameSession));
        game.setVisibility(WebView.GONE);
        root.addView(game, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        game.loadUrl(toUrl(contentRoot, entryPage));
        return true;
    }

    public void showGame(String loadingHtml) { if (game == null) return; showLoading(loadingHtml); game.setVisibility(WebView.VISIBLE); }
    public void hideGame() { if (game != null) game.setVisibility(WebView.GONE); hideLoading(); }
    public void destroyGame() {
        routes.clear(); gameSession = null;
        if (game != null) { root.removeView(game); game.removeJavascriptInterface("_cordovaGameHost"); game.loadUrl("about:blank"); game.destroy(); game = null; }
        hideLoading();
    }

    public void showLoading(String html) {
        hideLoading();
        if (html == null) {
            spinner = new ProgressBar(activity); spinner.setIndeterminate(true);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER);
            root.addView(spinner, p); return;
        }
        loading = new WebView(activity); configureVanilla(loading); loading.setBackgroundColor(Color.TRANSPARENT);
        root.addView(loading, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        loading.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }
    public void hideLoading() {
        if (spinner != null) { root.removeView(spinner); spinner = null; }
        if (loading != null) { root.removeView(loading); loading.loadUrl("about:blank"); loading.destroy(); loading = null; }
    }

    public void postMessageToGame(JSONObject message) { sendToGame("message", message); }
    private void sendToGame(String type, JSONObject detail) {
        if (game == null) return;
        JSONObject event = new JSONObject();
        try { event.put("type", type); event.put("detail", detail); } catch (JSONException ignored) { }
        game.evaluateJavascript("window.__cordovaHostReceive&&window.__cordovaHostReceive(" + event + ")", null);
    }

    private void configureVanilla(WebView view) {
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setAllowFileAccess(true);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setMediaPlaybackRequiresUserGesture(false);
    }
    private String toUrl(String rootPath, String entry) {
        String page = entry == null || entry.length() == 0 ? "index.html" : entry;
        if (rootPath.startsWith("file://")) return rootPath.replaceAll("/+$", "") + "/" + page;
        File rootFile = new File(rootPath);
        if (rootFile.isAbsolute()) return "file://" + rootFile.getPath().replaceAll("/+$", "") + "/" + page;
        return "file:///android_asset/" + rootPath.replaceAll("^/+|/+$", "") + "/" + page;
    }
    private String handoffScript(String id, JSONObject handoff) {
        JSONObject payload = new JSONObject();
        try { payload.put("appId", id); payload.put("context", handoff == null ? JSONObject.NULL : handoff); } catch (JSONException ignored) { }
        return "window.cordovaWebApp=" + payload + ";";
    }
    private JSONObject copyJson(JSONObject source) {
        if (source == null) return null;
        try { return new JSONObject(source.toString()); } catch (JSONException e) { throw new IllegalArgumentException("handoff context must be JSON-compatible", e); }
    }

    @SuppressLint("AddJavascriptInterface")
    private final class HostJsBridge {
        private final String session;
        HostJsBridge(String session) { this.session = session; }
        @JavascriptInterface public String getLoadedPlugins() {
            JSONArray answer = new JSONArray();
            for (Map.Entry<String, CordovaPlugin> item : activity.appView.getPluginManager().getPluginMap().entrySet()) {
                if (item.getValue() == null) continue;
                JSONObject row = new JSONObject();
                try { row.put("className", item.getValue().getClass().getName()); row.put("services", new JSONArray(Collections.singleton(item.getKey()))); answer.put(row); } catch (JSONException ignored) { }
            }
            return answer.toString();
        }
        @JavascriptInterface public void exec(final String requestId, final String service, final String action, final String args) {
            activity.runOnUiThread(new Runnable() { @Override public void run() {
                if (!session.equals(gameSession) || activity.appView == null) return;
                String callbackId = "GAME_" + session + "_" + requestId;
                routes.put(callbackId, session);
                activity.appView.getPluginManager().exec(service, action, callbackId, args == null ? "[]" : args);
            }});
        }
        @JavascriptInterface public void cancel(String requestId) { routes.remove("GAME_" + session + "_" + requestId); }
        @JavascriptInterface public void postMessage(String value) {
            try { emit("message", new JSONObject(value)); } catch (JSONException e) { emit("message", null); }
        }
        @JavascriptInterface public void ready() { activity.runOnUiThread(new Runnable() { @Override public void run() { if (session.equals(gameSession)) hideLoading(); }}); }
    }

    private final class GameClient extends WebViewClient {
        private final String session;
        GameClient(String session) { this.session = session; }
        @Override public void onPageFinished(WebView view, String url) { if (session.equals(gameSession)) view.evaluateJavascript(HOST_SCRIPT, null); }
        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) { if (request.isForMainFrame()) failure(error.toString()); }
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) { if (session.equals(gameSession)) { emit("terminated", null); destroyGame(); } return true; }
        private void failure(String error) { try { emit("loadFailed", new JSONObject().put("error", error)); } catch (JSONException ignored) { } }
    }
    private void emit(String type, JSONObject detail) { if (eventListener != null) eventListener.onEvent(type, detail); }

    /** Intercepts only GAME_ callbacks; normal Cordova callbacks remain untouched. */
    boolean onPluginResult(PluginResult result, String callbackId) {
        String session = routes.get(callbackId);
        if (session == null) return false;
        if (!result.getKeepCallback()) routes.remove(callbackId);
        if (!session.equals(gameSession) || game == null) return true;
        JSONObject envelope = new JSONObject();
        try { envelope.put("id", callbackId.substring(callbackId.lastIndexOf('_') + 1)); envelope.put("ok", result.getStatus() == PluginResult.Status.OK.ordinal()); envelope.put("keep", result.getKeepCallback()); envelope.put("result", encodeResult(result)); } catch (JSONException e) { return true; }
        sendToGame("pluginResult", envelope); return true;
    }

    private JSONObject encodeResult(PluginResult result) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("type", result.getMessageType());
        if (result.getMessageType() == PluginResult.MESSAGE_TYPE_MULTIPART) {
            JSONArray parts = new JSONArray();
            for (int i = 0; i < result.getMultipartMessagesSize(); i++) parts.put(encodeResult(result.getMultipartMessage(i)));
            value.put("value", parts);
        } else {
            value.put("value", new JSONObject("{\"v\":" + result.getMessage() + "}").get("v"));
        }
        return value;
    }

    private static final String HOST_SCRIPT = "(function(){if(window.host)return;var p={},seq=0;function decode(r){if(r.type===6){var b=atob(r.value),a=new Uint8Array(b.length);for(var i=0;i<b.length;i++)a[i]=b.charCodeAt(i);return a.buffer;}if(r.type===8)return r.value.map(decode);return r.value;}window.__cordovaHostReceive=function(e){if(e.type==='pluginResult'){var r=p[e.detail.id];if(!r)return;var v=decode(e.detail.result);if(e.detail.ok)r.ok(v);else r.bad(v);if(!e.detail.keep)delete p[e.detail.id];}else if(e.type==='message'&&typeof window.host.onmessage==='function')window.host.onmessage(e.detail);};function call(s,a,x,t,stream){var id=String(++seq);var q;var promise=new Promise(function(ok,bad){q={ok:stream?function(v){ok(v);}:ok,bad:bad};p[id]=q;_cordovaGameHost.exec(id,s,a,JSON.stringify(x||[]));if(t!==0)setTimeout(function(){if(p[id]){delete p[id];bad('timeout');}},t||30000);});return stream?function(){delete p[id];_cordovaGameHost.cancel(id);}:promise;}window.host={getLoadedPlugins:function(){return Promise.resolve(JSON.parse(_cordovaGameHost.getLoadedPlugins()));},callPlugin:function(s,a,x,t){return call(s,a,x,t,false);},subscribePlugin:function(s,a,x,ok,bad){var id=String(++seq);p[id]={ok:ok,bad:bad||function(){}};_cordovaGameHost.exec(id,s,a,JSON.stringify(x||[]));return function(){delete p[id];_cordovaGameHost.cancel(id);};},postMessage:function(m){_cordovaGameHost.postMessage(JSON.stringify(m));return Promise.resolve();},onmessage:null,ready:function(){_cordovaGameHost.ready();return Promise.resolve();}};}())";
}
