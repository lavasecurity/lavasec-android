package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.DnsQuestion
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Pinned Mullvad DoH endpoint and its DNS-free bootstrap lookup. */
object MullvadDohEndpoint : Dns {
    const val id: String = "mullvad-doh"

    val url: HttpUrl = "https://dns.mullvad.net/dns-query".toHttpUrl()

    private val bootstrapAddresses = listOf(
        InetAddress.getByName("194.242.2.2"),
        InetAddress.getByName("2a07:e340::2"),
    )

    internal fun bootstrapResponse(query: ByteArray, question: DnsQuestion): ByteArray? {
        if (question.normalizedDomain != url.host) return null
        return DnsMessage.bootstrapResponse(
            query = query,
            question = question,
            ipv4Address = bootstrapAddresses[0].address,
            ipv6Address = bootstrapAddresses[1].address,
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname != url.host) throw UnknownHostException(hostname)
        return Collections.unmodifiableList(bootstrapAddresses.toList())
    }
}
