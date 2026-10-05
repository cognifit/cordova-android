/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
*/

package org.apache.cordova;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.splashscreen.SplashScreenViewProvider;

import org.json.JSONArray;
import org.json.JSONException;

@SuppressLint("LongLogTag")
public class SplashScreenPlugin extends CordovaPlugin {
    static final String PLUGIN_NAME = "CordovaSplashScreenPlugin";

    // Default config preference values
    private static final boolean DEFAULT_AUTO_HIDE = true;
    private static final int DEFAULT_DELAY_TIME = -1;
    private static final String DEFAULT_HIDE_ON = "pageFinished";
    private static final boolean DEFAULT_FADE = true;
    private static final int DEFAULT_FADE_TIME = 500;

    // Config preference values
    /**
     * Boolean flag to auto hide splash screen (default=true)
     */
    private boolean autoHide;
    /**
     * Integer value of how long to delay in milliseconds (default=-1)
     */
    private int delayTime;
    private String hideOn;
    private int maxDelayTime;
    /**
     * Boolean flag if to fade to fade out splash screen (default=true)
     */
    private boolean isFadeEnabled;
    /**
     * Integer value of the fade duration in milliseconds (default=500)
     */
    private int fadeDuration;

    // Internal variables
    /**
     * Boolean flag to determine if the splash screen remains visible.
     */
    private volatile boolean keepOnScreen = true;
    private long setupTimeMs;
    private int pageStarts;
    private boolean firstPaintRequested;

    @Override
    protected void pluginInitialize() {
        // Auto Hide & Delay Settings
        autoHide = preferences.getBoolean("AutoHideSplashScreen", DEFAULT_AUTO_HIDE);
        delayTime = preferences.getInteger("SplashScreenDelay", DEFAULT_DELAY_TIME);
        hideOn = preferences.getString("SplashScreenHideOn", DEFAULT_HIDE_ON);
        maxDelayTime = preferences.getInteger("SplashScreenMaxDelay", DEFAULT_DELAY_TIME);
        LOG.d(PLUGIN_NAME, "Auto Hide: " + autoHide);
        if (delayTime != DEFAULT_DELAY_TIME) {
            LOG.d(PLUGIN_NAME, "Delay: " + delayTime + "ms");
        }

        // Fade & Fade Duration
        isFadeEnabled = preferences.getBoolean("FadeSplashScreen", DEFAULT_FADE);
        fadeDuration = preferences.getInteger("FadeSplashScreenDuration", DEFAULT_FADE_TIME);
        LOG.d(PLUGIN_NAME, "Fade: " + isFadeEnabled);
        if (isFadeEnabled) {
            LOG.d(PLUGIN_NAME, "Fade Duration: " + fadeDuration + "ms");
        }
    }

    @Override
    public boolean execute(
        String action,
        JSONArray args,
        CallbackContext callbackContext
    ) throws JSONException {
        if (action.equals("hide") && autoHide == false) {
            /*
             * The `.hide()` method can only be triggered if the `splashScreenAutoHide`
             * is set to `false`.
             */
            hideSplash("js");
        } else {
            return false;
        }

        callbackContext.success();
        return true;
    }

    @Override
    public Object onMessage(String id, Object data) {
        switch (id) {
            case "setupSplashScreen":
                setupSplashScreen((SplashScreen) data);
                break;

            case "onPageFinished":
                attemptCloseOnPageFinished();
                break;

            case "onPageStarted":
                if (usesFirstPaint() && pageStarts < 2) {
                    pageStarts++;
                }
                break;

            case "onPageCommitVisible":
                attemptCloseOnFirstPaint();
                break;
        }

        return null;
    }

    private void setupSplashScreen(SplashScreen splashScreen) {
        setupTimeMs = SystemClock.uptimeMillis();
        // Setup Splash Screen Delay
        splashScreen.setKeepOnScreenCondition(() -> keepOnScreen);

        Handler splashScreenDelayHandler = new Handler(cordova.getContext().getMainLooper());

        // auto hide splash screen when custom delay is defined.
        if (autoHide && delayTime != DEFAULT_DELAY_TIME) {
            splashScreenDelayHandler.postDelayed(() -> hideSplash("delay"), delayTime);
        }

        if (maxDelayTime >= 0) {
            splashScreenDelayHandler.postAtTime(() -> hideSplash("maxDelay"),
                    setupTimeMs + maxDelayTime);
        }

        // Without a fixed delay, auto hide is controlled by either onPageFinished or
        // the first page's visual state callback.

        // If auto hide is disabled (false), the hiding of the splash screen must be determined &
        // triggered by the front-end code with the `navigator.splashscreen.hide()` method.

        if (isFadeEnabled) {
            // Setup the fade
            splashScreen.setOnExitAnimationListener(new SplashScreen.OnExitAnimationListener() {
                @Override
                public void onSplashScreenExit(@NonNull SplashScreenViewProvider splashScreenViewProvider) {
                    View splashScreenView = splashScreenViewProvider.getView();

                    splashScreenView
                            .animate()
                            .alpha(0.0f)
                            .setDuration(fadeDuration)
                            .setStartDelay(0)
                            .setInterpolator(new AccelerateInterpolator())
                            .setListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    super.onAnimationEnd(animation);
                                    splashScreenViewProvider.remove();
                                    webView.getPluginManager().postMessage("updateSystemBars", null);
                                }
                            }).start();
                }
            });
        } else {
            webView.getPluginManager().postMessage("updateSystemBars", null);
        }
    }

    private void attemptCloseOnPageFinished() {
        // Also serves as a fallback when the first paint could not be observed.
        if (autoHide && delayTime == DEFAULT_DELAY_TIME) {
            hideSplash("pageFinished");
        }
    }

    private boolean usesFirstPaint() {
        return autoHide && delayTime == DEFAULT_DELAY_TIME && "firstPaint".equals(hideOn);
    }

    private void attemptCloseOnFirstPaint() {
        if (!usesFirstPaint() || !keepOnScreen || pageStarts != 1 || firstPaintRequested) {
            return;
        }
        View view = webView.getView();
        if (view instanceof WebView) {
            firstPaintRequested = true;
            ((WebView) view).postVisualStateCallback(0, new WebView.VisualStateCallback() {
                @Override
                public void onComplete(long requestId) {
                    if (pageStarts == 1) {
                        hideSplash("firstPaint");
                    }
                }
            });
        }
    }

    private synchronized void hideSplash(String trigger) {
        if (keepOnScreen) {
            keepOnScreen = false;
            LOG.d(PLUGIN_NAME, "Splash hidden by " + trigger + " after "
                    + (SystemClock.uptimeMillis() - setupTimeMs) + "ms");
        }
    }
}
