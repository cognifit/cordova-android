/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;

/** Adapts an already selected response to Chromium's automatic initial range seek. */
final class SecondaryWebViewResponseStream extends FilterInputStream {
    private final long representationSize;
    private long remaining;
    private boolean started;
    SecondaryWebViewResponseStream(InputStream input, long representationSize, long length) {
        super(input); this.representationSize = representationSize; this.remaining = length;
    }
    @Override public int available() {
        // Chromium derives Content-Length from this before its seek. Pipe.available() is only
        // currently queued bytes, not the completed body's length. A range must expose the
        // original file size here so Chromium computes the selected length, not a second range.
        return (int)Math.min(Integer.MAX_VALUE, Math.max(0, started ? remaining : representationSize));
    }
    @Override public long skip(long count) throws IOException {
        // The file was already sought and bounded while holding the read permit.
        if (!started) return Math.max(0, count);
        long skipped = super.skip(count); remaining -= skipped; return skipped;
    }
    @Override public int read() throws IOException {
        started = true; int value = in.read(); if (value >= 0) remaining--; return value;
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        started = true; int count = in.read(bytes, offset, length); if (count > 0) remaining -= count; return count;
    }
}
