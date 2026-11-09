package com.lavasec.app.reactnative

import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.security.PasscodeCredentialState
import com.lavasec.app.security.PasscodeVerification
import com.lavasec.app.security.PasscodeVerifierAvailability
import com.lavasec.app.security.PasscodeVerifierPort
import com.lavasec.app.security.SecurityOwner
import com.lavasec.app.security.SecurityPreferences
import com.lavasec.app.security.SecurityPreferencesPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suspended native reads use the real independent admission lanes and security turn owner. */
class NativeQueryReadFenceTest {
    @Test fun `domain checks require filter read authority independently of Activity authority`() = runTest {
        val fixture = Fixture(backgroundScope, dataSurface = ProtectedSurface.FILTERS)
        try {
            fixture.authorize(ProtectedSurface.APP_UNLOCK)
            suspend fun read(): QueryOutcome = NativeQueryReadFence(fixture.authority(), ProtectedSurface.FILTERS).read(fixture::authority) { fixture.privateRows }
            assertEquals(QueryOutcome.Rejected("filters-locked"), read())
            fixture.authorize(ProtectedSurface.ACTIVITY)
            assertEquals(QueryOutcome.Rejected("filters-locked"), read())
            fixture.authorize(ProtectedSurface.FILTERS)
            assertEquals(fixture.privateRows, read())
            val old = NativeQueryReadFence(fixture.authority(), ProtectedSurface.FILTERS)
            fixture.security.resetAuthenticationTurn()
            fixture.authorize(ProtectedSurface.APP_UNLOCK); fixture.authorize(ProtectedSurface.FILTERS)
            assertEquals(QueryOutcome.Rejected("stale-query"), old.read(fixture::authority) { fixture.privateRows })
        } finally { fixture.security.close() }
    }
    @Test fun `unchanged authorization returns the real suspended private result`() = runTest {
        val fixture = Fixture(backgroundScope)
        try {
            fixture.authorize()
            val captured = NativeQueryReadFence(fixture.authority())
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val query = async {
                fixture.admission.run("domains.query") {
                    captured.read(fixture::authority) { started.complete(Unit); release.await(); fixture.privateRows }
                }
            }
            started.await()
            assertFalse(query.isCompleted)
            release.complete(Unit)
            assertEquals(fixture.privateRows, query.await())
        } finally { fixture.security.close() }
    }

    @Test fun `an old query cannot borrow a reset then freshly authenticated security turn`() = runTest {
        val fixture = Fixture(backgroundScope)
        try {
            fixture.authorize()
            val revision = fixture.security.state.value.authenticationRevision
            val captured = NativeQueryReadFence(fixture.authority())
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val query = async {
                fixture.admission.run("domains.query") {
                    captured.read(fixture::authority) { started.complete(Unit); release.await(); fixture.privateRows }
                }
            }
            started.await()
            assertEquals("regranted", fixture.admission.run("navigation.authorize") {
                fixture.security.resetAuthenticationTurn()
                fixture.authorize()
                "regranted"
            })
            assertTrue(fixture.security.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            assertTrue(fixture.security.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
            assertTrue(fixture.security.state.value.authenticationRevision > revision)
            release.complete(Unit)
            assertEquals(QueryOutcome.Rejected("stale-query"), query.await())
            // A newly admitted query owns the new turn and does not strand the serialized lane.
            assertEquals(fixture.privateRows, fixture.admission.run("domains.query") {
                NativeQueryReadFence(fixture.authority()).read(fixture::authority) { fixture.privateRows }
            })
        } finally { fixture.security.close() }
    }

    @Test fun `pause and resume retire an all-off read even when current authorization stays true`() = runTest {
        val fixture = Fixture(backgroundScope, protectsData = false)
        try {
            assertTrue(fixture.security.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            assertTrue(fixture.security.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
            val captured = NativeQueryReadFence(fixture.authority())
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val query = async {
                fixture.admission.run("network.query") {
                    captured.read(fixture::authority) { started.complete(Unit); release.await(); fixture.privateRows }
                }
            }
            started.await()
            fixture.foreground = false
            // This is the production LavaReactActivity.onPause operation, including all-off.
            fixture.security.resetAuthenticationTurn()
            fixture.foreground = true
            assertTrue(fixture.security.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            assertTrue(fixture.security.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
            release.complete(Unit)
            assertEquals(QueryOutcome.Rejected("stale-query"), query.await())
        } finally { fixture.security.close() }
    }

    @Test fun `a replacement native window cannot receive a suspended private read`() = runTest {
        for (replaceIdentity in listOf(true, false)) {
            val fixture = Fixture(backgroundScope)
            try {
                fixture.authorize()
                val captured = NativeQueryReadFence(fixture.authority())
                val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val query = async {
                    fixture.admission.run("activity.query") {
                        captured.read(fixture::authority) { started.complete(Unit); release.await(); fixture.privateRows }
                    }
                }
                started.await()
                if (replaceIdentity) fixture.host = Any() else fixture.nativeHostID = "replacement-window"
                release.complete(Unit)
                assertEquals(QueryOutcome.Rejected("stale-host"), query.await())
            } finally { fixture.security.close() }
        }
    }

    @Test fun `a successor graph cannot fulfill the captured query even with current grants`() = runTest {
        val fixture = Fixture(backgroundScope)
        try {
            fixture.authorize()
            val captured = NativeQueryReadFence(fixture.authority())
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val query = async {
                fixture.admission.run("domains.query") {
                    captured.read(fixture::authority) { started.complete(Unit); release.await(); fixture.privateRows }
                }
            }
            started.await()
            assertEquals("rebuilt", fixture.admission.run("account.new") { ++fixture.generation; "rebuilt" })
            assertTrue(fixture.security.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
            release.complete(Unit)
            assertEquals(QueryOutcome.Rejected("stale-generation"), query.await())
        } finally { fixture.security.close() }
    }

    @Test fun `concurrent log retirement rejects old rows before private encoding`() = runTest {
        val fixture = Fixture(backgroundScope)
        try {
            fixture.authorize()
            val captured = NativeQueryReadFence(fixture.authority())
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var encodedValues = 0
            val query = async {
                fixture.admission.run("domains.query") {
                    val outcome = captured.read(fixture::authority) { started.complete(Unit); release.await(); fixture.privateRows }
                    if (outcome is QueryOutcome.Value) ++encodedValues
                    outcome
                }
            }
            started.await()
            val clearStarted = CompletableDeferred<Unit>(); val finishClear = CompletableDeferred<Unit>()
            val clearing = async {
                fixture.admission.run("logs.clear") {
                    ++fixture.clearRevision
                    clearStarted.complete(Unit)
                    finishClear.await()
                    ++fixture.clearRevision
                    "cleared"
                }
            }
            clearStarted.await()
            assertFalse(clearing.isCompleted)
            release.complete(Unit)
            assertEquals(QueryOutcome.Rejected("stale-query"), query.await())
            assertEquals(0, encodedValues)
            finishClear.complete(Unit)
            assertEquals("cleared", clearing.await())
        } finally { fixture.security.close() }
    }

    @Test fun `APP_UNLOCK and ACTIVITY are independently required before any owner read`() = runTest {
        val fixture = Fixture(backgroundScope)
        try {
            var reads = 0
            suspend fun read(): QueryOutcome = NativeQueryReadFence(fixture.authority()).read(fixture::authority) {
                ++reads; fixture.privateRows
            }
            assertEquals(QueryOutcome.Rejected("app-locked"), read())
            fixture.authorize(ProtectedSurface.APP_UNLOCK)
            assertEquals(QueryOutcome.Rejected("activity-locked"), read())
            fixture.authorize(ProtectedSurface.ACTIVITY)
            fixture.securityAvailable = false
            assertEquals(QueryOutcome.Rejected("app-locked"), read())
            assertEquals(0, reads)
        } finally { fixture.security.close() }
    }

    @Test fun `background destruction and unavailable owner refuse an in-flight private value`() = runTest {
        for (retirement in listOf("background", "destroyed", "owner-unavailable")) {
            val fixture = Fixture(backgroundScope)
            try {
                fixture.authorize()
                val captured = NativeQueryReadFence(fixture.authority())
                val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val query = async {
                    fixture.admission.run("stats.query") {
                        captured.read(fixture::authority) { started.complete(Unit); release.await(); fixture.privateRows }
                    }
                }
                started.await()
                when (retirement) {
                    "background" -> fixture.foreground = false
                    "destroyed" -> fixture.destroyed = true
                    "owner-unavailable" -> fixture.securityAvailable = false
                }
                release.complete(Unit)
                val reason = when (retirement) {
                    "background" -> "background"
                    "destroyed" -> "stale-host"
                    else -> "app-locked"
                }
                assertEquals(QueryOutcome.Rejected(reason), query.await())
            } finally { fixture.security.close() }
        }
    }

    private class Fixture(scope: CoroutineScope, protectsData: Boolean = true, dataSurface: ProtectedSurface = ProtectedSurface.ACTIVITY) {
        val admission = NativeCommandAdmission()
        var host: Any = Any()
        var nativeHostID = "source-window"
        var generation = 7L
        var foreground = true
        var destroyed = false
        var securityAvailable = true
        var clearRevision = 0L
        val privateRows = QueryOutcome.Value(listOf(DomainQueryRow(
            "real-row", "private.example", "1 blocked request", "shield", "blocked",
        )))
        val security = SecurityOwner(Verifier(protectsData), Preferences(protectsData, dataSurface), scope)

        fun authority() = NativeQueryReadAuthority(
            host, nativeHostID, generation, foreground, destroyed,
            security.state.value.takeIf { securityAvailable }, clearRevision,
        )

        suspend fun authorize(surface: ProtectedSurface? = null) {
            val surfaces = if (surface == null) listOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.ACTIVITY) else listOf(surface)
            for (target in surfaces) {
                assertTrue(security.requireAuthentication(target) { request ->
                    assertTrue(security.completeAuthentication(request.id, "1234"))
                })
            }
        }
    }

    private class Verifier(configured: Boolean) : PasscodeVerifierPort {
        private var code: String? = if (configured) "1234" else null
        override fun availability() = PasscodeVerifierAvailability.Available
        override fun credentialState() = if (code == null) PasscodeCredentialState.Absent else PasscodeCredentialState.Available
        override fun configure(code: String): Boolean { this.code = code; return true }
        override fun verify(code: String) = if (this.code == code) PasscodeVerification.Verified else PasscodeVerification.Wrong
        override fun remove(): Boolean { code = null; return true }
    }

    private class Preferences(protectsData: Boolean, dataSurface: ProtectedSurface) : SecurityPreferencesPort {
        private var value = SecurityPreferences(enabled = protectsData,
            protectedSurfaces = if (protectsData) setOf(ProtectedSurface.APP_UNLOCK, dataSurface) else emptySet())
        override fun load() = value
        override fun save(preferences: SecurityPreferences) { value = preferences }
    }
}
