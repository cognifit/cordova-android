/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.cordova;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebView;

/** Runs before Application.onCreate in the isolated process, before any WebView class is used there. */
public final class SecondaryWebViewProcessInit extends ContentProvider {
    static volatile boolean configured;
    @Override public boolean onCreate() {
        if (Build.VERSION.SDK_INT >= 30 && (getContext().getPackageName() + ":secondarywebview").equals(Application.getProcessName())) {
            WebView.setDataDirectorySuffix("secondarywebview");
            configured = true;
        }
        return true;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
