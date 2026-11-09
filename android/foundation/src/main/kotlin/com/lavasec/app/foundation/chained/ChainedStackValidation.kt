package com.lavasec.app.foundation.chained

enum class ChainedStackFailure {
    TOO_MANY_HOPS, ENTRY_REQUIRES_LITERAL_ENDPOINT, EXIT_REQUIRES_IPV4_ENDPOINT, ENTRY_MTU_TOO_SMALL,
}

class ChainedStackException(val failure: ChainedStackFailure) : IllegalArgumentException(failure.name)

/** Validates composition before a draft mutation or durable publication can replace the old rows. */
object ChainedStackValidation {
    fun validate(configuration: ChainedUpstreamConfiguration) {
        val entries = configuration.precedingHops
        if (entries.size > 1 || entries.any { it.precedingHops.isNotEmpty() }) {
            throw ChainedStackException(ChainedStackFailure.TOO_MANY_HOPS)
        }
        val entry = entries.firstOrNull() ?: return
        if (!configuration.isEnabled || !entry.isEnabled) return
        if (!ChainedUpstreamConfigurationValidator.isIPLiteral(entry.endpointHost)) {
            throw ChainedStackException(ChainedStackFailure.ENTRY_REQUIRES_LITERAL_ENDPOINT)
        }
        if (entry.routingPolicy == ChainedRoutingPolicy.FULL_TUNNEL) {
            if (ChainedUpstreamConfigurationValidator.ipv4Octets(configuration.endpointHost) == null) {
                throw ChainedStackException(ChainedStackFailure.EXIT_REQUIRES_IPV4_ENDPOINT)
            }
            if (entry.interfaceMTU != null && entry.interfaceMTU < 1340) {
                throw ChainedStackException(ChainedStackFailure.ENTRY_MTU_TOO_SMALL)
            }
        }
    }
}
