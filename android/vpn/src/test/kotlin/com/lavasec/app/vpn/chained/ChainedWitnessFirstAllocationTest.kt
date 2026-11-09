package com.lavasec.app.vpn.chained

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The witness-before-allocation ordering: a low-memory kill during the ~50 MB engine allocation
 * must be attributable to chaining, so the durable witness is written first and a failed write
 * refuses to allocate at all.
 */
class ChainedWitnessFirstAllocationTest {

    @Test
    fun `the witness is persisted before the engine is allocated`() {
        val order = mutableListOf<String>()

        val allocated = ChainedWitnessFirstAllocation.run(
            markWitness = {
                order += "witness"
                true
            },
            retireWitness = { order += "retire" },
            allocate = {
                order += "allocate"
                "engine"
            },
        )

        assertEquals("engine", allocated)
        assertEquals(listOf("witness", "allocate"), order)
    }

    @Test
    fun `a failed witness write refuses to allocate the engine`() {
        val order = mutableListOf<String>()

        val allocated = ChainedWitnessFirstAllocation.run(
            markWitness = {
                order += "witness"
                false
            },
            retireWitness = { order += "retire" },
            allocate = {
                order += "allocate"
                "engine"
            },
        )

        assertNull(allocated)
        assertEquals(listOf("witness"), order)
    }

    @Test
    fun `a failed allocation retires the witness before rethrowing`() {
        val order = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            ChainedWitnessFirstAllocation.run(
                markWitness = {
                    order += "witness"
                    true
                },
                retireWitness = { order += "retire" },
                allocate = {
                    order += "allocate"
                    error("engine refused")
                },
            )
        }

        assertEquals(listOf("witness", "allocate", "retire"), order)
    }

    @Test
    fun `the witness and keys are retired only after every started build returns`() = runBlocking {
        val order = mutableListOf<String>()

        ChainedBuildSettlement.run(
            awaitBuilds = { order += "builds-settled" },
            retireWitness = { order += "retire-witness" },
            releaseSecrets = { order += "zeroize-keys" },
        )

        // A kill during the build's remaining work must still be attributed, so the witness stays
        // until the build returns; the keys must not be zeroized under a live allocation either.
        assertEquals(listOf("builds-settled", "retire-witness", "zeroize-keys"), order)
    }

    @Test
    fun `a failed wait still retires the witness and releases the keys`() = runBlocking {
        val order = mutableListOf<String>()

        ChainedBuildSettlement.run(
            awaitBuilds = { error("build scope join failed") },
            retireWitness = { order += "retire-witness" },
            releaseSecrets = { order += "zeroize-keys" },
        )

        // The key material must be released on every path; the witness retirement is best-effort.
        assertEquals(listOf("retire-witness", "zeroize-keys"), order)
    }
}
