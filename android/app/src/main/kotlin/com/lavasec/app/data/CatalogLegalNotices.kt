package com.lavasec.app.data

import android.content.res.AssetManager
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.presentation.R
import com.lavasec.app.presentation.settings.LegalNoticeSectionUi
import com.lavasec.app.presentation.settings.LegalNoticeUi
import org.json.JSONObject

/**
 * Builds Legal Notices from the resolver set, pinned blocklist catalog, native runtime
 * attribution and complete installed, locked React Native presentation licenses.
 *
 * Attribution copy intentionally mirrors the iOS `ThirdPartyLegalNotices`
 * wording and stays in the upstream original language; only the surrounding
 * section chrome is localized.
 */
class CatalogLegalNotices(
    private val assets: AssetManager,
    private val stringResource: (Int) -> String,
) {
    fun buildSections(): List<LegalNoticeSectionUi> = listOf(
        LegalNoticeSectionUi(
            title = stringResource(R.string.legal_resolvers_title),
            notices = resolverNotices(),
        ),
        LegalNoticeSectionUi(
            title = stringResource(R.string.legal_blocklists_title),
            notices = blocklistNotices(),
        ),
        LegalNoticeSectionUi(
            title = stringResource(R.string.legal_runtime_title),
            notices = runtimeLibraryNotices() + reactNativePresentationNotices(),
        ),
    )

    /** Generated from installed package-lock dependencies; complete original licenses, not IDs. */
    private fun reactNativePresentationNotices(): List<LegalNoticeUi> {
        val source = assets.open("licenses/react-native-notices.json").bufferedReader().use { it.readText() }
        val notices = JSONObject(source).getJSONArray("notices")
        return List(notices.length()) { index ->
            val notice = notices.getJSONObject(index)
            LegalNoticeUi(
                displayName = notice.getString("name") + " " + notice.getString("version"),
                noticeText = notice.getString("text"),
                plannedUse = "Runtime dependency of the shared React Native presentation.",
                sourceUrl = notice.getString("sourceUrl").takeIf(String::isNotBlank),
                licenseName = notice.getString("license"),
                licenseUrl = licenseUrlFor(notice.getString("license")),
            )
        }
    }

    /**
     * Third-party code packaged with the release application. The packaging
     * excludes the upstream META-INF license files, so this inventory is the
     * installed app's only license surface for those artifacts.
     */
    private fun runtimeLibraryNotices(): List<LegalNoticeUi> {
        val plannedUse =
            "Attribution for third-party runtime code packaged with the application."
        return listOf(
            runtimeNotice(
                displayName = "OkHttp and Okio",
                owner = "Square, Inc.",
                license = "Apache-2.0",
                sourceUrl = "https://square.github.io/okhttp/",
                plannedUse = plannedUse,
            ),
            runtimeNotice(
                displayName = "Kotlin Coroutines (kotlinx)",
                owner = "JetBrains s.r.o.",
                license = "Apache-2.0",
                sourceUrl = "https://github.com/Kotlin/kotlinx.coroutines",
                plannedUse = plannedUse,
            ),
            runtimeNotice(
                displayName = "Android Jetpack Libraries",
                owner = "Google LLC",
                license = "Apache-2.0",
                sourceUrl = "https://developer.android.com/jetpack/androidx",
                plannedUse = plannedUse,
            ),
            runtimeNotice(
                displayName = "Material Symbols Rounded (weight 350)",
                owner = "Google LLC",
                license = "Apache-2.0",
                sourceUrl = "https://github.com/google/material-design-icons/tree/49d4db35df873165d6bd6ba09b063c7dafbac2f4",
                plannedUse = plannedUse,
                packagedLicense = "MATERIAL-SYMBOLS-LICENSE",
            ),
            runtimeNotice(
                displayName = "International Components for Unicode (ICU4J)",
                owner = "Unicode, Inc. and others",
                license = "ICU License",
                sourceUrl = "https://icu.unicode.org/",
                plannedUse = plannedUse,
                packagedLicense = "ICU4J-LICENSE",
            ),
            runtimeNotice(
                displayName = "Kotlin Standard Library",
                owner = "JetBrains s.r.o.",
                license = "Apache-2.0",
                sourceUrl = "https://kotlinlang.org/",
                plannedUse = plannedUse,
            ),
            runtimeNotice(
                displayName = "Guava ListenableFuture",
                owner = "Google LLC",
                license = "Apache-2.0",
                sourceUrl = "https://github.com/google/guava",
                plannedUse = plannedUse,
            ),
            runtimeNotice(
                displayName = "JSpecify",
                owner = "JSpecify project",
                license = "Apache-2.0",
                sourceUrl = "https://jspecify.dev/",
                plannedUse = plannedUse,
            ),
            runtimeNotice(
                displayName = "quiche QUIC transport",
                owner = "Cloudflare, Inc.",
                license = "BSD-2-Clause",
                sourceUrl = "https://github.com/cloudflare/quiche",
                plannedUse = "Certificate-verifying DNS over QUIC transport.",
                packagedLicense = "QUICHE-LICENSE",
            ),
            runtimeNotice(
                displayName = "BoringSSL",
                owner = "The BoringSSL Authors and upstream OpenSSL contributors",
                license = "BoringSSL and OpenSSL licenses",
                sourceUrl = "https://boringssl.googlesource.com/boringssl/",
                plannedUse = "TLS certificate verification and cryptography for DNS over QUIC.",
                packagedLicense = "BORINGSSL-LICENSE",
            ),
        )
    }

    private fun runtimeNotice(
        displayName: String,
        owner: String,
        license: String,
        sourceUrl: String,
        plannedUse: String,
        packagedLicense: String? = null,
    ) = LegalNoticeUi(
        displayName = displayName,
        noticeText = "$displayName is a third-party runtime library shown for attribution " +
            "and license identification. License: $license. Owner or project: $owner." +
            (packagedLicense?.let { name ->
                "\n\n" + assets.open("licenses/$name").bufferedReader().use { it.readText() }
            } ?: ""),
        plannedUse = plannedUse,
        sourceUrl = sourceUrl,
        licenseName = license,
        licenseUrl = licenseUrlFor(license),
    )

    private fun resolverNotices(): List<LegalNoticeUi> {
        val plannedUse = "Plain-text identification of a selectable DNS resolver and " +
            "optional encrypted upstream forwarding for allowed DNS lookups."
        // Attribute each selectable provider once across plain DNS, DoH and DoT.
        // Mullvad remains readable in previously saved runtime configurations;
        // custom endpoints have no fixed third-party provider to attribute.
        return listOf(
            LegalNoticeUi(
                displayName = stringResource(R.string.onboarding_resolver_device),
                noticeText = "Device DNS identifies the DNS resolver supplied by the " +
                    "current Wi-Fi, cellular, or system network configuration.",
                plannedUse = "Plain-text identification of the device DNS resolver used " +
                    "for allowed DNS lookups when selected as the primary resolver.",
                sourceUrl = null,
                licenseName = null,
                licenseUrl = null,
            ),
            resolverNotice(
                ResolverCatalog.quad9Unfiltered.displayName,
                "Quad9 is a trademark of Quad9 Foundation.",
                "https://www.quad9.net/about/", plannedUse,
            ),
            resolverNotice(
                ResolverCatalog.cloudflare.displayName,
                "Cloudflare is a trademark or registered trademark of Cloudflare, Inc. " +
                    "in the United States and other jurisdictions.",
                "https://www.cloudflare.com/learning/dns/what-is-1.1.1.1/", plannedUse,
            ),
            resolverNotice(
                ResolverCatalog.hagezi.displayName,
                "HaGeZi DNS is a public resolver operated by the HaGeZi project.",
                "https://github.com/hagezi/dns-servers", plannedUse,
            ),
            resolverNotice(
                ResolverCatalog.googlePublicDns.displayName,
                "Google and Google Public DNS are trademarks of Google LLC.",
                "https://developers.google.com/speed/public-dns", plannedUse,
            ),
            LegalNoticeUi(
                displayName = stringResource(R.string.resolver_mullvad_name),
                noticeText = "Mullvad is a trademark of Mullvad VPN AB.",
                plannedUse = plannedUse,
                sourceUrl = "https://mullvad.net/en/help/dns-over-https-and-dns-over-tls",
                licenseName = null,
                licenseUrl = null,
            ),
        )
    }

    private fun resolverNotice(
        displayName: String,
        noticeText: String,
        sourceUrl: String,
        plannedUse: String,
    ) = LegalNoticeUi(
        displayName = displayName,
        noticeText = noticeText,
        plannedUse = plannedUse,
        sourceUrl = sourceUrl,
        licenseName = null,
        licenseUrl = null,
    )

    private fun blocklistNotices(): List<LegalNoticeUi> {
        val root = assets.open(CatalogRepository.ASSET_PATH).use { input ->
            JSONObject(input.readBytes().toString(Charsets.UTF_8))
        }
        val sources = root.getJSONArray("sources")
        val notices = ArrayList<LegalNoticeUi>(sources.length())
        for (index in 0 until sources.length()) {
            val source = sources.getJSONObject(index)
            val id = source.getString("id")
            val name = source.getString("name")
            val license = source.getString("license")
            val sourceUrl = source.getString("source_url")
            val owner = blocklistOwnerName(id)
            notices.add(
                LegalNoticeUi(
                    displayName = name,
                    noticeText = "$name is a third-party source shown for attribution " +
                        "and source identification. License: $license. Owner or project: " +
                        "$owner.",
                    plannedUse = "Attribution and source identification for a selectable " +
                        "or guardrail DNS blocklist.",
                    sourceUrl = sourceUrl,
                    licenseName = license,
                    licenseUrl = licenseUrlFor(license),
                ),
            )
        }
        return notices
    }

    private fun blocklistOwnerName(id: String): String = when {
        id.startsWith("blocklistproject-") -> "The Block List Project"
        id.startsWith("hagezi-") -> "HaGeZi DNS Blocklists"
        id.startsWith("oisd-") -> "OISD"
        id.startsWith("stevenblack-") -> "Steven Black"
        id.startsWith("adguard-") -> "AdGuard"
        id.startsWith("1hosts-") -> "1Hosts (badmojr)"
        id.startsWith("phishing-database-") -> "Phishing.Database"
        else -> "Third-party source project"
    }

    private fun licenseUrlFor(license: String): String? = when {
        license.startsWith("GPL") -> "https://www.gnu.org/licenses/gpl-3.0.en.html"
        license.startsWith("MPL") -> "https://www.mozilla.org/en-US/MPL/2.0/"
        license.startsWith("MIT") -> "https://opensource.org/licenses/MIT"
        license.startsWith("Unlicense") -> "https://unlicense.org/"
        license.startsWith("Apache") -> "https://www.apache.org/licenses/LICENSE-2.0"
        license.startsWith("ICU") ->
            "https://github.com/unicode-org/icu/blob/main/icu4c/LICENSE"
        else -> null
    }
}
