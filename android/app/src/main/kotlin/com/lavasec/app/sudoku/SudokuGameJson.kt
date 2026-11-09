package com.lavasec.app.sudoku

import com.lavasec.app.foundation.sudoku.SudokuGame
import com.lavasec.app.foundation.sudoku.SudokuPuzzle
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON projection/validation for the Sudoku game.
 *
 * Two shapes are deliberately distinct, matching canon:
 *  - the WIRE/snapshot shape is the JS `SudokuGame` (`{puzzle:{givens,solution}, values, notes}`),
 *    which both the `sudoku.*` command payloads and the `sudoku` snapshot family use; and
 *  - the PERSISTED shape mirrors the iOS `SudokuGameState` `CodingKeys` (`userValues`, not `values`).
 *
 * Decoding routes through the pure validators ([SudokuPuzzle.restored], [SudokuGame.restored]), so a
 * foreign or malformed payload (wrong length, out-of-range cell, given contradicting the solution,
 * illegal/ambiguous board) returns `null` and is discarded rather than partially applied.
 */

/** Canonical wire/snapshot shape (JS `SudokuGame`). */
internal fun SudokuGame.toWireJson(): JSONObject = JSONObject().apply {
    put("puzzle", puzzleJson())
    put("values", JSONArray(userValues))
    put("notes", notesJson())
}

/** Canonical persisted shape (iOS `SudokuGameState` Codable keys). */
internal fun SudokuGame.toPersistedJson(): JSONObject = JSONObject().apply {
    put("puzzle", puzzleJson())
    put("userValues", JSONArray(userValues))
    put("notes", notesJson())
}

private fun SudokuGame.puzzleJson(): JSONObject = JSONObject().apply {
    put("givens", JSONArray(puzzle.givens))
    put("solution", JSONArray(puzzle.solution))
}

private fun SudokuGame.notesJson(): JSONArray = JSONArray().apply {
    notes.forEach { cell -> put(JSONArray(cell.sorted())) }
}

/** Decodes the persisted record, or returns `null` for a missing/corrupt/foreign payload. */
internal fun decodePersistedSudokuGame(raw: String?): SudokuGame? {
    if (raw.isNullOrEmpty()) return null
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    return decodeSudokuGame(json, "userValues")
}

/** Decodes the wire/snapshot shape, returning `null` on any malformed field. */
internal fun decodeWireSudokuGame(json: JSONObject?): SudokuGame? {
    if (json == null) return null
    return decodeSudokuGame(json, "values")
}

private fun decodeSudokuGame(json: JSONObject, valuesKey: String): SudokuGame? {
    val puzzleJson = json.optJSONObject("puzzle") ?: return null
    val givens = puzzleJson.intList("givens") ?: return null
    val solution = puzzleJson.intList("solution") ?: return null
    val values = json.intList(valuesKey) ?: return null
    val notes = json.intListList("notes") ?: return null
    val puzzle = SudokuPuzzle.restored(givens, solution) ?: return null
    return SudokuGame.restored(puzzle, values, notes)
}

/** Exact integers only: a JSON cell like `1.5` is malformed, never silently truncated. */
internal fun JSONObject.intList(key: String): List<Int>? {
    val array = optJSONArray(key) ?: return null
    val result = ArrayList<Int>(array.length())
    for (index in 0 until array.length()) {
        val value = array.opt(index)
        if (value !is Number) return null
        val long = value.toLong()
        if (value.toDouble() != long.toDouble()) return null
        result.add(long.toInt())
    }
    return result
}

internal fun JSONObject.intListList(key: String): List<List<Int>>? {
    val array = optJSONArray(key) ?: return null
    val result = ArrayList<List<Int>>(array.length())
    for (index in 0 until array.length()) {
        val cell = array.optJSONArray(index) ?: return null
        val values = ArrayList<Int>(cell.length())
        for (cellIndex in 0 until cell.length()) {
            val value = cell.opt(cellIndex)
            if (value !is Number) return null
            val long = value.toLong()
            if (value.toDouble() != long.toDouble()) return null
            values.add(long.toInt())
        }
        result.add(values)
    }
    return result
}
