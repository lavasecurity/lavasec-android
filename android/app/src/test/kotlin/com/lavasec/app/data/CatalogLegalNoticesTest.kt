package com.lavasec.app.data

import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.foundation.resolver.ResolverProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogLegalNoticesTest {
    @Test
    fun `sections cover the resolvers the milestone product can select and every catalog source`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notices = CatalogLegalNotices(
            assets = context.assets,
            stringResource = { id -> "res:$id" },
        ).buildSections()

        assertEquals(3, notices.size)
        val resolvers = notices[0]
        val blocklists = notices[1]
        val runtime = notices[2]
        assertEquals("res:${com.lavasec.app.presentation.R.string.legal_resolvers_title}", resolvers.title)
        assertEquals("res:${com.lavasec.app.presentation.R.string.legal_blocklists_title}", blocklists.title)

        assertEquals(6, resolvers.notices.size)
        assertEquals("res:${com.lavasec.app.presentation.R.string.onboarding_resolver_device}", resolvers.notices[0].displayName)
        assertEquals(
            "res:${com.lavasec.app.presentation.R.string.resolver_mullvad_name}",
            resolvers.notices.last().displayName,
        )
        assertEquals(
            "Mullvad is a trademark of Mullvad VPN AB.",
            resolvers.notices.last().noticeText,
        )

        val selectableProviders = ResolverCatalog.providers.filter {
            it.id != ResolverProviderId.DEVICE_DNS && it.id != ResolverProviderId.CUSTOM
        }
        assertEquals(selectableProviders.map { it.displayName }, resolvers.notices.drop(1).dropLast(1).map { it.displayName })
        for (provider in selectableProviders) {
            val notice = resolvers.notices.single { it.displayName == provider.displayName }
            assertTrue(notice.noticeText.isNotBlank())
            assertTrue(notice.sourceUrl?.startsWith("https://") == true)
            assertNull(notice.licenseName)
        }
        val quad9 = resolvers.notices.single { it.displayName == "Quad9" }
        assertEquals("Quad9 is a trademark of Quad9 Foundation.", quad9.noticeText)
        assertEquals("https://www.quad9.net/about/", quad9.sourceUrl)

        val catalog = CatalogRepository(context.assets).loadBundled()
        assertEquals(catalog.sources.map { it.name }, blocklists.notices.map { it.displayName })
        assertEquals(catalog.sources.map { it.sourceUrl }, blocklists.notices.map { it.sourceUrl })
        assertFalse(blocklists.notices.any { it.displayName == "Block List Malware" })
        val first = blocklists.notices[0]
        assertEquals("Block List Basic", first.displayName)
        assertEquals("Unlicense", first.licenseName)
        assertEquals("https://unlicense.org/", first.licenseUrl)
        assertTrue(first.noticeText.contains("The Block List Project"))

        val hagezi = blocklists.notices.first { it.displayName == "HaGeZi Threat Intelligence Feed Mini" }
        assertEquals("GPL-3.0", hagezi.licenseName)
        assertEquals("https://www.gnu.org/licenses/gpl-3.0.en.html", hagezi.licenseUrl)
        assertTrue(hagezi.noticeText.contains("HaGeZi DNS Blocklists"))

        val mit = blocklists.notices.first { it.displayName == "StevenBlack Unified Hosts" }
        assertEquals("MIT", mit.licenseName)
        assertEquals("https://opensource.org/licenses/MIT", mit.licenseUrl)
    }

    @Test
    fun `runtime libraries carry the packaged-code license inventory`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sections = CatalogLegalNotices(
            assets = context.assets,
            stringResource = { "x" },
        ).buildSections()

        val runtime = sections[2]
        assertEquals("x", runtime.title)
        assertTrue(runtime.notices.any { it.displayName == "OkHttp and Okio" })
        val icu = runtime.notices.first {
            it.displayName == "International Components for Unicode (ICU4J)"
        }
        assertEquals("ICU License", icu.licenseName)
        assertEquals("https://github.com/unicode-org/icu/blob/main/icu4c/LICENSE", icu.licenseUrl)
        val okhttp = runtime.notices.first { it.displayName == "OkHttp and Okio" }
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", okhttp.licenseUrl)
        val quiche = runtime.notices.first { it.displayName == "quiche QUIC transport" }
        assertTrue(quiche.noticeText.contains("Copyright (C) 2018-2019, Cloudflare, Inc."))
        assertTrue(quiche.noticeText.contains("Redistribution and use"))
        val boring = runtime.notices.first { it.displayName == "BoringSSL" }
        assertTrue(boring.noticeText.contains("OpenSSL"))
        assertTrue(boring.noticeText.contains("Copyright"))
        for (name in listOf("react-native ", "react ", "Hermes ", "react-native-svg ",
            "react-native-safe-area-context ", "react-native-screens ", "@react-navigation/native ")) {
            val packaged = runtime.notices.first { it.displayName.startsWith(name) }
            assertTrue(packaged.noticeText.contains("Permission is hereby granted"))
            assertTrue(packaged.noticeText.contains("Copyright"))
            assertTrue(packaged.noticeText.contains("THE SOFTWARE IS PROVIDED"))
        }
    }

    @Test
    fun `ICU runtime notice exposes the complete official license packaged offline`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val licenseBytes = context.assets.open("licenses/ICU4J-LICENSE").use { it.readBytes() }
        // Full LICENSE at unicode-org/icu release-77-1, commit 457157a92aa053e632cc7fcfd0e12f8a943b2d11.
        val digest = MessageDigest.getInstance("SHA-256").digest(licenseBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(26487, licenseBytes.size)
        assertEquals("451167c55c0fa447cc2d5632714f5e3c567fe4f1e1badefab2c1333852198aca", digest)

        val runtime = CatalogLegalNotices(context.assets) { "x" }.buildSections()[2]
        val icu = runtime.notices.single {
            it.displayName == "International Components for Unicode (ICU4J)"
        }
        assertTrue(icu.noticeText.endsWith(licenseBytes.toString(Charsets.UTF_8)))
        assertEquals("https://icu.unicode.org/", icu.sourceUrl)
        assertEquals("ICU License", icu.licenseName)
        assertEquals("https://github.com/unicode-org/icu/blob/main/icu4c/LICENSE", icu.licenseUrl)
    }

    @Test
    fun `resolver notice data stays honest for the device DNS entry`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sections = CatalogLegalNotices(
            assets = context.assets,
            stringResource = { "x" },
        ).buildSections()

        val device = sections[0].notices[0]
        assertNull(device.sourceUrl)
        assertNull(device.licenseName)
        assertFalse(device.noticeText.isBlank())
    }
}
