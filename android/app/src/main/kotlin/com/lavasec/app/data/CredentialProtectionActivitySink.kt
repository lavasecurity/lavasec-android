package com.lavasec.app.data

import com.lavasec.app.product.privacy.CredentialPrivacyFence
import com.lavasec.app.vpn.VpnProtectionEvidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Blocking transition sink: protection boundaries cannot be dropped across a privacy mutation. */
internal class CredentialProtectionActivitySink(
    private val writer: CredentialActivityWriter,
    private val privacyFence: CredentialPrivacyFence,
) {
    fun accept(evidence: VpnProtectionEvidence): Boolean = runBlocking(Dispatchers.IO) {
        privacyFence.withTelemetryWrite {
            when (evidence) {
                is VpnProtectionEvidence.State -> writer.recordProtectionState(
                    active = evidence.active,
                    atMillis = evidence.observedAtMillis,
                )
                is VpnProtectionEvidence.Heartbeat -> writer.recordProtectionHeartbeat(
                    atMillis = evidence.observedAtMillis,
                )
            }
            true
        }
    }
}
