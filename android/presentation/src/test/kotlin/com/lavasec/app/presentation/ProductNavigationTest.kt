package com.lavasec.app.presentation

import androidx.compose.ui.unit.dp
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProductNavigationTest {
    @Test
    fun `authentication turn follows the canonical child push and pop policy`() {
        val root = ProductNavigation.guard
        val activity = root.open(ProductDestination.Activity)
        val history = activity.open(ProductDestination.DomainHistory)
        assertFalse(activity.endsAuthenticationTurn(root))
        assertFalse(history.endsAuthenticationTurn(activity))
        assertFalse(history.endsAuthenticationTurn(history))
        assertTrue(activity.endsAuthenticationTurn(history))
        assertTrue(root.endsAuthenticationTurn(activity))
        assertTrue(ProductNavigation.settings.endsAuthenticationTurn(activity))
        assertTrue(root.open(ProductDestination.Filters).endsAuthenticationTurn(activity))
    }

    @Test
    fun `only Guard and Settings are root destinations`() {
        assertEquals(
            listOf(ProductRoot.Guard, ProductRoot.Settings),
            ProductRoot.entries.toList(),
        )
    }

    @Test
    fun `navigation exposes only canonical root and Guard child paths`() {
        assertEquals(listOf(ProductDestination.Guard), ProductNavigation.guard.path)
        assertEquals(listOf(ProductDestination.Settings), ProductNavigation.settings.path)
        assertEquals(
            listOf(ProductDestination.Guard, ProductDestination.Filters),
            ProductNavigation.guard.open(ProductDestination.Filters).path,
        )
        assertEquals(
            listOf(ProductDestination.Guard, ProductDestination.Activity),
            ProductNavigation.guard.open(ProductDestination.Activity).path,
        )
    }

    @Test
    fun `selecting a root replaces the current child path`() {
        val filters = ProductNavigation.guard.open(ProductDestination.Filters)

        assertEquals(ProductNavigation.settings, filters.selectRoot(ProductRoot.Settings))
        assertEquals(ProductNavigation.guard, ProductNavigation.settings.selectRoot(ProductRoot.Guard))
    }

    @Test
    fun `Back pops a Guard child`() {
        val filters = ProductNavigation.guard.open(ProductDestination.Filters)

        assertEquals(ProductBackResult.Navigate(ProductNavigation.guard), filters.onBack())
    }

    @Test
    fun `Back on a root delegates to the host`() {
        assertEquals(ProductBackResult.DelegateToHost, ProductNavigation.guard.onBack())
        assertEquals(ProductBackResult.DelegateToHost, ProductNavigation.settings.onBack())
    }

    @Test
    fun `canonical paths save and restore unchanged`() {
        val navigation = ProductNavigation.guard.open(ProductDestination.Activity)

        assertEquals(navigation, ProductNavigation.restore(navigation.save()))
    }

    @Test
    fun `Activity grandchildren are canonical only below Activity and Back pops one level`() {
        val activity = ProductNavigation.guard.open(ProductDestination.Activity)
        val top = activity.open(ProductDestination.TopDomains)
        val history = activity.open(ProductDestination.DomainHistory)

        assertEquals(
            listOf(ProductDestination.Guard, ProductDestination.Activity, ProductDestination.TopDomains),
            top.path,
        )
        assertEquals(ProductBackResult.Navigate(activity), top.onBack())
        assertEquals(ProductBackResult.Navigate(activity), history.onBack())
        assertEquals(ProductBackResult.Navigate(ProductNavigation.guard), activity.onBack())
        assertEquals(top, ProductNavigation.restore(top.save()))
        assertEquals(
            listOf(ProductDestination.Guard, ProductDestination.Activity, ProductDestination.DomainHistory),
            history.path,
        )
        assertNull(ProductDeepLink.parse("https://lavasecurity.app/app/activity/top-domains"))
    }

    @Test
    fun `roots cannot open Activity grandchildren and malformed three element paths fail closed`() {
        listOf(
            ProductNavigation.guard,
            ProductNavigation.settings,
            ProductNavigation.guard.open(ProductDestination.Filters),
        ).forEach { navigation ->
            listOf(
                ProductDestination.Activity,
                ProductDestination.Filters,
                ProductDestination.TopDomains,
                ProductDestination.DomainHistory,
            ).forEach { destination ->
                if (navigation != ProductNavigation.guard || destination !in listOf(ProductDestination.Activity, ProductDestination.Filters)) {
                    assertThrows(IllegalStateException::class.java) { navigation.open(destination) }
                }
            }
        }
        listOf(
            listOf("Guard", "Activity", "Filters"),
            listOf("Guard", "TopDomains", "Activity"),
            listOf("Settings", "Activity", "DomainHistory"),
            listOf("Guard", "Activity", "Unknown"),
        ).forEach { savedPath ->
            assertEquals(ProductNavigation.guard, ProductNavigation.restore(savedPath))
        }
    }

    @Test
    fun `noncanonical saved paths restore fail closed to Guard`() {
        listOf(
            emptyList(),
            listOf("Filters"),
            listOf("Guard", "Settings"),
            listOf("Settings", "Activity"),
            listOf("Settings", "LegalNotices", "Guard"),
            listOf("LegalNotices"),
            listOf("Guard", "Filters", "Activity"),
            listOf("Guard", "Activity", "TopDomains", "DomainHistory"),
            listOf("guard"),
            listOf("Unknown"),
        ).forEach { savedPath ->
            assertEquals(ProductNavigation.guard, ProductNavigation.restore(savedPath))
        }
    }

    @Test
    fun `width below 600 dp is compact and 600 dp is expanded`() {
        assertEquals(ProductLayout.Compact, ProductLayout.forWidth(599.dp))
        assertEquals(ProductLayout.Expanded, ProductLayout.forWidth(600.dp))
        assertEquals(ProductLayout.Expanded, ProductLayout.forWidth(601.dp))
    }

    @Test
    fun `accepted custom and web links return only their navigation paths`() {
        val expected = mapOf(
            "lavasecurity://guard" to ProductNavigation.guard,
            "https://lavasecurity.app/app/guard" to ProductNavigation.guard,
            "lavasecurity://filters" to ProductNavigation.guard.open(ProductDestination.Filters),
            "https://lavasecurity.app/app/filters" to ProductNavigation.guard.open(ProductDestination.Filters),
            "lavasecurity://activity" to ProductNavigation.guard.open(ProductDestination.Activity),
            "https://lavasecurity.app/app/activity" to ProductNavigation.guard.open(ProductDestination.Activity),
            "lavasecurity://settings" to ProductNavigation.settings,
            "https://lavasecurity.app/app/settings" to ProductNavigation.settings,
        )

        expected.forEach { (uri, navigation) ->
            assertEquals(navigation, ProductDeepLink.parse(uri))
        }
    }

    @Test
    fun `the filter library and detail are canonical only below Filters`() {
        val filters = ProductNavigation.guard.open(ProductDestination.Filters)
        val library = filters.open(ProductDestination.FilterLibrary)
        val detail = filters.open(ProductDestination.FilterDetail)

        assertEquals(
            listOf(ProductDestination.Guard, ProductDestination.Filters, ProductDestination.FilterLibrary),
            library.path,
        )
        assertEquals(
            listOf(ProductDestination.Guard, ProductDestination.Filters, ProductDestination.FilterDetail),
            detail.path,
        )
        assertEquals(ProductBackResult.Navigate(filters), library.onBack())
        assertEquals(ProductBackResult.Navigate(filters), detail.onBack())
        assertEquals(library, ProductNavigation.restore(library.save()))
        assertEquals(detail, ProductNavigation.restore(detail.save()))
        assertThrows(IllegalStateException::class.java) {
            ProductNavigation.guard.open(ProductDestination.FilterLibrary)
        }
        assertThrows(IllegalStateException::class.java) {
            ProductNavigation.guard.open(ProductDestination.FilterDetail)
        }
    }

    @Test
    fun `review and preparation chain only below the detail route`() {
        val filters = ProductNavigation.guard.open(ProductDestination.Filters)
        val detail = filters.open(ProductDestination.FilterDetail)
        val review = detail.open(ProductDestination.FilterReview)
        val preparation = review.open(ProductDestination.FilterPreparation)

        assertEquals(
            listOf(
                ProductDestination.Guard,
                ProductDestination.Filters,
                ProductDestination.FilterDetail,
                ProductDestination.FilterReview,
                ProductDestination.FilterPreparation,
            ),
            preparation.path,
        )
        assertEquals(ProductBackResult.Navigate(review), preparation.onBack())
        assertEquals(ProductBackResult.Navigate(detail), review.onBack())
        assertEquals(preparation, ProductNavigation.restore(preparation.save()))
        assertEquals(review, ProductNavigation.restore(review.save()))
        assertThrows(IllegalStateException::class.java) {
            filters.open(ProductDestination.FilterReview)
        }
        assertThrows(IllegalStateException::class.java) {
            filters.open(ProductDestination.FilterPreparation)
        }
    }

    @Test
    fun `noncanonical filter paths restore fail closed to Guard`() {
        listOf(
            listOf("Filters", "FilterLibrary"),
            listOf("Guard", "FilterLibrary"),
            listOf("Guard", "Filters", "FilterDetail", "FilterReview", "FilterReview"),
            listOf("Guard", "Filters", "FilterReview"),
        ).forEach { savedPath ->
            assertEquals(ProductNavigation.guard, ProductNavigation.restore(savedPath))
        }
    }

    @Test
    fun `Legal Notices is canonical only below Settings and Back pops one level`() {        val legal = ProductNavigation.settings.open(ProductDestination.LegalNotices)

        assertEquals(
            listOf(ProductDestination.Settings, ProductDestination.LegalNotices),
            legal.path,
        )
        assertEquals(ProductBackResult.Navigate(ProductNavigation.settings), legal.onBack())
        assertEquals(legal, ProductNavigation.restore(legal.save()))
        assertThrows(IllegalStateException::class.java) {
            ProductNavigation.guard.open(ProductDestination.LegalNotices)
        }
    }

    @Test
    fun `Nerd Stats is canonical only below Settings and Back pops one level`() {
        val nerdStats = ProductNavigation.settings.open(ProductDestination.NerdStats)

        assertEquals(
            listOf(ProductDestination.Settings, ProductDestination.NerdStats),
            nerdStats.path,
        )
        assertEquals(ProductBackResult.Navigate(ProductNavigation.settings), nerdStats.onBack())
        assertEquals(nerdStats, ProductNavigation.restore(nerdStats.save()))
        assertThrows(IllegalStateException::class.java) {
            ProductNavigation.guard.open(ProductDestination.NerdStats)
        }
    }

    @Test
    fun `DNS Resolver is canonical only below Settings and Back pops one level`() {
        val dnsResolver = ProductNavigation.settings.open(ProductDestination.DnsResolver)

        assertEquals(
            listOf(ProductDestination.Settings, ProductDestination.DnsResolver),
            dnsResolver.path,
        )
        assertEquals(ProductBackResult.Navigate(ProductNavigation.settings), dnsResolver.onBack())
        assertEquals(dnsResolver, ProductNavigation.restore(dnsResolver.save()))
        assertThrows(IllegalStateException::class.java) {
            ProductNavigation.guard.open(ProductDestination.DnsResolver)
        }
    }

    @Test
    fun `Network Activity is canonical only below Settings and Back pops one level`() {
        val networkActivity = ProductNavigation.settings.open(ProductDestination.NetworkActivity)

        assertEquals(
            listOf(ProductDestination.Settings, ProductDestination.NetworkActivity),
            networkActivity.path,
        )
        assertEquals(ProductBackResult.Navigate(ProductNavigation.settings), networkActivity.onBack())
        assertEquals(networkActivity, ProductNavigation.restore(networkActivity.save()))
        assertThrows(IllegalStateException::class.java) {
            ProductNavigation.guard.open(ProductDestination.NetworkActivity)
        }
    }

    @Test
    fun `slice 04 trust routes are canonical only below Settings and Back pops one level`() {
        val routes = mapOf(
            ProductDestination.Account to ProductNavigation.settings.open(ProductDestination.Account),
            ProductDestination.Upgrade to ProductNavigation.settings.open(ProductDestination.Upgrade),
            ProductDestination.Customization to ProductNavigation.settings.open(ProductDestination.Customization),
            ProductDestination.PrivacyData to ProductNavigation.settings.open(ProductDestination.PrivacyData),
            ProductDestination.Security to ProductNavigation.settings.open(ProductDestination.Security),
            ProductDestination.BugReport to ProductNavigation.settings.open(ProductDestination.BugReport),
        )
        routes.forEach { (destination, navigation) ->
            assertEquals(
                listOf(ProductDestination.Settings, destination),
                navigation.path,
            )
            assertEquals(ProductBackResult.Navigate(ProductNavigation.settings), navigation.onBack())
            assertEquals(navigation, ProductNavigation.restore(navigation.save()))
            assertThrows(IllegalStateException::class.java) {
                ProductNavigation.guard.open(destination)
            }
        }
    }

    @Test
    fun `settings children have no deep-link route and noncanonical paths fail closed`() {
        assertNull(ProductDeepLink.parse("lavasecurity://account"))
        assertNull(ProductDeepLink.parse("https://lavasecurity.app/app/settings/privacy-data"))
        assertEquals(
            ProductNavigation.guard,
            ProductNavigation.restore(listOf("Account", "Settings")),
        )
    }

    @Test
    fun `links with unsupported components or routes are rejected`() {
        val rejected = listOf(
            "lavasecurity://guard?start=true",
            "https://lavasecurity.app/app/filters?import=rules",
            "lavasecurity://guard#details",
            "https://lavasecurity.app/app/activity#today",
            "https://user@lavasecurity.app/app/guard",
            "https://lavasecurity.app:443/app/guard",
            "lavasecurity://filters/extra",
            "https://lavasecurity.app/app/activity/today",
            "lavasecurity://top-domains",
            "lavasecurity://domain-history",
            "https://lavasecurity.app/app/activity/top-domains",
            "https://lavasecurity.app/app/activity/domain-history",
            "lavasecurity://settings/import",
            "https://lavasecurity.app/app/settings/privacy",
            "https://lavasecurity.app/app/settings/import",
            "https://lavasecurity.app/app/import",
            "lavasecurity://import",
            "http://lavasecurity.app/app/guard",
            "lavasecurity://other/guard",
            "https://example.com/app/guard",
        )

        rejected.forEach { uri -> assertNull(uri, ProductDeepLink.parse(uri)) }
    }

    @Test
    fun `link parsing is locale independent`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            assertEquals(ProductNavigation.guard, ProductDeepLink.parse("LAVASECURITY://GUARD"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
