package com.lavasec.app.billing

import com.lavasec.app.work.installPlayReconciliationIfConfigured
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativePlusProcessObserverTest {
    @Test fun `close joins already submitted durable work and forbids subsequent foreground enqueue`() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val foreground = MutableStateFlow(false)
        val completion = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val observer = NativePlusProcessObserver(scope, foreground,
            install = {
                installPlayReconciliationIfConfigured(true) {
                    events += "enqueue"
                    completion.await()
                    events += "durable"
                }
                events += "installed"
            }, loadProducts = { events += "products" }, refreshPurchases = { events += "purchases" })
        try {
            runCurrent()
            assertEquals(listOf("enqueue"), events)
            val closing = launch { observer.closeAndJoin(); events += "closed" }
            runCurrent()
            assertFalse("submitted database work must drain before sandbox/process retirement", closing.isCompleted)
            foreground.value = true
            runCurrent()
            assertEquals(listOf("enqueue"), events)
            completion.complete(Unit)
            runCurrent()
            assertTrue(closing.isCompleted)
            assertTrue(job.isCompleted)
            assertEquals("cancelled installation must never report a completed caller result",
                listOf("enqueue", "durable", "closed"), events)
            foreground.value = false
            runCurrent()
            foreground.value = true
            runCurrent()
            assertEquals(listOf("enqueue", "durable", "closed"), events)
        } finally {
            completion.complete(Unit)
            observer.closeAndJoin()
        }
    }

    @Test fun `closing before observation starts never initializes scheduler or store RPCs`() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val observer = NativePlusProcessObserver(scope, MutableStateFlow(true),
            install = { error("closed process must not enqueue") },
            loadProducts = { error("closed process must not load offers") },
            refreshPurchases = { error("closed process must not query receipts") })
        observer.closeAndJoin()
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test fun `ordinary failed enqueue retries boundedly then remains eligible for a later foreground retry`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val foreground = MutableStateFlow(false)
        var healthy = false
        var enqueues = 0
        var durable = 0
        var products = 0
        var receipts = 0
        val observer = NativePlusProcessObserver(scope, foreground,
            install = {
                installPlayReconciliationIfConfigured(true) {
                    enqueues++
                    if (!healthy) throw IOException("durable work unavailable")
                    durable++
                }
            }, loadProducts = { products++ }, refreshPurchases = { receipts++ })
        try {
            runCurrent()
            assertEquals(1, enqueues)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(2, enqueues)
            assertEquals(0, durable)
            healthy = true
            foreground.value = true
            runCurrent()
            assertEquals(3, enqueues)
            assertEquals(1, durable)
            assertEquals(1, products)
            assertEquals(1, receipts)
            observer.closeAndJoin()
            foreground.value = false
            runCurrent()
            foreground.value = true
            runCurrent()
            assertEquals(3, enqueues)
            assertEquals(1, products)
            assertEquals(1, receipts)
        } finally { observer.closeAndJoin() }
    }

    @Test fun `closing cancels and joins a suspended foreground receipt observer`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val foreground = MutableStateFlow(true)
        var observations = 0
        var finished = false
        val observer = NativePlusProcessObserver(scope, foreground, install = {}, loadProducts = {},
            refreshPurchases = {
                observations++
                try { CompletableDeferred<Unit>().await() } finally { finished = true }
            })
        runCurrent()
        assertEquals(1, observations)
        observer.closeAndJoin()
        assertTrue(finished)
        foreground.value = false
        runCurrent()
        foreground.value = true
        runCurrent()
        assertEquals(1, observations)
    }
}
