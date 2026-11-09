package com.lavasec.app.product

import com.lavasec.app.platform.FilterMutationFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilterProfileSwitchOwnerTest {
    @Test
    fun `applied switch returns true and settles to idle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { target ->
            FilterProfileSwitchResult.Applied(target, generation = 2L)
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)

        assertTrue(owner.apply("core"))
        advanceUntilIdle()

        assertEquals(listOf("core"), port.requests)
        assertEquals(FilterProfileSwitchOwner.State.Idle, owner.state.value)
        assertFalse(owner.reconstructionRequired.value)
        owner.close()
    }

    @Test
    fun `in flight state is visible while the port is suspended`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        val port = FakePort { target ->
            release.await()
            FilterProfileSwitchResult.Applied(target, generation = 2L)
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)

        val applying = launch(dispatcher) { owner.apply("extra") }
        advanceUntilIdle()

        assertEquals(
            FilterProfileSwitchOwner.State.Applying("extra"),
            owner.state.value,
        )
        release.complete(Unit)
        applying.join()
        advanceUntilIdle()
        assertEquals(FilterProfileSwitchOwner.State.Idle, owner.state.value)
        assertTrue(applying.isCompleted)
        owner.close()
    }

    @Test
    fun `failed commit keeps honest failure facts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { target ->
            FilterProfileSwitchResult.Failed(target, FilterMutationFailure.COMMIT_FAILED)
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)

        assertFalse(owner.apply("core"))
        advanceUntilIdle()

        assertEquals(
            FilterProfileSwitchOwner.State.Failed(
                "core",
                FilterProfileSwitchOwner.Failure.COMMIT,
            ),
            owner.state.value,
        )
        assertFalse(owner.reconstructionRequired.value)
        owner.close()
    }

    @Test
    fun `manual rules refusal is distinguished from commit failures`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { target ->
            FilterProfileSwitchResult.Rejected(
                target,
                FilterProfileSwitchRejection.FROZEN_TARGET,
            )
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)

        assertFalse(owner.apply("extra"))
        advanceUntilIdle()

        assertEquals(
            FilterProfileSwitchOwner.State.Failed(
                "extra",
                FilterProfileSwitchOwner.Failure.FROZEN_TARGET,
            ),
            owner.state.value,
        )
        assertFalse(owner.reconstructionRequired.value)
        owner.close()
    }

    @Test
    fun `unavailable authority settles to idle and arms reconstruction for the host`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { target ->
            FilterProfileSwitchResult.ProtectionUnavailable(target)
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)
        assertFalse(owner.reconstructionRequired.value)

        assertFalse(owner.apply("core"))
        advanceUntilIdle()

        assertEquals(FilterProfileSwitchOwner.State.Idle, owner.state.value)
        assertTrue(owner.reconstructionRequired.value)
        owner.close()
    }

    @Test
    fun `detached unavailable authority settle still arms reconstruction`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        val port = FakePort { target ->
            release.await()
            FilterProfileSwitchResult.ProtectionUnavailable(target)
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)

        val applying = launch(dispatcher) { owner.apply("core") }
        advanceUntilIdle()
        applying.cancel()
        advanceUntilIdle()
        assertFalse(owner.reconstructionRequired.value)

        // The abandoned publication wedges the coordinator after the caller left; the
        // detached settle must still arm the reconstruction requirement for the host.
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(FilterProfileSwitchOwner.State.Idle, owner.state.value)
        assertTrue(owner.reconstructionRequired.value)
        owner.close()
    }

    @Test
    fun `superseded switch reports commit failure with the target`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { target ->
            FilterProfileSwitchResult.Superseded("balanced")
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)

        assertFalse(owner.apply("core"))
        advanceUntilIdle()

        assertEquals(
            FilterProfileSwitchOwner.State.Failed(
                "core",
                FilterProfileSwitchOwner.Failure.COMMIT,
            ),
            owner.state.value,
        )
        owner.close()
    }

    @Test
    fun `port exception reports commit failure without leaving in flight`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { _ ->
            error("port failure")
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)

        assertFalse(owner.apply("core"))
        advanceUntilIdle()

        assertEquals(
            FilterProfileSwitchOwner.State.Failed(
                "core",
                FilterProfileSwitchOwner.Failure.COMMIT,
            ),
            owner.state.value,
        )
        owner.close()
    }

    @Test
    fun `closed owner never reaches the port`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort { target ->
            FilterProfileSwitchResult.Applied(target, generation = 2L)
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)
        owner.close()

        assertFalse(owner.apply("core"))
        advanceUntilIdle()

        assertTrue(port.requests.isEmpty())
        assertEquals(FilterProfileSwitchOwner.State.Idle, owner.state.value)
    }

    @Test
    fun `caller cancellation lets the owned publication finish and settle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        val port = FakePort { _ ->
            release.await()
            FilterProfileSwitchResult.Applied("extra", generation = 2L)
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)

        val applying = launch(dispatcher) { owner.apply("extra") }
        advanceUntilIdle()
        assertEquals(
            FilterProfileSwitchOwner.State.Applying("extra"),
            owner.state.value,
        )

        applying.cancel()
        advanceUntilIdle()

        // The owned task keeps running under the owner job: an apply is really still in
        // flight, so the state stays honest instead of settling to Idle on a dead caller.
        assertEquals(
            FilterProfileSwitchOwner.State.Applying("extra"),
            owner.state.value,
        )
        assertTrue(applying.isCancelled)
        assertEquals(listOf("extra"), port.requests)

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(FilterProfileSwitchOwner.State.Idle, owner.state.value)
        owner.close()
    }

    @Test
    fun `caller cancelled port failure still reports honest commit failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        val port = FakePort { _ ->
            release.await()
            error("port failure after the caller left")
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)

        val applying = launch(dispatcher) { owner.apply("core") }
        advanceUntilIdle()
        applying.cancel()
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            FilterProfileSwitchOwner.State.Failed(
                "core",
                FilterProfileSwitchOwner.Failure.COMMIT,
            ),
            owner.state.value,
        )
        owner.close()
    }

    @Test
    fun `a detached settle never clobbers a newer apply`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val firstRelease = CompletableDeferred<Unit>()
        val port = object : FilterProfileSwitchPort {
            val requests = mutableListOf<String>()

            override suspend fun apply(targetProfileId: String): FilterProfileSwitchResult {
                requests += targetProfileId
                if (targetProfileId == "slow") {
                    firstRelease.await()
                    return FilterProfileSwitchResult.Failed(
                        "slow",
                        FilterMutationFailure.COMMIT_FAILED,
                    )
                }
                return FilterProfileSwitchResult.Applied(targetProfileId, generation = 2L)
            }
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)

        val slow = launch(dispatcher) { owner.apply("slow") }
        advanceUntilIdle()
        slow.cancel()
        advanceUntilIdle()
        assertEquals(
            FilterProfileSwitchOwner.State.Applying("slow"),
            owner.state.value,
        )

        assertTrue(owner.apply("fast"))
        advanceUntilIdle()
        assertEquals(FilterProfileSwitchOwner.State.Idle, owner.state.value)

        // The stale detached failure must not replace the newer applied settle.
        firstRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals(FilterProfileSwitchOwner.State.Idle, owner.state.value)
        owner.close()
    }

    @Test
    fun `closing the owner cancels the in flight port operation under the owner job`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val portCancelled = CompletableDeferred<Unit>()
        val port = object : FilterProfileSwitchPort {
            override suspend fun apply(targetProfileId: String): FilterProfileSwitchResult {
                try {
                    awaitCancellation()
                } finally {
                    portCancelled.complete(Unit)
                }
                error("the port operation must never complete after the owner closes")
            }
        }
        val owner = FilterProfileSwitchOwner(port, this, dispatcher)

        val applying = async(dispatcher) { owner.apply("extra") }
        advanceUntilIdle()
        assertEquals(
            FilterProfileSwitchOwner.State.Applying("extra"),
            owner.state.value,
        )

        owner.close()
        advanceUntilIdle()

        assertEquals(FilterProfileSwitchOwner.State.Idle, owner.state.value)
        assertFalse(applying.await())
        assertTrue(portCancelled.isCompleted)
    }

    private class FakePort(
        private val behavior: suspend (String) -> FilterProfileSwitchResult,
    ) : FilterProfileSwitchPort {
        val requests = mutableListOf<String>()

        override suspend fun apply(targetProfileId: String): FilterProfileSwitchResult {
            requests += targetProfileId
            return behavior(targetProfileId)
        }
    }
}
