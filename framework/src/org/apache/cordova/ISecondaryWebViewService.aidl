package org.apache.cordova;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.apache.cordova.ISecondaryWebViewCallback;

interface ISecondaryWebViewService {
    boolean ping();
    Bundle create(in Bundle request, ISecondaryWebViewCallback callback);
    void destroy(String sessionId);
    Bundle navigate(String sessionId, String url);
    Bundle send(String sessionId, in ParcelFileDescriptor payload);
    Bundle setTouchRegions(String sessionId, String regions);
    Bundle subscriptionControl(String sessionId, in ParcelFileDescriptor payload);
    ParcelFileDescriptor getMetrics(String sessionId);
    void resize(String sessionId, int width, int height);
    void setBackgrounded(String sessionId, boolean backgrounded);
    oneway void pushSamples(String sessionId, in ParcelFileDescriptor payload);
    void trimMemory(String sessionId, int level);
}
