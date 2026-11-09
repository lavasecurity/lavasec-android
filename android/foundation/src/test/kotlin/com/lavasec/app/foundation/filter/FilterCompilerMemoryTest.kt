package com.lavasec.app.foundation.filter

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterCompilerMemoryTest {
    @Test
    fun `five hundred thousand rules compile without truncation deterministically within heap budgets`() = runBlocking {
        val root = Files.createTempDirectory("filter-500k-").toFile()
        try {
            val payload = File(root, "source.txt")
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(payload).buffered(64 * 1024).use { output ->
                for (index in 0 until RULE_COUNT) {
                    val bytes = String.format(Locale.ROOT, "d%06d.example.com\n", index).encodeToByteArray()
                    output.write(bytes)
                    digest.update(bytes)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val request = FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(500_000, "memory-fixture"),
                selectedSources = listOf(FilterSourcePayload(
                    id = "generated-500k",
                    format = BlocklistFormat.PLAIN_DOMAINS,
                    acceptedSha256 = hash,
                    declaredByteSize = payload.length(),
                    openStream = payload::inputStream,
                )),
                catalogRevision = "memory-fixture",
                catalogSha256 = "88e15e8fc80092830d58da54fe01897327b24dd689fc45090f6734e3ae9155b0",
                maxRules = RULE_COUNT,
            )
            forceGc()
            val compiler = FilterCompiler(
                scratchRoot = File(root, "scratch"),
                memoryObserver = FilterCompileMemoryObserver {
                    forceGc()
                    usedHeap()
                },
            )
            val first = compiler.compile(request)
            val second = compiler.compile(request)
            try {
                assertEquals(RULE_COUNT, first.blockRuleCount)
                assertEquals(first.sha256, second.sha256)
                assertTrue(first.memoryMetrics.liveHeapObserved)
                assertTrue(
                    "transient compile delta was ${first.memoryMetrics.transientHeapDeltaBytes}",
                    first.memoryMetrics.transientHeapDeltaBytes <= 64L * MIB,
                )
                assertTrue(first.memoryMetrics.compactIndexBytes <= 32L * MIB)

                forceGc()
                val beforeOpen = usedHeap()
                val artifact = FilterArtifact.open(first.file, first.sha256, first.identity)
                forceGc()
                val steadyDelta = maxOf(0, usedHeap() - beforeOpen)
                assertTrue("steady mapped snapshot delta was $steadyDelta", steadyDelta <= 32L * MIB)
                println(
                    "FILTER_500K_METRICS rules=$RULE_COUNT " +
                        "transientLiveDelta=${first.memoryMetrics.transientHeapDeltaBytes} " +
                        "steadyLiveDelta=$steadyDelta compactIndexBytes=${first.memoryMetrics.compactIndexBytes} " +
                        "digest=${first.sha256}",
                )
                assertEquals(FilterDecisionReason.BLOCKLIST, artifact.snapshot.decide("d000000.example.com").reason)
                assertEquals(FilterDecisionReason.BLOCKLIST, artifact.snapshot.decide("x.d499999.example.com").reason)
                assertEquals(FilterDecisionReason.DEFAULT_ALLOW, artifact.snapshot.decide("d500000.example.com").reason)
                assertTrue(artifact.channelIsOpenForTesting)
                artifact.close()
                assertFalse(artifact.channelIsOpenForTesting)
                assertTrue("closing a mapping must not delete its immutable artifact", first.file.isFile)
            } finally {
                first.close()
                second.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun forceGc() {
        repeat(3) {
            System.gc()
            Thread.sleep(25)
        }
    }

    private fun usedHeap(): Long = Runtime.getRuntime().run { totalMemory() - freeMemory() }

    companion object {
        private const val RULE_COUNT = 500_000
        private const val MIB = 1024 * 1024
    }
}
