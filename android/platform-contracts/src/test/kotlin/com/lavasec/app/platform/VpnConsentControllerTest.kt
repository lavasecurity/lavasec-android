package com.lavasec.app.platform

import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConsentControllerTest {
    @Test
    fun `already prepared begins without a launch request`() {
        var queries = 0
        val controller = VpnConsentController(
            VpnPreparation<Any> {
                queries += 1
                VpnPreparationResult.Prepared
            },
        )

        assertEquals(VpnConsentBeginResult.Prepared, controller.begin())
        assertEquals(1, queries)
    }

    @Test
    fun `consent begin returns the exact native request and admits only one pending request`() {
        val request = Any()
        var queries = 0
        val controller = VpnConsentController(
            VpnPreparation {
                queries += 1
                VpnPreparationResult.ConsentRequired(request)
            },
        )

        val first = controller.begin() as VpnConsentBeginResult.LaunchRequired

        assertSame(request, first.request)
        assertEquals(VpnConsentBeginResult.AlreadyPending, controller.begin())
        assertEquals(1, queries)
    }

    @Test
    fun `active result is only a wakeup and fresh prepared authority completes setup`() {
        val request = Any()
        var queries = 0
        val controller = VpnConsentController(
            VpnPreparation {
                queries += 1
                if (queries == 1) {
                    VpnPreparationResult.ConsentRequired(request)
                } else {
                    VpnPreparationResult.Prepared
                }
            },
        )

        val launch = controller.begin() as VpnConsentBeginResult.LaunchRequired
        assertEquals(
            VpnConsentCompletion.Prepared,
            controller.completeActiveResult(launch.attempt),
        )
        assertEquals(
            VpnConsentCompletion.Ignored,
            controller.completeActiveResult(launch.attempt),
        )
        assertEquals(2, queries)
    }

    @Test
    fun `fresh still-required authority cannot complete and retry obtains a new request`() {
        val firstRequest = Any()
        val callbackRequest = Any()
        val retryRequest = Any()
        val results = ArrayDeque<VpnPreparationResult<Any>>().apply {
            add(VpnPreparationResult.ConsentRequired(firstRequest))
            add(VpnPreparationResult.ConsentRequired(callbackRequest))
            add(VpnPreparationResult.ConsentRequired(retryRequest))
        }
        var queries = 0
        val controller = VpnConsentController(
            VpnPreparation {
                queries += 1
                results.removeFirst()
            },
        )

        val first = controller.begin() as VpnConsentBeginResult.LaunchRequired
        assertSame(firstRequest, first.request)
        assertEquals(
            VpnConsentCompletion.ConsentStillRequired,
            controller.completeActiveResult(first.attempt),
        )

        val retry = controller.begin() as VpnConsentBeginResult.LaunchRequired
        assertSame(retryRequest, retry.request)
        assertEquals(3, queries)
    }

    @Test
    fun `unavailable and query exceptions never grant setup`() {
        val unavailable = VpnConsentController(
            VpnPreparation<Any> { VpnPreparationResult.Unavailable },
        )
        val throwing = VpnConsentController(
            VpnPreparation<Any> { throw IllegalStateException("platform failure") },
        )

        assertEquals(VpnConsentBeginResult.Unavailable, unavailable.begin())
        assertEquals(VpnConsentBeginResult.Unavailable, throwing.begin())
    }

    @Test
    fun `completion failure consumes pending and a duplicate does not query`() {
        var queries = 0
        val controller = VpnConsentController(
            VpnPreparation<Any> {
                queries += 1
                when (queries) {
                    1 -> VpnPreparationResult.ConsentRequired(Any())
                    2 -> throw IllegalStateException("fresh probe failed")
                    else -> error("unexpected query")
                }
            },
        )

        val launch = controller.begin() as VpnConsentBeginResult.LaunchRequired
        assertEquals(
            VpnConsentCompletion.Unavailable,
            controller.completeActiveResult(launch.attempt),
        )
        assertEquals(
            VpnConsentCompletion.Ignored,
            controller.completeActiveResult(launch.attempt),
        )
        assertEquals(2, queries)
    }

    @Test
    fun `pending may be abandoned once and a later retry re-queries`() {
        var queries = 0
        val controller = VpnConsentController(
            VpnPreparation {
                queries += 1
                VpnPreparationResult.ConsentRequired(Any())
            },
        )

        val first = controller.begin() as VpnConsentBeginResult.LaunchRequired
        assertEquals(
            VpnConsentAbandonResult.Abandoned,
            controller.abandonPending(first.attempt),
        )
        assertEquals(
            VpnConsentAbandonResult.Ignored,
            controller.abandonPending(first.attempt),
        )
        assertTrue(controller.begin() is VpnConsentBeginResult.LaunchRequired)
        assertEquals(2, queries)
    }

    @Test
    fun `stale completion or abandonment cannot consume a newer attempt`() {
        val firstRequest = Any()
        val retryRequest = Any()
        var queries = 0
        val controller = VpnConsentController(
            VpnPreparation {
                queries += 1
                when (queries) {
                    1 -> VpnPreparationResult.ConsentRequired(firstRequest)
                    2 -> VpnPreparationResult.ConsentRequired(retryRequest)
                    3 -> VpnPreparationResult.Prepared
                    else -> error("unexpected query")
                }
            },
        )

        val first = controller.begin() as VpnConsentBeginResult.LaunchRequired
        assertEquals(
            VpnConsentAbandonResult.Abandoned,
            controller.abandonPending(first.attempt),
        )
        val retry = controller.begin() as VpnConsentBeginResult.LaunchRequired
        assertSame(retryRequest, retry.request)

        assertEquals(
            VpnConsentCompletion.Ignored,
            controller.completeActiveResult(first.attempt),
        )
        assertEquals(
            VpnConsentAbandonResult.Ignored,
            controller.abandonPending(first.attempt),
        )
        assertEquals(2, queries)
        assertEquals(
            VpnConsentCompletion.Prepared,
            controller.completeActiveResult(retry.attempt),
        )
        assertEquals(3, queries)
    }

    @Test
    fun `new process controller ignores a stale result then explicitly re-probes`() {
        val oldController = VpnConsentController(
            VpnPreparation { VpnPreparationResult.ConsentRequired(Any()) },
        )
        val oldLaunch = oldController.begin() as VpnConsentBeginResult.LaunchRequired

        val freshRequest = Any()
        var freshQueries = 0
        val newController = VpnConsentController(
            VpnPreparation {
                freshQueries += 1
                VpnPreparationResult.ConsentRequired(freshRequest)
            },
        )

        assertEquals(
            VpnConsentCompletion.Ignored,
            newController.completeActiveResult(oldLaunch.attempt),
        )
        assertEquals(0, freshQueries)
        val retry = newController.begin() as VpnConsentBeginResult.LaunchRequired
        assertSame(freshRequest, retry.request)
        assertEquals(1, freshQueries)
    }

    @Test
    fun `pending is consumed before a reentrant completion query`() {
        var queries = 0
        var nestedResult: VpnConsentCompletion? = null
        lateinit var activeAttempt: VpnConsentAttempt
        lateinit var controller: VpnConsentController<Any>
        controller = VpnConsentController(
            VpnPreparation {
                queries += 1
                if (queries == 1) {
                    VpnPreparationResult.ConsentRequired(Any())
                } else {
                    nestedResult = controller.completeActiveResult(activeAttempt)
                    VpnPreparationResult.Prepared
                }
            },
        )

        val launch = controller.begin() as VpnConsentBeginResult.LaunchRequired
        activeAttempt = launch.attempt
        assertEquals(
            VpnConsentCompletion.Prepared,
            controller.completeActiveResult(activeAttempt),
        )
        assertEquals(VpnConsentCompletion.Ignored, nestedResult)
        assertEquals(2, queries)
    }

    @Test
    fun `reentrant begin during an initial probe is rejected without replacing the outer attempt`() {
        val request = Any()
        var queries = 0
        var nestedResult: VpnConsentBeginResult<Any>? = null
        lateinit var controller: VpnConsentController<Any>
        controller = VpnConsentController(
            VpnPreparation {
                queries += 1
                if (queries == 1) {
                    nestedResult = controller.begin()
                }
                VpnPreparationResult.ConsentRequired(request)
            },
        )

        val outer = controller.begin() as VpnConsentBeginResult.LaunchRequired

        assertEquals(VpnConsentBeginResult.AlreadyPending, nestedResult)
        assertSame(request, outer.request)
        assertEquals(VpnConsentBeginResult.AlreadyPending, controller.begin())
        assertEquals(1, queries)
    }

    @Test
    fun `reentrant begin during completion cannot leak a newer pending attempt`() {
        val firstRequest = Any()
        val retryRequest = Any()
        var queries = 0
        var nestedResult: VpnConsentBeginResult<Any>? = null
        lateinit var controller: VpnConsentController<Any>
        controller = VpnConsentController(
            VpnPreparation {
                queries += 1
                when (queries) {
                    1 -> VpnPreparationResult.ConsentRequired(firstRequest)
                    2 -> {
                        nestedResult = controller.begin()
                        VpnPreparationResult.Prepared
                    }
                    3 -> VpnPreparationResult.ConsentRequired(retryRequest)
                    else -> error("unexpected query")
                }
            },
        )

        val first = controller.begin() as VpnConsentBeginResult.LaunchRequired
        assertEquals(
            VpnConsentCompletion.Prepared,
            controller.completeActiveResult(first.attempt),
        )
        assertEquals(VpnConsentBeginResult.AlreadyPending, nestedResult)
        assertEquals(2, queries)

        val retry = controller.begin() as VpnConsentBeginResult.LaunchRequired
        assertSame(retryRequest, retry.request)
        assertEquals(3, queries)
    }

    @Test
    fun `concurrent begins admit one exact pending request and perform one query`() {
        val queryEntered = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val queries = AtomicInteger()
        val request = Any()
        val controller = VpnConsentController(
            VpnPreparation {
                queries.incrementAndGet()
                queryEntered.countDown()
                check(releaseQuery.await(2, TimeUnit.SECONDS))
                VpnPreparationResult.ConsentRequired(request)
            },
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<VpnConsentBeginResult<Any>> { controller.begin() }
            assertTrue(queryEntered.await(2, TimeUnit.SECONDS))
            val second = executor.submit<VpnConsentBeginResult<Any>> { controller.begin() }

            releaseQuery.countDown()

            val firstResult = first.get(2, TimeUnit.SECONDS)
                as VpnConsentBeginResult.LaunchRequired
            assertSame(request, firstResult.request)
            assertEquals(
                VpnConsentBeginResult.AlreadyPending,
                second.get(2, TimeUnit.SECONDS),
            )
            assertEquals(1, queries.get())
        } finally {
            releaseQuery.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `controller surface cannot launch start protect publish or expose pending state`() {
        val methodNames = VpnConsentController::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("begin", "completeActiveResult", "abandonPending"),
            methodNames,
        )
        assertFalse(methodNames.any { it.contains("launch", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("start", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("protect", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("publish", ignoreCase = true) })
        assertFalse(methodNames.any { it.startsWith("get") || it.startsWith("is") })
    }
}
