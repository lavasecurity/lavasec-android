package com.lavasec.app.presentation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** The only destinations that can replace the current navigation path. */
enum class ProductRoot {
    Guard,
    Settings,
}

/** A destination that can appear in a canonical product navigation path. */
enum class ProductDestination {
    Guard,
    Settings,
    Filters,
    FilterLibrary,
    FilterDetail,
    FilterReview,
    FilterPreparation,
    Activity,
    TopDomains,
    DomainHistory,
    LegalNotices,
    NerdStats,
    DnsResolver,
    NetworkActivity,
    Account,
    Upgrade,
    Customization,
    PrivacyData,
    Security,
    BugReport,
}

/** Immutable, canonical navigation data for the product shell. */
@ConsistentCopyVisibility
data class ProductNavigation private constructor(
    val path: List<ProductDestination>,
) {
    /** Port of canonical RN NavigationTurn: child pushes retain grants; pops/replacements revoke. */
    fun endsAuthenticationTurn(previous: ProductNavigation): Boolean =
        !previous.path.withIndex().all { (index, destination) -> path.getOrNull(index) == destination }

    fun selectRoot(root: ProductRoot): ProductNavigation = when (root) {
        ProductRoot.Guard -> guard
        ProductRoot.Settings -> settings
    }

    fun open(destination: ProductDestination): ProductNavigation = when (destination) {
        ProductDestination.Filters -> {
            check(this == guard) { "Filters is available only below Guard" }
            filters
        }
        ProductDestination.FilterLibrary -> {
            check(this == filters) { "Filter Library is available only below Filters" }
            filterLibrary
        }
        ProductDestination.FilterDetail -> {
            check(this == filters) { "Filter Detail is available only below Filters" }
            filterDetail
        }
        ProductDestination.FilterReview -> {
            check(this == filterDetail) { "Filter Review is available only below Filter Detail" }
            filterReview
        }
        ProductDestination.FilterPreparation -> {
            check(this == filterReview) { "Filter Preparation is available only below Filter Review" }
            filterPreparation
        }
        ProductDestination.Activity -> {
            check(this == guard) { "Activity is available only below Guard" }
            activity
        }
        ProductDestination.TopDomains -> {
            check(this == activity) { "Top Domains is available only below Activity" }
            topDomains
        }
        ProductDestination.DomainHistory -> {
            check(this == activity) { "Domain History is available only below Activity" }
            domainHistory
        }
        ProductDestination.LegalNotices -> {
            check(this == settings) { "Legal Notices is available only below Settings" }
            legalNotices
        }
        ProductDestination.NerdStats -> {
            check(this == settings) { "Nerd Stats is available only below Settings" }
            nerdStats
        }
        ProductDestination.DnsResolver -> {
            check(this == settings) { "DNS Resolver is available only below Settings" }
            dnsResolver
        }
        ProductDestination.NetworkActivity -> {
            check(this == settings) { "Network Activity is available only below Settings" }
            networkActivity
        }
        ProductDestination.Account -> {
            check(this == settings) { "Account is available only below Settings" }
            account
        }
        ProductDestination.Upgrade -> {
            check(this == settings) { "Upgrade is available only below Settings" }
            upgrade
        }
        ProductDestination.Customization -> {
            check(this == settings) { "Customization is available only below Settings" }
            customization
        }
        ProductDestination.PrivacyData -> {
            check(this == settings) { "Privacy & data is available only below Settings" }
            privacyData
        }
        ProductDestination.Security -> {
            check(this == settings) { "Security is available only below Settings" }
            security
        }
        ProductDestination.BugReport -> {
            check(this == settings) { "Bug Report is available only below Settings" }
            bugReport
        }
        ProductDestination.Guard,
        ProductDestination.Settings,
        -> error("Roots must be selected with selectRoot")
    }

    fun onBack(): ProductBackResult = when (this) {
        filters,
        activity,
        -> ProductBackResult.Navigate(guard)
        filterLibrary,
        filterDetail,
        -> ProductBackResult.Navigate(filters)
        filterReview -> ProductBackResult.Navigate(filterDetail)
        filterPreparation -> ProductBackResult.Navigate(filterReview)
        topDomains,
        domainHistory,
        -> ProductBackResult.Navigate(activity)
        legalNotices,
        nerdStats,
        dnsResolver,
        networkActivity,
        account,
        upgrade,
        customization,
        privacyData,
        security,
        bugReport,
        -> ProductBackResult.Navigate(settings)
        guard,
        settings,
        -> ProductBackResult.DelegateToHost
        else -> ProductBackResult.DelegateToHost
    }

    fun save(): List<String> = path.map(ProductDestination::name)

    companion object {
        val guard = ProductNavigation(listOf(ProductDestination.Guard))
        val settings = ProductNavigation(listOf(ProductDestination.Settings))

        private val filters = ProductNavigation(
            listOf(ProductDestination.Guard, ProductDestination.Filters),
        )
        private val filterLibrary = ProductNavigation(
            listOf(ProductDestination.Guard, ProductDestination.Filters, ProductDestination.FilterLibrary),
        )
        private val filterDetail = ProductNavigation(
            listOf(ProductDestination.Guard, ProductDestination.Filters, ProductDestination.FilterDetail),
        )
        private val filterReview = ProductNavigation(
            listOf(
                ProductDestination.Guard,
                ProductDestination.Filters,
                ProductDestination.FilterDetail,
                ProductDestination.FilterReview,
            ),
        )
        private val filterPreparation = ProductNavigation(
            listOf(
                ProductDestination.Guard,
                ProductDestination.Filters,
                ProductDestination.FilterDetail,
                ProductDestination.FilterReview,
                ProductDestination.FilterPreparation,
            ),
        )
        private val activity = ProductNavigation(
            listOf(ProductDestination.Guard, ProductDestination.Activity),
        )
        private val topDomains = ProductNavigation(
            listOf(ProductDestination.Guard, ProductDestination.Activity, ProductDestination.TopDomains),
        )
        private val domainHistory = ProductNavigation(
            listOf(ProductDestination.Guard, ProductDestination.Activity, ProductDestination.DomainHistory),
        )
        private val legalNotices = ProductNavigation(
            listOf(ProductDestination.Settings, ProductDestination.LegalNotices),
        )
        private val nerdStats = ProductNavigation(
            listOf(ProductDestination.Settings, ProductDestination.NerdStats),
        )
        private val dnsResolver = ProductNavigation(
            listOf(ProductDestination.Settings, ProductDestination.DnsResolver),
        )
        private val networkActivity = ProductNavigation(
            listOf(ProductDestination.Settings, ProductDestination.NetworkActivity),
        )
        private val account = ProductNavigation(
            listOf(ProductDestination.Settings, ProductDestination.Account),
        )
        private val upgrade = ProductNavigation(
            listOf(ProductDestination.Settings, ProductDestination.Upgrade),
        )
        private val customization = ProductNavigation(
            listOf(ProductDestination.Settings, ProductDestination.Customization),
        )
        private val privacyData = ProductNavigation(
            listOf(ProductDestination.Settings, ProductDestination.PrivacyData),
        )
        private val security = ProductNavigation(
            listOf(ProductDestination.Settings, ProductDestination.Security),
        )
        private val bugReport = ProductNavigation(
            listOf(ProductDestination.Settings, ProductDestination.BugReport),
        )

        fun restore(savedPath: List<String>): ProductNavigation = when (savedPath) {
            guard.save() -> guard
            settings.save() -> settings
            filters.save() -> filters
            filterLibrary.save() -> filterLibrary
            filterDetail.save() -> filterDetail
            filterReview.save() -> filterReview
            filterPreparation.save() -> filterPreparation
            activity.save() -> activity
            topDomains.save() -> topDomains
            domainHistory.save() -> domainHistory
            legalNotices.save() -> legalNotices
            nerdStats.save() -> nerdStats
            dnsResolver.save() -> dnsResolver
            networkActivity.save() -> networkActivity
            account.save() -> account
            upgrade.save() -> upgrade
            customization.save() -> customization
            privacyData.save() -> privacyData
            security.save() -> security
            bugReport.save() -> bugReport
            else -> guard
        }

        internal fun forRoute(destination: ProductDestination): ProductNavigation = when (destination) {
            ProductDestination.Guard -> guard
            ProductDestination.Settings -> settings
            ProductDestination.Filters -> filters
            ProductDestination.Activity -> activity
            ProductDestination.FilterLibrary,
            ProductDestination.FilterDetail,
            ProductDestination.FilterReview,
            ProductDestination.FilterPreparation,
            ProductDestination.TopDomains,
            ProductDestination.DomainHistory,
            ProductDestination.LegalNotices,
            ProductDestination.NerdStats,
            ProductDestination.DnsResolver,
            ProductDestination.NetworkActivity,
            ProductDestination.Account,
            ProductDestination.Upgrade,
            ProductDestination.Customization,
            ProductDestination.PrivacyData,
            ProductDestination.Security,
            ProductDestination.BugReport,
            -> error("Activity and Filters grandchildren and Settings children have no deep-link route")
        }
    }
}

sealed interface ProductBackResult {
    data class Navigate(val navigation: ProductNavigation) : ProductBackResult
    data object DelegateToHost : ProductBackResult
}

enum class ProductLayout {
    Compact,
    Expanded,
    ;

    companion object {
        fun forWidth(width: Dp): ProductLayout = if (width < 600.dp) Compact else Expanded
    }
}

/** Parses only the product's supported navigation routes and performs no other action. */
object ProductDeepLink {
    fun parse(value: String): ProductNavigation? = try {
        parse(URI(value))
    } catch (_: URISyntaxException) {
        null
    }

    private fun parse(uri: URI): ProductNavigation? {
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null || uri.port != -1) {
            return null
        }

        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            CUSTOM_SCHEME -> parseCustom(uri)
            WEB_SCHEME -> parseWeb(uri)
            else -> null
        }
    }

    private fun parseCustom(uri: URI): ProductNavigation? {
        if (uri.rawPath != "") return null

        return routeFor(uri.host?.lowercase(Locale.ROOT))
    }

    private fun parseWeb(uri: URI): ProductNavigation? {
        if (uri.host?.lowercase(Locale.ROOT) != WEB_HOST) return null

        return when (uri.rawPath) {
            "/app/guard" -> ProductNavigation.forRoute(ProductDestination.Guard)
            "/app/filters" -> ProductNavigation.forRoute(ProductDestination.Filters)
            "/app/activity" -> ProductNavigation.forRoute(ProductDestination.Activity)
            "/app/settings" -> ProductNavigation.forRoute(ProductDestination.Settings)
            else -> null
        }
    }

    private fun routeFor(route: String?): ProductNavigation? = when (route) {
        "guard" -> ProductNavigation.forRoute(ProductDestination.Guard)
        "filters" -> ProductNavigation.forRoute(ProductDestination.Filters)
        "activity" -> ProductNavigation.forRoute(ProductDestination.Activity)
        "settings" -> ProductNavigation.forRoute(ProductDestination.Settings)
        else -> null
    }

    private const val CUSTOM_SCHEME = "lavasecurity"
    private const val WEB_SCHEME = "https"
    private const val WEB_HOST = "lavasecurity.app"
}
