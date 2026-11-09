package com.lavasec.app.foundation.sudoku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM behavioral tests for the ported Sudoku generator/solver.
 *
 * The headline assertion pins the canonical cross-platform fixture: `SudokuPuzzle.generate(648)`
 * must reproduce the exact board the iOS engine/React Native review host ship. That is only true if
 * `SplitMix64`, Swift's bounded draw (Lemire), and its forward Fisher-Yates shuffle are all faithful
 * — so this single test guards the whole determinism contract.
 */
class SudokuPuzzleTest {
    // Pinned iOS `ReactNative/review/sudoku-model.ts` reference (native seed-648 fixture).
    private val referenceGivens =
        "032000601090602030000500000360800000910200080070000096020090070100020900450703800".map { it.digitToInt() }
    private val referenceSolution =
        "532987641791642538846531729364819257915276384278354196623198475187425963459763812".map { it.digitToInt() }

    private fun units(): List<List<Int>> {
        val rows = (0 until 9).map { row -> (0 until 9).map { col -> row * 9 + col } }
        val cols = (0 until 9).map { col -> (0 until 9).map { row -> row * 9 + col } }
        val boxes = (0 until 9).map { box ->
            val baseRow = (box / 3) * 3
            val baseCol = (box % 3) * 3
            (0 until 3).flatMap { r -> (0 until 3).map { c -> (baseRow + r) * 9 + (baseCol + c) } }
        }
        return rows + cols + boxes
    }

    @Test
    fun `seed 648 reproduces the pinned canonical reference board`() {
        val puzzle = SudokuPuzzle.generate(648)
        assertEquals(referenceGivens, puzzle.givens)
        assertEquals(referenceSolution, puzzle.solution)
    }

    @Test
    fun `generated board is a valid solved grid`() {
        val solution = SudokuPuzzle.generate(1).solution
        assertEquals(81, solution.size)
        assertTrue(solution.all { it in 1..9 })
        for (unit in units()) {
            assertEquals(9, unit.map { solution[it] }.toSet().size)
            assertEquals(45, unit.sumOf { solution[it] })
        }
    }

    @Test
    fun `generated givens agree with their solution cells`() {
        val puzzle = SudokuPuzzle.generate(42)
        puzzle.givens.indices.filter { puzzle.givens[it] != 0 }.forEach { index ->
            assertEquals(puzzle.givens[index], puzzle.solution[index])
        }
    }

    @Test
    fun `generated puzzle has a unique solution`() {
        listOf(1L, 7L, 99L, 1000L, 99_999L).forEach { seed ->
            assertEquals("seed $seed", 1, SudokuPuzzle.generate(seed).solutionCount(limit = 2))
        }
    }

    @Test
    fun `generation is deterministic for the same seed`() {
        assertEquals(SudokuPuzzle.generate(1234), SudokuPuzzle.generate(1234))
    }

    @Test
    fun `different seeds produce different boards`() {
        assertNotEquals(SudokuPuzzle.generate(1).givens, SudokuPuzzle.generate(2).givens)
    }

    @Test
    fun `given count stays in the difficulty band`() {
        // The target is sampled per seed from [28, 40]; digging stops the moment the target is
        // reached, so the count is at least the sampled target and, if a plateau stops the dig
        // early, a few clues more. No count can fall below the band's floor of 28.
        listOf(3L, 500L, 50_000L).forEach { seed ->
            val count = SudokuPuzzle.generate(seed).givenCount
            assertTrue("seed $seed had $count", count in 28..50)
        }
    }

    @Test
    fun `difficulty target varies across seeds`() {
        // The per-seed triangular draw must actually vary the clue count; a constant target would
        // defeat the feature. Across a spread of seeds the observed counts span most of the band.
        val counts = (1L..100L).map { SudokuPuzzle.generate(it).givenCount }.toSet()
        assertTrue("difficulty must vary across seeds; got ${counts.sorted()}", counts.size > 4)
        assertTrue("counts must stay in band; got ${counts.sorted()}", counts.min() >= 28 && counts.max() <= 40)
    }

    @Test
    fun `challenge mode digs to the fixed target, stays unique, and differs from the band`() {
        // The challenge contract is the fixed 28-clue greedy target (the band floor), not a
        // universal "<30" guarantee: the single greedy pass commits a removal only while the
        // board stays uniquely solvable, so it reaches 28 where reachable and may leave a few
        // more clues on seeds that plateau above it. The pinned seeds below do reach the floor,
        // so their <30 observation is seed-specific, not the generator's promise.
        listOf(1L, 7L, 42L, 1234L).forEach { seed ->
            val puzzle = SudokuPuzzle.generate(seed, challenge = true)
            assertTrue("seed $seed had ${puzzle.givenCount}", puzzle.givenCount >= 28)
            assertTrue("seed $seed had ${puzzle.givenCount}", puzzle.givenCount < 30)
            assertEquals("seed $seed", 1, puzzle.solutionCount(limit = 2))
            assertNotEquals("seed $seed", SudokuPuzzle.generate(seed).givens, puzzle.givens)
        }
    }

    @Test
    fun `empty board reports multiple solutions and a row duplicate is unsolvable`() {
        val empty = SudokuPuzzle(
            List(81) { 0 },
            List(81) { 1 },
        )
        assertTrue(empty.solutionCount(limit = 2) >= 2)

        val duplicated = List(81) { 0 }.toMutableList().also { it[0] = 5; it[1] = 5 }
        assertEquals(0, SudokuPuzzle(duplicated, List(81) { 1 }).solutionCount(limit = 2))
    }

    @Test
    fun `fully filled conflicting board is rejected by the pre-search guard`() {
        val generated = SudokuPuzzle.generate(5)
        val conflicting = generated.solution.toMutableList()
        conflicting[1] = conflicting[0]
        assertEquals(0, SudokuPuzzle(conflicting, generated.solution).solutionCount(limit = 2))
    }

    @Test
    fun `restored rejects every malformed persisted puzzle`() {
        val valid = SudokuPuzzle.generate(3)
        assertNotNull(SudokuPuzzle.restored(valid.givens, valid.solution))

        // Truncated / out-of-range lengths and ranges.
        assertNull(SudokuPuzzle.restored(listOf(1, 2, 3, 4, 5), valid.solution))
        val badGiven = valid.givens.toMutableList().also { it[0] = 10 }
        assertNull(SudokuPuzzle.restored(badGiven, valid.solution))
        val badSolution = valid.solution.toMutableList().also { it[0] = 10 }
        assertNull(SudokuPuzzle.restored(valid.givens, badSolution))

        // A non-zero given contradicting its solution cell.
        val givenIndex = valid.givens.indexOfFirst { it != 0 }
        val disagreeing = valid.solution.toMutableList().also { it[givenIndex] = (it[givenIndex] % 9) + 1 }
        assertNull(SudokuPuzzle.restored(valid.givens, disagreeing))

        // A legal-looking but ambiguous all-empty board must be rejected (unique-solution rule).
        assertNull(SudokuPuzzle.restored(List(81) { 0 }, valid.solution))

        // A solution that is in-range but not a legal grid (duplicated digit in a row).
        val illegal = valid.solution.toMutableList()
        val emptyColumn = valid.givens.indices.first { valid.givens[it] == 0 && it % 9 != 0 }
        illegal[emptyColumn] = illegal[(emptyColumn / 9) * 9]
        assertNull(SudokuPuzzle.restored(valid.givens, illegal))
    }

    @Test
    fun `internal shape-only constructor still validates length and range`() {
        // The test escape hatch enforces shape/range, so a fixture can never build a board the 9x9
        // renderer would index past 80.
        assertThrows(IllegalArgumentException::class.java) {
            SudokuPuzzle(List(5) { 0 }, List(81) { 1 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            SudokuPuzzle(List(81) { 0 }, List(81) { 0 })
        }
    }

    private fun assertThrows(expected: Class<out Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            if (expected.isInstance(throwable)) return
            throw throwable
        }
        throw AssertionError("Expected ${expected.simpleName} but nothing was thrown")
    }
}
