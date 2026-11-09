package com.lavasec.app.reactnative

import com.lavasec.app.diagnostics.BugReportEnvironment
import com.lavasec.app.diagnostics.BugReportReview
import com.lavasec.app.diagnostics.BugReportSendResult
import com.lavasec.app.diagnostics.NativeBugReportOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
class NativeSharedForegroundOwnerTest {
    private val testHost = NativeVisitHost(Any(), "test-native-host") { true }

    @Test fun `fresh grant hydrates metadata and identity but cannot edit or publish before exact native Ready`() = runTest {
        var readable = true; var ready = false; var renamed = 0
        val owner = NativeSharedForegroundOwner({ 1L }, { readable }, {}, currentHost = { testHost }, canInteract = { readable && ready })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.RENAME_FILTER,
            name = "Saved", emoji = "🌱", rename = { _, _ -> ++renamed; true })) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        readable = false; assertNull(owner.projection())
        readable = true
        assertEquals("Saved", owner.projection()?.getString("name"))
        assertEquals("completed", owner.execute(input("foreground.enter", id)).status)
        val validation = owner.execute(input("foreground.identity", id).put("name", "New name").put("emoji", "🌿"))
        assertTrue((validation.value as JSONObject).getBoolean("valid"))
        assertEquals("foreground-not-ready", owner.execute(input("foreground.dirty", id).put("dirty", true)).reason)
        assertEquals("foreground-not-ready", owner.execute(input("foreground.submit", id).put("name", "New name").put("emoji", "🌿")).reason)
        assertFalse(begun.isCompleted); assertEquals(0, renamed)
        ready = true
        owner.execute(input("foreground.dirty", id).put("dirty", true))
        readable = false; ready = false
        // Discard only retires this route; it cannot read hidden fields or cancel its filter owner.
        assertEquals("completed", owner.execute(input("foreground.dismiss", id)).status)
        assertEquals("cancelled", begun.await().status); assertEquals(0, renamed); owner.close()
    }

    @Test fun `feedback metadata prepares beneath cover while no Ready blocks editing private preview clipboard and send`() = runTest {
        var ready = true; var authorization = 1L; var posts = 0; var copies = 0
        val reports = NativeBugReportOwner({ error("No optional diagnostics") }, { ++posts; BugReportSendResult.SENT }, this)
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, currentHost = { testHost }, feedbackOwner = { reports },
            copyReceipt = { ++copies; true }, authorizationRevision = { authorization }, canInteract = { ready })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.FEEDBACK)) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        owner.execute(input("feedback.topic", id).put("topic", "other"))
        owner.execute(input("feedback.change", id).put("field", "details").put("value", "Reviewed detail"))
        owner.execute(input("feedback.step", id).put("next", true)); owner.execute(input("feedback.step", id).put("next", true))
        val review = checkNotNull(owner.projection()).getJSONObject("feedback").getString("review")
        ++authorization; ready = false
        assertEquals("completed", owner.execute(input("feedback.enter", id)).status)
        assertEquals("Reviewed detail", checkNotNull(owner.projection()).getJSONObject("feedback").getString("details"))
        for (command in listOf(
            input("foreground.dirty", id).put("dirty", true), input("feedback.topic", id).put("topic", "suggestion"),
            input("feedback.change", id).put("field", "details").put("value", "Stale edit"),
            input("feedback.diagnostics", id).put("value", true), input("feedback.step", id).put("step", 0),
            input("feedback.preview", id), input("feedback.copy", id), input("feedback.submit", id).put("review", review),
        )) assertEquals(command.toString(), "foreground-not-ready", owner.execute(command).reason)
        assertEquals(0, posts); assertEquals(0, copies)
        assertEquals("Reviewed detail", checkNotNull(owner.projection()).getJSONObject("feedback").getString("details"))
        ready = true
        assertEquals(true, owner.execute(input("feedback.submit", id).put("review", review)).value)
        assertEquals(1, posts)
        ready = false
        assertEquals("foreground-not-ready", owner.execute(input("feedback.copy", id)).reason); assertEquals(0, copies)
        owner.execute(input("foreground.dismiss", id)); begun.await(); owner.close(); reports.close()
    }

    @Test fun `losing native Ready during pre-send sample cannot grant consent or start POST`() = runTest {
        val sample = CompletableDeferred<Unit>(); var ready = true; var posts = 0
        val reports = NativeBugReportOwner({ error("No optional diagnostics") }, { ++posts; BugReportSendResult.SENT }, this)
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, currentHost = { testHost }, feedbackOwner = { reports },
            sampleFeedback = { sample.await() }, canInteract = { ready })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.FEEDBACK)) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        owner.execute(input("feedback.topic", id).put("topic", "other"))
        owner.execute(input("feedback.change", id).put("field", "details").put("value", "Reviewed detail"))
        owner.execute(input("feedback.step", id).put("next", true)); owner.execute(input("feedback.step", id).put("next", true))
        val review = checkNotNull(owner.projection()).getJSONObject("feedback").getString("review")
        val submitted = async { owner.execute(input("feedback.submit", id).put("review", review)) }
        runCurrent(); ready = false; sample.complete(Unit)
        assertEquals("foreground-stale", submitted.await().reason); assertEquals(0, posts)
        assertFalse(checkNotNull(owner.projection()).getJSONObject("feedback").getBoolean("busy"))
        owner.execute(input("foreground.dismiss", id)); begun.await(); owner.close(); reports.close()
    }

    @Test fun `publication callbacks never hold the owner monitor needed by native snapshot reads`() = runTest {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        lateinit var owner: NativeSharedForegroundOwner
        owner = NativeSharedForegroundOwner({ 1L }, { true }, {
            executor.submit<String?> { owner.currentId }.get(2, java.util.concurrent.TimeUnit.SECONDS)
        }, currentHost = { testHost })
        try {
            val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES)) }
            runCurrent()
            assertTrue(owner.requestDismiss())
            owner.close()
            assertEquals("cancelled", begun.await().status)
        } finally { owner.close(); executor.shutdownNow() }
    }

    private class TestHost(val nativeHostID: String) {
        val activity = Any()
        var alive = true
        fun witness() = NativeVisitHost(activity, nativeHostID) { alive }
    }

    @Test fun `same live Activity pause and absent context retain draft without admitting private reads or controls`() = runTest {
        val original = TestHost("original-window")
        var host: NativeVisitHost? = original.witness()
        var readable = true; var renames = 0
        val owner = NativeSharedForegroundOwner({ 1L }, { readable }, {}, currentHost = { host })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.RENAME_FILTER,
            name = "Retained", emoji = "🌱", rename = { _, _ -> ++renames; true })) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        owner.execute(input("foreground.dirty", id).put("dirty", true))
        readable = false; owner.authorityChanged()
        assertNull(owner.projection()); assertEquals(id, owner.currentId); assertFalse(begun.isCompleted)
        // An Activity pause can briefly clear the React context while the original window lives.
        host = null; readable = true; owner.authorityChanged()
        assertEquals(id, owner.currentId); assertEquals(NativeSharedForegroundKind.RENAME_FILTER, owner.currentKind)
        assertNull(owner.projection()); assertFalse(owner.requestDismiss()); assertFalse(begun.isCompleted)
        for (command in listOf(input("foreground.enter", id), input("foreground.identity", id),
            input("foreground.dirty", id).put("dirty", false), input("foreground.dismiss", id),
            input("foreground.submit", id).put("name", "Changed").put("emoji", "🌿"))) {
            assertEquals(command.toString(), "foreground-stale", owner.execute(command).reason)
        }
        assertEquals("foreground-busy", owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES)).reason)
        assertEquals(0, renames)
        host = original.witness(); owner.authorityChanged()
        assertEquals(id, owner.projection()?.getString("id")); assertEquals("Retained", owner.projection()?.getString("name"))
        assertTrue(owner.requestDismiss())
        assertEquals(1, owner.projection()?.getInt("dismissAttempt"))
        assertEquals(true, owner.execute(input("foreground.submit", id).put("name", "Changed").put("emoji", "🌿")).value)
        assertEquals(true, begun.await().value); assertEquals(1, renames); owner.close()
    }

    @Test fun `replacement host retires original deferred and late exact destruction cannot dismiss successor`() = runTest {
        val original = TestHost("original-window"); val successor = TestHost("successor-window")
        var host: NativeVisitHost? = original.witness()
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, currentHost = { host })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.RENAME_FILTER, name = "Private draft")) }
        runCurrent(); val oldID = checkNotNull(owner.currentId)
        host = successor.witness()
        assertNull(owner.projection()); assertFalse(owner.requestDismiss())
        assertEquals("foreground-stale", owner.execute(input("foreground.dismiss", oldID)).reason)
        owner.authorityChanged()
        assertEquals("cancelled", begun.await().status); assertNull(owner.currentId)
        val next = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES, notices = "Successor notices")) }
        runCurrent(); val newID = checkNotNull(owner.currentId); assertNotEquals(oldID, newID)
        owner.retireHost(original.activity, original.nativeHostID)
        owner.retireHost(successor.activity, original.nativeHostID)
        owner.retireHost(original.activity, successor.nativeHostID)
        assertEquals(newID, owner.projection()?.getString("id")); assertFalse(next.isCompleted)
        owner.retireHost(successor.activity, successor.nativeHostID)
        assertEquals("cancelled", next.await().status); assertNull(owner.projection()); owner.close()
    }

    @Test fun `dead original window cannot survive an absent context and begin cleans it before new admission`() = runTest {
        val original = TestHost("original-window"); var host: NativeVisitHost? = original.witness()
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, currentHost = { host })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES, notices = "Old notices")) }
        runCurrent(); original.alive = false; host = null; owner.authorityChanged()
        assertEquals("cancelled", begun.await().status); assertNull(owner.projection())
        assertEquals("foreground-unavailable", owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES)).reason)
        // Replacement before begin is independently fenced even without a lifecycle callback.
        val abandoned = TestHost("abandoned-window"); host = abandoned.witness()
        val old = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES)) }
        runCurrent(); val oldID = checkNotNull(owner.currentId)
        val successor = TestHost("successor-window"); host = successor.witness()
        val next = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES, notices = "New notices")) }
        runCurrent(); val newID = checkNotNull(owner.currentId)
        assertNotEquals(oldID, newID); assertEquals("cancelled", old.await().status)
        assertEquals("New notices", owner.projection()?.getString("notices"))
        owner.execute(input("foreground.dismiss", newID)); next.await(); owner.close()
    }

    @Test fun `obsolete editor spec cannot strand an invisible busy foreground visit`() = runTest {
        var current = true
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, currentHost = { testHost })
        val old = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.RENAME_FILTER,
            name = "Old editor", isCurrent = { current })) }
        runCurrent(); current = false; assertNull(owner.projection())
        val next = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES, notices = "Fresh notices")) }
        runCurrent(); assertEquals("cancelled", old.await().status)
        val nextID = checkNotNull(owner.currentId)
        assertEquals("Fresh notices", owner.projection()?.getString("notices"))
        owner.execute(input("foreground.dismiss", nextID)); next.await()
        assertEquals("foreground-unavailable", owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.RENAME_FILTER,
            name = "Already stale", isCurrent = { false })).reason)
        assertNull(owner.currentId); assertNull(owner.projection())
        current = true
        val retiring = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.RENAME_FILTER, isCurrent = { current })) }
        runCurrent(); current = false; owner.authorityChanged()
        assertEquals("cancelled", retiring.await().status); assertNull(owner.currentId); owner.close()
    }

    @Test fun `owned successful save may replace its own baseline without retiring before its callback returns`() = runTest {
        val writeCompleted = CompletableDeferred<Unit>(); var current = true
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, currentHost = { testHost })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.RENAME_FILTER,
            name = "Old", isCurrent = { current }, rename = { _, _ -> current = false; writeCompleted.await(); true })) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        val saved = async { owner.execute(input("foreground.submit", id).put("name", "New").put("emoji", "🌿")) }
        runCurrent(); owner.authorityChanged(); assertFalse(begun.isCompleted)
        assertEquals(id, owner.currentId); assertEquals(NativeSharedForegroundKind.RENAME_FILTER, owner.currentKind)
        assertNull(owner.projection())
        assertEquals("foreground-busy", owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES)).reason)
        writeCompleted.complete(Unit)
        assertEquals(true, saved.await().value); assertEquals(true, begun.await().value); owner.close()
    }

    @Test fun `late original save result cannot finish or clear a successor visit`() = runTest {
        val original = TestHost("original-window"); val successor = TestHost("successor-window")
        var host: NativeVisitHost? = original.witness(); val completed = CompletableDeferred<Unit>()
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, currentHost = { host })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.RENAME_FILTER,
            name = "Old", rename = { _, _ -> completed.await(); true })) }
        runCurrent(); val oldID = checkNotNull(owner.currentId)
        val saved = async { owner.execute(input("foreground.submit", oldID).put("name", "New").put("emoji", "🌿")) }
        runCurrent(); host = successor.witness(); owner.authorityChanged()
        assertEquals("cancelled", begun.await().status)
        val next = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES, notices = "Owned by successor")) }
        runCurrent(); val nextID = checkNotNull(owner.currentId)
        completed.complete(Unit)
        assertEquals("foreground-stale", saved.await().reason)
        assertEquals(nextID, owner.projection()?.getString("id")); assertFalse(next.isCompleted)
        owner.execute(input("foreground.dismiss", nextID)); next.await(); owner.close()
    }

    @Test fun `completed parent result cannot disclose its old filter identity after host changes before caller resumes`() = runTest {
        val original = TestHost("original-window"); val successor = TestHost("successor-window")
        var host: NativeVisitHost? = original.witness()
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, currentHost = { host })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.CREATE_FILTER,
            canCreate = true, create = { "private-filter-id" })) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        assertEquals(true, owner.execute(input("foreground.submit", id)).value)
        // The deferred completion schedules its parent on the test dispatcher, which has not run.
        assertFalse(begun.isCompleted); host = successor.witness()
        val result = begun.await()
        assertEquals("foreground-stale", result.reason); assertNull(result.value); owner.close()
    }

    @Test fun `host replacement during feedback preparation cannot grant consent or submit its frozen review`() = runTest {
        val original = TestHost("original-window"); val successor = TestHost("successor-window")
        var host: NativeVisitHost? = original.witness(); val sampled = CompletableDeferred<Unit>(); var posts = 0
        val reports = NativeBugReportOwner({ error("No optional diagnostics") }, { ++posts; BugReportSendResult.SENT }, this)
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, currentHost = { host },
            feedbackOwner = { reports }, sampleFeedback = { sampled.await() })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.FEEDBACK)) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        owner.execute(input("feedback.topic", id).put("topic", "other"))
        owner.execute(input("feedback.change", id).put("field", "details").put("value", "Original reviewed detail"))
        owner.execute(input("feedback.step", id).put("next", true)); owner.execute(input("feedback.step", id).put("next", true))
        val review = checkNotNull(owner.projection()).getJSONObject("feedback").getString("review")
        val saved = async { owner.execute(input("feedback.submit", id).put("review", review)) }
        runCurrent(); host = successor.witness(); owner.authorityChanged()
        assertEquals("cancelled", begun.await().status)
        val next = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES, notices = "Successor")) }
        runCurrent(); val nextID = checkNotNull(owner.currentId)
        sampled.complete(Unit)
        assertEquals("foreground-stale", saved.await().reason); assertEquals(0, posts)
        assertEquals(nextID, owner.projection()?.getString("id")); assertFalse(next.isCompleted)
        owner.execute(input("foreground.dismiss", nextID)); next.await(); owner.close(); reports.close()
    }

    @Test fun `host retirement preserves in-flight native POST ownership and hides its late receipt from successor`() = runTest {
        val original = TestHost("original-window"); val successor = TestHost("successor-window")
        var host: NativeVisitHost? = original.witness(); val delivered = CompletableDeferred<BugReportSendResult>()
        var nativeReview: BugReportReview? = null; var posts = 0
        val reports = NativeBugReportOwner({ error("No optional diagnostics") }, { nativeReview = it; ++posts; delivered.await() }, this)
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, currentHost = { host }, feedbackOwner = { reports })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.FEEDBACK)) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        owner.execute(input("feedback.topic", id).put("topic", "other"))
        owner.execute(input("feedback.change", id).put("field", "details").put("value", "Original reviewed detail"))
        owner.execute(input("feedback.step", id).put("next", true)); owner.execute(input("feedback.step", id).put("next", true))
        val review = checkNotNull(owner.projection()).getJSONObject("feedback").getString("review")
        val sending = async { owner.execute(input("feedback.submit", id).put("review", review)) }
        runCurrent(); assertEquals(1, posts)
        host = successor.witness(); owner.retireHost(original.activity, original.nativeHostID)
        assertEquals("cancelled", begun.await().status)
        // The report owner is still sending: forced form retirement must not discard its attempt.
        assertNull(reports.prepare(com.lavasec.app.foundation.feedback.FeedbackDraft(
            topic = com.lavasec.app.foundation.feedback.FeedbackTopic.OTHER, details = "Another report"), false))
        val next = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES, notices = "Successor")) }
        runCurrent(); val nextID = checkNotNull(owner.currentId)
        delivered.complete(BugReportSendResult.SENT)
        assertEquals("foreground-stale", sending.await().reason); assertEquals(1, posts)
        assertEquals("Issue: Something else\nDetails: Original reviewed detail", checkNotNull(nativeReview).let {
            JSONObject(it.requestBytes().toString(Charsets.UTF_8)).getString("user_description")
        })
        assertEquals(nextID, owner.projection()?.getString("id")); assertFalse(next.isCompleted)
        assertFalse(checkNotNull(owner.projection()).has("feedback"))
        owner.execute(input("foreground.dismiss", nextID)); next.await(); owner.close(); reports.close()
    }

    private fun input(type: String, id: String) = JSONObject().put("type", type).put("id", id)

    @Test fun `the pending create yields its original result only after explicit shared submit`() = runTest {
        var creates = 0
        val owner = NativeSharedForegroundOwner({ 3L }, { true }, {}, currentHost = { testHost })
        val begun = async { owner.begin(NativeSharedForegroundSpec(
            NativeSharedForegroundKind.CREATE_FILTER, canCreate = true,
            templates = listOf(NativeSharedForegroundTemplate("saved", "Saved")),
            create = { template -> assertEquals("saved", template); ++creates; "draft-created" },
        )) }
        runCurrent()
        val flow = checkNotNull(owner.projection())
        assertFalse(begun.isCompleted)
        assertEquals("createFilter", flow.getString("kind"))
        assertEquals("rejected", owner.execute(input("foreground.submit", flow.getString("id")).put("template", "removed")).status)
        assertEquals(0, creates)
        assertEquals(true, owner.execute(input("foreground.submit", flow.getString("id")).put("template", "saved")).value)
        assertEquals("draft-created", begun.await().value)
        assertNull(owner.projection())
        assertEquals(1, creates)
        owner.close()
    }

    @Test fun `generation and replaced editor baselines cannot rename or cancel a newer native editor`() = runTest {
        var generation = 4L; var currentEditor = true; var renames = 0
        val owner = NativeSharedForegroundOwner({ generation }, { true }, {}, currentHost = { testHost })
        val begun = async { owner.begin(NativeSharedForegroundSpec(
            NativeSharedForegroundKind.RENAME_FILTER, name = "Saved", emoji = "🌱",
            isCurrent = { currentEditor }, rename = { _, _ -> ++renames; true },
        )) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        currentEditor = false
        assertNull(owner.projection())
        assertEquals("foreground-stale", owner.execute(input("foreground.submit", id).put("name", "New name").put("emoji", "🌿")).reason)
        currentEditor = true; ++generation
        assertEquals("foreground-stale", owner.execute(input("foreground.submit", id).put("name", "New name").put("emoji", "🌿")).reason)
        // Local route dismissal releases only this visit; no filter.cancel or filter.close callback exists.
        assertEquals("completed", owner.execute(input("foreground.dismiss", id)).status)
        assertEquals("cancelled", begun.await().status)
        assertEquals(0, renames)
        owner.close()
    }

    @Test fun `identity uses native name emoji and availability policy before the commit callback`() = runTest {
        var renames = 0
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, currentHost = { testHost })
        val begun = async { owner.begin(NativeSharedForegroundSpec(
            NativeSharedForegroundKind.RENAME_FILTER, name = "Legacy-name", emoji = "🌱",
            isNameAvailable = { it != "Duplicate" }, rename = { name, emoji ->
                assertEquals("Legacy-name", name); assertEquals("🌿", emoji); ++renames; true
            },
        )) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        val duplicate = owner.execute(input("foreground.identity", id).put("name", "Duplicate").put("emoji", "🌿")).value as JSONObject
        assertFalse(duplicate.getBoolean("valid"))
        assertEquals("foreground-identity-invalid", owner.execute(input("foreground.submit", id).put("name", "Good").put("emoji", "plain")).reason)
        assertEquals(0, renames)
        assertEquals(true, owner.execute(input("foreground.submit", id).put("name", "Legacy-name").put("emoji", "🌿")).value)
        assertEquals(true, begun.await().value); assertEquals(1, renames)
        owner.close()
    }

    @Test fun `covered fields keep the native visit and reenter without replacing its editor`() = runTest {
        var available = true
        val owner = NativeSharedForegroundOwner({ 1L }, { available }, {}, currentHost = { testHost })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES, notices = "Bundled notices")) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        available = false
        assertNull(owner.projection())
        assertEquals("foreground-locked", owner.execute(input("foreground.enter", id)).reason)
        assertFalse(begun.isCompleted)
        available = true
        assertEquals(id, owner.projection()?.getString("id"))
        owner.execute(input("foreground.dismiss", id)); assertEquals("cancelled", begun.await().status)
        owner.close()
    }

    @Test fun `the shared feedback revision consumes only an explicitly reviewed capability and real receipt`() = runTest {
        val sent = mutableListOf<BugReportReview>()
        var copied = ""
        val reports = NativeBugReportOwner({ error("diagnostics were not opted in") }, { sent += it; BugReportSendResult.SENT }, this)
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, { testHost }, { reports }, { copied = it; true })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.FEEDBACK)) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        owner.execute(input("feedback.topic", id).put("topic", "websiteAccess"))
        owner.execute(input("feedback.step", id).put("next", true))
        owner.execute(input("feedback.change", id).put("field", "site").put("value", "  example.com  "))
        owner.execute(input("feedback.change", id).put("field", "details").put("value", "Old detail"))
        owner.execute(input("feedback.step", id).put("next", true))
        val oldReview = checkNotNull(owner.projection()).getJSONObject("feedback").getString("review")
        owner.execute(input("feedback.change", id).put("field", "details").put("value", "Reviewed detail"))
        assertEquals("feedback-review-required", owner.execute(input("feedback.submit", id).put("review", oldReview)).reason)
        assertTrue(sent.isEmpty())
        owner.execute(input("feedback.step", id).put("step", 2))
        val review = checkNotNull(owner.projection()).getJSONObject("feedback").getString("review")
        assertEquals(true, owner.execute(input("feedback.submit", id).put("review", review)).value)
        val submitted = checkNotNull(owner.projection()).getJSONObject("feedback")
        assertTrue(submitted.getBoolean("sent")); assertEquals(sent.single().reportID, submitted.getString("receipt"))
        assertEquals("feedback-review-required", owner.execute(input("feedback.submit", id).put("review", review)).reason)
        assertEquals(1, sent.size)
        assertEquals(true, owner.execute(input("feedback.copy", id)).value); assertEquals(sent.single().reportID, copied)
        assertEquals("feedback-busy", owner.execute(input("feedback.change", id).put("field", "details").put("value", "edit after send")).reason)
        owner.execute(input("foreground.dismiss", id)); begun.await(); owner.close(); reports.close()
    }

    @Test fun `unchanged failed feedback retains exact body for a manual retry and preview samples no new data`() = runTest {
        val sent = mutableListOf<BugReportReview>(); var captures = 0
        val reports = NativeBugReportOwner({ ++captures; BugReportEnvironment("1", "2", "9", 28, "connected", true, 120) },
            { sent += it; if (sent.size == 1) BugReportSendResult.FAILED else BugReportSendResult.SENT }, this)
        val owner = NativeSharedForegroundOwner({ 1L }, { true }, {}, { testHost }, { reports })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.FEEDBACK)) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        owner.execute(input("feedback.topic", id).put("topic", "other"))
        owner.execute(input("feedback.change", id).put("field", "details").put("value", "A detail"))
        owner.execute(input("feedback.diagnostics", id).put("value", true))
        owner.execute(input("feedback.step", id).put("next", true)); owner.execute(input("feedback.step", id).put("next", true))
        val review = checkNotNull(owner.projection()).getJSONObject("feedback").getString("review")
        val preview = owner.execute(input("feedback.preview", id)).value.toString()
        assertTrue(preview.contains("120")); assertFalse(preview.contains("A detail")); assertEquals(1, captures)
        assertEquals(false, owner.execute(input("feedback.submit", id).put("review", review)).value)
        assertFalse(checkNotNull(owner.projection()).getJSONObject("feedback").getBoolean("sent"))
        assertEquals(true, owner.execute(input("feedback.submit", id).put("review", review)).value)
        assertSame(sent[0], sent[1]); assertArrayEquals(sent[0].requestBytes(), sent[1].requestBytes())
        assertEquals(1, captures)
        owner.execute(input("foreground.dismiss", id)); begun.await(); owner.close(); reports.close()
    }

    @Test fun `feedback cannot dismiss or edit while a POST is owned and late completion cannot reach a replacement visit`() = runTest {
        val delivery = CompletableDeferred<BugReportSendResult>()
        var generation = 1L
        val reports = NativeBugReportOwner({ error("no optional diagnostics") }, { delivery.await() }, this)
        val owner = NativeSharedForegroundOwner({ generation }, { true }, {}, { testHost }, { reports })
        val begun = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.FEEDBACK)) }
        runCurrent(); val id = checkNotNull(owner.currentId)
        owner.execute(input("feedback.topic", id).put("topic", "other"))
        owner.execute(input("feedback.change", id).put("field", "details").put("value", "A detail"))
        owner.execute(input("feedback.step", id).put("next", true)); owner.execute(input("feedback.step", id).put("next", true))
        val review = checkNotNull(owner.projection()).getJSONObject("feedback").getString("review")
        val submitting = async { owner.execute(input("feedback.submit", id).put("review", review)) }
        runCurrent()
        assertEquals(false, owner.execute(input("foreground.dismiss", id)).value)
        assertFalse(owner.requestDismiss())
        assertEquals("feedback-busy", owner.execute(input("feedback.change", id).put("field", "email").put("value", "new@example.com")).reason)
        ++generation
        val replacement = async { owner.begin(NativeSharedForegroundSpec(NativeSharedForegroundKind.LICENSES, notices = "New visit")) }
        runCurrent(); val replacementId = checkNotNull(owner.currentId)
        assertNotEquals(id, replacementId)
        assertEquals("cancelled", begun.await().status)
        delivery.complete(BugReportSendResult.SENT); submitting.await()
        assertEquals(replacementId, owner.projection()?.getString("id")); assertFalse(replacement.isCompleted)
        owner.execute(input("foreground.dismiss", replacementId)); replacement.await(); owner.close(); reports.close()
    }
}
