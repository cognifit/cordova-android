package org.apache.cordova;

import android.os.ParcelFileDescriptor;

oneway interface ISecondaryWebViewCallback {
    void onEvent(String sessionId, in ParcelFileDescriptor event);
}
