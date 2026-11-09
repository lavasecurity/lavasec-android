package com.lavasec.app.reactnative

import com.lavasec.app.platform.VpnConfigurationEditorRequest
import com.lavasec.app.platform.VpnConfigurationPick
import com.lavasec.app.product.readVpnConfigurationDocument
import com.lavasec.app.product.StrictWireGuardConfigurationReader
import java.io.ByteArrayInputStream
import com.lavasec.app.platform.ChainedConfigurationCommandOwner
import com.lavasec.app.platform.ChainedUpstreamConfigurationRepository
import com.lavasec.app.platform.ChainedUpstreamIdentity
import com.lavasec.app.platform.ChainedUpstreamPendingRemovalResult
import com.lavasec.app.platform.ChainedUpstreamSaveExpectation
import com.lavasec.app.platform.ChainedUpstreamSaveResult
import com.lavasec.app.platform.ChainedUpstreamSecretMaterial
import com.lavasec.app.platform.StoredChainedUpstreamConfiguration
import com.lavasec.app.platform.VpnFirstConfigurationBegin
import com.lavasec.app.platform.VpnFirstConfigurationCoordinator
import com.lavasec.app.platform.canonicalChainedUpstreamConfigurationFingerprint
import com.lavasec.app.foundation.chained.ChainedUpstreamRotation
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeSharedVpnEditorOwnerTest {
    private val defaultHost = NativeVisitHost(Any(), "fixture-host") { true }
    @Test fun `authority changes without an editor do not publish redundant app frames`() = runTest {
        var publications = 0
        val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, { true }, { false },
            { VpnConfigurationPick.Cancelled }, { publications++ }, { 0L }, currentHost = { defaultHost }, coroutineScope = this)
        repeat(50) { owner.authorityChanged() }
        assertEquals(0, publications)
        owner.close()
    }

    @Test fun `immutable current draft snapshots use owner id and revision witness and retire replaced parent`() = runTest {
        val nativeOwner = Any(); var draftRevision = 2
        val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, { true }, { false },
            { VpnConfigurationPick.Cancelled }, {}, { 0L }, currentHost = { defaultHost },
            editorIdentity = { Triple(nativeOwner, "native-draft", draftRevision) }, coroutineScope = this)
        val pending = async { owner.edit(VpnConfigurationEditorRequest("Saved", true)) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        // The native command owner constructs a fresh immutable snapshot on each read.
        assertTrue(owner.canEditName(id)); assertTrue(owner.replace(id, configuration))
        ++draftRevision
        owner.authorityChanged()
        assertEquals(VpnConfigurationPick.Unavailable, pending.await())
        assertNull(owner.snapshot(id)); assertNull(owner.projection()); owner.close()
    }

    @Test fun `protected policy permits foreground typing but read loss conceals until fresh grant and native reveal`() = runTest {
        var readable = true; var interactive = true; var revision = 1L
        val owner = NativeSharedVpnEditorOwner({ 1L }, { readable }, { readable }, { true },
            { VpnConfigurationPick.Cancelled }, {}, { revision }, currentHost = { defaultHost }, coroutineScope = this,
            canInteract = { interactive })
        val pending = async { owner.pick() }
        runCurrent(); val id = checkNotNull(owner.currentId)
        assertTrue(owner.reveal(id)); assertTrue(owner.replace(id, configuration))
        assertEquals(configuration, owner.snapshot(id)?.text)
        interactive = false
        assertFalse(owner.replace(id, "hydrating callback")); assertFalse(owner.canEditName(id))
        interactive = true; readable = false; ++revision
        owner.authorityChanged()
        assertFalse(checkNotNull(owner.snapshot(id)).revealed)
        assertNull(owner.snapshot(id)?.text); assertFalse(owner.replace(id, "covered callback"))
        readable = true
        owner.execute(input("vpnEditor.enter", id))
        assertTrue(owner.canEditName(id)); assertNull(owner.snapshot(id)?.text)
        assertTrue(owner.reveal(id)); assertEquals(configuration, owner.snapshot(id)?.text)
        assertTrue(owner.replace(id, configuration + "\n# current authorized edit"))
        assertFalse(owner.projection().toString().contains("PrivateKey"))
        owner.execute(input("foreground.dismiss", id).put("discardConfirmed", true))
        assertEquals(VpnConfigurationPick.Cancelled, pending.await()); owner.close()
    }

    private fun input(type: String, id: String) = JSONObject().put("type", type).put("id", id)
    private val configuration = """
        [Interface]
        PrivateKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { 0x11 })}
        Address = 10.7.0.2/32
        [Peer]
        PublicKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { 0x22 })}
        AllowedIPs = 0.0.0.0/0
        Endpoint = vpn.example.com:51820
    """.trimIndent()

    @Test fun `new shared sheet keeps the parent pending and validates native content before save`() = runTest {
        val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, { true }, { false },
            { VpnConfigurationPick.Unavailable }, {}, { 0L }, currentHost = { defaultHost }, coroutineScope = this)
        val pending = async { owner.pick() }
        runCurrent(); val id = checkNotNull(owner.currentId)
        assertFalse(pending.isCompleted)
        assertEquals("vpnConfiguration", owner.projection()?.getString("kind"))
        assertEquals("", owner.snapshot(id)?.text)
        assertTrue(owner.replace(id, "PrivateKey = invalid-secret-fixture"))
        owner.execute(input("vpnEditor.save", id))
        assertFalse(pending.isCompleted)
        assertTrue(checkNotNull(owner.projection()).getJSONObject("vpnEditor").getString("error").isNotEmpty())
        val publicFrame = owner.projection().toString()
        assertFalse(publicFrame.contains("PrivateKey")); assertFalse(publicFrame.contains("invalid-secret-fixture"))
        assertTrue(owner.replace(id, configuration))
        owner.execute(input("vpnEditor.name", id).put("name", "  Office  "))
        owner.execute(input("vpnEditor.save", id))
        val saved = pending.await() as VpnConfigurationPick.Edited
        assertEquals("Office", saved.name); assertEquals(configuration, saved.rawConfiguration)
        assertPendingSelection(owner, id)
        owner.finishPick(saved)
        assertNull(owner.snapshot(id)); assertNull(owner.projection()); assertNull(owner.currentId)
        owner.close()
    }

    @Test fun `existing row name edit does not hydrate or replace its saved credentials`() = runTest {
        val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, { true }, { false },
            { error("No file should be requested") }, {}, { 0L }, currentHost = { defaultHost }, coroutineScope = this)
        val pending = async { owner.edit(VpnConfigurationEditorRequest("Saved", true)) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        assertEquals("", owner.snapshot(id)?.text)
        assertFalse(checkNotNull(owner.projection()).getJSONObject("vpnEditor").getBoolean("hasContent"))
        assertFalse(checkNotNull(owner.projection()).getJSONObject("vpnEditor").getBoolean("canSave"))
        owner.execute(input("vpnEditor.name", id).put("name", "Renamed"))
        owner.execute(input("vpnEditor.save", id))
        val saved = pending.await()
        assertEquals(VpnConfigurationPick.Edited("Renamed", null), saved)
        assertPendingSelection(owner, id)
        owner.finishPick(saved)
        assertNull(owner.currentId)
        owner.close()
    }

    @Test fun `native dirty dismissal probe preserves content until explicit discard and leaves saved row intact`() = runTest {
        val owner = NativeSharedVpnEditorOwner({ 2L }, { true }, { true }, { false },
            { VpnConfigurationPick.Cancelled }, {}, { 0L }, currentHost = { defaultHost }, coroutineScope = this)
        val pending = async { owner.edit(VpnConfigurationEditorRequest("Saved", true)) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        var observed = 0; val stop = owner.observe(id) { ++observed }
        assertTrue(owner.replace(id, configuration))
        assertEquals(false, owner.execute(input("foreground.dismiss", id)).value)
        assertFalse(pending.isCompleted); assertEquals(configuration, owner.snapshot(id)?.text)
        owner.execute(input("foreground.dismiss", id).put("discardConfirmed", true))
        assertEquals(VpnConfigurationPick.Cancelled, pending.await())
        assertNull(owner.snapshot(id)); assertTrue(observed >= 2)
        stop(); owner.close()
    }

    @Test fun `read revision privacy and exact editor baseline gate native paste and name at every write`() = runTest {
        var revision = 3L; var readable = true; var covered = false; var baseline = Any(); var generation = 4L
        val owner = NativeSharedVpnEditorOwner({ generation }, { readable }, { readable }, { covered },
            { VpnConfigurationPick.Cancelled }, {}, { revision }, { defaultHost }, { baseline }, this)
        val pending = async { owner.pick() }
        runCurrent(); val id = checkNotNull(owner.currentId)
        assertTrue(owner.replace(id, configuration))
        ++revision
        assertFalse(owner.replace(id, "late old revision paste")); assertFalse(owner.canEditName(id))
        assertEquals("vpn-editor-locked", owner.execute(input("vpnEditor.name", id).put("name", "stale")).reason)
        owner.execute(input("vpnEditor.enter", id))
        assertTrue(owner.canEditName(id))
        readable = false
        assertFalse(owner.replace(id, "background paste"))
        assertTrue(checkNotNull(owner.snapshot(id)).retainAcceptedDisplay)
        assertNull(owner.snapshot(id)?.text)
        covered = true
        assertFalse(checkNotNull(owner.snapshot(id)).revealed)
        assertFalse(checkNotNull(owner.snapshot(id)).retainAcceptedDisplay)
        readable = true; covered = false
        baseline = Any()
        assertFalse(owner.replace(id, "replacement draft paste"))
        assertFalse(owner.canEditName(id)); assertNull(owner.projection())
        ++generation
        assertEquals("vpn-editor-closed", owner.execute(input("vpnEditor.save", id)).reason)
        assertEquals(VpnConfigurationPick.Unavailable, pending.await()); owner.close()
    }

    @Test fun `document return reacquires current grant and conceals imported native text until explicit reveal`() = runTest {
        val picked = CompletableDeferred<VpnConfigurationPick>()
        var readable = true; var revision = 1L; var authorizations = 0
        val owner = NativeSharedVpnEditorOwner({ 1L }, { readable }, {
            ++authorizations; readable = true; ++revision; true
        }, { false }, { picked.await() }, {}, { revision }, currentHost = { defaultHost }, coroutineScope = this)
        val pending = async { owner.pick() }
        runCurrent(); val id = checkNotNull(owner.currentId)
        owner.execute(input("vpnEditor.file", id))
        runCurrent()
        assertTrue(checkNotNull(owner.projection()).getJSONObject("vpnEditor").getBoolean("reading"))
        assertFalse(owner.replace(id, "cannot paste during picker"))
        readable = false; ++revision
        picked.complete(VpnConfigurationPick.Read(configuration))
        runCurrent()
        assertEquals(1, authorizations)
        val state = checkNotNull(owner.projection()).getJSONObject("vpnEditor")
        assertTrue(state.getBoolean("hasContent")); assertTrue(state.getBoolean("concealed"))
        assertFalse(state.getBoolean("reading")); assertTrue(state.getBoolean("canSave"))
        assertNull(owner.snapshot(id)?.text); assertFalse(owner.replace(id, "concealed paste"))
        assertTrue(owner.reveal(id)); assertEquals(configuration, owner.snapshot(id)?.text)
        assertFalse(owner.projection().toString().contains("PrivateKey"))
        owner.execute(input("vpnEditor.save", id)); assertEquals(configuration, (pending.await() as VpnConfigurationPick.Edited).rawConfiguration)
        owner.close()
    }

    @Test fun `document cancellation or denied fresh grant retains prior supplied content without publishing new secrets`() = runTest {
        val picked = CompletableDeferred<VpnConfigurationPick>()
        var readable = true; var revision = 1L
        val owner = NativeSharedVpnEditorOwner({ 1L }, { readable }, { false }, { false },
            { picked.await() }, {}, { revision }, currentHost = { defaultHost }, coroutineScope = this)
        val pending = async { owner.pick() }
        runCurrent(); val id = checkNotNull(owner.currentId)
        owner.replace(id, configuration)
        owner.execute(input("vpnEditor.file", id)); runCurrent()
        readable = false; ++revision
        picked.complete(VpnConfigurationPick.Read("new secret denied by fresh authorization")); runCurrent()
        assertFalse(pending.isCompleted)
        readable = true
        assertNull(owner.snapshot(id)?.text)
        assertFalse(owner.canEditName(id))
        assertFalse(owner.projection().toString().contains("new secret"))
        owner.close(); assertEquals(VpnConfigurationPick.Cancelled, pending.await())
    }

    @Test fun `retired picker completion cannot overwrite a successor visit even when cancellation is ignored`() = runTest {
        val returned = CompletableDeferred<VpnConfigurationPick>()
        var generation = 1L
        val owner = NativeSharedVpnEditorOwner({ generation }, { true }, { true }, { false },
            { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { returned.await() } },
            {}, { 0L }, currentHost = { defaultHost }, coroutineScope = this)
        val first = async { owner.pick() }; runCurrent(); val oldId = checkNotNull(owner.currentId)
        owner.execute(input("vpnEditor.file", oldId)); runCurrent()
        ++generation
        val next = async { owner.edit(VpnConfigurationEditorRequest("Successor", true)) }
        runCurrent(); val nextId = checkNotNull(owner.currentId)
        assertNotEquals(oldId, nextId); assertEquals(VpnConfigurationPick.Unavailable, first.await())
        returned.complete(VpnConfigurationPick.Read(configuration)); runCurrent()
        assertEquals("", owner.snapshot(nextId)?.text)
        assertEquals("Successor", checkNotNull(owner.projection()).getJSONObject("vpnEditor").getString("name"))
        assertFalse(next.isCompleted)
        assertFalse(owner.replace(oldId, "old owner paste"))
        owner.execute(input("foreground.dismiss", nextId)); assertEquals(VpnConfigurationPick.Cancelled, next.await())
        owner.close()
    }

    private class TestHost(val id: String) {
        val owner = Any()
        var alive = true
        val witness = NativeVisitHost(owner, id) { alive }
    }

    @Test fun `a live original host may pause absent but cannot read or edit before returning`() = runTest {
        val original = TestHost("original")
        var current: NativeVisitHost? = original.witness
        var readable = true; var revision = 1L
        val owner = NativeSharedVpnEditorOwner({ 1L }, { readable }, { readable }, { true },
            { error("No picker while the original host is absent") }, {}, { revision },
            currentHost = { current }, coroutineScope = this)
        val pending = async { owner.pick() }; runCurrent(); val id = checkNotNull(owner.currentId)
        assertTrue(owner.replace(id, configuration))
        current = null; readable = false; ++revision
        owner.authorityChanged()
        assertEquals(id, owner.currentId); assertFalse(pending.isCompleted)
        assertNull(owner.projection()); assertNull(owner.snapshot(id))
        assertFalse(owner.replace(id, "background stale input")); assertFalse(owner.reveal(id))
        assertFalse(owner.canEditName(id)); assertFalse(owner.requestDismiss())
        assertEquals("vpn-editor-locked", owner.execute(input("vpnEditor.enter", id)).reason)
        current = NativeVisitHost(original.owner, original.id) { original.alive }; readable = true
        assertEquals("completed", owner.execute(input("vpnEditor.enter", id)).status)
        assertTrue(owner.canEditName(id)); assertNull(owner.snapshot(id)?.text)
        assertTrue(owner.reveal(id)); assertEquals(configuration, owner.snapshot(id)?.text)
        owner.execute(input("vpnEditor.save", id))
        assertEquals(configuration, (pending.await() as VpnConfigurationPick.Edited).rawConfiguration)
        owner.close()
    }

    @Test fun `original host death retires the pending parent and clears the retained native buffer`() = runTest {
        val original = TestHost("original")
        var current: NativeVisitHost? = original.witness
        val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, { true }, { false },
            { VpnConfigurationPick.Cancelled }, {}, { 0L }, currentHost = { current }, coroutineScope = this)
        val pending = async { owner.pick() }; runCurrent(); val id = checkNotNull(owner.currentId)
        assertTrue(owner.replace(id, configuration)); val raw = capturedRaw(owner)
        original.alive = false; current = null
        owner.authorityChanged()
        assertEquals(VpnConfigurationPick.Unavailable, pending.await())
        assertEquals("", raw()); assertNull(owner.currentId); assertNull(owner.snapshot(id)); assertNull(owner.projection())
        assertEquals(VpnConfigurationPick.Unavailable, owner.pick())
        owner.close()
    }

    /** Holds the retired Visit itself to prove confidential storage is actually cleared. */
    private fun capturedRaw(owner: NativeSharedVpnEditorOwner): () -> String {
        val owned = checkNotNull(owner.javaClass.getDeclaredField("visit").apply { isAccessible = true }.get(owner))
        val raw = owned.javaClass.getDeclaredField("raw").apply { isAccessible = true }
        return { raw.get(owned) as String }
    }

    @Test fun `every native read and write retires a different current host before successor adoption`() = runTest {
        listOf("projection", "snapshot", "paste", "reveal", "name", "name-command", "dismiss", "enter", "file", "save").forEach { action ->
            val original = TestHost("original-$action"); val successor = TestHost("successor-$action")
            var current: NativeVisitHost? = original.witness
            var authorizations = 0; var pickers = 0
            val owner = NativeSharedVpnEditorOwner({ 7L }, { true }, { ++authorizations; true }, { false },
                { ++pickers; VpnConfigurationPick.Cancelled }, {}, { 0L }, currentHost = { current }, coroutineScope = this)
            val first = async { owner.pick() }; runCurrent(); val oldId = checkNotNull(owner.currentId)
            assertTrue(owner.replace(oldId, configuration)); val raw = capturedRaw(owner)
            current = successor.witness
            when (action) {
                "projection" -> assertNull(owner.projection())
                "snapshot" -> assertNull(owner.snapshot(oldId))
                "paste" -> assertFalse(owner.replace(oldId, "stale native paste"))
                "reveal" -> assertFalse(owner.reveal(oldId))
                "name" -> assertFalse(owner.canEditName(oldId))
                "name-command" -> assertEquals("vpn-editor-stale", owner.execute(input("vpnEditor.name", oldId).put("name", "Stale")).reason)
                "dismiss" -> assertFalse(owner.requestDismiss())
                else -> assertEquals("vpn-editor-stale", owner.execute(input("vpnEditor.$action", oldId)).reason)
            }
            assertEquals(action, VpnConfigurationPick.Unavailable, first.await())
            assertEquals(action, "", raw()); assertNull(owner.currentId)
            assertEquals(0, authorizations); assertEquals(0, pickers)
            val next = async { owner.edit(VpnConfigurationEditorRequest("Fresh", true)) }
            runCurrent(); val nextId = checkNotNull(owner.currentId)
            assertNotEquals(oldId, nextId); assertEquals("", owner.snapshot(nextId)?.text)
            owner.retireHost(original.owner, original.id)
            assertEquals(nextId, owner.currentId); assertFalse(next.isCompleted)
            assertFalse(owner.replace(oldId, "late old paste"))
            assertEquals("vpn-editor-stale", owner.execute(input("vpnEditor.save", oldId)).reason)
            assertTrue(owner.replace(nextId, configuration))
            owner.execute(input("vpnEditor.save", nextId))
            assertEquals(VpnConfigurationPick.Edited("Fresh", configuration), next.await())
            owner.close()
        }
    }

    @Test fun `starting a successor on the same graph retires the old visit and late host destruction cannot cancel it`() = runTest {
        val original = TestHost("original"); val successor = TestHost("successor")
        var current: NativeVisitHost? = original.witness
        val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, { true }, { false },
            { VpnConfigurationPick.Cancelled }, {}, { 0L }, currentHost = { current }, coroutineScope = this)
        val first = async { owner.pick() }; runCurrent(); val oldId = checkNotNull(owner.currentId)
        owner.replace(oldId, configuration); val raw = capturedRaw(owner)
        current = successor.witness
        val next = async { owner.edit(VpnConfigurationEditorRequest("Successor", true)) }
        runCurrent(); val nextId = checkNotNull(owner.currentId)
        assertEquals(VpnConfigurationPick.Unavailable, first.await()); assertEquals("", raw())
        assertNotEquals(oldId, nextId); assertEquals("", owner.snapshot(nextId)?.text)
        original.alive = false; owner.retireHost(original.owner, original.id)
        owner.retireHost(successor.owner, "wrong-host-id")
        owner.retireHost(Any(), successor.id)
        assertEquals(nextId, owner.currentId); assertFalse(next.isCompleted)
        assertTrue(owner.replace(nextId, configuration))
        owner.execute(input("vpnEditor.save", nextId))
        assertEquals(VpnConfigurationPick.Edited("Successor", configuration), next.await())
        owner.close()
    }

    @Test fun `late picker result from a destroyed host cannot authorize or overwrite a new host visit`() = runTest {
        val original = TestHost("original"); val successor = TestHost("successor")
        var current: NativeVisitHost? = original.witness
        val returned = CompletableDeferred<VpnConfigurationPick>()
        var authorizations = 0
        val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, { ++authorizations; true }, { false }, {
            // Model a platform callback which returns its payload even after owner cancellation.
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { returned.await() } }
            catch (_: kotlinx.coroutines.CancellationException) { returned.getCompleted() }
        }, {}, { 0L }, currentHost = { current }, coroutineScope = this)
        val first = async { owner.pick() }; runCurrent(); val oldId = checkNotNull(owner.currentId)
        owner.replace(oldId, configuration); val raw = capturedRaw(owner)
        owner.execute(input("vpnEditor.file", oldId)); runCurrent()
        assertTrue(checkNotNull(owner.projection()).getJSONObject("vpnEditor").getBoolean("reading"))
        val retained = checkNotNull(owner.javaClass.getDeclaredField("visit").apply { isAccessible = true }.get(owner))
        val jobField = retained.javaClass.getDeclaredField("importJob").apply { isAccessible = true }
        val tokenField = retained.javaClass.getDeclaredField("importToken").apply { isAccessible = true }
        val importJob = checkNotNull(jobField.get(retained) as? kotlinx.coroutines.Job)
        val importToken = tokenField.get(retained)
        assertTrue(importJob.isActive)
        original.alive = false; current = successor.witness
        owner.retireHost(original.owner, original.id)
        assertEquals(VpnConfigurationPick.Unavailable, first.await()); assertEquals("", raw())
        assertTrue(importJob.isCancelled); assertNull(jobField.get(retained))
        assertNotEquals(importToken, tokenField.get(retained))
        val next = async { owner.edit(VpnConfigurationEditorRequest("Successor", true)) }
        runCurrent(); val nextId = checkNotNull(owner.currentId)
        val fresh = configuration + "\n# fresh successor content"
        assertTrue(owner.replace(nextId, fresh))
        returned.complete(VpnConfigurationPick.Read(configuration + "\n# late original content")); runCurrent()
        assertEquals(0, authorizations); assertEquals(fresh, owner.snapshot(nextId)?.text)
        val state = checkNotNull(owner.projection()).getJSONObject("vpnEditor")
        assertFalse(state.getBoolean("reading")); assertEquals("", state.getString("error"))
        assertFalse(next.isCompleted)
        owner.execute(input("vpnEditor.save", nextId))
        assertEquals(VpnConfigurationPick.Edited("Successor", fresh), next.await())
        owner.close()
    }

    @Test fun `late authorization success cannot reopen a retired host or change its successor`() = runTest {
        val original = TestHost("original"); val successor = TestHost("successor")
        var current: NativeVisitHost? = original.witness
        val authorized = CompletableDeferred<Boolean>()
        val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { authorized.await() }
        }, { false }, { VpnConfigurationPick.Cancelled }, {}, { 0L }, currentHost = { current }, coroutineScope = this)
        val first = async { owner.pick() }; runCurrent(); val oldId = checkNotNull(owner.currentId)
        assertTrue(owner.replace(oldId, configuration)); val raw = capturedRaw(owner)
        val entering = async { owner.execute(input("vpnEditor.enter", oldId)) }; runCurrent()
        assertFalse(entering.isCompleted)
        original.alive = false; current = successor.witness; owner.authorityChanged()
        assertEquals(VpnConfigurationPick.Unavailable, first.await()); assertEquals("", raw())
        val next = async { owner.edit(VpnConfigurationEditorRequest("Successor", true)) }
        runCurrent(); val nextId = checkNotNull(owner.currentId)
        authorized.complete(true); runCurrent()
        assertEquals("vpn-editor-locked", entering.await().reason)
        assertEquals(nextId, owner.currentId); assertEquals("", owner.snapshot(nextId)?.text)
        assertFalse(next.isCompleted); assertTrue(owner.replace(nextId, configuration))
        owner.execute(input("vpnEditor.save", nextId))
        assertEquals(VpnConfigurationPick.Edited("Successor", configuration), next.await())
        owner.close()
    }

    @Test fun `completed save cannot return private data when authority changes before parent resume`() = runTest {
        listOf("host", "graph", "editor", "death").forEach { change ->
            val original = TestHost("original-$change"); val successor = TestHost("successor-$change")
            var current: NativeVisitHost? = original.witness
            var generation = 1L; var editor: Any = Any()
            val owner = NativeSharedVpnEditorOwner({ generation }, { true }, { true }, { false },
                { VpnConfigurationPick.Cancelled }, {}, { 0L }, currentHost = { current },
                editorIdentity = { editor }, coroutineScope = this)
            val pending = async { owner.pick() }; runCurrent(); val id = checkNotNull(owner.currentId)
            assertTrue(owner.replace(id, configuration))
            assertEquals("completed", owner.execute(input("vpnEditor.save", id)).status)
            assertPendingSelection(owner, id)
            assertFalse("The waiting parent has not run its continuation yet", pending.isCompleted)
            when (change) {
                "host" -> current = successor.witness
                "graph" -> ++generation
                "editor" -> editor = Any()
                "death" -> original.alive = false
            }
            runCurrent()
            assertEquals(change, VpnConfigurationPick.Unavailable, pending.await())
            assertNull(owner.currentId)
            owner.close()
        }
    }


    private fun capturedPendingPick(owner: NativeSharedVpnEditorOwner): () -> Any? {
        val owned = checkNotNull(owner.javaClass.getDeclaredField("visit").apply { isAccessible = true }.get(owner))
        val selection = owned.javaClass.getDeclaredField("pendingPick").apply { isAccessible = true }
        return { selection.get(owned) }
    }

    private fun assertPendingSelection(owner: NativeSharedVpnEditorOwner, id: String) {
        assertEquals(id, owner.currentId)
        val state = checkNotNull(owner.projection()).getJSONObject("vpnEditor")
        assertFalse(state.getBoolean("canEdit")); assertFalse(state.getBoolean("canSave"))
        assertFalse(state.getBoolean("hasContent")); assertFalse(state.getBoolean("reading"))
        assertFalse(owner.canEditName(id)); assertFalse(owner.reveal(id))
        assertFalse(owner.replace(id, "late native field callback"))
        val field = checkNotNull(owner.snapshot(id))
        assertFalse(field.authorized); assertFalse(field.revealed); assertNull(field.text)
        assertFalse(field.retainAcceptedDisplay)
        assertEquals("", capturedRaw(owner)())
        assertFalse(owner.projection().toString().contains("PrivateKey"))
    }

    @Test fun `selected sheet stays readonly across its own parent changes and closes only for its exact native ack`() = runTest {
        var identity: Any = Any(); var publications = 0; var authorizations = 0
        val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, { ++authorizations; true }, { true },
            { error("No picker while publishing") }, { ++publications }, { 0L }, currentHost = { defaultHost },
            editorIdentity = { identity }, coroutineScope = this)
        val parent = async { owner.pick() }; runCurrent(); val id = checkNotNull(owner.currentId)
        assertTrue(owner.replace(id, configuration))
        owner.execute(input("vpnEditor.save", id))
        val selected = parent.await() as VpnConfigurationPick.Edited
        val selection = capturedPendingPick(owner)
        assertSame(selected, selection())
        assertPendingSelection(owner, id)
        for (type in listOf("vpnEditor.name", "vpnEditor.file", "vpnEditor.save", "foreground.dismiss")) {
            assertEquals("vpn-editor-locked", owner.execute(input(type, id).put("name", "Late").put("discardConfirmed", true)).reason)
        }
        assertFalse(owner.requestDismiss())
        // The native coordinator creates/revises the parent draft after receiving the selection.
        identity = Any(); owner.authorityChanged()
        assertPendingSelection(owner, id)
        assertEquals(0, authorizations)
        owner.finishPick(selected.copy())
        assertPendingSelection(owner, id)
        val before = publications
        owner.finishPick(selected)
        assertNull(owner.currentId); assertNull(owner.projection()); assertNull(owner.snapshot(id))
        assertNull(selection())
        assertEquals(before + 1, publications)
        owner.finishPick(selected)
        assertEquals("One actual close notification", before + 1, publications)
        owner.close()
    }

    @Test fun `late selected ack cannot close an equal valued successor and read free ack closes an absent live host`() = runTest {
        val original = TestHost("original"); val successor = TestHost("successor")
        var current: NativeVisitHost? = original.witness
        val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, { true }, { true },
            { VpnConfigurationPick.Cancelled }, {}, { 0L }, currentHost = { current }, coroutineScope = this)
        val first = async { owner.pick() }; runCurrent(); val a = checkNotNull(owner.currentId)
        owner.replace(a, configuration); owner.execute(input("vpnEditor.save", a))
        val selectedA = first.await() as VpnConfigurationPick.Edited
        val retiredSelection = capturedPendingPick(owner)
        current = successor.witness; owner.authorityChanged()
        assertNull(owner.currentId); assertNull(retiredSelection())
        val next = async { owner.pick() }; runCurrent(); val b = checkNotNull(owner.currentId)
        owner.replace(b, configuration); owner.execute(input("vpnEditor.save", b))
        val selectedB = next.await() as VpnConfigurationPick.Edited
        assertEquals(selectedA, selectedB); assertNotSame(selectedA, selectedB)
        owner.finishPick(selectedA); assertPendingSelection(owner, b)
        current = null; owner.authorityChanged()
        assertEquals(b, owner.currentId); assertNull(owner.projection()); assertNull(owner.snapshot(b))
        owner.finishPick(selectedB)
        assertNull(owner.currentId)
        current = successor.witness
        assertNull(owner.projection()); owner.close()
    }

    @Test fun `parent cancellation after native selection clears the pending slot without exposing its cleared buffer`() = runTest {
        val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, { true }, { true },
            { VpnConfigurationPick.Cancelled }, {}, { 0L }, currentHost = { defaultHost }, coroutineScope = this)
        val parent = async { owner.pick() }; runCurrent(); val id = checkNotNull(owner.currentId)
        owner.replace(id, configuration); val raw = capturedRaw(owner)
        owner.execute(input("vpnEditor.save", id))
        assertPendingSelection(owner, id)
        parent.cancelAndJoin()
        assertEquals("", raw()); assertNull(owner.currentId); assertNull(owner.projection())
        owner.close()
    }

    @Test fun `protected selected sheet retains its grant through real coordinator suspended repository publication`() = runTest {
        val repository = PendingSelectionRepository()
        val commands = ChainedConfigurationCommandOwner(repository)
        val editor = NativeSharedVpnEditorOwner({ 1L }, { true }, { true }, { true },
            { error("Shared native input supplies the configuration") }, {}, { 0L }, currentHost = { defaultHost },
            editorIdentity = { commands.currentDraft()?.let { Triple(commands, it.id, it.revision) } ?: commands },
            coroutineScope = this)
        val coordinator = VpnFirstConfigurationCoordinator(commands, editor,
            revalidate = { editor.currentId != null }, isCurrent = { defaultHost.matches(defaultHost) })
        val importing = async { coordinator.beginOrImport("native-parent", 0L) }
        runCurrent(); val id = checkNotNull(editor.currentId)
        assertTrue(editor.replace(id, configuration))
        editor.execute(input("vpnEditor.name", id).put("name", "Office"))
        editor.execute(input("vpnEditor.save", id))
        runCurrent()
        assertFalse("Save must not revoke admission before native publication", importing.isCompleted)
        assertTrue("The actual repository write must be reached", repository.writing.isCompleted)
        assertNull(repository.loadConfiguration())
        assertPendingSelection(editor, id)
        assertTrue("Original native read admission remains owned until publication", editor.currentId != null)
        repository.release.complete(Unit)
        val outcome = importing.await()
        assertTrue(outcome is VpnFirstConfigurationBegin.Draft)
        assertTrue((outcome as VpnFirstConfigurationBegin.Draft).imported)
        assertEquals("Office", outcome.draft.rows.single().name)
        assertEquals("native-parent", repository.loadConfiguration()?.pendingImportId)
        assertEquals(outcome.draft, commands.currentDraft())
        assertNull(editor.currentId); assertNull(editor.projection()); assertNull(editor.snapshot(id))
        editor.close()
    }

    private class PendingSelectionRepository : ChainedUpstreamConfigurationRepository {
        private val stored = MutableStateFlow<StoredChainedUpstreamConfiguration?>(null)
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override val storedConfiguration: Flow<StoredChainedUpstreamConfiguration?> = stored
        override suspend fun loadConfiguration(): StoredChainedUpstreamConfiguration? = stored.value
        override suspend fun usableSecretsGeneration(): Long? = stored.value?.identity?.generation
        override suspend fun loadSecrets(): ChainedUpstreamSecretMaterial? = null
        override suspend fun deleteSecrets() = Unit
        override suspend fun removeAll() { stored.value = null }
        override suspend fun save(rotation: ChainedUpstreamRotation, expectation: ChainedUpstreamSaveExpectation): ChainedUpstreamSaveResult =
            saveWhileCurrent(rotation, expectation) { true }
        override suspend fun saveWhileCurrent(rotation: ChainedUpstreamRotation, expectation: ChainedUpstreamSaveExpectation,
            isCurrent: () -> Boolean): ChainedUpstreamSaveResult {
            writing.complete(Unit); release.await()
            if (!isCurrent()) return ChainedUpstreamSaveResult.Superseded
            val identity = ChainedUpstreamIdentity(1L, canonicalChainedUpstreamConfigurationFingerprint(rotation.configuration))
            stored.value = StoredChainedUpstreamConfiguration(rotation.configuration, identity, 1L,
                (expectation as? ChainedUpstreamSaveExpectation.AbsentPendingImport)?.editorId)
            return ChainedUpstreamSaveResult.Committed(identity)
        }
        override suspend fun removePendingImport(editorId: String, identity: ChainedUpstreamIdentity): ChainedUpstreamPendingRemovalResult {
            val current = stored.value
            if (current == null || current.pendingImportId != editorId || current.identity != identity) return ChainedUpstreamPendingRemovalResult.Superseded
            stored.value = null
            return ChainedUpstreamPendingRemovalResult.Removed
        }
    }


    @Test fun `actual document read failures show distinct messages without replacing supplied content or completing parent`() = runTest {
        for ((bytes, reason) in listOf(
            ByteArray(StrictWireGuardConfigurationReader.MAXIMUM_BYTES + 1) { '#'.code.toByte() } to VpnConfigurationPick.ImportFailureReason.TOO_LARGE,
            byteArrayOf(0xc3.toByte(), 0x28) to VpnConfigurationPick.ImportFailureReason.NOT_TEXT,
        )) {
            var authorizations = 0
            val owner = NativeSharedVpnEditorOwner({ 1L }, { true }, { ++authorizations; true }, { false },
                { readVpnConfigurationDocument { ByteArrayInputStream(bytes) } }, {}, { 0L }, currentHost = { defaultHost }, coroutineScope = this,
                importFailureMessage = { if (it == VpnConfigurationPick.ImportFailureReason.TOO_LARGE) "actual-too-large" else "actual-not-text" })
            try {
                val pending = async { owner.pick() }; runCurrent(); val id = checkNotNull(owner.currentId)
                assertTrue(owner.replace(id, configuration))
                assertEquals("completed", owner.execute(input("vpnEditor.file", id)).status); runCurrent()
                val projection = checkNotNull(owner.projection()).getJSONObject("vpnEditor")
                assertEquals(if (reason == VpnConfigurationPick.ImportFailureReason.TOO_LARGE) "actual-too-large" else "actual-not-text", projection.getString("error"))
                assertFalse(projection.getBoolean("reading")); assertTrue(projection.getBoolean("hasContent"))
                assertEquals(configuration, owner.snapshot(id)?.text); assertFalse(pending.isCompleted)
                assertEquals(1, authorizations); assertFalse(owner.projection().toString().contains("PrivateKey"))
                owner.execute(input("foreground.dismiss", id).put("discardConfirmed", true))
                assertEquals(VpnConfigurationPick.Cancelled, pending.await())
            } finally { owner.close() }
        }
    }

    @Test fun `typed document failure cannot publish an error after denied fresh authority`() = runTest {
        val returned = CompletableDeferred<VpnConfigurationPick>(); var readable = true; var revision = 1L
        val owner = NativeSharedVpnEditorOwner({ 1L }, { readable }, { false }, { true },
            { returned.await() }, {}, { revision }, currentHost = { defaultHost }, coroutineScope = this,
            importFailureMessage = { "must-not-be-shown" })
        try {
            val pending = async { owner.pick() }; runCurrent(); val id = checkNotNull(owner.currentId)
            assertTrue(owner.replace(id, configuration)); owner.execute(input("vpnEditor.file", id)); runCurrent()
            readable = false; ++revision
            returned.complete(readVpnConfigurationDocument { ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)) }); runCurrent()
            assertFalse(pending.isCompleted); assertNull(owner.snapshot(id)?.text); assertNull(owner.projection())
            readable = true
            assertEquals("", checkNotNull(owner.projection()).getJSONObject("vpnEditor").getString("error"))
            assertFalse(owner.canEditName(id)); assertNull(owner.snapshot(id)?.text)
            assertFalse(owner.projection().toString().contains("must-not-be-shown"))
            owner.close(); assertEquals(VpnConfigurationPick.Cancelled, pending.await())
        } finally { owner.close() }
    }

    @Test fun `late typed document failure cannot change fresh host or graph successor`() = runTest {
        for (change in listOf("host", "graph")) {
            val original = TestHost("file-original"); val successor = TestHost("file-successor")
            var current: NativeVisitHost? = original.witness; var generation = 1L
            val returned = CompletableDeferred<VpnConfigurationPick>()
            val owner = NativeSharedVpnEditorOwner({ generation }, { true }, { true }, { false },
                { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { returned.await() } }, {}, { 0L },
                currentHost = { current }, coroutineScope = this, importFailureMessage = { "old-file-error" })
            try {
                val first = async { owner.pick() }; runCurrent(); val oldId = checkNotNull(owner.currentId)
                owner.execute(input("vpnEditor.file", oldId)); runCurrent()
                if (change == "host") { original.alive = false; current = successor.witness }
                else ++generation
                owner.authorityChanged(); assertEquals(VpnConfigurationPick.Unavailable, first.await())
                val next = async { owner.edit(VpnConfigurationEditorRequest("Successor", true)) }; runCurrent()
                val nextId = checkNotNull(owner.currentId); assertTrue(owner.replace(nextId, configuration))
                returned.complete(readVpnConfigurationDocument { ByteArrayInputStream(byteArrayOf(0xe2.toByte(), 0x82.toByte())) }); runCurrent()
                assertEquals(nextId, owner.currentId); assertEquals(configuration, owner.snapshot(nextId)?.text)
                assertEquals("", checkNotNull(owner.projection()).getJSONObject("vpnEditor").getString("error"))
                assertFalse(next.isCompleted)
                owner.execute(input("foreground.dismiss", nextId).put("discardConfirmed", true))
                assertEquals(VpnConfigurationPick.Cancelled, next.await())
            } finally { returned.complete(VpnConfigurationPick.Cancelled); owner.close(); runCurrent() }
        }
    }

    @Test fun `unavailable provider reads refresh chooser revoked authority before publishing their owned error`() = runTest {
        for (failure in listOf("null-stream", "provider-throws")) {
            val returned = CompletableDeferred<Unit>()
            val authorizing = CompletableDeferred<Unit>()
            val granted = CompletableDeferred<Boolean>()
            var readable = true; var revision = 1L; var reads = 0; var authorizations = 0
            val owner = NativeSharedVpnEditorOwner({ 1L }, { readable }, {
                ++authorizations; authorizing.complete(Unit)
                granted.await().also { if (it) { readable = true; ++revision } }
            }, { true }, {
                ++reads; returned.await()
                readVpnConfigurationDocument {
                    if (failure == "null-stream") null else throw java.io.IOException("Controlled provider failure")
                }
            }, {}, { revision }, currentHost = { defaultHost }, coroutineScope = this,
                importFailedMessage = { "owned-unavailable-error" })
            val pending = async { owner.pick() }
            try {
                runCurrent(); val id = checkNotNull(owner.currentId)
                assertTrue(owner.replace(id, configuration))
                assertEquals("completed", owner.execute(input("vpnEditor.file", id)).status); runCurrent()
                assertEquals(1, reads)
                // The real chooser pause revokes the old view grant before the IO result returns.
                readable = false; ++revision; owner.authorityChanged()
                returned.complete(Unit); runCurrent()
                assertTrue("Unavailable must await genuine fresh authorization", authorizing.isCompleted)
                assertEquals(1, authorizations); assertNull(owner.projection())
                assertFalse(pending.isCompleted); assertNull(owner.snapshot(id)?.text)
                granted.complete(true); runCurrent()
                val projection = checkNotNull(owner.projection()).getJSONObject("vpnEditor")
                assertEquals("owned-unavailable-error", projection.getString("error"))
                assertFalse(projection.getBoolean("reading")); assertTrue(projection.getBoolean("hasContent"))
                assertTrue(owner.canEditName(id)); assertNull(owner.snapshot(id)?.text)
                assertFalse(pending.isCompleted)
                // A later foreground entry keeps the error and never replays the failed provider read.
                assertEquals("completed", owner.execute(input("vpnEditor.enter", id)).status)
                assertEquals(1, reads); assertEquals(2, authorizations)
                assertEquals("owned-unavailable-error", checkNotNull(owner.projection()).getJSONObject("vpnEditor").getString("error"))
                assertTrue(owner.reveal(id)); assertEquals(configuration, owner.snapshot(id)?.text)
                assertFalse(owner.projection().toString().contains("PrivateKey"))
                owner.execute(input("foreground.dismiss", id).put("discardConfirmed", true))
                assertEquals(VpnConfigurationPick.Cancelled, pending.await())
            } finally { returned.complete(Unit); granted.complete(false); owner.close(); runCurrent() }
        }
    }

    @Test fun `denied unavailable read and chooser cancellation never become authenticated errors or replayed reads`() = runTest {
        for (result in listOf("unavailable", "cancelled")) {
            val returned = CompletableDeferred<Unit>()
            var readable = true; var revision = 1L; var reads = 0; var authorizations = 0
            var allow = false
            val owner = NativeSharedVpnEditorOwner({ 1L }, { readable }, {
                ++authorizations; if (allow) readable = true; allow
            }, { true }, {
                ++reads; returned.await()
                if (result == "cancelled") VpnConfigurationPick.Cancelled
                else readVpnConfigurationDocument { null }
            }, {}, { revision }, currentHost = { defaultHost }, coroutineScope = this,
                importFailedMessage = { "must-not-be-shown" })
            val pending = async { owner.pick() }
            try {
                runCurrent(); val id = checkNotNull(owner.currentId)
                assertTrue(owner.replace(id, configuration)); owner.execute(input("vpnEditor.file", id)); runCurrent()
                readable = false; ++revision; owner.authorityChanged()
                returned.complete(Unit); runCurrent()
                assertEquals(if (result == "unavailable") 1 else 0, authorizations)
                assertNull(owner.projection()); assertNull(owner.snapshot(id)?.text); assertFalse(pending.isCompleted)
                // Merely restoring a readable frame cannot fabricate the denied result's error.
                readable = true
                assertEquals("", checkNotNull(owner.projection()).getJSONObject("vpnEditor").getString("error"))
                assertFalse(owner.canEditName(id)); assertNull(owner.snapshot(id)?.text)
                allow = true
                assertEquals("completed", owner.execute(input("vpnEditor.enter", id)).status)
                assertEquals(1, reads); assertEquals("", checkNotNull(owner.projection()).getJSONObject("vpnEditor").getString("error"))
                assertTrue(owner.reveal(id)); assertEquals(configuration, owner.snapshot(id)?.text)
                owner.execute(input("foreground.dismiss", id).put("discardConfirmed", true))
                assertEquals(VpnConfigurationPick.Cancelled, pending.await())
            } finally { returned.complete(Unit); owner.close(); runCurrent() }
        }
    }

    @Test fun `unavailable provider result cannot authorize or publish after its original custody retires`() = runTest {
        for (change in listOf("host", "graph", "editor", "death")) {
            val original = TestHost("unavailable-original-$change"); val successor = TestHost("unavailable-successor-$change")
            var current: NativeVisitHost? = original.witness; var generation = 1L; var identity: Any = Any()
            val returned = CompletableDeferred<Unit>(); var authorizations = 0; var reads = 0
            val owner = NativeSharedVpnEditorOwner({ generation }, { true }, { ++authorizations; true }, { true }, {
                ++reads
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { returned.await() }
                readVpnConfigurationDocument { null }
            }, {}, { 1L }, currentHost = { current }, editorIdentity = { identity }, coroutineScope = this,
                importFailedMessage = { "retired-error" })
            val first = async { owner.pick() }
            try {
                runCurrent(); val id = checkNotNull(owner.currentId)
                assertTrue(owner.replace(id, configuration)); val raw = capturedRaw(owner)
                owner.execute(input("vpnEditor.file", id)); runCurrent()
                when (change) {
                    "host" -> current = successor.witness
                    "graph" -> ++generation
                    "editor" -> identity = Any()
                    "death" -> { original.alive = false; current = successor.witness }
                }
                owner.authorityChanged(); assertEquals(VpnConfigurationPick.Unavailable, first.await())
                assertEquals("", raw())
                val next = async { owner.edit(VpnConfigurationEditorRequest("Successor", true)) }; runCurrent()
                val nextId = checkNotNull(owner.currentId); assertTrue(owner.replace(nextId, configuration))
                returned.complete(Unit); runCurrent()
                assertEquals(1, reads); assertEquals(0, authorizations)
                assertEquals(nextId, owner.currentId); assertEquals(configuration, owner.snapshot(nextId)?.text)
                assertEquals("", checkNotNull(owner.projection()).getJSONObject("vpnEditor").getString("error"))
                assertFalse(next.isCompleted)
                owner.execute(input("foreground.dismiss", nextId).put("discardConfirmed", true))
                assertEquals(VpnConfigurationPick.Cancelled, next.await())
            } finally { returned.complete(Unit); owner.close(); runCurrent() }
        }
    }

    @Test fun `late unavailable authorization cannot publish into a successor or a closed editor`() = runTest {
        for (change in listOf("host", "graph", "editor", "death", "close")) {
            val original = TestHost("authorization-original-$change"); val successor = TestHost("authorization-successor-$change")
            var current: NativeVisitHost? = original.witness; var generation = 1L; var identity: Any = Any()
            var readable = true; var revision = 1L; var reads = 0; var authorizations = 0
            val returned = CompletableDeferred<Unit>(); val authorizing = CompletableDeferred<Unit>(); val granted = CompletableDeferred<Boolean>()
            val owner = NativeSharedVpnEditorOwner({ generation }, { readable }, {
                ++authorizations; authorizing.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { granted.await() }
            }, { true }, {
                ++reads; returned.await(); readVpnConfigurationDocument { null }
            }, {}, { revision }, currentHost = { current }, editorIdentity = { identity }, coroutineScope = this,
                importFailedMessage = { "retired-error" })
            val first = async { owner.pick() }
            try {
                runCurrent(); val id = checkNotNull(owner.currentId)
                assertTrue(owner.replace(id, configuration)); val raw = capturedRaw(owner)
                owner.execute(input("vpnEditor.file", id)); runCurrent()
                readable = false; ++revision; owner.authorityChanged()
                returned.complete(Unit); runCurrent(); assertTrue(authorizing.isCompleted)
                when (change) {
                    "host" -> current = successor.witness
                    "graph" -> ++generation
                    "editor" -> identity = Any()
                    "death" -> { original.alive = false; current = successor.witness }
                    "close" -> owner.close()
                }
                if (change != "close") owner.authorityChanged()
                assertEquals(if (change == "close") VpnConfigurationPick.Cancelled else VpnConfigurationPick.Unavailable, first.await())
                assertEquals("", raw())
                readable = true
                if (change == "close") {
                    granted.complete(true); runCurrent()
                    assertNull(owner.currentId); assertNull(owner.projection())
                } else {
                    val next = async { owner.edit(VpnConfigurationEditorRequest("Successor", true)) }; runCurrent()
                    val nextId = checkNotNull(owner.currentId); assertTrue(owner.replace(nextId, configuration))
                    granted.complete(true); runCurrent()
                    assertEquals(nextId, owner.currentId); assertEquals(configuration, owner.snapshot(nextId)?.text)
                    assertEquals("", checkNotNull(owner.projection()).getJSONObject("vpnEditor").getString("error"))
                    assertFalse(next.isCompleted)
                    owner.execute(input("foreground.dismiss", nextId).put("discardConfirmed", true))
                    assertEquals(VpnConfigurationPick.Cancelled, next.await())
                }
                assertEquals(1, reads); assertEquals(1, authorizations)
            } finally { returned.complete(Unit); granted.complete(false); owner.close(); runCurrent() }
        }
    }
}
