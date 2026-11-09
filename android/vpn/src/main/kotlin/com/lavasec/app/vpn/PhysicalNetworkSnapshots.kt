package com.lavasec.app.vpn

/** Only immutable callback facts enter this cache. No synchronous platform reads in callbacks. */
internal data class PhysicalNetworkCapabilities(
    val internet: Boolean,
    val notVpn: Boolean,
    val cellular: Boolean,
    val isWifi: Boolean = false,
    // Platform internet validation is independent of DNS replies and resolver selection.
    val validated: Boolean = false,
)
internal data class PhysicalDnsProperties(
    val servers: List<java.net.InetAddress>, val privateDnsActive: Boolean, val privateDnsName: String?,
)
internal data class PhysicalNetworkSnapshot<Key>(
    val key: Key, val capabilities: PhysicalNetworkCapabilities, val dns: PhysicalDnsProperties,
)

internal class PhysicalNetworkSnapshots<Key> {
    private data class Entry(val capabilities: PhysicalNetworkCapabilities? = null, val dns: PhysicalDnsProperties? = null, val blocked: Boolean = false)
    private val entries = linkedMapOf<Key, Entry>()
    private var closed = false

    @Synchronized fun seed(snapshot: PhysicalNetworkSnapshot<Key>) {
        if (!closed) entries[snapshot.key] = Entry(snapshot.capabilities, snapshot.dns)
    }
    @Synchronized fun available(key: Key) {
        if (!closed) entries.putIfAbsent(key, Entry())
    }
    @Synchronized fun capabilities(key: Key, value: PhysicalNetworkCapabilities): Boolean {
        val old = entries[key] ?: return false
        if (closed || old.capabilities == value) return false
        entries[key] = old.copy(capabilities = value); return true
    }
    @Synchronized fun dns(key: Key, value: PhysicalDnsProperties): Boolean {
        val old = entries[key] ?: return false
        if (closed || old.dns == value) return false
        entries[key] = old.copy(dns = value.copy(servers = value.servers.toList())); return true
    }
    @Synchronized fun blocked(key: Key, value: Boolean): Boolean {
        val old = entries[key] ?: return false
        if (closed || old.blocked == value) return false
        entries[key] = old.copy(blocked = value); return true
    }
    @Synchronized fun lost(key: Key): Boolean = !closed && entries.remove(key) != null
    @Synchronized fun internetValidated(key: Key): Boolean {
        val entry = entries[key] ?: return false
        val capabilities = entry.capabilities ?: return false
        return !closed && !entry.blocked && entry.dns != null && capabilities.internet &&
            capabilities.notVpn && capabilities.validated
    }
    @Synchronized fun select(candidates: List<Key>): PhysicalNetworkSnapshot<Key>? {
        if (closed) return null
        return candidates.firstNotNullOfOrNull { key ->
            val entry = entries[key] ?: return@firstNotNullOfOrNull null
            val capabilities = entry.capabilities ?: return@firstNotNullOfOrNull null
            val dns = entry.dns ?: return@firstNotNullOfOrNull null
            if (entry.blocked || !capabilities.internet || !capabilities.notVpn) null
            else PhysicalNetworkSnapshot(key, capabilities, dns)
        }
    }
    @Synchronized fun close() { closed = true; entries.clear() }
}
