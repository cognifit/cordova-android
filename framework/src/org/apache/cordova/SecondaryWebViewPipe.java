/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded channel payload transport. Each pipe owns its writer; reads have a deadline. */
final class SecondaryWebViewPipe {
    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final long RECEIVE_TIMEOUT_MS = 5000;
    private static final AtomicInteger nextWriter = new AtomicInteger();

    static ParcelFileDescriptor send(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("MESSAGE_TOO_LARGE");
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createPipe();
        Thread writer = new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pair[1])) { out.write(bytes); }
            catch (IOException ignored) { }
        }, "SecondaryWebViewPipe-" + nextWriter.incrementAndGet());
        writer.setDaemon(true);
        writer.start();
        return pair[0];
    }

    static void checkJsonDepth(String text) throws IOException {
        if (text == null || text.length() > MAX_BYTES) throw new IOException("MESSAGE_TOO_LARGE");
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') quoted = true;
            else if (ch == '{' || ch == '[') { if (++depth > MAX_DEPTH) throw new IOException("JSON_DEPTH_EXCEEDED"); }
            else if (ch == '}' || ch == ']') depth--;
        }
    }

    static String receive(ParcelFileDescriptor descriptor) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + RECEIVE_TIMEOUT_MS;
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16384];
            while (true) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new IOException("PIPE_RECEIVE_TIMEOUT");
                StructPollfd poll = new StructPollfd();
                poll.fd = descriptor.getFileDescriptor();
                poll.events = (short)(OsConstants.POLLIN | OsConstants.POLLHUP);
                try { if (Os.poll(new StructPollfd[] { poll }, (int)remaining) == 0) throw new IOException("PIPE_RECEIVE_TIMEOUT"); }
                catch (ErrnoException e) { throw new IOException("Pipe poll failed", e); }
                int count = in.read(chunk);
                if (count == -1) break;
                if (out.size() + count > MAX_BYTES) throw new IOException("MESSAGE_TOO_LARGE");
                out.write(chunk, 0, count);
            }
            String text = out.toString(StandardCharsets.UTF_8.name());
            checkJsonDepth(text);
            return text;
        }
    }
    private SecondaryWebViewPipe() { }
}
