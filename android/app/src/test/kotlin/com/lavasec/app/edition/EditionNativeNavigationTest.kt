package com.lavasec.app.edition

import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation
import org.junit.Assert.*
import org.junit.Test

class EditionNativeNavigationTest {
    @Test fun `FOSS legacy commercial report and Settings entries return to edition-aware React Settings`() {
        for (destination in listOf(ProductDestination.Account, ProductDestination.Upgrade, ProductDestination.BugReport)) {
            val navigation = ProductNavigation.settings.open(destination)
            assertTrue(EditionNativeNavigation.requiresReactSettings(navigation, AppEdition.FOSS))
            assertFalse(EditionNativeNavigation.requiresReactSettings(navigation, AppEdition.PLAY))
        }
        assertTrue(EditionNativeNavigation.requiresReactSettings(ProductNavigation.settings, AppEdition.FOSS))
        assertFalse(EditionNativeNavigation.requiresReactSettings(ProductNavigation.settings.open(ProductDestination.Security), AppEdition.FOSS))
        assertFalse(EditionNativeNavigation.requiresReactSettings(ProductNavigation.guard, AppEdition.FOSS))
    }
}
