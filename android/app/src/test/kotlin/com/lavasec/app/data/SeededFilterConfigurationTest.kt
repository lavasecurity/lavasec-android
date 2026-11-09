package com.lavasec.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeededFilterConfigurationTest {
    @Test
    fun `app selection produces exact signed-catalog filter draft and identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = CatalogRepository(context.assets).loadBundled()
        val appConfiguration = AppConfiguration.default().copy(
            protectionLevel = ProtectionLevel.EXTRA,
            activeFilterId = ProtectionLevel.EXTRA.seededFilterId,
            resolver = MilestoneResolverPlan.MULLVAD_ONLY.configuration,
        )

        val seeded = SeededFilterConfiguration.from(appConfiguration, catalog, generation = 7L)

        assertEquals(ProtectionLevel.EXTRA.seededFilterId, seeded.draft.activeProfileId)
        assertEquals(MilestoneResolverPlan.MULLVAD_ONLY, seeded.draft.resolverPlan)
        assertEquals(
            catalog.filterSnapshot.sources
                .filter { it.defaultEnabled || it.category.name == "ADS_TRACKING" }
                .map { it.id }
                .toSet(),
            seeded.draft.profile(ProtectionLevel.EXTRA.seededFilterId)?.enabledSourceIds,
        )
        assertEquals(7L, seeded.identity.generation)
        assertEquals(
            canonicalFilterConfigurationFingerprint(
                seeded.draft,
                catalog.revision,
                catalog.sha256,
            ),
            seeded.identity.fingerprint,
        )
    }
}
