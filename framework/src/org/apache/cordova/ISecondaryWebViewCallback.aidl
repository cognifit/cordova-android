package org.apache.cordova;

import android.os.ParcelFileDescriptor;

oneway interface ISecondaryWebViewCallback {
    void onEvent(String sessionId, in ParcelFileDescriptor event);
    void onSubscriptions(String sessionId, in String[] names, in int[] ratesHz, in int[] batchFlags);
}
