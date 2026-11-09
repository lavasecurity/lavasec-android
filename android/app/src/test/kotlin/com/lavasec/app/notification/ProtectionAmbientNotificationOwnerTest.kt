package com.lavasec.app.notification

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.VpnOwnership
import com.lavasec.app.vpn.VpnHealthStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionAmbientNotificationOwnerTest {
    @Test
    fun `a protected baseline posts once and reposts only on status changes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val protection = MutableStateFlow(protected())
        val health = MutableStateFlow(VpnHealthStatus(health = healthy()))
        val configuration = MutableStateFlow<FilterLibraryDraft?>(library())
        val presenter = RecordingPresenter()
        var now = 1_000L
        val owner = ProtectionAmbientNotificationOwner(
            protection,
            health,
            configuration,
            presenter,
            this,
            dispatcher,
            nowMillis = { now },
        )
        runCurrent()

        // First emission restores the ongoing notification without a change edge.
        assertEquals(1, presenter.posted.size)
        assertEquals(ProtectionAmbientStatus.PROTECTED, presenter.posted.last().status)

        // Same facts with a newer clock: no re-post.
        now += 5_000L
        health.value = VpnHealthStatus(revision = 1L, health = healthy())
        runCurrent()
        assertEquals(1, presenter.posted.size)

        // Fallback activation is a status change.
        now += 5_000L
        health.value = VpnHealthStatus(
            revision = 2L,
            health = healthy(activeFallback = ProtectionFallback.DEVICE_DNS),
        )
        runCurrent()
        assertEquals(2, presenter.posted.size)
        assertEquals(
            ProtectionAmbientStatus.DEVICE_DNS_FALLBACK,
            presenter.posted.last().status,
        )

        owner.close()
    }

    @Test
    fun `turning protection off cancels once and keeps the surface absent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val protection = MutableStateFlow<ProtectionState>(protected())
        val health = MutableStateFlow(VpnHealthStatus(health = healthy()))
        val presenter = RecordingPresenter()
        val owner = ProtectionAmbientNotificationOwner(
            protection,
            health,
            MutableStateFlow<FilterLibraryDraft?>(null),
            presenter,
            this,
            dispatcher,
            nowMillis = { 1_000L },
        )
        runCurrent()
        assertEquals(1, presenter.posted.size)

        protection.value = ProtectionState.Off
        runCurrent()
        assertEquals(1, presenter.cancelCount)
        assertEquals(1, presenter.posted.size)

        protection.value = protected()
        runCurrent()
        assertEquals(2, presenter.posted.size)
        protection.value = ProtectionState.Off
        runCurrent()
        assertEquals(2, presenter.cancelCount)

        owner.close()
    }

    @Test
    fun `an OFF baseline posts nothing and close removes a visible notification`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val protection = MutableStateFlow<ProtectionState>(ProtectionState.Off)
        val presenter = RecordingPresenter()
        val owner = ProtectionAmbientNotificationOwner(
            protection,
            MutableStateFlow(VpnHealthStatus(health = healthy())),
            MutableStateFlow<FilterLibraryDraft?>(null),
            presenter,
            this,
            dispatcher,
            nowMillis = { 1_000L },
        )
        runCurrent()
        assertTrue(presenter.posted.isEmpty())

        protection.value = protected()
        runCurrent()
        assertEquals(1, presenter.posted.size)

        owner.close()
        assertEquals(1, presenter.cancelCount)
    }

    @Test
    fun `a rejected post keeps the previous state comparable so it retries later`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val protection = MutableStateFlow<ProtectionState>(protected())
        val presenter = RecordingPresenter(failNextPost = true)
        val owner = ProtectionAmbientNotificationOwner(
            protection,
            MutableStateFlow(VpnHealthStatus(health = healthy())),
            MutableStateFlow<FilterLibraryDraft?>(null),
            presenter,
            this,
            dispatcher,
            nowMillis = { 1_000L },
        )
        runCurrent()
        assertEquals(0, presenter.posted.size)

        // A status change re-attempts the post even though the failed attempt is not recorded.
        protection.value = ProtectionState.Starting(StartOrigin.VisibleUser, 2L)
        runCurrent()
        assertEquals(1, presenter.posted.size)

        owner.close()
    }

    @Test
    fun `the foreground retry re-posts a dropped notification once settings change`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val presenter = RecordingPresenter()
        presenter.failAll = true
        val owner = ProtectionAmbientNotificationOwner(
            MutableStateFlow<ProtectionState>(protected()),
            MutableStateFlow(VpnHealthStatus(health = healthy())),
            MutableStateFlow<FilterLibraryDraft?>(null),
            presenter,
            this,
            dispatcher,
            nowMillis = { 1_000L },
        )
        runCurrent()
        assertEquals(0, presenter.posted.size)

        // Android silently dropped the post while notifications were blocked; the
        // owner does not mark the state visible, so the state flow alone would
        // never re-emit it. The host's foreground refresh retries explicitly.
        presenter.failAll = false
        assertTrue(owner.rePostCurrent())
        runCurrent()
        assertEquals(1, presenter.posted.size)

        // Idempotent retry: an already-posted equivalent state stays posted.
        assertTrue(owner.rePostCurrent())
        runCurrent()
        assertEquals(2, presenter.posted.size)

        owner.close()
    }

    @Test
    fun `the foreground retry is honest when the presenter keeps refusing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val presenter = RecordingPresenter()
        presenter.failAll = true
        val owner = ProtectionAmbientNotificationOwner(
            MutableStateFlow<ProtectionState>(protected()),
            MutableStateFlow(VpnHealthStatus(health = healthy())),
            MutableStateFlow<FilterLibraryDraft?>(null),
            presenter,
            this,
            dispatcher,
            nowMillis = { 1_000L },
        )
        runCurrent()
        assertFalse(owner.rePostCurrent())
        assertEquals(0, presenter.posted.size)
        owner.close()
    }

    private fun protected() = ProtectionState.Protected(
        configurationIdentity = ConfigurationIdentity(1L, "fixture"),
        ownership = VpnOwnership.User,
    )

    private fun healthy(
        activeFallback: ProtectionFallback = ProtectionFallback.NONE,
    ) = ProtectionHealthAccumulator(
        isConnected = true,
        activeFallback = activeFallback,
    )

    private fun library(): FilterLibraryDraft = FilterLibraryDraft(
        profiles = listOf(
            FilterProfileDraft(
                id = "balanced",
                name = "Balanced",
                protectionLevel = ProtectionLevel.BALANCED,
                enabledSourceIds = emptySet(),
            ),
        ),
        activeProfileId = "balanced",
        resolverPlan = MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
    )

    private class RecordingPresenter(
        private var failNextPost: Boolean = false,
    ) : ProtectionAmbientNotificationPresenter {
        val posted = mutableListOf<ProtectionAmbientUiState>()
        var cancelCount = 0
        var failAll = false

        override fun post(state: ProtectionAmbientUiState): Boolean {
            if (failAll) return false
            if (failNextPost) {
                failNextPost = false
                return false
            }
            posted += state
            return true
        }

        override fun cancel(): Boolean {
            cancelCount += 1
            return true
        }
    }
}
