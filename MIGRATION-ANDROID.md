# CogniFit Cordova Android 15.1 migration

Base: Apache Cordova Android 15.1.0 (`034075d96ad1b83a2cb1d1b6f2fcef80f794cc1f`).
Branch: `codex/15.1-independent-webviews`.

## Model

`CordovaActivity` has one main `CordovaWebView` at a time. Registered main apps
replace that view, its engine, command queue and plugin manager. Plugins are
therefore disposed and initialized again. Do not assume a third-party plugin can
be initialized twice; fix such plugins individually. Old game result routes and
main callback interceptors are removed before replacement.

An optional game is a plain Android `WebView`: it has no Cordova engine, plugin
manager, `cordova.js`, plugin wrappers or compatibility mode. It uses only the
injected `window.host` object. Treat game content as trusted app-owned content.

## Main applications

Call this after `init()` and before the first main load. Relative roots are under
`assets/`; absolute paths and `file://` roots support downloaded app-private
content.

```java
Map<String, IndependentWebViewHost.WebApp> apps = new LinkedHashMap<>();
apps.put("home", new IndependentWebViewHost.WebApp("www-home", "index.html"));
apps.put("assessment", new IndependentWebViewHost.WebApp(
    getFilesDir() + "/assessment", "index.html"));
registerWebApps(apps, "home");
switchToWebApp("assessment", true, new JSONObject().put("assessmentId", 123));
```

The incoming document receives `window.cordovaWebApp` at document start where
the installed WebView supports AndroidX document-start scripts. It contains
`{appId, context}`. The context must be JSON-compatible. A `true` return from
`switchToWebApp` means replacement began, not that the application started.

Call `confirmWebAppReady()` only after business readiness. That atomically saves
the pending ID for the next cold boot and hides the transition overlay; startup
failure preserves the old saved choice. `remember=false` is session-only.
`clearRememberedWebApp()` removes the stored ID. Use
`setWebAppSelectionKey()` before registration to use another preference key.

## Games and host bridge

```java
createGameWebView(getFilesDir() + "/game", "index.html?level=1");
showGameWebView("<main>Loading game…</main>");
// hideGameWebView() retains a ready game; destroyGameWebView() releases it.
postMessageToGame(new JSONObject().put("type", "pause"));
```

In the game:

```js
await host.ready();                 // removes its startup overlay
const plugins = await host.getLoadedPlugins();
const value = await host.callPlugin('Device', 'getDeviceInfo', []);
const stop = host.subscribePlugin('MyPlugin', 'watch', [], onValue, onError);
stop();                             // stops delivery, not native work
await host.postMessage({ type: 'finished', score: 120 });
host.onmessage = message => console.log(message);
```

Discovery reports instantiated plugins in the main environment only, with
service names and implementation classes. Calls use that same plugin manager and
can lazily instantiate a registered service. There are no action allowlists or
plugin adapters. Calls support Cordova result types, including binary/multipart
payload values, and keep-callback streams. Requests have a 30 second default
timeout; pass `0` to `callPlugin` to disable it. Timeout/unsubscribe removes the
route but cannot generically cancel native work—call the plugin's own stop action.

`setIndependentWebViewEventListener` receives `message`, `loadFailed`, and
`terminated` events. Main-page JavaScript needs an app-owned Cordova plugin if it
must invoke `postMessageToGame`; no bridge is injected into the main page.

## Overlays, lifecycle, and Android differences

`showLoadingScreen(null)` uses a native spinner. Passing HTML creates a separate
vanilla WebView and `hideLoadingScreen()` destroys it. Game overlays remain until
`host.ready()`, hide, or destruction. No packaged loading document is required.

On game renderer termination Android requires the dead WebView to be removed and
destroyed; this implementation does so and emits `terminated`. It never reloads
the game or selects a fallback. Main renderer termination continues through
Cordova's existing plugin hooks; consuming applications own checkpoint/retry
policy. Android does not guarantee separate renderer processes for two WebViews;
cookies, DOM storage and file origins may be shared according to the system
WebView, and `destroy()` does not promise immediate GPU/renderer/cache reclamation.

## Validation

Validated locally on 2026-09-14 with JDK 21, Android Gradle Plugin 8.10.1 and
Gradle 8.14.2: `npm run lint` passed; the AndroidX Gradle `test` task passed
(debug and release unit-test variants). The repository's normal Java-test runner
could not bootstrap because no global `gradle` executable was installed; its test
project was run using the cached Gradle distribution instead. The implementation
has not been exercised on a physical device. In particular, verify the real
plugin inventory, repeated switches, downloaded-file CSP/origin behavior,
binary/multipart plugin results, and actual memory-pressure renderer termination.
Unit termination callbacks are simulations, not OS process-kill tests.
