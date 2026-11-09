package com.lavasec.app.reactnative

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeAccountBackupFlowTest {
    private class Fixture(kind: String = "backupSetup") {
        var host = NativeVisitHost(Any(), "host") { true }
        var generation: Long? = 4
        var sameAccount = true
        var granted = true
        var ready = true
        var revision = 0L
        val flow = NativeAccountBackupFlow(kind, host, 4, { host }, { generation },
            { sameAccount }, { granted }, { ready }, {}, { revision })
        fun command(action: String) = JSONObject().put("id", flow.id).put("action", action)
        fun state() = flow.projection()!!.getJSONObject("accountBackup")
    }

    @Test fun `copy remains available during upload and retry but revocation still denies it`() {
        val f = Fixture(); f.flow.createPhrase()
        val phrase = f.flow.credential()!!.first
        for (uploading in listOf(false, true)) {
            f.flow.phase("upload", uploading)
            var copied: String? = null
            assertTrue(f.flow.copyRecoveryPhrase { copied = it })
            assertEquals(phrase, copied)
        }
        f.granted = false
        assertFalse(f.flow.copyRecoveryPhrase { fail("Revoked phrase reached clipboard") })
        f.flow.close()
        assertFalse(f.flow.copyRecoveryPhrase { fail("Retired phrase reached clipboard") })
    }

    @Test fun `setup requires native phrase confirmation and both consent values`() = runTest {
        val f = Fixture(); f.flow.createPhrase()
        val waiter = async { f.flow.awaitAction("setup.finish") }; runCurrent()
        assertFalse(f.flow.command(f.command("setup.finish")))
        val phrase = f.flow.credential()!!.first
        assertFalse(f.flow.projection().toString().contains(phrase))
        assertTrue(f.flow.replaceCredential("wrong words".toCharArray()))
        assertTrue(f.flow.command(f.command("setup.consent").put("saved", true).put("understands", true)))
        assertFalse(f.flow.command(f.command("setup.finish")))
        assertTrue(f.flow.replaceCredential(phrase.toCharArray()))
        assertTrue(f.flow.command(f.command("setup.finish")))
        assertFalse(f.flow.command(f.command("setup.finish")))
        assertEquals("setup.finish", waiter.await().getString("action"))
        val borrowed = f.flow.borrowSecret()
        f.flow.close()
        assertNull(f.flow.credential()); assertTrue(borrowed.contentEquals(phrase.toCharArray()))
        borrowed.fill('\u0000')
    }

    @Test fun `new recovery phrase clears passkey refusal and reflects the retained native method`() {
        for (retainedPasskey in listOf(false, true)) {
            val f = Fixture()
            f.flow.phase("overview", passkey = true)
            f.flow.failure("Passkey could not be used.")
            f.flow.createPhrase(withPasskey = retainedPasskey)
            assertEquals("recoveryPhrase", f.state().getString("step"))
            assertEquals("", f.state().getString("error"))
            assertEquals(retainedPasskey, f.state().getBoolean("selectedPasskey"))
            f.flow.close()
        }
    }

    @Test fun `revoked private read cannot expose input or block exact close`() = runTest {
        val f = Fixture(); f.flow.createPhrase()
        val waiting = async { runCatching { f.flow.awaitAction("setup.finish") } }; runCurrent()
        f.granted = false
        assertNull(f.flow.projection()); assertNull(f.flow.credential())
        assertFalse(f.flow.replaceCredential("anything".toCharArray()))
        assertFalse(f.flow.cancel("successor"))
        assertTrue(f.flow.cancel(f.flow.id))
        assertTrue(waiting.await().isFailure)
        assertFalse(f.flow.retained())
    }

    @Test fun `local upload retry is not success and never mints another phrase`() = runTest {
        val f = Fixture(); f.flow.createPhrase()
        val initial = f.flow.credential()!!.first
        f.flow.phase("upload", true)
        assertFalse(f.state().getBoolean("success"))
        f.flow.failure("Upload failed", upload = true)
        val waiting = async { f.flow.awaitAction("setup.retry") }; runCurrent()
        assertFalse(f.state().getBoolean("uploading"))
        assertEquals(initial, f.flow.credential()!!.first)
        assertTrue(f.flow.command(f.command("setup.retry"))); waiting.await()
        f.flow.complete()
        assertTrue(f.state().getBoolean("success"))
        assertNull(f.flow.credential())
        f.flow.close()
    }

    @Test fun `host account and graph retirement each rejects publication and erases credentials`() {
        for (changed in 0..2) {
            val f = Fixture(); f.flow.createPhrase()
            when (changed) { 0 -> f.host = NativeVisitHost(Any(), "new") { true }; 1 -> f.sameAccount = false; 2 -> f.generation = 5 }
            f.flow.authorityChanged()
            assertFalse(f.flow.retained()); assertNull(f.flow.credential())
            assertFalse(f.flow.admitPublication { true })
        }
    }

    @Test fun `restore mode switching clears native input and does not select unavailable methods`() {
        val f = Fixture("backupRestore"); f.flow.configureRestore(false, false)
        assertEquals("recoveryCode", f.flow.method())
        assertTrue(f.flow.replaceCredential("saved recovery words".toCharArray()))
        assertFalse(f.flow.command(f.command("restore.mode").put("mode", "passkey")))
        assertEquals("saved recovery words", f.flow.credential()!!.second)
        f.flow.configureRestore(true, false)
        assertTrue(f.flow.command(f.command("restore.mode").put("mode", "deviceKey")))
        assertNull(f.flow.credential())
        assertTrue(f.flow.command(f.command("restore.mode").put("mode", "recoveryCode")))
        assertEquals("", f.flow.credential()!!.second)
    }

    @Test fun `restore resolver acknowledgment and mutation fence are required exactly once`() = runTest {
        val f = Fixture("backupRestore")
        f.flow.failure("That unlock secret did not match your backup.")
        assertFalse(f.state().getString("error").isEmpty())
        f.flow.review(JSONArray(), true)
        assertEquals("", f.state().getString("error"))
        val waiting = async { f.flow.awaitAction("restore.confirm") }; runCurrent()
        assertFalse(f.flow.command(f.command("restore.confirm")))
        assertTrue(f.flow.command(f.command("restore.consent").put("value", true)))
        assertTrue(f.flow.command(f.command("restore.confirm"))); waiting.await()
        assertFalse(f.flow.admitPublication { false })
        assertTrue(f.flow.admitPublication { true })
        assertFalse(f.flow.admitPublication { true })
        f.flow.close()
    }

    @Test fun `account deletion status belongs only to the claimed delete action`() = runTest {
        for (action in listOf("account.signOut", "account.delete")) {
            val f = Fixture("account")
            val waiting = async { f.flow.awaitAction("account.signOut", "account.delete") }; runCurrent()
            assertTrue(f.flow.command(f.command(action)))
            assertEquals(action == "account.delete", f.state().getBoolean("deleting"))
            waiting.await(); f.flow.working(true)
            assertEquals(action == "account.delete", f.state().getBoolean("deleting"))
            f.flow.failure("Try again")
            assertFalse(f.state().getBoolean("deleting")); f.flow.close()
        }
    }

    @Test fun `retry clears obsolete refusal copy when the action is claimed`() = runTest {
        val f = Fixture(); f.flow.phase("upload")
        f.flow.failure("Prior refusal"); f.flow.failure("Upload failed", upload = true)
        val waiting = async { f.flow.awaitAction("setup.retry") }; runCurrent()
        assertEquals("Prior refusal", f.state().getString("error"))
        assertEquals("Upload failed", f.state().getString("uploadError"))
        assertTrue(f.flow.command(f.command("setup.retry"))); waiting.await()
        assertEquals("", f.state().getString("error")); assertEquals("", f.state().getString("uploadError"))
        f.flow.failure("New refusal"); f.flow.working(true)
        assertEquals("", f.state().getString("error")); f.flow.close()
    }

    @Test fun `exact user dismissal is distinct from authority retirement and never admits publication`() {
        for (action in listOf("close", "back", "cancel", "authority")) {
            val f = Fixture("backupRestore"); f.flow.configureRestore(true, false); f.flow.working(true)
            assertFalse(f.flow.cancel("different-visit")); assertFalse(f.flow.dismissedByUser())
            when (action) {
                "cancel" -> assertTrue(f.flow.cancel(f.flow.id))
                "authority" -> { f.sameAccount = false; f.flow.authorityChanged(); f.flow.cancel(f.flow.id) }
                else -> assertTrue(f.flow.command(f.command(action)))
            }
            assertEquals(action != "authority", f.flow.dismissedByUser())
            f.flow.close()
            assertEquals(action != "authority", f.flow.dismissedByUser())
            assertFalse(f.flow.retained()); assertFalse(f.flow.admitPublication { true })
        }
    }

    @Test fun `account projection carries provider labels but no email`() {
        val f = Fixture("account")
        f.flow.configureAccount(listOf(NativeLinkedAccountConnection("google", "person@example.test")))
        assertFalse(f.flow.projection().toString().contains("person@example.test"))
        assertTrue(f.flow.credential()!!.first.contains("person@example.test"))
        f.granted = false
        assertNull(f.flow.credential()); assertNull(f.flow.projection())
    }

    @Test fun `unready measured route cannot change consent or submit`() = runTest {
        val f = Fixture()
        val waiting = async { runCatching { f.flow.awaitAction("setup.begin") } }; runCurrent()
        f.ready = false
        assertFalse(f.flow.command(f.command("setup.begin")))
        f.flow.close(); assertTrue(waiting.await().isFailure)
    }

    @Test fun `queued work cannot borrow a renewed authentication turn`() = runTest {
        val f = Fixture()
        val waiting = async { runCatching { f.flow.awaitAction("setup.begin") } }; runCurrent()
        assertTrue(f.flow.command(f.command("setup.begin")))
        f.revision++
        assertTrue(waiting.await().isFailure)
        f.flow.close()
    }

    @Test fun `a submitted restore freezes its method before the worker resumes`() = runTest {
        val f = Fixture("backupRestore"); f.flow.configureRestore(true, false)
        val waiting = async { f.flow.awaitAction("restore.prepare") }; runCurrent()
        assertTrue(f.flow.command(f.command("restore.prepare")))
        assertFalse(f.flow.command(f.command("restore.mode").put("mode", "recoveryCode")))
        assertEquals("deviceKey", f.flow.method())
        waiting.await(); f.flow.close()
    }
}
