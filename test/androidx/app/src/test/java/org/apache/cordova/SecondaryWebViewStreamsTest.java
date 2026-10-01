/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SecondaryWebViewStreamsTest {
    @Test public void snapshotAcceptsJsonValuesAndRejectsNonFiniteNumbers() throws Exception {
        assertEquals(JSONObject.NULL, SecondaryWebViewStreams.snapshot(null));
        assertEquals("ok", SecondaryWebViewStreams.snapshot("ok"));
        assertEquals(1, SecondaryWebViewStreams.snapshot(1));
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            reject(invalid);
            reject(Arrays.asList(1, invalid));
            Map<String, Object> nested = new HashMap<>(); nested.put("nested", Arrays.asList(invalid)); reject(nested);
        }
        Map<String, Object> value = new HashMap<>(); value.put("numbers", Arrays.asList(1, 2));
        JSONObject snapshot = (JSONObject)SecondaryWebViewStreams.snapshot(value);
        value.put("numbers", Arrays.asList(3));
        assertEquals("[1,2]", snapshot.getJSONArray("numbers").toString());
        assertEquals("[1,2]", SecondaryWebViewStreams.snapshot(new JSONArray("[1,2]")).toString());
        assertEquals("{\"ok\":true}", SecondaryWebViewStreams.snapshot(new JSONObject("{\"ok\":true}")).toString());
        reject(new float[] {1f, 2f});
        reject(new Object());
    }

    @Test public void noSubscriberIsFalseAndPushIsNoOp() {
        assertFalse(SecondaryWebViewStreams.hasSubscriber("missing"));
        assertEquals(0.0, SecondaryWebViewStreams.maxRateHz("missing"), 0.0);
        SecondaryWebViewStreams.push("missing", Double.NaN);
        assertFalse(SecondaryWebViewStreams.hasSubscriber("missing"));
        assertEquals(0.0, SecondaryWebViewStreams.maxRateHz("missing"), 0.0);
    }

    @Test public void rateGateUsesNinetyPercentAndSerializesProducers() throws Exception {
        SecondaryWebViewStreams.RateGate gate = new SecondaryWebViewStreams.RateGate();
        long period = 50_000_000L;
        assertTrue(gate.reserveAt(1_000_000_000L, 20)); gate.finish(true);
        assertFalse(gate.reserveAt(1_000_000_000L + period * 89 / 100, 20));
        assertTrue(gate.reserveAt(1_000_000_000L + period * 91 / 100, 20)); gate.finish(false);
        assertTrue(gate.reserveAt(1_000_000_000L + period * 91 / 100, 20)); gate.finish(true);

        gate = new SecondaryWebViewStreams.RateGate();
        long now = 1_000_000_000L;
        int accepted = 0;
        for (int i = 0; i < 20; i++) {
            if (gate.reserveAt(now, 20)) { accepted++; gate.finish(true); }
            now += period * (i % 2 == 0 ? 106 : 94) / 100;
        }
        assertEquals(20, accepted);
        gate = new SecondaryWebViewStreams.RateGate(); now = 1_000_000_000L; accepted = 0;
        for (int i = 0; i < 40; i++) {
            if (gate.reserveAt(now, 20)) { accepted++; gate.finish(true); }
            now += period / 2;
        }
        assertTrue(accepted >= 19 && accepted <= 21);

        SecondaryWebViewStreams.RateGate concurrent = new SecondaryWebViewStreams.RateGate();
        AtomicInteger concurrentAccepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(2);
        Runnable producer = () -> {
            try {
                start.await();
                if (concurrent.reserveAt(1_000_000_000L, 20)) {
                    concurrentAccepted.incrementAndGet();
                    Thread.sleep(10);
                    concurrent.finish(true);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        };
        new Thread(producer).start(); new Thread(producer).start(); start.countDown();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(1, concurrentAccepted.get());
    }

    private static void reject(Object value) throws Exception {
        try { SecondaryWebViewStreams.snapshot(value); fail("Accepted non-JSON sample"); }
        catch (JSONException expected) { assertTrue(expected.getMessage().contains("INVALID_JSON")); }
    }
}
