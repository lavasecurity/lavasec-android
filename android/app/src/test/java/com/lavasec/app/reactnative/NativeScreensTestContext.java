package com.lavasec.app.reactnative;

import android.content.Context;
import com.facebook.react.bridge.BridgeReactContext;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.uimanager.events.BlackHoleEventDispatcher;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.uimanager.events.EventDispatcherProvider;

/**
 * RN 0.87's JVM context contract for isolated native-header tests. Java can
 * implement the library's JVM-public, Kotlin-internal dispatcher interface.
 * Header/Fragment code is real; the absent JS engine receives RN's own empty
 * dispatcher, as specified by EventDispatcherProvider for an unready instance.
 */
@SuppressWarnings("deprecation")
public final class NativeScreensTestContext extends BridgeReactContext implements EventDispatcherProvider {
    public NativeScreensTestContext(Context context) {
        super(context);
    }

    @Override public boolean isBridgeless() {
        return true;
    }

    @Override public void runOnUiQueueThread(Runnable runnable) {
        UiThreadUtil.assertOnUiThread();
        runnable.run();
    }

    @Override public EventDispatcher getEventDispatcher() {
        return BlackHoleEventDispatcher.INSTANCE;
    }
}
