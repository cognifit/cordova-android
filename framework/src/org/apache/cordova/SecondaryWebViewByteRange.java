/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;

/** Single HTTP byte range. Invalid syntax falls back to the complete representation. */
final class SecondaryWebViewByteRange {
    private static final java.util.regex.Pattern PATTERN = java.util.regex.Pattern.compile("(?i)^bytes=([0-9]*)-([0-9]*)$");
    final int status;
    final long start, length, size;
    private SecondaryWebViewByteRange(int status, long start, long length, long size) {
        this.status = status; this.start = start; this.length = length; this.size = size;
    }
    static SecondaryWebViewByteRange parse(String header, long size) {
        SecondaryWebViewByteRange full = new SecondaryWebViewByteRange(200, 0, size, size);
        if (header == null) return full;
        java.util.regex.Matcher match = PATTERN.matcher(trim(header));
        if (!match.matches() || match.group(1).isEmpty() && match.group(2).isEmpty()) return full;
        String first = match.group(1), last = match.group(2);
        long start, end;
        if (first.isEmpty()) {
            long suffix = decimal(last);
            if (suffix == 0 || size == 0) return new SecondaryWebViewByteRange(416, 0, 0, size);
            start = suffix >= size ? 0 : size - suffix; end = size - 1;
        } else {
            start = decimal(first); end = last.isEmpty() ? Long.MAX_VALUE : decimal(last);
            if (!last.isEmpty() && compareDecimal(last, first) < 0) return full;
            if (start >= size) return new SecondaryWebViewByteRange(416, 0, 0, size);
            end = Math.min(end, size - 1);
        }
        return new SecondaryWebViewByteRange(206, start, end - start + 1, size);
    }
    private static String trim(String value) {
        int first = 0, last = value.length();
        while (first < last && (value.charAt(first) == ' ' || value.charAt(first) == '\t')) first++;
        while (last > first && (value.charAt(last - 1) == ' ' || value.charAt(last - 1) == '\t')) last--;
        return value.substring(first, last);
    }
    private static int compareDecimal(String left, String right) {
        left = left.replaceFirst("^0+(?!$)", ""); right = right.replaceFirst("^0+(?!$)", "");
        return left.length() == right.length() ? left.compareTo(right) : Integer.compare(left.length(), right.length());
    }
    private static long decimal(String value) {
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = value.charAt(i) - '0';
            if (result > (Long.MAX_VALUE - digit) / 10) return Long.MAX_VALUE;
            result = result * 10 + digit;
        }
        return result;
    }
    String contentRange() {
        return status == 416 ? "bytes */" + size : "bytes " + start + "-" + (start + length - 1) + "/" + size;
    }
}
