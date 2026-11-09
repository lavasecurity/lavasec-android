package com.lavasec.app.reactnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.platform.ChainedConfigurationEditorCommitResult
import com.lavasec.app.platform.ChainedUpstreamPendingRemovalResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CI runs stagePending -> force-stop -> recoverPending and stageSaved -> force-stop -> verifySaved.
 * No phase argument performs both restart reconstruction journeys in the ordinary emulator suite.
 * Each phase uses dedicated test-owned native files/alias, with no production preferences or service.
 */
@RunWith(AndroidJUnit4::class)
class VpnProcessDeathInstrumentationTest {
    @Test
    fun persistedImportTransactionRecoversAcrossRunnerProcesses() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("parityProcessDeath")
        when (phase) {
            "stagePending" -> stagePending()
            "recoverPending" -> recoverPending()
            "stageSaved" -> stageSaved()
            "verifySaved" -> verifySaved()
            null -> {
                stagePending()
                recoverPending()
                stageSaved()
                verifySaved()
            }
            else -> throw AssertionError("unknown parityProcessDeath phase: $phase")
        }
    }

    private fun fixture() = VpnImportTransactionInstrumentationTest.Fixture(
        InstrumentationRegistry.getInstrumentation().targetContext,
        id = "managed-process-death",
    )

    private suspend fun cleanPreviousPhase() { fixture().close() }

    private suspend fun stagePending() {
        cleanPreviousPhase()
        val fixture = fixture()
        try {
            fixture.importFirst(EDITOR_ID)
            val stored = checkNotNull(fixture.repository.loadConfiguration())
            assertEquals(1L, stored.identity.generation)
            assertEquals(EDITOR_ID, stored.pendingImportId)
            fixture.assertPendingCannotActivate()
        } finally { fixture.release() }
    }

    private suspend fun recoverPending() {
        val fixture = fixture()
        try {
            val stored = checkNotNull(fixture.repository.loadConfiguration()) {
                "stagePending must durably persist before the target is force-stopped"
            }
            assertEquals(1L, stored.identity.generation)
            assertEquals(EDITOR_ID, stored.pendingImportId)
            fixture.assertPendingCannotActivate()
            assertEquals(ChainedUpstreamPendingRemovalResult.Removed,
                fixture.repository.removePendingImport(EDITOR_ID, stored.identity))
            assertNull(fixture.repository.loadConfiguration())
            assertNull(fixture.repository.loadSecrets())
            assertTrue(fixture.keys.accounts().isEmpty())
        } finally { fixture.close() }
    }

    private suspend fun stageSaved() {
        cleanPreviousPhase()
        val fixture = fixture()
        try {
            val owner = fixture.importFirst(EDITOR_ID)
            val saved = owner.commitEditor(EDITOR_ID, forceNewGeneration = true)
                as? ChainedConfigurationEditorCommitResult.Committed
                ?: throw AssertionError("pending import did not durably Save")
            assertEquals(2L, saved.identity.generation)
            assertNull(fixture.repository.loadConfiguration()?.pendingImportId)
        } finally { fixture.release() }
    }

    private suspend fun verifySaved() {
        val fixture = fixture()
        try {
            val saved = checkNotNull(fixture.repository.loadConfiguration()) {
                "stageSaved must durably persist before the target is force-stopped"
            }
            assertEquals(2L, saved.identity.generation)
            assertNull(saved.pendingImportId)
            assertNull(fixture.repository.configurationIssue())
            val secrets = checkNotNull(fixture.repository.loadSecrets())
            try { assertEquals(saved.identity.generation, secrets.generation) } finally { secrets.close() }
            assertEquals(ChainedUpstreamPendingRemovalResult.Superseded,
                fixture.repository.removePendingImport(EDITOR_ID, saved.identity.copy(generation = 1)))
            assertEquals(saved, fixture.repository.loadConfiguration())
        } finally { fixture.close() }
    }

    private companion object { const val EDITOR_ID = "managed-process-death-editor" }
}
