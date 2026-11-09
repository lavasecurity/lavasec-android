package com.lavasec.app.edition

import com.lavasec.app.presentation.ProductDestination
import com.lavasec.app.presentation.ProductNavigation

/** Legacy native hosts must return to the edition-aware Settings surface. */
internal object EditionNativeNavigation {
    fun requiresReactSettings(navigation: ProductNavigation, edition: AppEdition = AppEdition.current): Boolean =
        edition.isFoss && navigation.path.lastOrNull() in setOf(
            ProductDestination.Settings, ProductDestination.Account,
            ProductDestination.Upgrade, ProductDestination.BugReport,
        )
}
