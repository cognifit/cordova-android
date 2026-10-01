/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Process-wide native producer for shared and isolated secondary WebView streams. */
public final class SecondaryWebViewStreams {
    private static final Object lock = new Object();
    private static final Set<SecondaryWebViewManager> shared = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<SecondaryWebViewIsolatedController> isolated = Collections.newSetFromMap(new WeakHashMap<>());

    private SecondaryWebViewStreams() { }

    static void register(SecondaryWebViewManager manager) { synchronized (lock) { shared.add(manager); } }
    static void register(SecondaryWebViewIsolatedController controller) { synchronized (lock) { isolated.add(controller); } }

    public static boolean hasSubscriber(String streamName) {
        if (streamName == null || streamName.isEmpty()) return false;
        synchronized (lock) {
            for (SecondaryWebViewManager manager : shared) if (manager.hasStreamSubscriber(streamName)) return true;
            for (SecondaryWebViewIsolatedController controller : isolated) if (controller.hasStreamSubscriber(streamName)) return true;
        }
        return false;
    }

    public static void push(String streamName, Object sample) {
        if (streamName == null || streamName.isEmpty()) return;
        List<SecondaryWebViewManager> locals = null;
        List<SecondaryWebViewIsolatedController> remotes = null;
        synchronized (lock) {
            for (SecondaryWebViewManager manager : shared) if (manager.hasStreamSubscriber(streamName)) {
                if (locals == null) locals = new ArrayList<>();
                locals.add(manager);
            }
            for (SecondaryWebViewIsolatedController controller : isolated) if (controller.hasStreamSubscriber(streamName)) {
                if (remotes == null) remotes = new ArrayList<>();
                remotes.add(controller);
            }
        }
        if (locals == null && remotes == null) return;
        Object snapshot;
        try { snapshot = snapshot(sample); }
        catch (JSONException | RuntimeException e) {
            if (locals != null) for (SecondaryWebViewManager manager : locals) manager.rejectStreamSample(streamName);
            if (remotes != null) for (SecondaryWebViewIsolatedController controller : remotes) controller.rejectStreamSample(streamName);
            return;
        }
        if (locals != null) for (SecondaryWebViewManager manager : locals) manager.enqueueStreamSample(streamName, snapshot);
        if (remotes != null) for (SecondaryWebViewIsolatedController controller : remotes) controller.pushSample(streamName, snapshot);
    }

    static Object snapshot(Object sample) throws JSONException { return copy(sample, new IdentityHashMap<>(), 0); }

    private static Object copy(Object value, IdentityHashMap<Object, Boolean> ancestors, int depth) throws JSONException {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        if (value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number) {
            if (!Double.isFinite(((Number)value).doubleValue())) throw new JSONException("INVALID_JSON: non-finite number");
            return value;
        }
        if (depth >= 64 || ancestors.put(value, Boolean.TRUE) != null) throw new JSONException("INVALID_JSON: depth or cycle");
        try {
            if (value instanceof JSONObject) {
                JSONObject result = new JSONObject();
                Iterator<String> keys = ((JSONObject)value).keys();
                while (keys.hasNext()) { String key = keys.next(); result.put(key, copy(((JSONObject)value).opt(key), ancestors, depth + 1)); }
                return result;
            }
            if (value instanceof JSONArray) {
                JSONArray result = new JSONArray(); JSONArray array = (JSONArray)value;
                for (int i = 0; i < array.length(); i++) result.put(copy(array.opt(i), ancestors, depth + 1));
                return result;
            }
            if (value instanceof Map) {
                JSONObject result = new JSONObject();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>)value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw new JSONException("INVALID_JSON: object key");
                    result.put((String)entry.getKey(), copy(entry.getValue(), ancestors, depth + 1));
                }
                return result;
            }
            if (value instanceof List) {
                JSONArray result = new JSONArray();
                for (Object item : (List<?>)value) result.put(copy(item, ancestors, depth + 1));
                return result;
            }
            throw new JSONException("INVALID_JSON: unsupported value");
        } finally { ancestors.remove(value); }
    }
}
