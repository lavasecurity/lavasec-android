package com.lavasec.app.reactnative

import com.lavasec.app.product.LocalDomainCheckEvidence
import java.util.UUID

/** Native proofs bind one checked result to its original window, visit and saved rules.
 * Cancellation can retire only that visit; an old result never authorizes a newer one. */
internal class NativeDomainCheckOwner {
    class Ticket internal constructor(val visit: String, val host: String, val generation: Long)
    class Grant internal constructor(val ticket: Ticket, val proof: String, val evidence: LocalDomainCheckEvidence)
    private var ticket: Ticket? = null
    private var grant: Grant? = null

    @Synchronized fun begin(visit: String, host: String, generation: Long): Ticket =
        Ticket(visit, host, generation).also { ticket = it; grant = null }
    @Synchronized fun isCurrent(value: Ticket): Boolean = ticket === value
    @Synchronized fun accept(value: Ticket, evidence: LocalDomainCheckEvidence): Grant? =
        if (ticket !== value) null else Grant(value, UUID.randomUUID().toString(), evidence).also { grant = it }
    @Synchronized fun checked(visit: String, proof: String, host: String, generation: Long, revision: String?): Grant? =
        grant?.takeIf { it.ticket === ticket && it.ticket.visit == visit && it.proof == proof &&
            it.ticket.host == host && it.ticket.generation == generation && it.evidence.revision == revision }
    @Synchronized fun isCurrent(value: Grant, revision: String?): Boolean =
        grant === value && ticket === value.ticket && value.evidence.revision == revision
    @Synchronized fun cancel(visit: String, host: String) {
        if (ticket?.visit == visit && ticket?.host == host) retire()
    }
    @Synchronized fun retire() { ticket = null; grant = null }
}
