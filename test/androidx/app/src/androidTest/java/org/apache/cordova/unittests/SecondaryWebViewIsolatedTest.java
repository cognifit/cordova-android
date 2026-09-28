/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova.unittests;

import android.os.Build;
import android.webkit.WebView;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.apache.cordova.SecondaryWebViewIsolatedController;
import org.apache.cordova.SecondaryWebViewManager;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

/** Exercises the real service process on a cold start and repeated teardown. */
@RunWith(AndroidJUnit4.class)
public class SecondaryWebViewIsolatedTest {
    @Rule public ActivityTestRule<StandardActivity> activityRule = new ActivityTestRule<>(StandardActivity.class);

    private JSONObject call(Operation operation) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<JSONObject> value = new AtomicReference<>();
        AtomicReference<JSONObject> error = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> operation.run(new SecondaryWebViewIsolatedController.Result() {
            @Override public void success(JSONObject answer) { value.set(answer); finished.countDown(); }
            @Override public void error(JSONObject failure) { error.set(failure); finished.countDown(); }
        }));
        assertTrue("Timed out waiting for isolated service", finished.await(30, TimeUnit.SECONDS));
        assertNull(String.valueOf(error.get()), error.get());
        return value.get();
    }

    private static WebView findWebView(View root) {
        if (root instanceof WebView) return (WebView)root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)root;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private String pageResults(WebView view) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> view.evaluateJavascript("document.getElementById('results').textContent", value -> { result.set(value); latch.countDown(); }));
        latch.await(2, TimeUnit.SECONDS);
        return result.get();
    }

    private interface Operation { void run(SecondaryWebViewIsolatedController.Result result); }

    @Test public void concurrencyStressSharedAndIsolated() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30);
        for (String mode : new String[] {"shared", "isolated"}) {
            StandardActivity activity = activityRule.getActivity();
            AtomicReference<WebView> hostView = new AtomicReference<>();
            CountDownLatch hostReady = new CountDownLatch(1);
            activity.runOnUiThread(() -> { hostView.set(findWebView(activity.getWindow().getDecorView())); hostReady.countDown(); });
            assertTrue(hostReady.await(1, TimeUnit.SECONDS));
            activity.runOnUiThread(() -> activity.loadUrl("https://localhost/secondary-concurrency-host.html?mode=" + mode));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (System.nanoTime() < deadline) {
                CountDownLatch sampled = new CountDownLatch(1);
                AtomicReference<String> title = new AtomicReference<>();
                activity.runOnUiThread(() -> {
                    title.set(hostView.get().getTitle());
                    sampled.countDown();
                });
                assertTrue("Host main thread stalled during " + mode, sampled.await(1, TimeUnit.SECONDS));
                String value = title.get();
                if (value != null && value.startsWith("PASS secondary concurrency")) break;
                if (value != null && value.startsWith("FAIL secondary concurrency")) fail("Stress page failed in " + mode + ": " + pageResults(hostView.get()));
                Thread.sleep(100);
            }
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<String> finalTitle = new AtomicReference<>();
            activity.runOnUiThread(() -> { finalTitle.set(hostView.get().getTitle()); completed.countDown(); });
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertTrue("Stress did not finish in " + mode + ": " + finalTitle.get() + " " + pageResults(hostView.get()),
                finalTitle.get() != null && finalTitle.get().startsWith("PASS secondary concurrency"));
        }
    }

    @Test public void largeAssetStreamsThroughCompletedPipe() throws Exception {
        StandardActivity activity = activityRule.getActivity();
        File directory = new File(activity.getCacheDir(), "secondary-large-asset-test");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File asset = new File(directory, "large.txt");
        byte[] chunk = new byte[64 * 1024]; java.util.Arrays.fill(chunk, (byte)'x');
        try (FileOutputStream output = new FileOutputStream(asset)) {
            for (int i = 0; i < 32; i++) output.write(chunk);
        }
        File entry = new File(directory, "entry.html");
        String html = "<script>fetch('large.txt').then(r=>r.text()).then(t=>secondaryWebView.post('largeAssetResult',{length:t.length})).catch(e=>secondaryWebView.post('largeAssetResult',{error:String(e)}))</script>";
        try (FileOutputStream output = new FileOutputStream(entry)) { output.write(html.getBytes(StandardCharsets.UTF_8)); }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<JSONObject> payload = new AtomicReference<>();
        AtomicReference<String> createError = new AtomicReference<>();
        JSONObject config = new JSONObject(); config.put("url", android.net.Uri.fromFile(entry.getCanonicalFile()).toString()); config.put("zOrder", "above");
        activity.runOnUiThread(() -> {
            try {
                activity.secondaryWebViews().create(config, event -> {
                    JSONObject detail = event.optJSONObject("detail");
                    if ("message".equals(event.optString("type")) && detail != null && "largeAssetResult".equals(detail.optString("name"))) {
                        payload.set(detail.optJSONObject("payload")); completed.countDown();
                    }
                });
            } catch (SecondaryWebViewManager.Failure error) { createError.set(error.code); completed.countDown(); }
        });
        assertTrue("Large asset read timed out", completed.await(20, TimeUnit.SECONDS));
        activity.runOnUiThread(() -> activity.secondaryWebViews().destroy());
        assertNull("Create failed: " + createError.get(), createError.get());
        assertTrue("Large asset was not fully delivered: " + payload.get(), payload.get() != null && payload.get().optInt("length") == 2 * 1024 * 1024);
    }

    @Test public void destroyDuringCreateSettlesCreateBeforeDestroyedEvent() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30);
        SecondaryWebViewIsolatedController controller = activityRule.getActivity().isolatedSecondaryWebViews();
        JSONObject config = new JSONObject();
        config.put("url", "www/index.html");
        config.put("processIsolation", "isolated");
        if (Build.VERSION.SDK_INT < 33) config.put("unsafeAllowIsolatedBelowApi33", true);
        CountDownLatch createSettled = new CountDownLatch(1);
        CountDownLatch destroyed = new CountDownLatch(1);
        AtomicReference<String> errorCode = new AtomicReference<>();
        AtomicReference<Boolean> ordered = new AtomicReference<>(true);
        activityRule.getActivity().runOnUiThread(() -> {
            controller.create(config, event -> {
                if ("destroyed".equals(event.optString("type"))) {
                    if (createSettled.getCount() != 0) ordered.set(false);
                    destroyed.countDown();
                }
            }, new SecondaryWebViewIsolatedController.Result() {
                @Override public void success(JSONObject value) { errorCode.set("UNEXPECTED_SUCCESS"); createSettled.countDown(); }
                @Override public void error(JSONObject value) { errorCode.set(value.optString("code")); createSettled.countDown(); }
            });
            controller.destroy();
        });
        assertTrue("create promise did not settle", createSettled.await(5, TimeUnit.SECONDS));
        assertTrue("destroyed event missing", destroyed.await(5, TimeUnit.SECONDS));
        assertTrue("create settled after destroyed event", ordered.get());
        assertTrue("unexpected create result: " + errorCode.get(), "DESTROYED".equals(errorCode.get()));
    }

    @Test public void experimentalApi30To32CanStartWithoutChangingCapabilities() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30 && Build.VERSION.SDK_INT < 33);
        StandardActivity activity = activityRule.getActivity();
        SecondaryWebViewIsolatedController controller = activity.isolatedSecondaryWebViews();
        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Boolean> supported = new AtomicReference<>(true);
        activity.runOnUiThread(() -> controller.probe(value -> { supported.set(value); checked.countDown(); }));
        assertTrue(checked.await(15, TimeUnit.SECONDS));
        assertTrue("The supported capability must remain false", !supported.get());
        JSONObject config = new JSONObject();
        config.put("url", "www/index.html");
        config.put("processIsolation", "isolated");
        config.put("unsafeAllowIsolatedBelowApi33", true);
        CountDownLatch ready = new CountDownLatch(1);
        call(result -> controller.create(config, event -> {
            if ("ready".equals(event.optString("type"))) ready.countDown();
        }, result));
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        JSONObject none = new JSONObject(); none.put("mode", "none");
        call(result -> controller.setTouchRegions(none, result));
        call(controller::destroy);
    }

    @Test public void coldStartAndRepeatedCreateDestroy() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 33);
        StandardActivity activity = activityRule.getActivity();
        SecondaryWebViewIsolatedController controller = activity.isolatedSecondaryWebViews();
        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Boolean> available = new AtomicReference<>(false);
        activity.runOnUiThread(() -> controller.probe(ok -> { available.set(ok); checked.countDown(); }));
        assertTrue(checked.await(15, TimeUnit.SECONDS));
        assertTrue("Isolated service is not available", available.get());
        for (int i = 0; i < 3; i++) {
            JSONObject config = new JSONObject();
            config.put("url", "www/index.html");
            config.put("processIsolation", "isolated");
            if (i == 1) config.put("storageIsolation", "ephemeral");
            CountDownLatch ready = new CountDownLatch(1);
            JSONObject created = call(result -> controller.create(config, event -> {
                if ("ready".equals(event.optString("type"))) ready.countDown();
            }, result));
            assertTrue(created.has("sessionId"));
            assertTrue("origin".equals(created.getString("storageIsolation")));
            assertTrue("Secondary page did not become ready", ready.await(15, TimeUnit.SECONDS));
            JSONObject touch = new JSONObject(); touch.put("mode", "all");
            call(result -> controller.setTouchRegions(touch, result));
            JSONObject metrics = call(controller::metrics);
            assertTrue(metrics.getLong("processStartToReadyNs") > 0);
            assertTrue(metrics.getLong("createToReadyNs") >= metrics.getLong("processStartToReadyNs"));
            call(controller::destroy);
        }
    }
}
