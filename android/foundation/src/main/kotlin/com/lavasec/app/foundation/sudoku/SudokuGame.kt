package com.lavasec.app.foundation.sudoku

/**
 * The resumable progress for one Sudoku round: the underlying [puzzle] plus the player's mutable
 * entries, candidate notes, and a derived solved flag.
 *
 * Ported from the canonical iOS `Sources/LavaSecKit/SudokuGameState.swift` (pinned commit
 * `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`). [puzzle.givens] carries the locked clue cells;
 * [userValues] carries the player's entries in the empty positions (a non-zero `userValues[i]` is
 * only ever allowed where `givens[i] == 0`). The working board — what the UI renders and the solve
 * check evaluates — merges the two: a given overrides an empty cell, otherwise the user value.
 *
 * Immutable value type: every mutator returns a new instance, so a game is a durable snapshot and
 * [isSolved] can never disagree with [userValues].
 */
@ConsistentCopyVisibility
data class SudokuGame internal constructor(
    val puzzle: SudokuPuzzle,
    val userValues: List<Int>,
    val notes: List<Set<Int>>,
) {
    init {
        require(userValues.size == SUDOKU_CELL_COUNT && notes.size == SUDOKU_CELL_COUNT) {
            "SudokuGame requires 81 user values and 81 note sets"
        }
    }

    /** The wire/snapshot name for [userValues] (the canonical JS `SudokuGame.values`). */
    val values: List<Int> get() = userValues

    /**
     * The merged, player-facing board: a given where present, else the user value, else `0`.
     * Indexed row-major; used for rendering and the solve check.
     */
    val workingBoard: List<Int>
        get() = List(SUDOKU_CELL_COUNT) { index ->
            if (puzzle.givens[index] != 0) puzzle.givens[index] else userValues[index]
        }

    /**
     * `true` exactly when the working board is fully and correctly filled. DERIVED, never stored, so
     * it can never disagree with [userValues] or be forged.
     */
    val isSolved: Boolean get() = workingBoard == puzzle.solution

    /**
     * `true` exactly when every cell of the working board holds a value. Distinct from [isSolved]:
     * a fully filled board may still be wrong.
     */
    val isComplete: Boolean get() = workingBoard.none { it == 0 }

    /** `true` if [index] is a clue cell (a given) and therefore not editable by the player. */
    fun isCellGiven(index: Int): Boolean = puzzle.givens[index] != 0

    /**
     * Places [digit] at [index] (replace-on-tap), clearing that cell's notes. Given cells are
     * immutable and out-of-range input is ignored, so the call is an honest no-op returning `this`.
     */
    fun placeValue(digit: Int, index: Int): SudokuGame {
        if (index !in 0 until SUDOKU_CELL_COUNT || isCellGiven(index) || digit !in 1..9) return this
        val values = userValues.toMutableList().also { it[index] = digit }
        val cellNotes = notes.toMutableList().also { it[index] = emptySet() }
        return copy(userValues = values, notes = cellNotes)
    }

    /**
     * Toggles [digit] as a candidate note at [index]. Given cells and cells holding a placed value
     * are not note-editable; out-of-range input is ignored.
     */
    fun toggleNote(digit: Int, index: Int): SudokuGame {
        if (index !in 0 until SUDOKU_CELL_COUNT || isCellGiven(index)) return this
        if (userValues[index] != 0 || digit !in 1..9) return this
        val updated = notes[index].toMutableSet().also { if (!it.add(digit)) it.remove(digit) }
        return copy(notes = notes.toMutableList().also { it[index] = updated })
    }

    /** Erases the value and all candidate notes at [index]; given cells are immutable. */
    fun clearCell(index: Int): SudokuGame {
        if (index !in 0 until SUDOKU_CELL_COUNT || isCellGiven(index)) return this
        val values = userValues.toMutableList().also { it[index] = 0 }
        val cellNotes = notes.toMutableList().also { it[index] = emptySet() }
        return copy(userValues = values, notes = cellNotes)
    }

    /**
     * How many more of [digit] (1...9) the player may place: nine minus the given and user-entered
     * occurrences already on the working board.
     */
    fun remainingCount(digit: Int): Int {
        if (digit !in 1..9) return 0
        val placed = workingBoard.count { it == digit }
        return (9 - placed).coerceAtLeast(0)
    }

    companion object {
        /** A fresh, empty-progress game for [puzzle]. */
        fun fresh(puzzle: SudokuPuzzle): SudokuGame =
            SudokuGame(puzzle, List(SUDOKU_CELL_COUNT) { 0 }, List(SUDOKU_CELL_COUNT) { emptySet() })

        /**
         * Validates persisted/wire progress and returns it, or `null` when it is malformed.
         * Mirroring the iOS decoder: [userValues] must be 81 cells of `0...9` and [notes] 81 sets
         * drawn from `1...9`. Duplicate note digits collapse to a set, exactly as `Set<Int>` decoding
         * would. A corrupt payload is rejected (the loader discards it), never partially applied.
         */
        fun restored(puzzle: SudokuPuzzle, userValues: List<Int>, notes: List<List<Int>>): SudokuGame? {
            if (userValues.size != SUDOKU_CELL_COUNT || userValues.any { it !in 0..9 }) return null
            if (notes.size != SUDOKU_CELL_COUNT) return null
            if (notes.any { cell -> cell.any { it !in 1..9 } }) return null
            return SudokuGame(puzzle, userValues.toList(), notes.map { it.toSet() })
        }
    }
}
