package com.lavasec.app.edition

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.account.AccountAvailability
import com.lavasec.app.account.AccountBackend
import com.lavasec.app.backend.NativeApplicationServices
import com.lavasec.app.backup.AndroidBackupPasskeyPrfCapability
import com.lavasec.app.billing.NativePlusBackend
import com.lavasec.app.foundation.subscription.FeatureLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class FossServiceBoundaryTest {
    @Test fun `FOSS construction cannot open Lava services credentials or billing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertSame(AppEdition.FOSS, AppEdition.current)
        assertFalse(AppEdition.current.accounts)
        assertFalse(AppEdition.current.dynamicCatalog)
        assertFalse(AppEdition.current.nativeSupport)
        val services = NativeApplicationServices(context)
        assertNull(services.primary)
        val accounts = AccountBackend(context)
        assertFalse(accounts.googleSignIn.configured)
        assertFalse(accounts.appleSignIn.configured)
        assertNull(accounts.cloud)
        assertNull(accounts.backups)
        assertEquals(AccountAvailability.NOT_CONFIGURED, accounts.sessions.state.value.availability)
        assertFalse(AndroidBackupPasskeyPrfCapability.production(context).isAvailable())
        val plus = NativePlusBackend(context, MutableStateFlow(true), null)
        try {
            assertTrue(plus.entitlement.initialize())
            assertEquals(FeatureLimits.FOSS, plus.entitlement.state.value.limits)
            assertFalse(plus.entitlement.state.value.hasPlus)
            assertTrue(plus.billing.offers().isEmpty())
            assertFalse(plus.purchases.refresh())
            assertTrue(plus.entitlement.state.value.localFeaturesAvailable)
        } finally { plus.closeAndJoin() }
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().isEmpty())
    }
    @Test fun `FOSS catalog never inspects dynamic cache or installs background catalog jobs`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundled = com.lavasec.app.data.CatalogRepository(context.assets).loadBundled()
        val cacheForbidden = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): java.io.File = error("FOSS must not inspect dynamic metadata")
        }
        assertSame(bundled, com.lavasec.app.data.CatalogRuntimeCatalog.resolveInstalled(cacheForbidden, bundled))
        // A context without backing services proves neither entry point even opens WorkManager.
        val noServices = android.content.ContextWrapper(null)
        com.lavasec.app.work.CatalogMaintenanceScheduler.install(noServices)
        com.lavasec.app.work.CatalogMaintenanceScheduler.repair(noServices)
    }

}
