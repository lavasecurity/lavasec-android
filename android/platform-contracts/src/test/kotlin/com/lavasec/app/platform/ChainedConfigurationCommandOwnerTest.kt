package com.lavasec.app.platform

import com.lavasec.app.foundation.chained.ChainedConfRefusal
import com.lavasec.app.foundation.chained.ChainedRoutingPolicy
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.foundation.chained.ChainedUpstreamRotation
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainedConfigurationCommandOwnerTest {
    @Test
    fun `unchanged ordinary save remains a no-op without reading secret material`() = runTest {
        val initial = stored(configuration(), 1)
        val repository = FakeChainedUpstreamRepository(initial)
        val owner = ChainedConfigurationCommandOwner(repository)
        owner.beginEditor("ordinary", 1)
        assertEquals(ChainedConfigurationEditorCommitResult.Committed(initial.identity), owner.commitEditor("ordinary"))
        assertEquals(0, repository.secretLoads)
        assertTrue(repository.savedRotations.isEmpty())
    }

    @Test
    fun `unchanged pending save copies one coherent key pair and requires generation CAS`() = runTest {
        val initial = stored(configuration(), 1).copy(pendingImportId = "pending")
        val repository = FakeChainedUpstreamRepository(initial,
            ChainedUpstreamSaveResult.Committed(initial.identity.copy(generation = 2)))
        val material = checkNotNull(ChainedUpstreamSecretMaterial.create(1, ByteArray(32) { 0x11 }, ByteArray(32) { 0x33 }))
        repository.secretMaterial = material
        val owner = ChainedConfigurationCommandOwner(repository)
        owner.beginEditor("pending", 1)
        assertEquals(
            ChainedConfigurationEditorCommitResult.Committed(initial.identity.copy(generation = 2)),
            owner.commitEditor("pending"),
        )
        assertEquals(listOf(ChainedUpstreamSaveExpectation.Generation(1)), repository.savedExpectations)
        assertEquals(initial.configuration, repository.savedRotations.single().configuration)
        assertArrayEquals(ByteArray(32) { 0x11 }, repository.savedRotations.single().decodedPrivateKey())
        assertArrayEquals(ByteArray(32) { 0x33 }, repository.savedRotations.single().decodedPresharedKey())
        assertTrue(material.privateKeyCopy().all { it == 0.toByte() })
        assertTrue(material.presharedKeyCopy()!!.all { it == 0.toByte() })
        assertNull(owner.currentDraft())
    }

    @Test
    fun `forced save refuses mismatched secret generation and keeps pending draft`() = runTest {
        val initial = stored(configuration(), 1).copy(pendingImportId = "pending")
        val repository = FakeChainedUpstreamRepository(initial)
        val material = checkNotNull(ChainedUpstreamSecretMaterial.create(2, ByteArray(32) { 0x11 }, null))
        repository.secretMaterial = material
        val owner = ChainedConfigurationCommandOwner(repository)
        owner.beginEditor("pending", 1)
        assertEquals(ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION),
            owner.commitEditor("pending", forceNewGeneration = true))
        assertEquals("pending", owner.currentDraft()?.id)
        assertTrue(repository.savedRotations.isEmpty())
        assertTrue(material.privateKeyCopy().all { it == 0.toByte() })
    }

    @Test
    fun `pending rotation cannot be adopted by another editor id`() = runTest {
        val repository = FakeChainedUpstreamRepository(stored(configuration(), 1).copy(pendingImportId = "original"))
        val owner = ChainedConfigurationCommandOwner(repository)
        assertTrue(owner.beginEditor("original", 1) is ChainedConfigurationEditorResult.Draft)
        assertEquals(ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION),
            owner.beginEditor("foreign", 1))
        assertEquals("original", owner.currentDraft()?.id)
    }

    @Test
    fun `failed first import cancellation discards the draft and retries the same durable witness`() = runTest {
        val pending = stored(configuration(), 1).copy(pendingImportId = "pending")
        val repository = FakeChainedUpstreamRepository(pending)
        repository.pendingRemovalFailure = ChainedUpstreamPendingRemovalResult.Failed("key deletion failed")
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        owner.beginEditor("pending", 1)
        owner.stageEditorConfiguration("pending", 0, "replacement")
        assertTrue(checkNotNull(owner.currentDraft()).changed)

        assertEquals(ChainedUpstreamPendingRemovalResult.Failed("key deletion failed"),
            owner.cancelFirstImport("pending", pending.identity))
        assertNull(owner.currentDraft())
        assertEquals(pending, repository.loadConfiguration())

        repository.pendingRemovalFailure = null
        assertEquals(ChainedUpstreamPendingRemovalResult.Removed,
            owner.cancelFirstImport("pending", pending.identity))
        assertEquals(List(2) { "pending" to pending.identity }, repository.pendingRemovals)
        assertNull(repository.loadConfiguration())
        assertEquals(0, repository.removeAllCalls)
    }

    @Test
    fun `stale first import cancellation preserves the saved successor and another open editor`() = runTest {
        val pending = stored(configuration(), 1).copy(pendingImportId = "pending")
        val repository = FakeChainedUpstreamRepository(pending)
        val owner = ChainedConfigurationCommandOwner(repository)
        owner.beginEditor("pending", 1)
        val successor = stored(configuration(), 2)
        repository.setStored(successor)
        owner.beginEditor("successor", 2)

        assertEquals(ChainedUpstreamPendingRemovalResult.Superseded,
            owner.cancelFirstImport("pending", pending.identity))

        assertEquals(listOf("pending" to pending.identity), repository.pendingRemovals)
        assertEquals(successor, repository.loadConfiguration())
        assertEquals("successor", owner.currentDraft()?.id)
        assertEquals(0, repository.removeAllCalls)
    }

    @Test fun `concurrent key consumers have exactly one winner and a released lease can retry`() {
        val material = ChainedConfigurationKeyMaterial(rotation())
        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(2) {
            Thread {
                ready.countDown()
                start.await()
                if (material.consume() != null) winners.incrementAndGet()
            }.apply { start() }
        }
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
        start.countDown()
        threads.forEach { it.join(5_000); assertFalse(it.isAlive) }
        assertEquals(1, winners.get())
        assertNull(material.consume())
        material.release()
        assertTrue(material.consume() != null)
        assertNull(material.consume())
    }


    @Test
    fun `valid import returns display-safe metadata and an opaque handle`() {
        val repository = FakeChainedUpstreamRepository()
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }

        val review = owner.importReview("anything")

        assertTrue(review is ChainedConfigurationReview.Ready)
        val ready = review as ChainedConfigurationReview.Ready
        assertEquals("vpn.example.com", ready.metadata.endpointHost)
        assertEquals(51820, ready.metadata.endpointPort)
        assertEquals("vpn.example.com:51820", ready.metadata.endpoint)
        assertEquals("10.7.0.2", ready.metadata.clientAddress)
        assertEquals(listOf("0.0.0.0/0"), ready.metadata.allowedIPs)
        assertEquals(listOf("10.64.0.1"), ready.metadata.dnsAddresses)
        assertEquals(25, ready.metadata.persistentKeepaliveSeconds)
        assertEquals(1420, ready.metadata.interfaceMTU)
        assertEquals(PEER_KEY, ready.metadata.peerPublicKey)
        assertEquals(ChainedRoutingPolicy.FULL_TUNNEL, ready.metadata.eligibility.routingPolicy)
        assertEquals(listOf("10.64.0.1"), ready.metadata.eligibility.usableTunnelResolvers)
        assertNull(ready.metadata.eligibility.refusal)
        assertTrue(ready.metadata.eligibility.isEligible)
        assertEquals(
            "ChainedConfigurationKeyMaterial(<redacted>)",
            ready.keyMaterial.toString(),
        )
        assertTrue(repository.savedRotations.isEmpty())
    }

    @Test
    fun `invalid import returns the typed refusal and never stages anything`() = runTest {
        val repository = FakeChainedUpstreamRepository()
        val owner = ChainedConfigurationCommandOwner(repository)

        val review = owner.importReview("this is not a wireguard configuration")

        assertTrue(review is ChainedConfigurationReview.Rejected)
        assertEquals(
            ChainedConfRefusal.MalformedLine,
            (review as ChainedConfigurationReview.Rejected).refusal,
        )
        assertTrue(repository.savedRotations.isEmpty())
        assertNull(owner.storedMetadata())
    }

    @Test
    fun `save commits the reviewed rotation and reports its identity`() = runTest {
        val configuration = configuration()
        val identity = ChainedUpstreamIdentity(
            generation = 4L,
            fingerprint = canonicalChainedUpstreamConfigurationFingerprint(configuration),
        )
        val repository = FakeChainedUpstreamRepository(
            nextSaveResult = ChainedUpstreamSaveResult.Committed(identity),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation(configuration) }

        val ready = owner.importReview("anything") as ChainedConfigurationReview.Ready
        val result = owner.save(ready)

        assertEquals(ChainedConfigurationSaveResult.Committed(identity), result)
        assertEquals(1, repository.savedRotations.size)
        assertEquals(configuration, repository.savedRotations.single().configuration)
    }

    @Test
    fun `save failure preserves the prior committed configuration and permits retry`() = runTest {
        val priorConfiguration = configuration(allowedIPs = listOf("10.0.0.0/8"))
        val prior = stored(priorConfiguration, generation = 3L)
        val repository = FakeChainedUpstreamRepository(
            initial = prior,
            nextSaveResult = ChainedUpstreamSaveResult.Rejected(
                ChainedUpstreamSecretStaging.Failure.PUBLISH_FAILED,
            ),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }

        val ready = owner.importReview("anything") as ChainedConfigurationReview.Ready
        val result = owner.save(ready)

        assertEquals(
            ChainedConfigurationSaveResult.Rejected(
                ChainedUpstreamSecretStaging.Failure.PUBLISH_FAILED,
            ),
            result,
        )
        assertSame(prior, repository.loadConfiguration())

        val identity = ChainedUpstreamIdentity(
            generation = 4L,
            fingerprint = canonicalChainedUpstreamConfigurationFingerprint(configuration()),
        )
        repository.nextSaveResult = ChainedUpstreamSaveResult.Committed(identity)
        assertEquals(
            ChainedConfigurationSaveResult.Committed(identity),
            owner.save(ready),
        )
        assertEquals(2, repository.savedRotations.size)
    }

    @Test
    fun `authoritative read failure is typed and keeps the review retryable`() = runTest {
        val repository = FakeChainedUpstreamRepository(
            nextSaveResult = ChainedUpstreamSaveResult.AuthoritativeReadFailed,
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val ready = owner.importReview("anything") as ChainedConfigurationReview.Ready

        assertEquals(ChainedConfigurationSaveResult.AuthoritativeReadFailed, owner.save(ready))

        val identity = ChainedUpstreamIdentity(
            generation = 1L,
            fingerprint = canonicalChainedUpstreamConfigurationFingerprint(configuration()),
        )
        repository.nextSaveResult = ChainedUpstreamSaveResult.Committed(identity)
        assertEquals(
            ChainedConfigurationSaveResult.Committed(identity),
            owner.save(ready),
        )
    }

    @Test
    fun `save of a rejected review is not reviewable and never touches the repository`() = runTest {
        val repository = FakeChainedUpstreamRepository()
        val owner = ChainedConfigurationCommandOwner(repository)
        val rejected = owner.importReview("PrivateKey without a section") as ChainedConfigurationReview.Rejected

        val result = owner.save(rejected)

        assertEquals(
            ChainedConfigurationSaveResult.NotReviewable(rejected.refusal),
            result,
        )
        assertTrue(repository.savedRotations.isEmpty())
    }

    @Test
    fun `a committed review cannot be replayed into a second generation`() = runTest {
        val configuration = configuration()
        val identity = ChainedUpstreamIdentity(
            generation = 1L,
            fingerprint = canonicalChainedUpstreamConfigurationFingerprint(configuration),
        )
        val repository = FakeChainedUpstreamRepository(
            nextSaveResult = ChainedUpstreamSaveResult.Committed(identity),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation(configuration) }
        val ready = owner.importReview("anything") as ChainedConfigurationReview.Ready

        assertEquals(ChainedConfigurationSaveResult.Committed(identity), owner.save(ready))
        assertEquals(ChainedConfigurationSaveResult.AlreadyConsumed, owner.save(ready))
        assertEquals(1, repository.savedRotations.size)
    }

    @Test
    fun `remove reports success and maps a storage failure to a typed result`() = runTest {
        val repository = FakeChainedUpstreamRepository()
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }

        assertEquals(ChainedConfigurationRemovalResult.Removed, owner.remove())
        assertEquals(1, repository.removeAllCalls)
        assertNull(owner.storedMetadata())

        repository.removeThrows = IOException("Chained keys could not be removed")
        val failure = owner.remove()

        assertTrue(failure is ChainedConfigurationRemovalResult.Failed)
        assertEquals(
            "Chained keys could not be removed",
            (failure as ChainedConfigurationRemovalResult.Failed).message,
        )
    }

    @Test
    fun `no key material appears in review metadata or stored metadata`() = runTest {
        val configuration = configuration()
        val repository = FakeChainedUpstreamRepository(
            initial = stored(configuration, generation = 2L),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation(configuration) }

        val ready = owner.importReview("anything") as ChainedConfigurationReview.Ready
        assertNoSecretFields(ready.metadata)
        assertFalse(ready.toString().contains(PRIVATE_KEY))
        assertFalse(ready.toString().contains(PRESHARED_KEY))
        ChainedConfigurationReview.Ready::class.java.declaredFields.forEach { field ->
            field.isAccessible = true
            val text = field.get(ready)?.toString().orEmpty()
            assertFalse("review field ${field.name} leaked key material", text.contains(PRIVATE_KEY))
            assertFalse("review field ${field.name} leaked key material", text.contains(PRESHARED_KEY))
        }

        val storedMetadata = requireNotNull(owner.storedMetadata())
        assertNoSecretFields(storedMetadata)
        assertFalse(storedMetadata.toString().contains(PRIVATE_KEY))
        assertFalse(storedMetadata.toString().contains(PRESHARED_KEY))
    }

    @Test
    fun `editor begin edit remove and commit project the single rotation without key material`() = runTest {
        val configuration = configuration()
        val prior = stored(configuration, generation = 3L)
        val committedIdentity = ChainedUpstreamIdentity(
            generation = 4L,
            fingerprint = canonicalChainedUpstreamConfigurationFingerprint(configuration),
        )
        val repository = FakeChainedUpstreamRepository(
            initial = prior,
            nextSaveResult = ChainedUpstreamSaveResult.Committed(committedIdentity),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation(configuration) }

        // begin: one display-safe row derived from the committed rotation.
        val opened = owner.beginEditor("e1", 3L)
        val draft = (opened as ChainedConfigurationEditorResult.Draft).draft
        assertEquals("e1", draft.id)
        assertEquals(3L, draft.generation)
        assertFalse(draft.changed)
        assertFalse(draft.removeStaged)
        assertEquals(1, draft.rows.size)
        assertEquals("vpn.example.com:51820", draft.rows.single().name)
        assertEquals("Full tunnel", draft.rows.single().mode)
        assertTrue(draft.rows.single().isEnabled)
        assertTrue(draft.containsFullTunnel)

        // edit: stage a replacement for row 0; the lease stays opaque.
        val edited = owner.stageEditorConfiguration("e1", 0, "anything")
        val editedDraft = (edited as ChainedConfigurationEditorResult.Draft).draft
        assertTrue(editedDraft.changed)
        assertFalse(editedDraft.removeStaged)
        assertEquals(1, editedDraft.rows.size)
        assertFalse(editedDraft.toString().contains(PRIVATE_KEY))
        assertFalse(editedDraft.toString().contains(PRESHARED_KEY))

        // Discard and reopen: back to the committed row with no staged change.
        owner.cancelEditor("e1")
        assertEquals(
            false,
            (owner.beginEditor("e1", 3L) as ChainedConfigurationEditorResult.Draft).draft.changed,
        )

        // commit the staged replacement.
        assertTrue(owner.stageEditorConfiguration("e1", 0, "anything") is ChainedConfigurationEditorResult.Draft)
        assertEquals(
            ChainedConfigurationEditorCommitResult.Committed(committedIdentity),
            owner.commitEditor("e1"),
        )
        // A committed draft cannot be replayed.
        assertEquals(
            ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.UNKNOWN_DRAFT),
            owner.commitEditor("e1"),
        )
        assertNull(owner.editorDraft("e1"))
        assertEquals(1, repository.savedRotations.size)
    }

    @Test
    fun `editor removal commits the single rotation away`() = runTest {
        val repository = FakeChainedUpstreamRepository(
            initial = stored(configuration(), generation = 2L),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }

        owner.beginEditor("e1", 2L)
        val staged = owner.stageEditorRemoval("e1", 0) as ChainedConfigurationEditorResult.Draft
        assertTrue(staged.draft.changed)
        assertTrue(staged.draft.removeStaged)
        assertTrue(staged.draft.rows.isEmpty())

        assertEquals(ChainedConfigurationEditorCommitResult.Removed, owner.commitEditor("e1"))
        assertEquals(1, repository.removeAllCalls)
        assertNull(owner.editorDraft("e1"))
    }

    @Test
    fun `invalid row and composition operations refuse without partially changing the draft`() = runTest {
        val repository = FakeChainedUpstreamRepository(initial = stored(configuration(), generation = 1L))
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        owner.beginEditor("e1", 1L)

        assertEquals(
            ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.INVALID_ROW),
            owner.swapEditorRows("e1"),
        )
        assertEquals(
            ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.INVALID_COMPOSITION),
            owner.stageEditorConfiguration("e1", 1, "anything"),
        )
        assertEquals(
            ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.INVALID_ROW),
            owner.stageEditorRemoval("e1", 1),
        )
        // A command for an id that names no draft is a distinct refusal, never the multi-row code.
        assertEquals(
            ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.UNKNOWN_DRAFT),
            owner.swapEditorRows("other"),
        )
    }

    @Test
    fun `editor refuses a mismatched generation at begin and at commit`() = runTest {
        val repository = FakeChainedUpstreamRepository(initial = stored(configuration(), generation = 3L))
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }

        assertEquals(
            ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION),
            owner.beginEditor("e1", 999L),
        )

        owner.beginEditor("e1", 3L)
        owner.stageEditorConfiguration("e1", 0, "anything")
        // A rotation commits while the draft is open.
        repository.setStored(stored(configuration(), generation = 5L))
        assertEquals(
            ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION),
            owner.commitEditor("e1"),
        )
    }

    @Test
    fun `editor refuses to open when nothing is configured`() = runTest {
        val owner = ChainedConfigurationCommandOwner(FakeChainedUpstreamRepository()) { rotation() }
        assertEquals(
            ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.NOT_CONFIGURED),
            owner.beginEditor("e1", 1L),
        )
    }

    @Test
    fun `toggle enabled commits the preference and unsupported keys refuse`() = runTest {
        val committed = java.util.concurrent.atomic.AtomicBoolean(false)
        val repository = FakeChainedUpstreamRepository()
        val owner = ChainedConfigurationCommandOwner(
            repository,
            enabledPreference = { enabled -> committed.set(enabled); true },
            parseRotation = { rotation() },
        )

        assertEquals(ChainedConfigurationToggleResult.Applied, owner.toggle(ChainedConfigurationToggleKey.ENABLED, true))
        assertTrue(committed.get())
        assertEquals(
            ChainedConfigurationToggleResult.Refused(ChainedConfigurationEditorRefusal.UNSUPPORTED_TOGGLE),
            owner.toggle(ChainedConfigurationToggleKey.SETUP, true),
        )
        assertEquals(
            ChainedConfigurationToggleResult.Refused(ChainedConfigurationEditorRefusal.UNSUPPORTED_TOGGLE),
            owner.toggle(ChainedConfigurationToggleKey.FALLBACK, true),
        )
        // No preference port: enabled is an explicit refusal, never a fabricated success.
        assertEquals(
            ChainedConfigurationToggleResult.Refused(ChainedConfigurationEditorRefusal.UNSUPPORTED_TOGGLE),
            ChainedConfigurationCommandOwner(repository).toggle(ChainedConfigurationToggleKey.ENABLED, true),
        )
    }

    @Test
    fun `two row add rename and swap preserve original references until one generation fenced save`() = runTest {
        val entry = configuration(endpointHost = "192.0.2.1").named("Entry")
        val exit = configuration(endpointHost = "192.0.2.2")
        val next = stored(exit.withEntryHop(entry), 4).identity
        val repository = FakeChainedUpstreamRepository(stored(entry, 3), ChainedUpstreamSaveResult.Committed(next))
        val owner = ChainedConfigurationCommandOwner(repository) { rotation(exit, base64Key(99)) }
        owner.beginEditor("two", 3)
        val added = owner.stageEditorConfiguration("two", 1, "native only", "Exit", expectedRevision = 0) as ChainedConfigurationEditorResult.Draft
        assertEquals(listOf("Entry", "Exit"), added.draft.rows.map { it.name })
        assertEquals(0, repository.secretLoads)
        assertEquals(ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION),
            owner.stageEditorName("two", 0, "Late sheet", expectedRevision = 0))
        owner.stageEditorName("two", 0, "Renamed entry", expectedRevision = 1)
        val swapped = owner.swapEditorRows("two") as ChainedConfigurationEditorResult.Draft
        assertEquals(listOf("Exit", "Renamed entry"), swapped.draft.rows.map { it.name })
        assertEquals(stored(entry, 3), repository.loadConfiguration())
        assertEquals(ChainedConfigurationEditorCommitResult.Committed(next), owner.commitEditor("two"))
        val saved = repository.savedSelections.single()
        assertEquals(listOf(null, 0), saved.map { it.originalIndex })
        assertArrayEquals(Base64.getDecoder().decode(base64Key(99)), checkNotNull(saved.first().replacement).decodedPrivateKey())
        assertNull(saved.last().replacement)
        assertEquals(listOf(ChainedUpstreamSaveExpectation.Generation(3)), repository.savedExpectations)
        assertNull(owner.currentDraft())
    }

    @Test
    fun `row toggle is a metadata reference transaction and reset cancellation writes nothing`() = runTest {
        val entry = configuration(endpointHost = "192.0.2.1")
        val exit = configuration(endpointHost = "192.0.2.2")
        val original = stored(exit.withEntryHop(entry), 5)
        val repository = FakeChainedUpstreamRepository(original, ChainedUpstreamSaveResult.Committed(original.identity.copy(generation = 6)))
        val owner = ChainedConfigurationCommandOwner(repository)
        assertTrue(owner.setRowEnabled(1, false, 5) is ChainedConfigurationEditorCommitResult.Committed)
        val rows = repository.savedSelections.single()
        assertEquals(listOf(0, 1), rows.map { it.originalIndex })
        assertEquals(listOf(true, false), rows.map { it.configuration.isEnabled })
        assertTrue(rows.all { it.replacement == null })
        assertEquals(ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION), owner.setRowEnabled(0, false, 4))
        owner.beginEditor("reset", 5)
        assertEquals(ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.BUSY), owner.setRowEnabled(0, false, 5))
        val reset = owner.resetEditor("reset") as ChainedConfigurationEditorResult.Draft
        assertTrue(reset.draft.changed)
        assertTrue(reset.draft.rows.isEmpty())
        owner.cancelEditor("reset")
        assertEquals(original, repository.loadConfiguration())
        assertEquals(0, repository.removeAllCalls)
    }

    @Test
    fun `committing draft rejects concurrent sheet edits cancellation and new visits until publication settles`() = runTest {
        val original = stored(configuration(), 5)
        val repository = FakeChainedUpstreamRepository(original, ChainedUpstreamSaveResult.Committed(original.identity.copy(generation = 6)))
        val saving = CompletableDeferred<Unit>()
        val publish = CompletableDeferred<Unit>()
        repository.beforeSelectionSave = { saving.complete(Unit); publish.await() }
        val owner = ChainedConfigurationCommandOwner(repository)
        owner.beginEditor("saving", 5)
        owner.stageEditorName("saving", 0, "New name")
        var result: ChainedConfigurationEditorCommitResult? = null
        val commit = launch { result = owner.commitEditor("saving") }
        saving.await()
        assertEquals(ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.BUSY), owner.stageEditorName("saving", 0, "Late name"))
        assertEquals(ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.BUSY), owner.beginEditor("other", 5))
        assertEquals(ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.BUSY), owner.commitEditor("saving"))
        owner.cancelEditor("saving")
        assertEquals("saving", owner.currentDraft()?.id)
        publish.complete(Unit)
        commit.join()
        assertTrue(result is ChainedConfigurationEditorCommitResult.Committed)
        assertEquals("New name", repository.savedSelections.single().single().configuration.displayName)
        assertNull(owner.currentDraft())
    }

    @Test
    fun `enabling one row from globally off clears other participants before durable ON`() = runTest {
        val entry = configuration(endpointHost = "192.0.2.1")
        val original = stored(configuration(endpointHost = "192.0.2.2").withEntryHop(entry), 5)
        val repository = FakeChainedUpstreamRepository(original, ChainedUpstreamSaveResult.Committed(original.identity.copy(generation = 6)))
        val writes = mutableListOf<String>()
        repository.beforeSelectionSave = { writes += "profiles" }
        val owner = ChainedConfigurationCommandOwner(repository, enabledPreference = { enabled -> writes += "enabled=$enabled"; true }, loadEnabled = { false })
        assertTrue(owner.setRowEnabled(1, true, 5) is ChainedConfigurationEditorCommitResult.Committed)
        assertEquals(listOf(false, true), repository.savedSelections.single().map { it.configuration.isEnabled })
        assertEquals(listOf("profiles", "enabled=true"), writes)
        assertTrue(repository.savedSelections.single().all { it.replacement == null })
    }

    @Test
    fun `failed final OFF preference leaves profiles and editor intact without deletion`() = runTest {
        val original = stored(configuration(), 5)
        val repository = FakeChainedUpstreamRepository(original)
        val owner = ChainedConfigurationCommandOwner(repository, enabledPreference = { false }, loadEnabled = { true })
        assertTrue(owner.setRowEnabled(0, false, 5) is ChainedConfigurationEditorCommitResult.Failed)
        assertTrue(repository.savedSelections.isEmpty())
        owner.beginEditor("delete", 5)
        owner.resetEditor("delete")
        assertTrue(owner.commitEditor("delete") is ChainedConfigurationEditorCommitResult.Failed)
        assertEquals(original, repository.loadConfiguration())
        assertEquals(0, repository.removeAllCalls)
        assertNotNull(owner.currentDraft())
    }

    private fun assertNoSecretFields(metadata: ChainedConfigurationMetadata) {
        ChainedConfigurationMetadata::class.java.declaredFields.forEach { field ->
            field.isAccessible = true
            val text = field.get(metadata)?.toString().orEmpty()
            assertFalse("metadata field ${field.name} leaked the private key", text.contains(PRIVATE_KEY))
            assertFalse("metadata field ${field.name} leaked the pre-shared key", text.contains(PRESHARED_KEY))
        }
        ChainedConfigurationEligibility::class.java.declaredFields.forEach { field ->
            field.isAccessible = true
            val text = field.get(metadata.eligibility)?.toString().orEmpty()
            assertFalse("eligibility field ${field.name} leaked the private key", text.contains(PRIVATE_KEY))
            assertFalse("eligibility field ${field.name} leaked the pre-shared key", text.contains(PRESHARED_KEY))
        }
    }

    @Test
    fun `full profile publication rebases failed settings and retry never restages or reuses keys`() = runTest {
        val original = stored(configuration(), 1)
        val successor = stored(configuration().named("Renamed"), 2)
        val identity = successor.identity
        val repository = FakeChainedUpstreamRepository(original, ChainedUpstreamSaveResult.Committed(identity))
        var allowSettings = false
        var settingsWrites = 0
        val owner = ChainedConfigurationCommandOwner(repository, onFullTunnelCommitted = { generation ->
            assertEquals(2L, generation)
            settingsWrites++
            repository.setStored(successor)
            allowSettings
        })
        owner.beginEditor("retry-settings", 1)
        owner.stageEditorName("retry-settings", 0, "Renamed")
        assertEquals(ChainedConfigurationEditorCommitResult.Failed("Chained fallback preference write failed", identity), owner.commitEditor("retry-settings"))
        val rebased = checkNotNull(owner.currentDraft())
        assertEquals(2L, rebased.generation)
        assertTrue(rebased.changed)
        assertEquals("Renamed", rebased.rows.single().name)
        assertEquals(1, repository.savedSelections.size)
        assertEquals(0, repository.secretLoads)
        allowSettings = true
        assertTrue(owner.commitEditor("retry-settings", forceNewGeneration = true) is ChainedConfigurationEditorCommitResult.Committed)
        assertEquals(1, repository.savedSelections.size)
        assertEquals(0, repository.secretLoads)
        assertEquals(2, settingsWrites)
        assertNull(owner.currentDraft())
    }

    @Test
    fun `fallback consent cannot race a full profile commit and never saves DNS or credentials`() = runTest {
        val original = stored(configuration(), 1)
        val repository = FakeChainedUpstreamRepository(original)
        val consent = mutableListOf<Boolean>()
        val owner = ChainedConfigurationCommandOwner(repository, dnsFallbackPreference = { consent += it; true })
        assertEquals(ChainedConfigurationToggleResult.Refused(ChainedConfigurationEditorRefusal.INVALID_COMPOSITION), owner.setDnsFallbackEnabled(true))
        assertEquals(ChainedConfigurationToggleResult.Applied, owner.setDnsFallbackEnabled(false))
        assertEquals(listOf(false), consent)
        assertTrue(repository.savedSelections.isEmpty())
        assertEquals(0, repository.secretLoads)
    }

    @Test
    fun `a settings write in flight fences fallback toggles until the profile commit settles`() = runTest {
        val original = stored(configuration(), 1)
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val repository = FakeChainedUpstreamRepository(original, ChainedUpstreamSaveResult.Committed(original.identity.copy(generation = 2)))
        val owner = ChainedConfigurationCommandOwner(repository,
            onFullTunnelCommitted = { entered.complete(Unit); proceed.await(); true },
            dnsFallbackPreference = { error("a racing preference must not be admitted") },
        )
        owner.beginEditor("full", 1)
        owner.stageEditorName("full", 0, "New name")
        val commit = launch { assertTrue(owner.commitEditor("full") is ChainedConfigurationEditorCommitResult.Committed) }
        entered.await()
        assertEquals(ChainedConfigurationToggleResult.Refused(ChainedConfigurationEditorRefusal.BUSY), owner.setDnsFallbackEnabled(false))
        assertEquals(ChainedConfigurationToggleResult.Refused(ChainedConfigurationEditorRefusal.BUSY), owner.setDnsFallbackEnabled(true))
        proceed.complete(Unit); commit.join()
    }

    @Test fun `guarded pending and replacement commits refuse retired or regranted authority at publication`() = runTest {
        for (pending in listOf(false, true)) for (regrant in listOf(false, true)) {
            val initial = stored(configuration(), 1).copy(pendingImportId = if (pending) "editor" else null)
            val repository = FakeChainedUpstreamRepository(initial, ChainedUpstreamSaveResult.Committed(initial.identity.copy(generation = 2)))
            repository.secretMaterial = checkNotNull(ChainedUpstreamSecretMaterial.create(1, ByteArray(32) { 0x11 }, ByteArray(32) { 0x33 }))
            val owner = ChainedConfigurationCommandOwner(repository,
                onFullTunnelCommittedWhileCurrent = { _, current -> current() }, parseRotation = { rotation() })
            owner.beginEditor("editor", 1)
            if (!pending) owner.stageEditorConfiguration("editor", 0, "replacement")
            val draft = owner.currentDraft()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            repository.beforeGuardedWrite = { entered.complete(Unit); release.await() }
            val revision = java.util.concurrent.atomic.AtomicLong(1)
            val granted = java.util.concurrent.atomic.AtomicBoolean(true)
            val captured = revision.get()
            val guard = { granted.get() && revision.get() == captured }
            var result: ChainedConfigurationEditorCommitResult? = null
            val saving = launch { result = owner.commitEditor("editor", isCurrent = guard) }
            entered.await()
            granted.set(false); revision.incrementAndGet()
            if (regrant) granted.set(true)
            release.complete(Unit); saving.join()
            assertEquals(ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION), result)
            assertEquals(initial, repository.loadConfiguration()); assertEquals(draft, owner.currentDraft())
            assertTrue(repository.savedSelections.isEmpty()); assertTrue(repository.savedRotations.isEmpty())
            // Real storage produces a fresh one-shot secret lease for the retry.
            repository.secretMaterial = checkNotNull(ChainedUpstreamSecretMaterial.create(1, ByteArray(32) { 0x11 }, ByteArray(32) { 0x33 }))
            repository.beforeGuardedWrite = null; granted.set(true)
            val fresh = revision.get()
            assertTrue(owner.commitEditor("editor", isCurrent = { granted.get() && revision.get() == fresh }) is ChainedConfigurationEditorCommitResult.Committed)
            assertNull(owner.currentDraft()); assertNull(repository.loadConfiguration()?.pendingImportId)
            assertEquals(1, repository.savedRotations.size)
            val expectedPrivate = if (pending) ByteArray(32) { 0x11 } else Base64.getDecoder().decode(PRIVATE_KEY)
            val expectedPsk = if (pending) ByteArray(32) { 0x33 } else Base64.getDecoder().decode(PRESHARED_KEY)
            assertArrayEquals(expectedPrivate, repository.savedRotations.single().decodedPrivateKey())
            assertArrayEquals(expectedPsk, repository.savedRotations.single().decodedPresharedKey())
        }
    }

    @Test fun `guarded commit rejects retirement after metadata and secret reads before any write`() = runTest {
        for (secretRead in listOf(false, true)) {
            val initial = stored(configuration(), 1).copy(pendingImportId = "pending")
            val repository = FakeChainedUpstreamRepository(initial)
            val material = checkNotNull(ChainedUpstreamSecretMaterial.create(1, ByteArray(32) { 0x11 }, ByteArray(32) { 0x33 }))
            repository.secretMaterial = material
            val owner = ChainedConfigurationCommandOwner(repository)
            owner.beginEditor("pending", 1)
            val before = owner.currentDraft()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val alive = java.util.concurrent.atomic.AtomicBoolean(true)
            val barrier: suspend () -> Unit = { entered.complete(Unit); release.await() }
            if (secretRead) repository.beforeSecrets = barrier else repository.beforeLoad = barrier
            var result: ChainedConfigurationEditorCommitResult? = null
            val saving = launch { result = owner.commitEditor("pending", isCurrent = alive::get) }
            entered.await(); alive.set(false); release.complete(Unit); saving.join()
            repository.beforeLoad = null; repository.beforeSecrets = null
            assertEquals(ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION), result)
            assertEquals(initial, repository.loadConfiguration()); assertEquals(before, owner.currentDraft())
            assertTrue(repository.savedRotations.isEmpty()); assertTrue(repository.savedSelections.isEmpty())
            assertEquals(if (secretRead) 1 else 0, repository.secretLoads)
            if (secretRead) {
                assertTrue(material.privateKeyCopy().all { it == 0.toByte() })
                assertTrue(material.presharedKeyCopy()!!.all { it == 0.toByte() })
            }
        }
    }

    @Test fun `post publication retirement keeps exact successor keys and retries only pending settings`() = runTest {
        val initial = stored(configuration(), 1)
        val replacement = rotation(configuration(endpointHost = "replacement.example"))
        val successor = stored(replacement.configuration, 2)
        val repository = FakeChainedUpstreamRepository(initial, ChainedUpstreamSaveResult.Committed(successor.identity))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val revision = java.util.concurrent.atomic.AtomicLong(1)
        var settingsWrites = 0; var pauseSettings = true
        val owner = ChainedConfigurationCommandOwner(repository,
            onFullTunnelCommittedWhileCurrent = { _, guard ->
                if (pauseSettings) { entered.complete(Unit); release.await() }
                if (!guard()) false else { settingsWrites++; true }
            }, parseRotation = { replacement })
        owner.beginEditor("editor", 1); owner.stageEditorConfiguration("editor", 0, "replacement")
        val captured = revision.get()
        var result: ChainedConfigurationEditorCommitResult? = null
        val saving = launch { result = owner.commitEditor("editor", isCurrent = { revision.get() == captured }) }
        entered.await()
        assertEquals(successor, repository.loadConfiguration())
        assertEquals(2L, owner.currentDraft()?.generation)
        assertEquals(ChainedConfigurationEditorResult.Refused(ChainedConfigurationEditorRefusal.BUSY), owner.beginEditor("other", 2))
        revision.incrementAndGet(); release.complete(Unit); saving.join()
        assertEquals(ChainedConfigurationEditorCommitResult.Failed("Native VPN mutation authority retired", successor.identity), result)
        assertEquals(0, settingsWrites); assertEquals(successor, repository.loadConfiguration())
        assertEquals(2L, owner.currentDraft()?.generation); assertTrue(owner.currentDraft()!!.changed)
        assertEquals(1, repository.savedRotations.size)
        assertArrayEquals(replacement.decodedPrivateKey(), repository.savedRotations.single().decodedPrivateKey())
        assertArrayEquals(replacement.decodedPresharedKey(), repository.savedRotations.single().decodedPresharedKey())
        pauseSettings = false
        val fresh = revision.get()
        assertEquals(ChainedConfigurationEditorCommitResult.Committed(successor.identity),
            owner.commitEditor("editor", isCurrent = { revision.get() == fresh }))
        assertEquals(1, settingsWrites); assertEquals(1, repository.savedRotations.size)
        assertNull(owner.currentDraft())
    }

    @Test fun `post removal retirement reports the durable generation without authorizing ordinary or pending UI completion`() = runTest {
        for (pending in listOf(false, true)) {
            val initial = stored(configuration(), 1).copy(pendingImportId = if (pending) "editor" else null)
            val repository = FakeChainedUpstreamRepository(initial)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val revision = java.util.concurrent.atomic.AtomicLong(1)
            val granted = java.util.concurrent.atomic.AtomicBoolean(true)
            repository.afterGuardedRemoval = { entered.complete(Unit); release.await() }
            val owner = ChainedConfigurationCommandOwner(repository, loadEnabled = { false })
            owner.beginEditor("editor", 1); owner.stageEditorRemoval("editor", 0)
            val before = owner.currentDraft()
            val captured = revision.get()
            var result: ChainedConfigurationEditorCommitResult? = null
            val saving = launch { result = owner.commitEditor("editor", isCurrent = { granted.get() && revision.get() == captured }) }
            entered.await()
            assertNull("The real repository removal must precede retirement", repository.loadConfiguration())
            assertEquals(1, repository.removeAllCalls)
            granted.set(false); revision.incrementAndGet(); granted.set(true)
            release.complete(Unit); saving.join()
            val failed = result as ChainedConfigurationEditorCommitResult.Failed
            assertEquals("Native VPN mutation authority retired", failed.message)
            assertNull(failed.committedIdentity)
            assertEquals(1L, failed.removedGeneration)
            assertNull(repository.loadConfiguration()); assertEquals(1, repository.removeAllCalls)
            assertEquals(before, owner.currentDraft())
            assertTrue(repository.savedSelections.isEmpty()); assertTrue(repository.savedRotations.isEmpty())
            val freshRevision = revision.get()
            assertEquals(ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.NOT_CONFIGURED),
                owner.commitEditor("editor", isCurrent = { granted.get() && revision.get() == freshRevision }))
            assertEquals(1, repository.removeAllCalls)
        }
    }

    @Test fun `old post removal completion preserves a new graph owner and its fresh configuration and draft`() = runTest {
        val initial = stored(configuration(), 1)
        val successorRotation = rotation(configuration(endpointHost = "successor.example"))
        val successor = stored(successorRotation.configuration, 2)
        val repository = FakeChainedUpstreamRepository(initial, ChainedUpstreamSaveResult.Committed(successor.identity))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        repository.afterGuardedRemoval = { entered.complete(Unit); release.await() }
        val graph = java.util.concurrent.atomic.AtomicLong(11)
        val old = ChainedConfigurationCommandOwner(repository, loadEnabled = { false })
        old.beginEditor("old", 1); old.stageEditorRemoval("old", 0)
        var result: ChainedConfigurationEditorCommitResult? = null
        val saving = launch { result = old.commitEditor("old", isCurrent = { graph.get() == 11L }) }
        entered.await(); assertNull(repository.loadConfiguration())
        graph.set(12)
        val fresh = ChainedConfigurationCommandOwner(repository,
            onFullTunnelCommittedWhileCurrent = { _, guard -> guard() }, parseRotation = { successorRotation })
        assertTrue(fresh.save(fresh.importReview("successor"), ChainedUpstreamSaveExpectation.Absent,
            isCurrent = { graph.get() == 12L }) is ChainedConfigurationSaveResult.Committed)
        fresh.beginEditor("fresh", 2); fresh.stageEditorName("fresh", 0, "Successor draft")
        val draft = fresh.currentDraft()
        val storedSuccessor = repository.loadConfiguration()
        release.complete(Unit); saving.join()
        assertEquals(ChainedConfigurationEditorCommitResult.Failed("Native VPN mutation authority retired", removedGeneration = 1), result)
        assertEquals(storedSuccessor, repository.loadConfiguration())
        assertEquals(draft, fresh.currentDraft()); assertEquals("fresh", fresh.currentDraft()?.id)
        assertEquals(1, repository.removeAllCalls); assertEquals(1, repository.savedRotations.size)
        repository.nextSaveResult = ChainedUpstreamSaveResult.Committed(successor.identity.copy(generation = 3))
        assertEquals(ChainedConfigurationEditorCommitResult.Committed(successor.identity.copy(generation = 3)),
            fresh.commitEditor("fresh", isCurrent = { graph.get() == 12L }))
        assertEquals("Successor draft", repository.loadConfiguration()?.configuration?.displayName)
        assertEquals(3L, repository.loadConfiguration()?.identity?.generation)
        assertNull(fresh.currentDraft())
    }

    @Test fun `guarded OFF preference checks the supplied authority after its own suspension`() = runTest {
        val initial = stored(configuration(), 1)
        val repository = FakeChainedUpstreamRepository(initial)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        var writes = 0
        val preference = object : ChainedEnabledPreferencePort {
            override suspend fun setEnabled(enabled: Boolean): Boolean = error("Guarded commit must not use legacy preference")
            override suspend fun setEnabledWhileCurrent(enabled: Boolean, isCurrent: () -> Boolean): Boolean {
                entered.complete(Unit); release.await()
                if (!isCurrent()) return false
                writes++; return true
            }
        }
        val owner = ChainedConfigurationCommandOwner(repository, enabledPreference = preference)
        owner.beginEditor("editor", 1); owner.stageEditorRemoval("editor", 0)
        val before = owner.currentDraft()
        var result: ChainedConfigurationEditorCommitResult? = null
        val saving = launch { result = owner.commitEditor("editor", isCurrent = current::get) }
        entered.await(); current.set(false); release.complete(Unit); saving.join()
        assertEquals(ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION), result)
        assertEquals(0, writes); assertEquals(0, repository.removeAllCalls)
        assertEquals(initial, repository.loadConfiguration()); assertEquals(before, owner.currentDraft())
    }

    @Test fun `guarded writers fail closed instead of falling back to unsupported legacy publication`() = runTest {
        for (operation in listOf("pending", "replacement", "remove")) {
            val initial = stored(configuration(), 1).copy(pendingImportId = if (operation == "pending") "editor" else null)
            var legacyWrites = 0
            val repository = object : ChainedUpstreamConfigurationRepository {
                override val storedConfiguration: Flow<StoredChainedUpstreamConfiguration?> = MutableStateFlow(initial)
                override suspend fun loadConfiguration() = initial
                override suspend fun usableSecretsGeneration() = 1L
                override suspend fun loadSecrets() = ChainedUpstreamSecretMaterial.create(1, ByteArray(32) { 0x11 }, ByteArray(32) { 0x33 })
                override suspend fun save(rotation: ChainedUpstreamRotation, expectation: ChainedUpstreamSaveExpectation): ChainedUpstreamSaveResult {
                    legacyWrites++; return ChainedUpstreamSaveResult.Committed(initial.identity)
                }
                override suspend fun saveSelection(rows: List<ChainedUpstreamSelectionRow>, expectation: ChainedUpstreamSaveExpectation): ChainedUpstreamSaveResult {
                    legacyWrites++; return ChainedUpstreamSaveResult.Committed(initial.identity)
                }
                override suspend fun deleteSecrets() = Unit
                override suspend fun removeAll() { legacyWrites++ }
                override suspend fun removeIfGeneration(generation: Long): ChainedUpstreamPendingRemovalResult {
                    legacyWrites++; return ChainedUpstreamPendingRemovalResult.Removed
                }
            }
            val owner = ChainedConfigurationCommandOwner(repository, loadEnabled = { false }, parseRotation = { rotation() })
            owner.beginEditor("editor", 1)
            if (operation == "replacement") owner.stageEditorConfiguration("editor", 0, "replacement")
            if (operation == "remove") owner.stageEditorRemoval("editor", 0)
            val before = owner.currentDraft()
            assertEquals(ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION),
                owner.commitEditor("editor", isCurrent = { true }))
            assertEquals(0, legacyWrites); assertEquals(before, owner.currentDraft())
        }
    }

    @Test fun `guarded final write CAS cannot overwrite a newly committed rotation`() = runTest {
        val initial = stored(configuration(), 1)
        val successor = stored(configuration(endpointHost = "successor.example"), 9)
        val repository = FakeChainedUpstreamRepository(initial, ChainedUpstreamSaveResult.Committed(initial.identity.copy(generation = 2)))
        val owner = ChainedConfigurationCommandOwner(repository)
        owner.beginEditor("editor", 1); owner.stageEditorName("editor", 0, "Old name")
        val before = owner.currentDraft()
        repository.beforeGuardedWrite = { repository.setStored(successor) }
        assertEquals(ChainedConfigurationEditorCommitResult.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION),
            owner.commitEditor("editor", isCurrent = { true }))
        assertEquals(successor, repository.loadConfiguration()); assertEquals(before, owner.currentDraft())
        assertTrue(repository.savedSelections.isEmpty()); assertTrue(repository.savedRotations.isEmpty())
    }

    private class FakeChainedUpstreamRepository(
        initial: StoredChainedUpstreamConfiguration? = null,
        var nextSaveResult: ChainedUpstreamSaveResult =
            ChainedUpstreamSaveResult.Rejected(ChainedUpstreamSecretStaging.Failure.PUBLISH_FAILED),
    ) : ChainedUpstreamConfigurationRepository {
        private val storedFlow = MutableStateFlow(initial)
        val savedRotations = mutableListOf<ChainedUpstreamRotation>()
        val savedExpectations = mutableListOf<ChainedUpstreamSaveExpectation>()
        val savedSelections = mutableListOf<List<ChainedUpstreamSelectionRow>>()
        var secretMaterial: ChainedUpstreamSecretMaterial? = null
        var secretLoads = 0
        var removeAllCalls = 0
        var removeThrows: Exception? = null
        var saveThrows: Exception? = null
        var beforeSelectionSave: (suspend () -> Unit)? = null
        var beforeGuardedWrite: (suspend () -> Unit)? = null
        var afterGuardedRemoval: (suspend () -> Unit)? = null
        var beforeLoad: (suspend () -> Unit)? = null
        var beforeSecrets: (suspend () -> Unit)? = null
        var pendingRemovalFailure: ChainedUpstreamPendingRemovalResult? = null
        val pendingRemovals = mutableListOf<Pair<String, ChainedUpstreamIdentity>>()

        override val storedConfiguration: Flow<StoredChainedUpstreamConfiguration?> = storedFlow

        fun setStored(value: StoredChainedUpstreamConfiguration?) {
            storedFlow.value = value
        }

        override suspend fun loadConfiguration(): StoredChainedUpstreamConfiguration? {
            beforeLoad?.invoke()
            return storedFlow.value
        }

        override suspend fun save(
            rotation: ChainedUpstreamRotation,
            expectation: ChainedUpstreamSaveExpectation,
        ): ChainedUpstreamSaveResult {
            savedRotations += rotation
            savedExpectations += expectation
            saveThrows?.let { throw it }
            return nextSaveResult
        }

        override suspend fun usableSecretsGeneration(): Long? =
            storedFlow.value?.identity?.generation

        override suspend fun saveSelection(rows: List<ChainedUpstreamSelectionRow>, expectation: ChainedUpstreamSaveExpectation): ChainedUpstreamSaveResult {
            beforeSelectionSave?.invoke()
            savedSelections += rows.toList()
            savedRotations += rows.mapNotNull { it.replacement }
            savedExpectations += expectation
            saveThrows?.let { throw it }
            return nextSaveResult
        }

        override suspend fun saveWhileCurrent(
            rotation: ChainedUpstreamRotation, expectation: ChainedUpstreamSaveExpectation, isCurrent: () -> Boolean,
        ): ChainedUpstreamSaveResult {
            beforeGuardedWrite?.invoke()
            if (!isCurrent() || !matches(expectation)) return ChainedUpstreamSaveResult.Superseded
            val result = save(rotation, expectation)
            if (result is ChainedUpstreamSaveResult.Committed) {
                storedFlow.value = StoredChainedUpstreamConfiguration(rotation.configuration, result.identity, 17L)
            }
            return result
        }

        override suspend fun saveSelectionWhileCurrent(
            rows: List<ChainedUpstreamSelectionRow>, expectation: ChainedUpstreamSaveExpectation, isCurrent: () -> Boolean,
        ): ChainedUpstreamSaveResult {
            beforeGuardedWrite?.invoke()
            if (!isCurrent() || !matches(expectation)) return ChainedUpstreamSaveResult.Superseded
            // The legacy recorder owns its own beforeSelectionSave seam, so recheck authority
            // after that suspension before recording or publishing anything.
            beforeSelectionSave?.invoke()
            if (!isCurrent() || !matches(expectation)) return ChainedUpstreamSaveResult.Superseded
            savedSelections += rows.toList()
            savedRotations += rows.mapNotNull { it.replacement }
            savedExpectations += expectation
            saveThrows?.let { throw it }
            val result = nextSaveResult
            if (result is ChainedUpstreamSaveResult.Committed) {
                val configuration = rows.last().configuration.let { exit ->
                    if (rows.size == 2) exit.withEntryHop(rows.first().configuration) else exit
                }
                storedFlow.value = StoredChainedUpstreamConfiguration(configuration, result.identity, 17L)
            }
            return result
        }

        override suspend fun removeIfGenerationWhileCurrent(
            generation: Long, isCurrent: () -> Boolean,
        ): ChainedUpstreamPendingRemovalResult {
            beforeGuardedWrite?.invoke()
            if (!isCurrent()) return ChainedUpstreamPendingRemovalResult.Superseded
            val result = removeIfGeneration(generation)
            if (result is ChainedUpstreamPendingRemovalResult.Removed) afterGuardedRemoval?.invoke()
            return result
        }

        private fun matches(expectation: ChainedUpstreamSaveExpectation): Boolean = when (expectation) {
            ChainedUpstreamSaveExpectation.AnyCommitted -> storedFlow.value?.pendingImportId == null
            ChainedUpstreamSaveExpectation.Absent, is ChainedUpstreamSaveExpectation.AbsentPendingImport -> storedFlow.value == null
            is ChainedUpstreamSaveExpectation.Generation -> storedFlow.value?.identity?.generation == expectation.generation
        }

        override suspend fun loadSecrets(): ChainedUpstreamSecretMaterial? {
            beforeSecrets?.invoke()
            secretLoads += 1
            return secretMaterial
        }

        override suspend fun deleteSecrets() = Unit

        override suspend fun removeAll() {
            removeAllCalls += 1
            removeThrows?.let { throw it }
            storedFlow.value = null
        }

        override suspend fun removeIfGeneration(generation: Long): ChainedUpstreamPendingRemovalResult {
            if (storedFlow.value?.identity?.generation != generation) return ChainedUpstreamPendingRemovalResult.Superseded
            return try { removeAll(); ChainedUpstreamPendingRemovalResult.Removed }
            catch (_: Exception) { ChainedUpstreamPendingRemovalResult.Failed("Chained keys could not be removed") }
        }

        override suspend fun removePendingImport(
            editorId: String,
            identity: ChainedUpstreamIdentity,
        ): ChainedUpstreamPendingRemovalResult {
            pendingRemovals += editorId to identity
            pendingRemovalFailure?.let { return it }
            val current = storedFlow.value
            if (current == null || current.pendingImportId != editorId || current.identity != identity) {
                return ChainedUpstreamPendingRemovalResult.Superseded
            }
            storedFlow.value = null
            return ChainedUpstreamPendingRemovalResult.Removed
        }
    }

    private companion object {
        private val PRIVATE_KEY: String = base64Key(1)
        private val PEER_KEY: String = base64Key(33)
        private val PRESHARED_KEY: String = base64Key(65)

        private fun base64Key(seed: Int): String =
            Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + seed).toByte() })

        private fun configuration(
            allowedIPs: List<String> = listOf("0.0.0.0/0"),
            dnsAddresses: List<String> = listOf("10.64.0.1"),
            endpointHost: String = "vpn.example.com",
        ): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
            endpointHost = endpointHost,
            endpointPort = 51820,
            peerPublicKey = PEER_KEY,
            clientAddress = "10.7.0.2",
            allowedIPs = allowedIPs,
            persistentKeepaliveSeconds = 25,
            interfaceMTU = 1420,
            dnsAddresses = dnsAddresses,
        )

        private fun rotation(
            configuration: ChainedUpstreamConfiguration = configuration(),
            privateKey: String = PRIVATE_KEY,
            presharedKey: String? = PRESHARED_KEY,
        ): ChainedUpstreamRotation =
            ChainedUpstreamRotation.create(configuration, privateKey, presharedKey)

        private fun stored(
            configuration: ChainedUpstreamConfiguration,
            generation: Long,
        ): StoredChainedUpstreamConfiguration = StoredChainedUpstreamConfiguration(
            configuration = configuration,
            identity = ChainedUpstreamIdentity(
                generation = generation,
                fingerprint = canonicalChainedUpstreamConfigurationFingerprint(configuration),
            ),
            savedAtEpochMillis = 17L,
        )
    }
}
