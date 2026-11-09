package com.lavasec.app.reactnative

import android.view.View
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.internal.featureflags.ReactNativeFeatureFlags
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.views.view.ReactViewGroup
import com.facebook.react.views.view.ReactViewManager
import com.lavasec.app.BuildConfig

/**
 * Fabric can move a child while Screens retains the outgoing tree for its animation.
 * Android removes that child from the old group's array but keeps its parent until
 * endViewTransition. Fabric's subsequent removeView is then a no-op, so its actual
 * insertion edge must release only that already-removed child's transition custody.
 */
internal class NativeReactViewManager : ReactViewManager() {
    override fun createViewInstance(context: ThemedReactContext): ReactViewGroup =
        if (BuildConfig.DEBUG) NativeMeasuredReactView(context) else super.createViewInstance(context)

    override fun onDropViewInstance(view: ReactViewGroup) {
        (view as? NativeMeasuredReactView)?.clearMeasurementTrace()
        super.onDropViewInstance(view)
    }

    init {
        // ReactViewManager enables recycling only for its exact concrete class.
        // This adapter retains the same opt-in and inherited reset/prop behavior.
        if (ReactNativeFeatureFlags.enableViewRecyclingForView()) setupViewRecycling()
    }

    override fun addView(parent: ReactViewGroup, child: View, index: Int) {
        UiThreadUtil.assertOnUiThread()
        val former = child.parent as? ReactViewGroup
        if (former != null && former !== parent &&
            !former.removeClippedSubviews && !parent.removeClippedSubviews &&
            index in 0..parent.childCount &&
            former.indexOfChild(child) == -1 &&
            former.context === parent.context && child.context === parent.context &&
            child.parent === former
        ) {
            // Do not remove an indexed/clipped child or end the whole screen's
            // transition. Unknown retention still reaches RN's original failure.
            former.endViewTransition(child)
        }
        super.addView(parent, child, index)
    }
}
