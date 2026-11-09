package com.lavasec.app.reactnative

import com.lavasec.app.reactnative.components.LavaNativePageManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the registered names of the Android platform leaves that back the
 * shared native specs. `LavaTextField`, `LavaChoice`, `LavaSwitch` and
 * `LavaContextMenu` are implemented as React Native platform siblings and need no
 * view-manager registration; `LavaNativePage` is an embedded containment host and
 * is registered. The JS `requireNativeComponent` names below must match these
 * managers or Fabric cannot resolve the leaf.
 *
 * Pure JVM (no `ThemedReactContext`): constructing the managers and reading their
 * declared names does not touch the Android runtime, so this runs on the aarch64
 * host where Robolectric/Fabric cannot.
 */
class LavaPlatformViewsTest {
    @Test
    fun `registered view managers match the names the shared specs request`() {
        assertEquals("LavaDecoration", LavaDecorationManager().name)
        assertEquals("LavaSlider", LavaSliderManager().name)
        assertEquals("LavaNativePage", LavaNativePageManager().name)
    }
}
