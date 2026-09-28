/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Cordova entry point owned only by the main web view. */
public final class SecondaryWebViewPlugin extends CordovaPlugin {
    private CordovaActivity activity() { return (CordovaActivity)cordova.getActivity(); }
    private SecondaryWebViewManager manager() { return activity().secondaryWebViews(); }
    private SecondaryWebViewIsolatedController isolated() { return activity().isolatedSecondaryWebViews(); }
    private static void event(CallbackContext callback, JSONObject value) {
        PluginResult result = new PluginResult(PluginResult.Status.OK, value);
        result.setKeepCallback(!"destroyed".equals(value.optString("type")));
        callback.sendPluginResult(result);
    }
    private static SecondaryWebViewIsolatedController.Result result(CallbackContext callback, boolean persistent) {
        return new SecondaryWebViewIsolatedController.Result() {
            @Override public void success(JSONObject value) { if (persistent) { PluginResult result = new PluginResult(PluginResult.Status.OK, value); result.setKeepCallback(true); callback.sendPluginResult(result); } else callback.success(value); }
            @Override public void error(JSONObject value) { callback.error(value); }
        };
    }

    @Override public boolean execute(String action, JSONArray args, CallbackContext callback) throws JSONException {
        if (!(cordova.getActivity() instanceof CordovaActivity)) {
            callback.error(error("UNSUPPORTED_HOST", "Secondary web view requires CordovaActivity")); return true;
        }
        try {
            switch (action) {
                case "pingMainThread":
                    cordova.getActivity().runOnUiThread(() -> callback.success()); return true;
                case "getCapabilities":
                    cordova.getActivity().runOnUiThread(() -> isolated().probe(available -> {
                        JSONObject capabilities = manager().capabilities();
                        try { capabilities.put("processIsolation", available); } catch (JSONException ignored) { }
                        callback.success(capabilities);
                    })); return true;
                case "create":
                    cordova.getActivity().runOnUiThread(() -> {
                        JSONObject config = args.optJSONObject(0) == null ? new JSONObject() : args.optJSONObject(0);
                        if ("isolated".equals(config.optString("processIsolation", "shared"))) {
                            try { isolated().create(config, value -> event(callback, value), result(callback, true)); }
                            catch (RuntimeException | OutOfMemoryError e) {
                                callback.error(error("INTERNAL_ERROR", e.toString()));
                                isolated().destroy();
                            }
                            return;
                        }
                        if (isolated().hasSession()) { callback.error(error("ALREADY_EXISTS", "Destroy the isolated secondary web view first")); return; }
                        try {
                            String id = manager().create(config, value -> event(callback, value));
                            JSONObject created = new JSONObject(); created.put("sessionId", id); created.put("storageIsolation", "origin");
                            PluginResult result = new PluginResult(PluginResult.Status.OK, created);
                            result.setKeepCallback(true);
                            callback.sendPluginResult(result);
                        } catch (SecondaryWebViewManager.Failure e) { callback.error(e.json()); }
                        catch (JSONException e) { callback.error(error("INVALID_CONFIG", e.toString())); }
                        catch (RuntimeException | OutOfMemoryError e) { callback.error(error("INTERNAL_ERROR", e.toString())); }
                    });
                    return true;
                case "destroy":
                    cordova.getActivity().runOnUiThread(() -> { if (isolated().hasSession() || isolated().isDestroying()) isolated().destroy(result(callback, false)); else { manager().destroy(); callback.success(); } }); return true;
                case "setTouchRegions":
                    cordova.getActivity().runOnUiThread(() -> { JSONObject regions = args.optJSONObject(0) == null ? new JSONObject() : args.optJSONObject(0); if (isolated().hasSession()) isolated().setTouchRegions(regions, result(callback, false)); else try { manager().setTouchRegions(regions); callback.success(); } catch (SecondaryWebViewManager.Failure e) { callback.error(e.json()); } }); return true;
                case "send":
                    cordova.getActivity().runOnUiThread(() -> { if (isolated().hasSession()) isolated().send(args.optJSONObject(0), result(callback, false)); else try { manager().send(args.optJSONObject(0)); callback.success(); } catch (SecondaryWebViewManager.Failure e) { callback.error(e.json()); } }); return true;
                case "getMetrics": cordova.getActivity().runOnUiThread(() -> { if (isolated().hasSession()) isolated().metrics(result(callback, false)); else callback.success(manager().metrics()); }); return true;
                default: return false;
            }
        } catch (RuntimeException e) { callback.error(error("INTERNAL_ERROR", e.toString())); return true; }
    }
    private static JSONObject error(String code, String message) {
        JSONObject o = new JSONObject(); try { o.put("code", code); o.put("message", message); } catch (JSONException ignored) { } return o;
    }
}
