package com.lavasec.app.reactnative

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCommandAdmissionTest {
    @Test fun `recovery waits for import publication while protection remains available`() = runBlocking {
        val admission = NativeCommandAdmission()
        val release = CompletableDeferred<Unit>()
        val publication = async(start = CoroutineStart.UNDISPATCHED) {
            admission.run("vpn.begin") { release.await(); "opened" }
        }
        var recovered = false
        val recovery = async(start = CoroutineStart.UNDISPATCHED) {
            admission.recover { recovered = true }
        }
        assertFalse(recovered)
        assertEquals("stopped", admission.run("protection.toggle") { "stopped" })
        release.complete(Unit)
        assertEquals("opened", publication.await())
        recovery.await()
        assertEquals(true, recovered)
        assertEquals("saved", admission.run("vpn.commit") { "saved" })
    }

    @Test fun `Stop and Pause reach their owner during suspended filter publication`() = runBlocking {
        val admission = NativeCommandAdmission()
        val release = CompletableDeferred<Unit>()
        val publication = async(start = CoroutineStart.UNDISPATCHED) {
            admission.run("filter.apply") { release.await(); "saved" }
        }
        assertEquals("stopped", admission.run("protection.toggle") { "stopped" })
        assertEquals("paused", admission.run("protection.pause") { "paused" })
        assertNull(admission.run("filter.edit") { error("overlapping mutation") })
        release.complete(Unit)
        assertEquals("saved", publication.await())
    }

    @Test fun `protection commands remain serialized and cancellation releases admission`() = runBlocking {
        val admission = NativeCommandAdmission()
        val release = CompletableDeferred<Unit>()
        val control = async(start = CoroutineStart.UNDISPATCHED) {
            admission.run("protection.toggle") { release.await(); "stopped" }
        }
        assertNull(admission.run("protection.pause") { error("overlapping protection") })
        control.cancel(); control.join()
        assertEquals("paused", admission.run("protection.pause") { "paused" })
    }

    @Test fun `completion releases its lane before follow-up and failures do not strand it`() = runBlocking {
        val admission = NativeCommandAdmission()
        try { admission.run("filter.apply") { throw IllegalStateException("refused") } }
        catch (_: IllegalStateException) { }
        assertEquals("saved", admission.run("filter.apply") { "saved" })
        assertEquals("closed", admission.run("filter.close") { "closed" })
    }

    @Test fun `current search waits for the prior query lens while mutations and Stop keep their lanes`() = runBlocking {
        val admission = NativeCommandAdmission()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val poll = async(start = CoroutineStart.UNDISPATCHED) {
            admission.run("activity.query") { order += "poll"; release.await(); "old-read" }
        }
        val search = async(start = CoroutineStart.UNDISPATCHED) {
            admission.run("domains.query") { order += "search"; "current-read" }
        }
        assertEquals(listOf("poll"), order)
        assertFalse(search.isCompleted)
        assertEquals("saved", admission.run("filter.apply") { "saved" })
        assertEquals("stopped", admission.run("protection.toggle") { "stopped" })
        release.complete(Unit)
        assertEquals("old-read", poll.await())
        assertEquals("current-read", search.await())
        assertEquals(listOf("poll", "search"), order)
    }

    @Test fun `cancelled queued search never runs and failed read releases the query lane`() = runBlocking {
        val admission = NativeCommandAdmission()
        val release = CompletableDeferred<Unit>()
        val poll = async(start = CoroutineStart.UNDISPATCHED) {
            admission.run("domains.query") { release.await(); "poll" }
        }
        var retiredBodyRan = false
        val retired = async(start = CoroutineStart.UNDISPATCHED) {
            admission.run("domains.query") { retiredBodyRan = true }
        }
        retired.cancel(); retired.join()
        release.complete(Unit)
        assertEquals("poll", poll.await())
        assertTrue(retired.isCancelled)
        assertFalse(retiredBodyRan)
        try { admission.run("domains.query") { throw IllegalStateException("read refused") } }
        catch (_: IllegalStateException) { }
        assertEquals("current", admission.run("domains.query") { "current" })
    }

}
