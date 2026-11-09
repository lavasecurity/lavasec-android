package com.lavasec.app.foundation.diagnostics

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the retained-report delivery boundary: no secret, resolver address, account identity, or
 * domain history is part of the state. The one user-supplied affected-site field lives only inside
 * the frozen [ReportReviewRequest] (and its request bytes), which is why the machine can score it
 * as a single value and never as a list.
 */
class ReportDeliveryPrivacyTest {
    private val modelClasses = listOf(
        ReportIdentity::class.java,
        ReportReviewRequest::class.java,
        ReportReview::class.java,
        ReportAttempt::class.java,
        ReportDeliveryState::class.java,
    )

    @Test
    fun `no state field can carry a domain history map or address list`() {
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
    fun `no state field name names a secret, resolver address or account identity`() {
        val forbidden = listOf(
            "url", "uri", "address", "secret", "token", "credential", "password",
            "account", "email", "contact", "history", "queried", "ssid", "bssid",
            "resolver", "domain",
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
    fun `the delivery entry points cannot accept a domain history`() {
        val prepare = ReportDeliveryStateMachine::class.java.getDeclaredMethod(
            "prepare",
            ReportDeliveryState::class.java,
            ReportReviewRequest::class.java,
            Function0::class.java,
            Function2::class.java,
        )
        prepare.parameterTypes.forEach { parameter ->
            assertFalse(Collection::class.java.isAssignableFrom(parameter))
            assertFalse(Map::class.java.isAssignableFrom(parameter))
        }

        val beginSend = ReportDeliveryStateMachine::class.java.getDeclaredMethod(
            "beginSend",
            ReportDeliveryState::class.java,
            ReportReview::class.java,
        )
        assertEquals(2, beginSend.parameterCount)
    }

    @Test
    fun `the request holds only the reviewed draft and the consent flag`() {
        val fields = instanceFields(ReportReviewRequest::class.java).map { it.name }.sorted()
        assertEquals(listOf("draft", "includeDiagnostics"), fields)
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
