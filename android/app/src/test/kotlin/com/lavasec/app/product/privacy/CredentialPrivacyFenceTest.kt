package com.lavasec.app.product.privacy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CredentialPrivacyFenceTest {
    @Test
    fun `export clear attachment graph rotation and telemetry share one exclusion boundary`() =
        runTest {
            val fence = CredentialPrivacyFence()
            val exportEntered = CompletableDeferred<Unit>()
            val releaseExport = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val export = async {
                fence.withExport {
                    events += "export-start"
                    exportEntered.complete(Unit)
                    releaseExport.await()
                    events += "export-end"
                }
            }
            exportEntered.await()

            assertFalse(fence.tryTelemetryWrite { events += "stale-telemetry" })
            val clear = async { fence.withClear { events += "clear" } }
            val attachment = async { fence.withAttachmentRead { events += "attachment" } }
            val rotation = async { fence.withGraphRotation { events += "rotation" } }
            runCurrent()
            assertEquals(listOf("export-start"), events)

            releaseExport.complete(Unit)
            export.await()
            clear.await()
            attachment.await()
            rotation.await()
            assertEquals(
                listOf("export-start", "export-end", "clear", "attachment", "rotation"),
                events,
            )
        }

    @Test
    fun `cancellation releases privacy ownership without running queued telemetry`() = runTest {
        val fence = CredentialPrivacyFence()
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val owner = async {
            fence.withExport {
                entered.complete(Unit)
                never.await()
            }
        }
        entered.await()
        assertFalse(fence.tryTelemetryWrite {})

        owner.cancelAndJoin()

        assertTrue(fence.tryTelemetryWrite {})
    }
}
