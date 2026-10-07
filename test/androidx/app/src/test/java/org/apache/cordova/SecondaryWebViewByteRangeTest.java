/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SecondaryWebViewByteRangeTest {
    private void check(String header, long size, int status, long start, long length) {
        SecondaryWebViewByteRange range = SecondaryWebViewByteRange.parse(header, size);
        assertEquals(header, status, range.status); assertEquals(header, start, range.start); assertEquals(header, length, range.length);
    }
    @Test public void singleRangesClampWithoutIntegerOverflow() {
        check("bytes=0-15", 1024, 206, 0, 16); check("bytes=1000-", 1024, 206, 1000, 24);
        check("bytes=-16", 1024, 206, 1008, 16); check("bytes=-2000", 1024, 206, 0, 1024);
        check("bytes=1016-2000", 1024, 206, 1016, 8);
        check("bytes=0-999999999999999999999", 1024, 206, 0, 1024);
        check("bytes=4294967296-4294967311", 4294967312L, 206, 4294967296L, 16);
        assertEquals("bytes 0-15/1024", SecondaryWebViewByteRange.parse("bytes=0-15", 1024).contentRange());
    }
    @Test public void unsatisfiableRangesIncludeEmptyFilesAndZeroSuffix() {
        check("bytes=1024-", 1024, 416, 0, 0); check("bytes=-0", 1024, 416, 0, 0);
        check("bytes=999999999999999999999-", 1024, 416, 0, 0);
        check("bytes=0-0", 0, 416, 0, 0); check("bytes=-1", 0, 416, 0, 0);
        assertEquals("bytes */1024", SecondaryWebViewByteRange.parse("bytes=1024-", 1024).contentRange());
    }
    @Test public void malformedAndMultipleRangesAreIgnored() {
        for (String header : new String[] {null, "bytes=0-1,4-5", "bytes=broken", "bytes=9-3", "items=0-15", "bytes=-", "bytes=1.5-2", "bytes=999999999999999999999-9223372036854775808", "bytes=0-15\n"}) check(header, 1024, 200, 0, 1024);
        check(null, 0, 200, 0, 0);
    }
}
