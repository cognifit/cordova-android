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
import org.apache.cordova.SecondaryWebViewStreams;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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
    private String evaluate(WebView view, String expression) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        activityRule.getActivity().runOnUiThread(() -> view.evaluateJavascript(expression, result -> { value.set(result); latch.countDown(); }));
        assertTrue("Host main thread stalled", latch.await(2, TimeUnit.SECONDS));
        return value.get();
    }
    private JSONObject streamState(WebView view) throws Exception {
        String encoded = evaluate(view, "JSON.stringify(window.streamState)");
        return new JSONObject(new JSONArray("[" + encoded + "]").getString(0));
    }

    private interface Operation { void run(SecondaryWebViewIsolatedController.Result result); }

    @Test public void inlineMediaPlaybackSharedWithNativeInlineDefault() throws Exception { inlineMediaPlayback("shared"); }

    @Test public void inlineMediaPlaybackIsolatedWithNativeInlineDefault() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30);
        inlineMediaPlayback("isolated");
    }

    private void inlineMediaPlayback(String mode) throws Exception {
        StandardActivity activity = activityRule.getActivity();
        AtomicReference<WebView> host = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        activity.runOnUiThread(() -> { host.set(findWebView(activity.getWindow().getDecorView())); ready.countDown(); });
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        activity.runOnUiThread(() -> activity.loadUrl("https://localhost/secondary-inline-media-host.html?mode=" + mode));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String title = "";
        while (System.nanoTime() < deadline) {
            title = evaluate(host.get(), "document.title");
            if (title.contains("PASS secondary inline media") || title.contains("FAIL secondary inline media")) break;
            Thread.sleep(100);
        }
        assertTrue("Inline playback failed in " + mode + ": " + title + " " + pageResults(host.get()), title.contains("PASS secondary inline media"));
    }

    @Test public void mediaAutoplaySharedWithWebAudioRunningDefault() throws Exception { mediaAutoplay("shared"); }

    @Test public void mediaAutoplayIsolatedWithWebAudioRunningDefault() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30);
        mediaAutoplay("isolated");
    }

    private void mediaAutoplay(String mode) throws Exception {
        StandardActivity activity = activityRule.getActivity();
        AtomicReference<WebView> host = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        activity.runOnUiThread(() -> { host.set(findWebView(activity.getWindow().getDecorView())); ready.countDown(); });
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        activity.runOnUiThread(() -> activity.loadUrl("https://localhost/secondary-autoplay-host.html?mode=" + mode));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String title = "";
        while (System.nanoTime() < deadline) {
            title = evaluate(host.get(), "document.title");
            if (title.contains("PASS secondary autoplay") || title.contains("FAIL secondary autoplay")) break;
            Thread.sleep(100);
        }
        assertTrue("Autoplay failed in " + mode + ": " + title + " " + pageResults(host.get()), title.contains("PASS secondary autoplay"));
    }

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

    @Test public void channelValidationSharedAndIsolated() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30);
        StandardActivity activity = activityRule.getActivity();
        for (String mode : new String[] {"shared", "isolated"}) {
            AtomicReference<WebView> hostView = new AtomicReference<>();
            CountDownLatch hostReady = new CountDownLatch(1);
            activity.runOnUiThread(() -> { hostView.set(findWebView(activity.getWindow().getDecorView())); hostReady.countDown(); });
            assertTrue(hostReady.await(2, TimeUnit.SECONDS));
            activity.runOnUiThread(() -> activity.loadUrl("https://localhost/secondary-channel-validation-host.html?mode=" + mode));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            String title = null;
            while (System.nanoTime() < deadline) {
                CountDownLatch sampled = new CountDownLatch(1);
                AtomicReference<String> current = new AtomicReference<>();
                activity.runOnUiThread(() -> { current.set(hostView.get().getTitle()); sampled.countDown(); });
                assertTrue("Host main thread stalled during " + mode, sampled.await(1, TimeUnit.SECONDS));
                title = current.get();
                if (title != null && (title.startsWith("PASS secondary channel") || title.startsWith("FAIL secondary channel"))) break;
                Thread.sleep(100);
            }
            assertTrue("Channel validation failed in " + mode + ": " + title + " " + pageResults(hostView.get()),
                "PASS secondary channel validation".equals(title));
        }
    }

    @Test public void nativeStreamsSharedAndIsolated() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30);
        assertTrue(!SecondaryWebViewStreams.hasSubscriber("nativeBurst"));
        assertTrue(SecondaryWebViewStreams.maxRateHz("nativeBurst") == 0);
        SecondaryWebViewStreams.push("nativeBurst", 0);
        StandardActivity activity = activityRule.getActivity();
        WebView host = findWebView(activity.getWindow().getDecorView());
        for (String mode : new String[] {"shared", "isolated"}) {
            activity.runOnUiThread(() -> activity.loadUrl("https://localhost/secondary-streams-host.html?mode=" + mode));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (System.nanoTime() < deadline) {
                String title = evaluate(host, "document.title");
                if (title.contains("READY secondary streams") || title.contains("FAIL secondary streams")) break;
                Thread.sleep(50);
            }
            assertTrue("Subscriptions failed in " + mode, evaluate(host, "document.title").contains("READY secondary streams"));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!SecondaryWebViewStreams.hasSubscriber("nativeBurst") && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue("Subscriber mirror missing in " + mode, SecondaryWebViewStreams.hasSubscriber("nativeBurst"));
            assertTrue(SecondaryWebViewStreams.hasSubscriber("nativeRate"));
            assertTrue(!SecondaryWebViewStreams.hasSubscriber("missing"));
            assertTrue(SecondaryWebViewStreams.maxRateHz("missing") == 0);
            assertTrue(SecondaryWebViewStreams.maxRateHz("nativeRate") == 1);
            assertTrue(SecondaryWebViewStreams.maxRateHz("nativeFast") == 30);
            assertTrue(SecondaryWebViewStreams.maxRateHz("nativeBurst") == 60);
            assertTrue(SecondaryWebViewStreams.maxRateHz("nativeBatchOnly") == 20);
            AtomicReference<Double> producerRate = new AtomicReference<>();
            Thread producer = new Thread(() -> {
                producerRate.set(SecondaryWebViewStreams.maxRateHz("nativeFast"));
                Map<String, Object> invalid = new HashMap<>(); invalid.put("value", Double.NaN);
                SecondaryWebViewStreams.push("nativeBurst", invalid);
                for (int i = 1; i <= 3; i++) SecondaryWebViewStreams.push("nativeBurst", i);
                for (int i = 0; i < 20; i++) SecondaryWebViewStreams.push("nativeRate", i);
            }, "SecondaryStreamProducer");
            producer.start(); producer.join(3000);
            assertTrue("Producer stalled", !producer.isAlive());
            assertTrue("Background rate query failed in " + mode, producerRate.get() != null && producerRate.get() == 30);
            JSONObject state = null;
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                state = streamState(host);
                if (state.has("burst") && state.optInt("rateCount") > 0 && state.optJSONArray("channelErrors") != null) break;
                Thread.sleep(50);
            }
            assertTrue("No stream delivery in " + mode + ": " + state, state != null && state.has("burst"));
            assertTrue("Wrong latest sample in " + mode, state.getJSONObject("burst").getInt("latest") == 3);
            assertTrue("Wrong batch in " + mode, "[1,2,3]".equals(state.getJSONObject("burst").getJSONArray("batch").toString()));
            assertTrue("Invalid JSON not reported in " + mode, state.getJSONArray("channelErrors").toString().contains("INVALID_JSON"));
            int errorsBeforeFast = state.getJSONArray("channelErrors").length();
            SecondaryWebViewStreams.push("nativeFast", 1000);
            Map<String, Object> invalidFast = new HashMap<>(); invalidFast.put("value", Double.NaN);
            SecondaryWebViewStreams.push("nativeFast", invalidFast);
            Thread.sleep(200);
            assertTrue("Dropped invalid sample reported in " + mode, streamState(host).getJSONArray("channelErrors").length() == errorsBeforeFast);
            SecondaryWebViewStreams.push("nativeFast", invalidFast);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && streamState(host).getJSONArray("channelErrors").length() == errorsBeforeFast) Thread.sleep(50);
            assertTrue("Accepted invalid sample not reported in " + mode, streamState(host).getJSONArray("channelErrors").length() > errorsBeforeFast);
            SecondaryWebViewStreams.push("nativeFast", 2000);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && !streamState(host).getJSONArray("fastValues").toString().contains("2000")) Thread.sleep(50);
            int fastBefore = streamState(host).getJSONArray("fastValues").length();
            assertTrue("Valid sample after invalid was gated in " + mode, streamState(host).getJSONArray("fastValues").toString().contains("2000"));
            Thread fastProducer = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    SecondaryWebViewStreams.push("nativeFast", i);
                    SecondaryWebViewStreams.push("nativeBatchOnly", i);
                    try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }, "SecondaryFastStreamProducer");
            fastProducer.start(); fastProducer.join(5000);
            assertTrue("Fast producer stalled", !fastProducer.isAlive());
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                state = streamState(host);
                if (state.optJSONArray("batchValues") != null && state.getJSONArray("batchValues").length() == 50) break;
                Thread.sleep(50);
            }
            JSONArray expectedBatch = new JSONArray(); for (int i = 0; i < 50; i++) expectedBatch.put(i);
            assertTrue("Batch samples lost in " + mode + ": " + state, state != null && expectedBatch.toString().equals(state.getJSONArray("batchValues").toString()));
            assertTrue("Latest samples not thinned in " + mode, state.getJSONArray("fastValues").length() > fastBefore && state.getJSONArray("fastValues").length() - fastBefore <= 8);
            Thread paced = new Thread(() -> {
                try {
                    for (int i = 0; i < 20; i++) { SecondaryWebViewStreams.push("nativeJitter", i); Thread.sleep(i % 2 == 0 ? 53 : 47); }
                    for (int i = 0; i < 40; i++) { SecondaryWebViewStreams.push("nativeHalf", i); Thread.sleep(25); }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "SecondaryPacedStreamProducer");
            paced.start(); paced.join(5000);
            assertTrue("Paced producer stalled", !paced.isAlive());
            Thread.sleep(300);
            state = streamState(host);
            assertTrue("Jittered producer lost samples in " + mode, state.getJSONArray("jitterValues").length() >= 17);
            assertTrue("Half-period producer was not thinned in " + mode,
                state.getJSONArray("halfValues").length() >= 16 && state.getJSONArray("halfValues").length() <= 24);
            Thread.sleep(300);
            assertTrue("Rate cap failed in " + mode, streamState(host).getInt("rateCount") == 1);
            CountDownLatch paused = new CountDownLatch(1);
            activity.runOnUiThread(() -> { if ("shared".equals(mode)) activity.secondaryWebViews().onPause(); else activity.isolatedSecondaryWebViews().onPause(); paused.countDown(); });
            assertTrue(paused.await(2, TimeUnit.SECONDS));
            assertTrue("Subscription survived background in " + mode, !SecondaryWebViewStreams.hasSubscriber("nativeBurst"));
            assertTrue("Rate survived background in " + mode, SecondaryWebViewStreams.maxRateHz("nativeFast") == 0);
            assertTrue("Batch rate survived background in " + mode, SecondaryWebViewStreams.maxRateHz("nativeBatchOnly") == 0);
            SecondaryWebViewStreams.push("nativeBurst", 4);
            Thread.sleep(100);
            assertTrue("Unsubscribed push was delivered in " + mode, streamState(host).getJSONObject("burst").getInt("latest") == 3);
            CountDownLatch resumed = new CountDownLatch(1);
            activity.runOnUiThread(() -> { if ("shared".equals(mode)) activity.secondaryWebViews().onResume(); else activity.isolatedSecondaryWebViews().onResume(); resumed.countDown(); });
            assertTrue(resumed.await(2, TimeUnit.SECONDS));
            Thread.sleep(300);
            assertTrue("Subscriber returned without resubscribe in " + mode, !SecondaryWebViewStreams.hasSubscriber("nativeBurst"));
            CountDownLatch destroyed = new CountDownLatch(1);
            activity.runOnUiThread(() -> { if ("shared".equals(mode)) activity.secondaryWebViews().destroy(); else activity.isolatedSecondaryWebViews().destroy(); destroyed.countDown(); });
            assertTrue(destroyed.await(2, TimeUnit.SECONDS));
            assertTrue("Rate survived destroy in " + mode, SecondaryWebViewStreams.maxRateHz("nativeBurst") == 0);
            assertTrue("Rate survived destroy in " + mode, SecondaryWebViewStreams.maxRateHz("nativeRate") == 0);
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
