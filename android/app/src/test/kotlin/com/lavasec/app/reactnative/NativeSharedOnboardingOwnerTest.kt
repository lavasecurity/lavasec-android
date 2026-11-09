package com.lavasec.app.reactnative

import android.app.Application
import android.content.Intent
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.onboarding.OnboardingPage
import com.lavasec.app.foundation.resolver.BuiltInDohResolverIds
import com.lavasec.app.onboarding.OnboardingOwner
import com.lavasec.app.onboarding.PendingVpnConsent
import com.lavasec.app.platform.VpnPreparation
import com.lavasec.app.platform.VpnPreparationResult
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeSharedOnboardingOwnerTest {
    @Test fun `shared setup uses actual permissions and saves Quad9 only after measured timed handoff`() = runTest {
        Fixture(this).use { f ->
            f.move(1); f.move(2)
            assertEquals(0, f.commits)
            assertRejected(f.send("onboarding.navigate", "page" to 3), "vpn-required")
            assertEquals(2, f.snapshot().getInt("page"))
            assertCompleted(f.send("onboarding.vpn"))
            assertTrue(f.snapshot().getBoolean("vpnInstalled"))
            f.move(3)
            assertCompleted(f.send("onboarding.choice", "level" to "comprehensive"))
            f.move(4)
            assertEquals(BuiltInDohResolverIds.QUAD9_UNFILTERED, f.native.state.value.onboarding?.draft?.resolver?.encryptedFallbackId)
            assertCompleted(f.send("onboarding.choice", "fallback" to false))
            f.move(5)
            assertEquals(OnboardingPage.CONNECTION_QUALITY, f.native.state.value.onboarding?.page)
            assertEquals(0, f.commits)
            assertRejected(f.send("onboarding.complete"), "onboarding-not-ready")
            f.time = 1740
            f.ready = false
            assertRejected(f.send("onboarding.ready"), "onboarding-not-ready")
            f.ready = true
            assertCompleted(f.send("onboarding.ready"))
            assertCompleted(f.send("onboarding.open"))
            assertRejected(f.send("onboarding.navigate", "page" to 0, "revisit" to true), "onboarding-navigation")
            f.time += 839
            assertRejected(f.send("onboarding.release"), "onboarding-not-ready")
            f.time++
            assertCompleted(f.send("onboarding.release"))
            assertRejected(f.send("onboarding.complete"), "onboarding-not-ready")
            f.time += 80
            val completed = async(start = CoroutineStart.UNDISPATCHED) { f.owner.awaitCompletion(f.id) }
            assertCompleted(f.send("onboarding.complete"))
            assertTrue(completed.await())
            assertEquals(1, f.commits)
            assertEquals(ProtectionLevel.EXTRA, f.saved?.protectionLevel)
            assertEquals(BuiltInDohResolverIds.QUAD9_UNFILTERED, f.saved?.resolver?.encryptedFallbackId)
            assertFalse(checkNotNull(f.saved).resolver.useEncryptedFallback)
            assertNull(f.owner.snapshot())
        }
    }

    @Test fun `permission denial cannot advance and later acceptance retains the same shared visit`() = runTest {
        Fixture(this).use { f ->
            f.move(1); f.move(2); f.acceptVpn = false
            assertRejected(f.send("onboarding.vpn"), "vpn-denied")
            assertTrue(f.native.state.value.vpnConsentDenied)
            assertNull(f.native.state.value.pendingVpnConsent)
            assertEquals(1, f.cancelledConsentIds.size)
            assertEquals(2, f.snapshot().getInt("page"))
            assertFalse(f.snapshot().getBoolean("vpnInstalled"))
            assertEquals(0, f.commits)
            f.acceptVpn = true
            assertCompleted(f.send("onboarding.vpn"))
            assertEquals(f.id, f.snapshot().getString("id"))
            assertCompleted(f.send("onboarding.notifications"))
            assertFalse(f.snapshot().getBoolean("notifications"))
            f.move(3)
            assertTrue(f.native.state.value.onboarding?.notificationHandled == true)
            assertEquals(0, f.commits)
        }
    }

    @Test fun `real denied permission records its result on resume but next waits for exact viewport Ready`() = runTest {
        Fixture(this).use { f ->
            f.move(1); f.move(2)
            assertCompleted(f.send("onboarding.vpn"))
            assertEquals(OnboardingPage.NOTIFICATIONS, f.native.state.value.onboarding?.page)
            val draft = f.native.state.value.onboarding?.draft
            val vpnPrompts = f.permissionPrompts
            f.ready = false
            assertRejected(f.send("onboarding.notifications"), "onboarding-not-ready")
            assertEquals(vpnPrompts, f.permissionPrompts)
            assertNull(f.native.state.value.notificationPermissionGranted)

            val started = CompletableDeferred<Unit>()
            val result = CompletableDeferred<Boolean>()
            f.requestNotificationsResult = { started.complete(Unit); result.await() }
            f.ready = true
            val command = async(start = CoroutineStart.UNDISPATCHED) { f.send("onboarding.notifications") }
            started.await()
            assertEquals("notifications", f.snapshot().getString("busy"))
            assertEquals(vpnPrompts + 1, f.permissionPrompts)
            f.active = false; f.ready = false
            assertNull(f.native.state.value.notificationPermissionGranted)
            // The actual Android permission port returns only after this same host
            // resumes; its recorded result does not manufacture a layout receipt.
            f.active = true
            result.complete(false)
            assertCompleted(command.await())
            assertEquals(false, f.native.state.value.notificationPermissionGranted)
            assertTrue(f.native.state.value.onboarding?.notificationHandled == true)
            assertEquals(OnboardingPage.NOTIFICATIONS, f.native.state.value.onboarding?.page)
            assertEquals("", f.snapshot().getString("busy"))
            assertEquals(f.id, f.snapshot().getString("id"))
            assertEquals(draft, f.native.state.value.onboarding?.draft)
            assertEquals(0, f.commits)

            val calls = f.nativeCalls
            assertRejected(f.send("onboarding.navigate", "page" to 3), "onboarding-not-ready")
            assertEquals(calls, f.nativeCalls)
            assertEquals(2, f.snapshot().getInt("page"))
            assertEquals(OnboardingPage.NOTIFICATIONS, f.native.state.value.onboarding?.page)
            f.ready = true
            f.move(3)
            assertEquals(OnboardingPage.PROTECTION_LEVEL, f.native.state.value.onboarding?.page)
            assertEquals(draft, f.native.state.value.onboarding?.draft)
            assertEquals(0, f.commits)
        }
    }

    @Test fun `unknown jumps choices and stale visits never mutate native onboarding`() = runTest {
        Fixture(this).use { f ->
            assertRejected(f.send("onboarding.navigate", "page" to 5), "onboarding-navigation")
            assertRejected(f.send("onboarding.choice", "level" to "comprehensive"), "onboarding-navigation")
            assertRejected(f.send("onboarding.choice", "dnsProfile" to true), "unsupported-platform")
            assertFalse(f.snapshot().getBoolean("supportsDNSProfile"))
            f.generation++
            assertRejected(f.send("onboarding.navigate", "page" to 1), "stale-onboarding")
            assertEquals(OnboardingPage.LAVA, f.native.state.value.onboarding?.page)
            assertNull(f.owner.snapshot())
        }
    }

    @Test fun `background and a stale readiness receipt cannot release the final marker`() = runTest {
        Fixture(this).use { f ->
            f.move(1); f.move(2); f.send("onboarding.vpn"); f.move(3); f.move(4); f.move(5)
            f.time = 1740; f.ready = false
            assertRejected(f.send("onboarding.ready"), "onboarding-not-ready")
            f.active = false
            assertRejected(f.send("onboarding.ready"), "app-background")
            assertEquals(0, f.commits)
            f.active = true; f.ready = true
            assertCompleted(f.send("onboarding.ready"))
            assertCompleted(f.send("onboarding.back"))
            assertEquals(4, f.snapshot().getInt("page"))
            assertEquals(OnboardingPage.CONNECTION_QUALITY, f.native.state.value.onboarding?.page)
            assertEquals(0, f.commits)
        }
    }

    @Test fun `queued navigation choices and permissions require the exact shared viewport receipt`() = runTest {
        Fixture(this).use { f ->
            f.ready = false
            assertCompleted(f.send("onboarding.enter"))
            assertRejected(f.send("onboarding.navigate", "page" to 1), "onboarding-not-ready")
            assertEquals(0, f.nativeCalls)
            assertEquals(0, f.permissionPrompts)
            f.ready = true; f.move(1); f.move(2)
            val before = f.native.state.value
            val calls = f.nativeCalls
            f.ready = false
            assertRejected(f.send("onboarding.back"), "onboarding-not-ready")
            assertRejected(f.send("onboarding.navigate", "page" to 3), "onboarding-not-ready")
            assertRejected(f.send("onboarding.vpn"), "onboarding-not-ready")
            assertRejected(f.send("onboarding.notifications"), "onboarding-not-ready")
            assertEquals(before, f.native.state.value)
            assertEquals(calls, f.nativeCalls)
            assertEquals(0, f.permissionPrompts)
            f.ready = true; assertCompleted(f.send("onboarding.vpn")); f.move(3)
            val level = f.native.state.value.onboarding?.draft?.protectionLevel
            val levelCalls = f.nativeCalls
            f.ready = false
            assertRejected(f.send("onboarding.choice", "level" to "comprehensive"), "onboarding-not-ready")
            assertEquals(level, f.native.state.value.onboarding?.draft?.protectionLevel)
            assertEquals(levelCalls, f.nativeCalls)
            f.ready = true; f.move(4)
            val fallback = f.native.state.value.onboarding?.draft?.resolver
            val fallbackCalls = f.nativeCalls
            f.ready = false
            assertRejected(f.send("onboarding.choice", "fallback" to false), "onboarding-not-ready")
            assertEquals(fallback, f.native.state.value.onboarding?.draft?.resolver)
            assertEquals(fallbackCalls, f.nativeCalls)
            assertEquals(0, f.commits)
        }
    }

    @Test fun `readiness revoked during VPN preparation prevents the system prompt`() = runTest {
        Fixture(this).use { f ->
            f.move(1); f.move(2)
            f.afterForward = { f.ready = false }
            assertRejected(f.send("onboarding.vpn"), "onboarding-not-ready")
            assertEquals(OnboardingPage.VPN, f.native.state.value.onboarding?.page)
            assertEquals(0, f.permissionPrompts)
            assertNull(f.native.state.value.pendingVpnConsent)
            assertEquals(0, f.commits)
            f.afterForward = null; f.ready = true
            assertCompleted(f.send("onboarding.vpn"))
            assertEquals(1, f.permissionPrompts)
            assertTrue(f.snapshot().getBoolean("vpnInstalled"))
        }
    }

    @Test fun `readiness loss after native consent preparation retires the exact request`() = runTest {
        Fixture(this).use { f ->
            f.move(1); f.move(2)
            val draft = f.native.state.value.onboarding?.draft
            var pendingId: Long? = null
            f.afterForward = {
                f.native.state.value.pendingVpnConsent?.let { pendingId = it.id; f.ready = false }
            }
            assertRejected(f.send("onboarding.vpn"), "onboarding-not-ready")
            assertEquals(listOf(7L to checkNotNull(pendingId)), f.cancelledConsentIds)
            assertNull(f.native.state.value.pendingVpnConsent)
            assertFalse(f.native.state.value.vpnConsentDenied)
            assertEquals(OnboardingPage.VPN, f.native.state.value.onboarding?.page)
            assertEquals(draft, f.native.state.value.onboarding?.draft)
            assertEquals(0, f.permissionPrompts)
            f.afterForward = null; f.ready = true
            assertCompleted(f.send("onboarding.vpn"))
            assertEquals(1, f.permissionPrompts)
        }
    }

    @Test fun `background loss after native consent preparation cannot strand a prompt`() = runTest {
        Fixture(this).use { f ->
            f.move(1); f.move(2)
            f.afterForward = { if (f.native.state.value.pendingVpnConsent != null) f.active = false }
            assertRejected(f.send("onboarding.vpn"), "onboarding-not-ready")
            assertNull(f.native.state.value.pendingVpnConsent)
            assertFalse(f.native.state.value.vpnConsentDenied)
            assertEquals(OnboardingPage.VPN, f.native.state.value.onboarding?.page)
            assertEquals(0, f.permissionPrompts)
            f.afterForward = null; f.active = true
            assertCompleted(f.send("onboarding.vpn"))
            assertEquals(1, f.permissionPrompts)
        }
    }

    @Test fun `caller cancellation at preparation return still retires the captured native request`() = runTest {
        Fixture(this).use { f ->
            f.move(1); f.move(2)
            var pendingId = 0L
            lateinit var command: Job
            f.afterForward = {
                f.native.state.value.pendingVpnConsent?.let { pending ->
                    pendingId = pending.id
                    command.cancel()
                }
            }
            command = launch { f.send("onboarding.vpn") }
            command.join()
            assertTrue(command.isCancelled)
            assertTrue(pendingId > 0L)
            assertEquals(listOf(7L to pendingId), f.cancelledConsentIds)
            assertNull(f.native.state.value.pendingVpnConsent)
            assertFalse(f.native.state.value.vpnConsentDenied)
            assertEquals(OnboardingPage.VPN, f.native.state.value.onboarding?.page)
            assertEquals(0, f.permissionPrompts)
            f.afterForward = null
            assertCompleted(f.send("onboarding.vpn"))
        }
    }

    @Test fun `cancelled permission coroutine clears pending consent and permits a clean retry`() = runTest {
        Fixture(this).use { f ->
            f.move(1); f.move(2)
            val started = CompletableDeferred<Long>()
            val waiting = CompletableDeferred<Boolean>()
            f.presentVpnResult = { pending -> started.complete(pending.id); waiting.await() }
            val command = launch { f.send("onboarding.vpn") }
            val pendingId = started.await()
            command.cancel(); command.join()
            assertTrue(command.isCancelled)
            assertEquals(listOf(7L to pendingId), f.cancelledConsentIds)
            assertNull(f.native.state.value.pendingVpnConsent)
            assertFalse(f.native.state.value.vpnConsentDenied)
            assertEquals(OnboardingPage.VPN, f.native.state.value.onboarding?.page)
            assertEquals("", f.snapshot().getString("busy"))
            f.presentVpnResult = null
            assertCompleted(f.send("onboarding.vpn"))
            assertEquals(2, f.permissionPrompts)
        }
    }

    @Test fun `cancelled host result cannot erase a successor native consent`() = runTest {
        Fixture(this).use { f ->
            f.move(1); f.move(2)
            var originalId = 0L
            var successorId = 0L
            f.presentVpnResult = { pending ->
                originalId = pending.id
                assertTrue(f.native.cancelVpnConsent(pending.id))
                f.native.forward()
                successorId = checkNotNull(f.native.state.value.pendingVpnConsent).id
                throw CancellationException("host-closed")
            }
            val command = launch { f.send("onboarding.vpn") }
            command.join()
            assertTrue(command.isCancelled)
            assertTrue(successorId > originalId)
            assertEquals(listOf(7L to originalId), f.cancelledConsentIds)
            assertEquals(successorId, f.native.state.value.pendingVpnConsent?.id)
            assertFalse(f.native.state.value.vpnConsentDenied)
            assertEquals(OnboardingPage.VPN, f.native.state.value.onboarding?.page)
            assertEquals(0, f.commits)
        }
    }

    @Test fun `mock close stays read free while other queued actions remain concealed`() = runTest {
        Fixture(this, mock = true, configured = true).use { f ->
            f.ready = false
            assertCompleted(f.send("onboarding.enter"))
            assertRejected(f.send("onboarding.navigate", "page" to 1), "onboarding-not-ready")
            assertRejected(f.send("onboarding.vpn"), "onboarding-not-ready")
            assertCompleted(f.send("onboarding.dismiss"))
            assertNull(f.owner.snapshot())
            assertEquals(0, f.nativeCalls)
            assertEquals(0, f.permissionPrompts)
            assertEquals(0, f.commits)
        }
    }

    @Test fun `debug mock rehearses shipping shared phases without native changes or permission prompts`() = runTest {
        Fixture(this, mock = true, configured = true).use { f ->
            val before = f.native.state.value
            assertTrue(f.snapshot().getBoolean("mock"))
            assertEquals("balanced", f.snapshot().getString("level"))
            f.move(1); f.move(2)
            assertRejected(f.send("onboarding.navigate", "page" to 3), "vpn-required")
            assertCompleted(f.send("onboarding.vpn"))
            assertCompleted(f.send("onboarding.notifications"))
            assertTrue(f.snapshot().getBoolean("vpnInstalled"))
            assertTrue(f.snapshot().getBoolean("notifications"))
            f.move(3)
            assertCompleted(f.send("onboarding.choice", "level" to "comprehensive"))
            f.move(4)
            assertCompleted(f.send("onboarding.choice", "fallback" to false))
            assertEquals("comprehensive", f.snapshot().getString("level"))
            assertFalse(f.snapshot().getBoolean("fallback"))
            f.move(5)
            f.time = NativeSharedOnboardingOwner.ARRIVAL_MILLIS
            assertCompleted(f.send("onboarding.ready"))
            assertCompleted(f.send("onboarding.open"))
            f.time += NativeSharedOnboardingOwner.RELEASE_MILLIS
            assertCompleted(f.send("onboarding.release"))
            f.time += 80 // Real setup can commit here; mock rehearses its full return animation.
            assertRejected(f.send("onboarding.complete"), "onboarding-not-ready")
            f.time = NativeSharedOnboardingOwner.ARRIVAL_MILLIS + NativeSharedOnboardingOwner.MOCK_COMPLETE_MILLIS
            val complete = async(start = CoroutineStart.UNDISPATCHED) { f.owner.awaitCompletion(f.id) }
            assertCompleted(f.send("onboarding.complete"))
            assertTrue(complete.await())
            assertTrue(f.owner.awaitCompletion(f.id))
            assertEquals(before, f.native.state.value)
            assertEquals(0, f.nativeCalls)
            assertEquals(0, f.permissionPrompts)
            assertEquals(0, f.commits)
            assertNull(f.owner.snapshot())
        }
    }

    @Test fun `mock dismissal stale generation and release build gate cannot save onboarding`() = runTest {
        Fixture(this, mock = true, configured = true).use { f ->
            val completion = async(start = CoroutineStart.UNDISPATCHED) { f.owner.awaitCompletion(f.id) }
            assertCompleted(f.send("onboarding.dismiss"))
            assertFalse(completion.await())
            assertNull(f.owner.snapshot())
            assertEquals(0, f.nativeCalls)
            assertEquals(0, f.permissionPrompts)
            val denied = NativeSharedOnboardingOwner(this, read = { f.generation to f.native.state.value },
                port = f.port, foreground = { true }, readyForOnboarding = { _, _ -> true }, allowMock = false)
            assertNull(denied.enter(f.generation, mock = true))
            assertNull(denied.snapshot())
            val stale = checkNotNull(f.owner.enter(f.generation, mock = true))
            f.generation++
            assertRejected(f.owner.execute(7, JSONObject().put("type", "onboarding.vpn").put("id", stale)), "stale-onboarding")
            assertNull(f.owner.snapshot())
            assertEquals(0, f.nativeCalls)
            assertEquals(0, f.commits)
        }
    }

    private fun assertCompleted(result: JSONObject) { assertEquals(result.toString(), "completed", result.getString("status")) }
    private fun assertRejected(result: JSONObject, code: String) { assertEquals("rejected", result.getString("status")); assertEquals(code, result.getString("code")) }
    private class Fixture(scope: TestScope, mock: Boolean = false, configured: Boolean = false) : AutoCloseable {
        var generation = 7L
        var time = 0L
        var active = true
        var ready = true
        var installed = false
        var notified = false
        var acceptVpn = true
        var commits = 0
        var nativeCalls = 0
        var permissionPrompts = 0
        var afterForward: (() -> Unit)? = null
        var presentVpnResult: (suspend (PendingVpnConsent) -> Boolean)? = null
        var requestNotificationsResult: (suspend () -> Boolean)? = null
        val cancelledConsentIds = mutableListOf<Pair<Long, Long>>()
        var saved: AppConfiguration? = null
        val native = OnboardingOwner(configured, vpnPreparation = VpnPreparation {
            if (installed) VpnPreparationResult.Prepared else VpnPreparationResult.ConsentRequired(Intent("synthetic-vpn-consent"))
        }, commit = { commits++; saved = it }, scope = scope, dispatcher = UnconfinedTestDispatcher(scope.testScheduler))
        val port = object : NativeSharedOnboardingOwner.Port {
                override suspend fun forward(generation: Long): PendingVpnConsent? { nativeCalls++; native.forward(); afterForward?.invoke(); return native.state.value.pendingVpnConsent }
                override suspend fun back(generation: Long) { nativeCalls++; native.back() }
                override suspend fun selectProtectionLevel(generation: Long, level: ProtectionLevel) { nativeCalls++; native.selectProtectionLevel(level) }
                override suspend fun selectFallback(generation: Long, enabled: Boolean) { nativeCalls++; native.selectFallback(enabled) }
                override suspend fun completeVpnConsent(generation: Long, id: Long, granted: Boolean): Boolean { nativeCalls++; return native.completeVpnConsent(id, granted) }
                override suspend fun cancelVpnConsent(generation: Long, id: Long): Boolean { cancelledConsentIds += generation to id; return native.cancelVpnConsent(id) }
                override suspend fun completeNotifications(generation: Long, granted: Boolean): Boolean { nativeCalls++; return native.completeNotificationPermission(granted) }
                override suspend fun presentVpn(pending: PendingVpnConsent): Boolean {
                    permissionPrompts++
                    val granted = presentVpnResult?.invoke(pending) ?: acceptVpn
                    installed = granted
                    return granted
                }
                override suspend fun requestNotifications(): Boolean { permissionPrompts++; return requestNotificationsResult?.invoke() ?: notified }
                override fun notificationsGranted() = notified
                override fun vpnInstalled() = installed
                override suspend fun finish(generation: Long): Boolean { nativeCalls++; native.forward(); return native.state.value.completed }
            }
        val owner = NativeSharedOnboardingOwner(scope, read = { generation to native.state.value },
            port = port, foreground = { active },
            readyForOnboarding = { generation, id -> ready && generation == this.generation && id == ownerID },
            clockMillis = { time }, allowMock = true)
        private var ownerID = ""
        val id = checkNotNull(owner.enter(generation, mock = mock)).also { ownerID = it }
        fun snapshot() = checkNotNull(owner.snapshot())
        suspend fun send(type: String, vararg values: Pair<String, Any>): JSONObject {
            val request = JSONObject().put("type", type).put("id", id)
            values.forEach { (key, value) -> request.put(key, value) }
            return owner.execute(7, request)
        }
        suspend fun move(page: Int) { val result = send("onboarding.navigate", "page" to page); assertEquals(result.toString(), "completed", result.getString("status")) }

        override fun close() {
            // This is the real graph owner, whose SupervisorJob is a child of
            // runTest. A completed shared visit does not close the native graph.
            try { owner.close() } finally { native.close() }
        }
    }
}
