package com.lavasec.app.foundation.sudoku

/**
 * Durable storage for the resumable Sudoku game, mirroring iOS's Lava Guard persistence.
 *
 * Deliberately a narrow port owned by this slice: implementations live beside the platform, not in
 * any configuration/DataStore repository. [load] must return `null` (not throw) for a missing or
 * corrupt record, so a foreign or malformed payload is discarded and a fresh puzzle generated
 * rather than surfaced to the UI.
 */
interface SudokuGameStore {
    fun load(): SudokuGame?

    fun save(game: SudokuGame)

    fun clear()
}
