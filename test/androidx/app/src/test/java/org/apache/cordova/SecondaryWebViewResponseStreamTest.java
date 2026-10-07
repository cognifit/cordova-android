/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;
import java.io.ByteArrayInputStream;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SecondaryWebViewResponseStreamTest {
    @Test public void originalSizeAndInitialSeekDoNotDiscardSelectedBytes() throws Exception {
        SecondaryWebViewResponseStream input = new SecondaryWebViewResponseStream(new ByteArrayInputStream(new byte[] {7, 8}), 1024, 2);
        assertEquals(1024, input.available()); assertEquals(1022, input.skip(1022));
        assertEquals(7, input.read()); assertEquals(1, input.available()); assertEquals(8, input.read()); assertEquals(-1, input.read());
    }
    @Test public void completedPipeLengthIsIndependentOfUnderlyingAvailable() throws Exception {
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[] {1, 2, 3}) { @Override public int available() { return 0; } };
        SecondaryWebViewResponseStream input = new SecondaryWebViewResponseStream(source, 3, 3);
        assertEquals(3, input.available()); byte[] bytes = new byte[3]; assertEquals(3, input.read(bytes)); assertEquals(0, input.available());
        input.close();
    }
}
