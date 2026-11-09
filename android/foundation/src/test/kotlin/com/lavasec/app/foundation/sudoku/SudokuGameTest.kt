package com.lavasec.app.foundation.sudoku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the ported Sudoku game state: placement/notes/erase semantics, the DERIVED
 * solved/complete flags, and the malformed/foreign persisted-state rejection the durable loader
 * relies on.
 */
class SudokuGameTest {
    /** Shape+range-only fixture (internal constructor), mirroring the iOS `shapeAndRangeValidatedGivens`. */
    private fun emptyGivensBoard(solution: List<Int> = List(81) { 1 }): SudokuPuzzle =
        SudokuPuzzle(List(81) { 0 }, solution)

    private fun givenBoard(vararg givens: Pair<Int, Int>): SudokuPuzzle {
        val board = List(81) { 0 }.toMutableList()
        givens.forEach { (index, digit) -> board[index] = digit }
        return SudokuPuzzle(board, List(81) { 1 })
    }

    @Test
    fun `fresh state starts empty and unsolved`() {
        val state = SudokuGame.fresh(emptyGivensBoard())
        assertEquals(List(81) { 0 }, state.userValues)
        assertTrue(state.notes.all { it.isEmpty() })
        assertFalse(state.isSolved)
        assertFalse(state.isComplete)
    }

    @Test
    fun `place value sets an empty cell and a given wins over it`() {
        val state = SudokuGame.fresh(emptyGivensBoard()).placeValue(7, 0)
        assertEquals(7, state.userValues[0])
        assertEquals(7, state.workingBoard[0])

        val given = SudokuGame.fresh(givenBoard(0 to 5)).placeValue(9, 0)
        assertEquals(0, given.userValues[0])
        assertEquals(5, given.workingBoard[0])
        assertTrue(given.isCellGiven(0))
    }

    @Test
    fun `place value replaces and clears that cell's notes`() {
        val state = SudokuGame.fresh(emptyGivensBoard())
            .toggleNote(2, 1)
            .toggleNote(5, 1)
            .placeValue(6, 1)
            .placeValue(8, 1)
        assertEquals(8, state.userValues[1])
        assertTrue(state.notes[1].isEmpty())
    }

    @Test
    fun `notes toggle only on empty non-given cells`() {
        var state = SudokuGame.fresh(emptyGivensBoard())
        state = state.toggleNote(4, 9)
        assertTrue(state.notes[9].contains(4))
        state = state.toggleNote(4, 9)
        assertFalse(state.notes[9].contains(4))

        val onGiven = SudokuGame.fresh(givenBoard(10 to 2)).toggleNote(7, 10)
        assertTrue(onGiven.notes[10].isEmpty())

        val onFilled = SudokuGame.fresh(emptyGivensBoard()).placeValue(5, 0).toggleNote(3, 0)
        assertTrue(onFilled.notes[0].isEmpty())
    }

    @Test
    fun `clear removes value and notes but never a given`() {
        val cleared = SudokuGame.fresh(emptyGivensBoard())
            .placeValue(4, 5)
            .toggleNote(2, 5)
            .clearCell(5)
        assertEquals(0, cleared.userValues[5])
        assertTrue(cleared.notes[5].isEmpty())
        assertEquals(0, cleared.workingBoard[5])

        val given = SudokuGame.fresh(givenBoard(5 to 4)).clearCell(5)
        assertEquals(4, given.workingBoard[5])
        assertEquals(4, given.puzzle.givens[5])
    }

    @Test
    fun `isSolved is derived and cannot be stored`() {
        val solution = SudokuPuzzle.generate(8).solution
        var state = SudokuGame.fresh(emptyGivensBoard(solution))
        solution.forEachIndexed { index, digit ->
            assertFalse("unsolved before cell $index", state.isSolved)
            state = state.placeValue(digit, index)
        }
        assertTrue(state.isSolved)
        // A stored flag could not be un-set by clearing a cell; the derived board can.
        assertFalse(state.clearCell(0).isSolved)
    }

    @Test
    fun `isComplete distinguishes a full but wrong board`() {
        val solution = SudokuPuzzle.generate(8).solution
        var state = SudokuGame.fresh(emptyGivensBoard(solution))
        assertFalse(state.isComplete)
        for (index in 0 until 80) state = state.placeValue(solution[index], index)
        assertFalse("one empty cell is not complete", state.isComplete)

        val wrongLast = if (solution[80] == 9) 1 else solution[80] + 1
        val wrong = state.placeValue(wrongLast, 80)
        assertTrue(wrong.isComplete)
        assertFalse(wrong.isSolved)
        assertTrue(wrong.placeValue(solution[80], 80).isSolved)
    }

    @Test
    fun `remaining count tallies givens plus user values`() {
        val state = SudokuGame.fresh(givenBoard(0 to 1, 1 to 1)).placeValue(1, 2)
        assertEquals(6, state.remainingCount(1))
        assertEquals(0, state.remainingCount(0))
        assertEquals(0, state.remainingCount(10))
    }

    @Test
    fun `restored round-trips and collapses duplicate note digits`() {
        val puzzle = SudokuPuzzle.generate(2026)
        val empties = puzzle.givens.indices.filter { puzzle.givens[it] == 0 }
        val valueCell = empties.first()
        val noteCell = empties.first { it != valueCell }
        val game = SudokuGame.fresh(puzzle).placeValue(3, valueCell).toggleNote(5, noteCell).toggleNote(8, noteCell)

        val notes = List(81) { emptyList<Int>() }.toMutableList().also { it[noteCell] = listOf(5, 5, 8) }
        val restored = SudokuGame.restored(puzzle, game.userValues, notes)
        assertEquals(game, restored)
        assertEquals(setOf(5, 8), restored!!.notes[noteCell])
        assertEquals(3, restored.userValues[valueCell])
    }

    @Test
    fun `restored rejects truncated or out-of-range persisted state`() {
        val puzzle = SudokuPuzzle.generate(1)
        val fresh = SudokuGame.fresh(puzzle)
        assertEquals(fresh, SudokuGame.restored(puzzle, fresh.userValues, List(81) { emptyList() }))
        assertNull(SudokuGame.restored(puzzle, List(5) { 0 }, List(81) { emptyList() }))
        assertNull(SudokuGame.restored(puzzle, List(81) { 10 }, List(81) { emptyList() }))
        assertNull(SudokuGame.restored(puzzle, fresh.userValues, List(5) { emptyList() }))
        assertNull(SudokuGame.restored(puzzle, fresh.userValues, List(81) { listOf(0) }))
    }
}
