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

    private static void reject(Object value) throws Exception {
        try { SecondaryWebViewStreams.snapshot(value); fail("Accepted non-JSON sample"); }
        catch (JSONException expected) { assertTrue(expected.getMessage().contains("INVALID_JSON")); }
    }
}
