package com.lavasec.app.foundation.diagnostics

import com.lavasec.app.foundation.filter.DomainFilterDecisionSource
import com.lavasec.app.foundation.filter.DomainRuleSet
import com.lavasec.app.foundation.filter.FilterSnapshot
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the diagnostic decision's redaction boundary: one affected-site domain, no history,
 * no resolver secret/address, no credential, and no account identity.
 */
class DiagnosticDecisionPrivacyTest {
    private val modelClasses = listOf(
        AffectedSiteDiagnosticDecision.Decided::class.java,
        IncidentEvidence::class.java,
        IncidentDiagnosticDecision::class.java,
        DiagnosticDecision::class.java,
    )

    @Test
    fun `the only string field is the single affected-site domain`() {
        val stringFields = modelClasses.flatMap { type ->
            instanceFields(type).filter { it.type == String::class.java }
        }

        assertEquals(listOf("domain"), stringFields.map { it.name })
    }

    @Test
    fun `no field can carry a domain history map or address list`() {
        for (type in modelClasses) {
            for (field in instanceFields(type)) {
                assertFalse(
                    "${type.simpleName}.${field.name} must not be a Map",
                    Map::class.java.isAssignableFrom(field.type),
                )
                assertFalse(
                    "${type.simpleName}.${field.name} must not be a collection of strings",
                    isStringCollection(field),
                )
            }
        }
    }

    @Test
    fun `no field name names a resolver secret or account identity`() {
        // The banned concepts are a queried-domain history, a resolver address/secret, and an
        // account identity. Bounded resolver counters (e.g. fail-closed query count) are allowed.
        val forbidden = listOf(
            "url", "uri", "address", "secret", "token", "credential", "password",
            "account", "email", "contact", "history", "queried", "ssid", "bssid",
        )
        for (type in modelClasses) {
            for (field in instanceFields(type)) {
                val name = field.name.lowercase()
                forbidden.forEach { token ->
                    assertFalse("${type.simpleName}.${field.name} names '$token'", token in name)
                }
            }
        }
    }

    @Test
    fun `the decision entry points cannot accept a domain history`() {
        val evaluate = DiagnosticDecisionModel::class.java.getDeclaredMethod(
            "evaluate",
            String::class.java,
            DomainFilterDecisionSource::class.java,
            IncidentEvidence::class.java,
            com.lavasec.app.foundation.health.ProtectionHealthEvidence::class.java,
        )
        evaluate.parameterTypes.forEach { parameter ->
            assertFalse(Collection::class.java.isAssignableFrom(parameter))
            assertFalse(Map::class.java.isAssignableFrom(parameter))
        }

        val companion = AffectedSiteDiagnosticDecision::class.java.declaredClasses
            .first { it.simpleName == "Companion" }
        val make = companion.getDeclaredMethod(
            "make",
            String::class.java,
            DomainFilterDecisionSource::class.java,
        )
        assertEquals(2, make.parameterCount)
    }

    @Test
    fun `a credentialed URL exposes only the normalized host`() {
        val decision = AffectedSiteDiagnosticDecision.make(
            rawAffectedSite = "https://user:pass@example.com:8443/secret?token=abc#frag",
            filterDecisions = FilterSnapshot(blockRules = DomainRuleSet.EMPTY),
        ) as AffectedSiteDiagnosticDecision.Decided

        assertEquals("example.com", decision.domain)
        val rendered = decision.toString().lowercase()
        listOf("user", "pass", "secret", "token", "abc", "8443", "frag").forEach {
            assertFalse("credential material '$it' leaked into the decision", it in rendered)
        }
    }

    private fun instanceFields(type: Class<*>): List<Field> =
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }

    private fun isStringCollection(field: Field): Boolean {
        val generic = field.genericType as? ParameterizedType ?: return false
        val raw = generic.rawType as? Class<*> ?: return false
        if (!Collection::class.java.isAssignableFrom(raw)) return false
        return generic.actualTypeArguments.any { it == String::class.java }
    }
}
