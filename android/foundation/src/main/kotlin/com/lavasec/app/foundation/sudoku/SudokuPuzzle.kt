package com.lavasec.app.foundation.sudoku

/**
 * A generated Sudoku: the puzzle as presented (with empty `0` cells) and its full solution.
 *
 * Ported from the canonical iOS `Sources/LavaSecKit/SudokuPuzzle.swift` (pinned commit
 * `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`). Both arrays are always 81 ints, row-major
 * (`index = row * 9 + col`); [givens] uses `0` for an empty cell, [solution] is fully filled.
 *
 * Generation is deterministic from a seed and only emits a board with a UNIQUE solution, so the
 * player never faces an ambiguous puzzle. Construction goes through [generate] or the validating
 * [restored]; the primary constructor is internal and shape/range-only so tests can build minimal
 * fixtures (mirroring the iOS `shapeAndRangeValidatedGivens` escape hatch) without weakening the
 * public decode path.
 */
@ConsistentCopyVisibility
data class SudokuPuzzle internal constructor(
    val givens: List<Int>,
    val solution: List<Int>,
) {
    init {
        require(givens.size == SUDOKU_CELL_COUNT && solution.size == SUDOKU_CELL_COUNT) {
            "SudokuPuzzle requires 81 cells"
        }
        require(givens.all { it in 0..9 } && solution.all { it in 1..9 }) {
            "SudokuPuzzle givens must be 0...9 and solution must be 1...9"
        }
    }

    /** How many clue cells the player starts with (non-zero givens). */
    val givenCount: Int get() = givens.count { it != 0 }

    /**
     * Counts solutions of this puzzle's givens, short-circuiting at [limit]. Returns `0`
     * (unsolvable), `1` (unique), or `limit` (ambiguous).
     */
    fun solutionCount(limit: Int = 2): Int = countSolutions(givens.toIntArray(), limit)

    companion object {
        /**
         * The deterministic generator: the same seed always produces the same board, and digging
         * guarantees a unique solution. [challenge] digs to the fixed 28-clue challenge target (the
         * practical greedy floor) instead of the sampled difficulty band. The greedy dig commits a
         * removal only while the board stays uniquely solvable, so it reaches 28 where reachable and
         * may leave a few more clues on seeds that plateau above it — the challenge board is never
         * harder than the band floor but is not guaranteed to be strictly below 30 clues.
         */
        fun generate(seed: Long, challenge: Boolean = false): SudokuPuzzle = generateSudokuPuzzle(seed, challenge)

        /**
         * Validates a persisted/wire puzzle and returns it, or `null` when it is malformed. Enforces
         * the same invariants as the iOS decoder: exactly 81 cells, givens `0...9`, solution
         * `1...9`, every non-zero given agreeing with its solution cell, a LEGAL solution grid, and
         * a UNIQUE solution. A payload failing any check is rejected so the fixed 9×9 renderer and
         * solver never index a malformed board.
         */
        fun restored(givens: List<Int>, solution: List<Int>): SudokuPuzzle? {
            if (!shapeAndRangeValid(givens, solution)) return null
            if (!solutionIsPermutationGrid(solution)) return null
            if (countSolutions(givens.toIntArray(), 2) != 1) return null
            return SudokuPuzzle(givens.toList(), solution.toList())
        }

        private fun shapeAndRangeValid(givens: List<Int>, solution: List<Int>): Boolean =
            givens.size == SUDOKU_CELL_COUNT &&
                solution.size == SUDOKU_CELL_COUNT &&
                givens.all { it in 0..9 } &&
                solution.all { it in 1..9 } &&
                givens.indices.all { givens[it] == 0 || givens[it] == solution[it] }

        private fun solutionIsPermutationGrid(board: List<Int>): Boolean {
            if (board.size != SUDOKU_CELL_COUNT) return false
            val full = (1..9).toSet()
            for (unit in 0 until 9) {
                val row = HashSet<Int>()
                val col = HashSet<Int>()
                val box = HashSet<Int>()
                for (offset in 0 until 9) {
                    row.add(board[unit * 9 + offset])
                    col.add(board[offset * 9 + unit])
                    val boxRow = (unit / 3) * 3 + offset / 3
                    val boxCol = (unit % 3) * 3 + offset % 3
                    box.add(board[boxRow * 9 + boxCol])
                }
                if (row != full || col != full || box != full) return false
            }
            return true
        }
    }
}
