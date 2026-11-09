package com.lavasec.app.directboot

import com.lavasec.app.platform.DirectBootSnapshotKeyRotationResult
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDirectBootFreshnessAuthorityTest {
    @Test
    fun `rotation deletes old alias before creating and using replacement`() {
        val operations = mutableListOf<String>()
        val gateway = FakeGateway(operations)
        val authority = AndroidDirectBootFreshnessAuthority(gateway)

        val rotation = authority.rotateForTesting()
        val tag = authority.authenticateForTesting("abc")

        assertTrue(rotation is DirectBootSnapshotKeyRotationResult.Rotated)
        assertEquals(listOf("delete", "create", "current"), operations)
        assertEquals(64, tag?.length)
        assertTrue(tag.orEmpty().all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `failure before alias deletion rejects without creating a replacement`() {
        val operations = mutableListOf<String>()
        val gateway = FakeGateway(operations, deleteSucceeds = false)
        val authority = AndroidDirectBootFreshnessAuthority(gateway)

        val rotation = authority.rotateForTesting()

        assertEquals(DirectBootSnapshotKeyRotationResult.RejectedBeforeInvalidation, rotation)
        assertEquals(listOf("delete"), operations)
    }

    @Test
    fun `failure after deletion reports invalidated without replacement`() {
        val operations = mutableListOf<String>()
        val gateway = FakeGateway(operations, createSucceeds = false)
        val authority = AndroidDirectBootFreshnessAuthority(gateway)

        val rotation = authority.rotateForTesting()

        assertEquals(DirectBootSnapshotKeyRotationResult.InvalidatedWithoutReplacement, rotation)
        assertEquals(listOf("delete", "create"), operations)
        assertFalse(gateway.hasCurrentKey)
    }

    private class FakeGateway(
        private val operations: MutableList<String>,
        private val deleteSucceeds: Boolean = true,
        private val createSucceeds: Boolean = true,
    ) : DirectBootKeyStoreGateway {
        private var key: SecretKey? = SecretKeySpec(ByteArray(32) { 1 }, "HmacSHA256")
        val hasCurrentKey: Boolean get() = key != null

        override fun deleteCurrent(): Boolean {
            operations += "delete"
            if (!deleteSucceeds) return false
            key = null
            return true
        }

        override fun createCurrent(): SecretKey? {
            operations += "create"
            if (!createSucceeds) return null
            return SecretKeySpec(ByteArray(32) { 2 }, "HmacSHA256").also { key = it }
        }

        override fun currentOrNull(): SecretKey? {
            operations += "current"
            return key
        }
    }
}
