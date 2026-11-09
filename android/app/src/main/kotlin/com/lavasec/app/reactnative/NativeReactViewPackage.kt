package com.lavasec.app.reactnative

import com.facebook.react.ReactPackage
import com.facebook.react.ViewManagerOnDemandReactPackage
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import com.facebook.react.views.view.ReactViewManager

/** RN 0.87's bridgeless resolver uses the first on-demand match, before eager packages. */
internal class NativeReactViewPackage : ReactPackage, ViewManagerOnDemandReactPackage {
    override fun getViewManagerNames(reactContext: ReactApplicationContext): Collection<String> =
        listOf(ReactViewManager.REACT_CLASS)

    override fun createViewManager(
        reactContext: ReactApplicationContext,
        viewManagerName: String,
    ): ViewManager<*, *>? =
        if (viewManagerName == ReactViewManager.REACT_CLASS) NativeReactViewManager() else null

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        listOf(NativeReactViewManager())
}
