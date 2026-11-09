package com.lavasec.app.edition

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.data.BundledCatalogRefreshOwner
import com.lavasec.app.data.CatalogBundledArtifact
import com.lavasec.app.data.CatalogRepository
import com.lavasec.app.foundation.catalog.CatalogProvenance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class FossCatalogRefreshTest {
    @Test fun `unchanged bundled metadata still re-enters source compilation for every explicit refresh`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundled = CatalogBundledArtifact.from(CatalogRepository(context.assets).loadBundled())
        var currentSourceBytes = "source-version-one"
        var installedSourceBytes = "old-filter"
        var installations = 0
        val owner = BundledCatalogRefreshOwner(bundled) { artifact ->
            assertSame(bundled, artifact)
            assertEquals(CatalogProvenance.BUNDLED, artifact.provenance)
            assertTrue(artifact.manifest.sources.all { it.sourceUrl.startsWith("https://") })
            installations++
            installedSourceBytes = currentSourceBytes
            true
        }
        assertTrue(owner.refresh())
        assertEquals("source-version-one", installedSourceBytes)
        currentSourceBytes = "source-version-two"
        assertTrue(owner.refresh())
        assertEquals("source-version-two", installedSourceBytes)
        assertEquals(2, installations)
    }

    @Test fun `failed upstream refresh reports failure without inventing a catalog or fallback success`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundled = CatalogBundledArtifact.from(CatalogRepository(context.assets).loadBundled())
        var attempts = 0
        val owner = BundledCatalogRefreshOwner(bundled) { attempts++; false }
        assertFalse(owner.refresh())
        assertEquals(1, attempts)
    }
    @Test fun `an upstream exception remains a failed refresh and cancellation still propagates`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundled = CatalogBundledArtifact.from(CatalogRepository(context.assets).loadBundled())
        assertFalse(BundledCatalogRefreshOwner(bundled) { throw java.io.IOException("offline") }.refresh())
        try {
            BundledCatalogRefreshOwner(bundled) { throw kotlinx.coroutines.CancellationException("retired") }.refresh()
            fail("Retirement must cancel refresh")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

}
