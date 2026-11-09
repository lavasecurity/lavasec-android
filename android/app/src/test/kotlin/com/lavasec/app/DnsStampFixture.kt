package com.lavasec.app

import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import java.util.Base64

/** Literal pairs emitted by frozen iOS 3bd86116 ExportStampBackups.swift. */
internal data class DnsStampFixture(val transport: CatalogResolverTransport, val primary: String, val secondary: String) {
    fun entry(primary: String = this.primary, secondary: String = this.secondary) = ResolverSelectionEntry(
        ResolverProviderId.CUSTOM, transport,
        customDohUrl = primary.takeIf { transport == CatalogResolverTransport.DOH },
        customDotEndpoint = primary.takeIf { transport == CatalogResolverTransport.DOT },
        customDoqEndpoint = primary.takeIf { transport == CatalogResolverTransport.DOQ },
        customPlainEndpoint = primary.takeIf { transport == CatalogResolverTransport.PLAIN_DNS },
        customName = "Stamp fixture", customSecondaryEndpoint = secondary)

    companion object {
        val frozen = listOf(
            DnsStampFixture(CatalogResolverTransport.PLAIN_DNS,
                "sdns://AAAAAAAAAAAABzkuOS45Ljk", "sdns://AAAAAAAAAAAAEjE0OS4xMTIuMTEyLjExMjo1Mw"),
            DnsStampFixture(CatalogResolverTransport.DOH,
                "sdns://AgAAAAAAAAAABzEuMS4xLjEAEmNsb3VkZmxhcmUtZG5zLmNvbQovZG5zLXF1ZXJ5hzEuMC4wLjEUMjYwNjo0NzAwOjQ3MDA6OjExMTE",
                "sdns://AgAAAAAAAAAABzguOC44LjgACmRucy5nb29nbGUKL2Rucy1xdWVyeYc4LjguNC40FDIwMDE6NDg2MDo0ODYwOjo4ODg4"),
            DnsStampFixture(CatalogResolverTransport.DOT,
                "sdns://AwAAAAAAAAAAEDk0LjE0MC4xNC4xNDo4NTMAE2Rucy5hZGd1YXJkLWRucy5jb22MOTQuMTQwLjE1LjE1ETJhMTA6NTBjMDo6YWQxOmZm",
                "sdns://AwAAAAAAAAAADDkuOS45Ljk6ODg1MwASZG5zLnF1YWQ5Lm5ldDo4ODUzjzE0OS4xMTIuMTEyLjExMgsyNjIwOmZlOjpmZQ"),
            DnsStampFixture(CatalogResolverTransport.DOQ,
                "sdns://BAAAAAAAAAAACzkuOS45Ljk6ODUzAA1kbnMucXVhZDkubmV0jzE0OS4xMTIuMTEyLjExMgsyNjIwOmZlOjpmZQ",
                "sdns://BAAAAAAAAAAAETE5Mi4xNjguMS41Mzo4ODUzABxzZWNvbmRhcnkucmVzb2x2ZXIudGVzdDo4ODUzjDE5Mi4xNjguMS41NAhmZDAwOjo1Mw"),
        )

        /** Changes only a retained bootstrap literal's final hex digit in these encrypted fixtures. */
        fun changedBootstrap(raw: String): String {
            val bytes = Base64.getUrlDecoder().decode(raw.substring(7))
            bytes[bytes.lastIndex] = when (bytes.last().toInt().toChar()) {
                'f' -> 'e'.code.toByte()
                'e' -> 'f'.code.toByte()
                else -> '2'.code.toByte()
            }
            return "sdns://" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
