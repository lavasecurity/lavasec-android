package com.lavasec.app.foundation.sudoku

import kotlin.math.roundToInt

/**
 * Deterministic generation and solving for the Sudoku easter egg.
 *
 * Ported from the canonical iOS engine (`Sources/LavaSecKit/SudokuPuzzle.swift`, pinned commit
 * `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`) so the same seed reproduces the exact board on both
 * platforms. The reproduction is byte-for-byte: [SudokuPuzzle.generate] for seed 648 yields the
 * `referencePuzzle` fixture the iOS React Native review host ships, which `SudokuPuzzleTest` pins.
 *
 * Matching Swift requires reproducing two stdlib behaviors exactly:
 *  - `SplitMix64` — the same single-value state PRNG seedable puzzles rely on.
 *  - `Array.shuffled(using:)` — Swift's forward Fisher-Yates (`0..<amount`, advancing the first
 *    index) driven by `Int.random(in:using:)`, which since Swift 6 uses Lemire's nearly
 *    divisionless bounded-integer method rather than rejection-plus-modulo. Both are reproduced
 *    here; a plain `kotlin.random.Random` shuffle would diverge on the first draw.
 *
 * This layer is pure Kotlin — no Android or JSON types — so it is exercised by plain JVM tests.
 */

/** Number of cells on the board (9×9). */
internal const val SUDOKU_CELL_COUNT = 81

/**
 * Difficulty band for the greedy dig. The target clue count is drawn per seed from a TRIANGULAR
 * distribution over `[MIN_TARGET_GIVEN_COUNT, MAX_TARGET_GIVEN_COUNT]` with the mode at the
 * midpoint: mostly medium puzzles, with the easier and harder ends progressively rarer.
 *
 * 28 is the practical floor for random digging — below it the dig plateaus and returns a higher
 * count anyway — and far above the 17-clue theoretical minimum for a unique Sudoku; ~40 is the
 * comfortable-medium upper end the product locked. Mirrors the iOS `SudokuGeneration` exactly so
 * the same seed reproduces the same board on both platforms.
 */
private const val MIN_TARGET_GIVEN_COUNT = 28
private const val MAX_TARGET_GIVEN_COUNT = 40

/**
 * The clue count the hidden challenge mode digs to: the fixed 28-clue target, the same value the
 * pinned iOS engine uses (`challengeGivenCount = 28`, `SudokuGeneration.swift` @ c1fdb8e5) and the
 * most aggressive target the greedy dig can reach (it is also the band floor above). The dig
 * commits a removal only while the board stays uniquely solvable, so a challenge board reaches 28
 * where reachable and may leave a few more clues on seeds that plateau above it — it is not
 * guaranteed to be strictly below 30 clues for every seed. Diverging the dig or target would break
 * the same-seed board contract with iOS.
 */
private const val CHALLENGE_TARGET_GIVEN_COUNT = 28

/** Which 3×3 box each board index belongs to, row-major. */
internal val SUDOKU_BOX_OF: IntArray = IntArray(SUDOKU_CELL_COUNT) { index ->
    (index / 9 / 3) * 3 + (index % 9 / 3)
}

/**
 * A small deterministic PRNG. Mirrors the iOS `SplitMix64` exactly: `next()` is the documented
 * mix of the incrementally advanced state, with wrapping 64-bit arithmetic.
 */
internal class SplitMix64(seed: Long) {
    private var state: ULong = seed.toULong()

    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /**
     * A uniform value in `0 until bound`, implementing Swift's `next(upperBound:)` (Lemire's
     * method) so bounded draws consume the same [next] sequence the iOS generator does.
     */
    fun nextUpper(bound: Int): Int {
        require(bound > 0) { "upperBound cannot be zero" }
        val upperBound = bound.toULong()
        var random = next()
        var product = random * upperBound
        var high = multiplyHigh(random, upperBound)
        if (product < upperBound) {
            val threshold = (0uL - upperBound) % upperBound
            while (product < threshold) {
                random = next()
                product = random * upperBound
                high = multiplyHigh(random, upperBound)
            }
        }
        return high.toInt()
    }

    /**
     * A uniform [Double] in `[0, 1)` drawn from the top 53 bits of one [next], mirroring Swift's
     * `Double` draw. Consumed only when sampling the difficulty target below.
     */
    fun nextUnitInterval(): Double = (next() shr 11).toDouble() * (1.0 / 9007199254740992.0)
}

/** The high 64 bits of the unsigned 128-bit product `a * b` (no `UInt128` in Kotlin). */
private fun multiplyHigh(a: ULong, b: ULong): ULong {
    val aLow = a and 0xFFFFFFFFuL
    val aHigh = a shr 32
    val bLow = b and 0xFFFFFFFFuL
    val bHigh = b shr 32
    val p00 = aLow * bLow
    val p01 = aLow * bHigh
    val p10 = aHigh * bLow
    val p11 = aHigh * bHigh
    val middle = (p00 shr 32) + (p01 and 0xFFFFFFFFuL) + (p10 and 0xFFFFFFFFuL)
    return p11 + (p01 shr 32) + (p10 shr 32) + (middle shr 32)
}

/**
 * Swift's `Array.shuffled(using:)` exactly: advance a forward cursor while shrinking the bound,
 * swapping the cursor with a bounded draw. This is deliberately NOT `Collections.shuffle`.
 */
private fun shuffled(values: List<Int>, rng: SplitMix64): List<Int> {
    val result = values.toMutableList()
    if (result.size <= 1) return result
    var amount = result.size
    var current = 0
    while (amount > 1) {
        val random = rng.nextUpper(amount)
        amount -= 1
        val swapIndex = current + random
        val swap = result[current]
        result[current] = result[swapIndex]
        result[swapIndex] = swap
        current += 1
    }
    return result
}

/**
 * Builds a full valid solved board. The three diagonal boxes are seeded independently (no shared
 * row/column), then the rest is backtracked with a randomized candidate order.
 */
private fun generateFullSolution(rng: SplitMix64): IntArray {
    val board = IntArray(SUDOKU_CELL_COUNT)
    for (box in 0 until 3) {
        val baseRow = box * 3
        val baseCol = box * 3
        val digits = shuffled((1..9).toList(), rng).toMutableList()
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                board[(baseRow + row) * 9 + (baseCol + col)] = digits.removeAt(digits.lastIndex)
            }
        }
    }
    check(fillFirstSolution(board, rng)) { "Sudoku generation must complete a full solution" }
    return board
}

private fun fillFirstSolution(board: IntArray, rng: SplitMix64): Boolean {
    val cell = board.indexOfFirst { it == 0 }
    if (cell < 0) return true
    for (digit in shuffled((1..9).toList(), rng)) {
        if (!canPlace(board, digit, cell)) continue
        board[cell] = digit
        if (fillFirstSolution(board, rng)) return true
        board[cell] = 0
    }
    return false
}

private fun canPlace(board: IntArray, digit: Int, cell: Int): Boolean {
    val row = cell / 9
    val col = cell % 9
    for (i in 0 until 9) {
        if (board[row * 9 + i] == digit) return false
        if (board[i * 9 + col] == digit) return false
    }
    val box = SUDOKU_BOX_OF[cell]
    val baseRow = (box / 3) * 3
    val baseCol = (box % 3) * 3
    for (r in 0 until 3) {
        for (c in 0 until 3) {
            if (board[(baseRow + r) * 9 + (baseCol + c)] == digit) return false
        }
    }
    return true
}

/**
 * Removes values until [targetGivens] clues remain, committing a removal only while the board
 * stays uniquely solvable. Cells are dug in shuffled order; a plateau leaves a few extra clues,
 * so the result is always uniquely solvable and has at least [targetGivens] clues.
 */
private fun digHoles(solution: IntArray, targetGivens: Int, rng: SplitMix64): IntArray {
    val givens = solution.copyOf()
    var remaining = givens.count { it != 0 }
    for (index in shuffled((0 until SUDOKU_CELL_COUNT).toList(), rng)) {
        if (remaining <= targetGivens) break
        if (givens[index] == 0) continue
        val removed = givens[index]
        givens[index] = 0
        if (countSolutions(givens, 2) <= 1) {
            remaining -= 1
        } else {
            givens[index] = removed
        }
    }
    return givens
}

/**
 * Draws the per-seed target clue count from the triangular distribution described on
 * [MIN_TARGET_GIVEN_COUNT]. Sampling as the average of two independent uniform draws is the standard
 * sum-of-two-uniforms triangular (mode at the midpoint, tapering linearly to each end); the two
 * draws and the rounded arithmetic match the iOS implementation exactly.
 */
private fun targetGivenCount(rng: SplitMix64): Int {
    val lower = MIN_TARGET_GIVEN_COUNT.toDouble()
    val upper = MAX_TARGET_GIVEN_COUNT.toDouble()
    val first = rng.nextUnitInterval()
    val second = rng.nextUnitInterval()
    return (lower + (upper - lower) * (first + second) / 2.0).roundToInt()
}

/** @see SudokuPuzzle.generate */
internal fun generateSudokuPuzzle(seed: Long, challenge: Boolean = false): SudokuPuzzle {
    val rng = SplitMix64(seed)
    val solution = generateFullSolution(rng)
    val target = if (challenge) CHALLENGE_TARGET_GIVEN_COUNT else targetGivenCount(rng)
    val givens = digHoles(solution, target, rng)
    return SudokuPuzzle(givens.toList(), solution.toList())
}

/**
 * Counts solutions of [givens] up to [limit], choosing the minimum-remaining-values cell at each
 * step (fast pruning, short-circuits at the limit). Conflicting givens are rejected up front —
 * without that guard the bitmask solver would collapse a duplicate into one bit and misreport a
 * fully filled conflicting board as uniquely solved.
 */
internal fun countSolutions(givens: IntArray, limit: Int): Int {
    if (hasConflictingGivens(givens)) return 0
    val board = givens.copyOf()
    val rows = IntArray(9)
    val cols = IntArray(9)
    val boxes = IntArray(9)
    for (index in board.indices) {
        val value = board[index]
        if (value == 0) continue
        val bit = 1 shl value
        rows[index / 9] = rows[index / 9] or bit
        cols[index % 9] = cols[index % 9] or bit
        boxes[SUDOKU_BOX_OF[index]] = boxes[SUDOKU_BOX_OF[index]] or bit
    }
    return countSolutionsFrom(board, rows, cols, boxes, limit)
}

private fun hasConflictingGivens(board: IntArray): Boolean {
    val seen = IntArray(27)
    for (index in board.indices) {
        val digit = board[index]
        if (digit == 0) continue
        val row = index / 9
        val col = index % 9
        val box = SUDOKU_BOX_OF[index]
        val bit = 1 shl digit
        if ((seen[row] and bit) != 0 || (seen[9 + col] and bit) != 0 || (seen[18 + box] and bit) != 0) {
            return true
        }
        seen[row] = seen[row] or bit
        seen[9 + col] = seen[9 + col] or bit
        seen[18 + box] = seen[18 + box] or bit
    }
    return false
}

private fun countSolutionsFrom(
    board: IntArray,
    rows: IntArray,
    cols: IntArray,
    boxes: IntArray,
    limit: Int,
): Int {
    val cell = fewestCandidatesCell(board, rows, cols, boxes) ?: return 1
    val row = cell / 9
    val col = cell % 9
    val box = SUDOKU_BOX_OF[cell]
    val used = rows[row] or cols[col] or boxes[box]
    var total = 0
    for (digit in 1..9) {
        if ((used and (1 shl digit)) != 0) continue
        val bit = 1 shl digit
        board[cell] = digit
        rows[row] = rows[row] or bit
        cols[col] = cols[col] or bit
        boxes[box] = boxes[box] or bit
        total += countSolutionsFrom(board, rows, cols, boxes, limit - total)
        board[cell] = 0
        rows[row] = rows[row] and bit.inv()
        cols[col] = cols[col] and bit.inv()
        boxes[box] = boxes[box] and bit.inv()
        if (total >= limit) return total
    }
    return total
}

private fun fewestCandidatesCell(board: IntArray, rows: IntArray, cols: IntArray, boxes: IntArray): Int? {
    var best: Int? = null
    var bestCount = Int.MAX_VALUE
    for (cell in board.indices) {
        if (board[cell] != 0) continue
        val used = rows[cell / 9] or cols[cell % 9] or boxes[SUDOKU_BOX_OF[cell]]
        var candidates = 0
        for (digit in 1..9) {
            if ((used and (1 shl digit)) == 0) candidates += 1
        }
        if (candidates < bestCount) {
            bestCount = candidates
            best = cell
            if (candidates == 0) break
        }
    }
    return best
}
