package com.lavasec.app.sudoku

import android.content.Context
import android.content.SharedPreferences
import com.lavasec.app.foundation.sudoku.SudokuGame
import com.lavasec.app.foundation.sudoku.SudokuGameStore

/**
 * A small `SharedPreferences`-backed [SudokuGameStore]. Owned by this slice and kept out of the
 * config/DataStore repositories; [load] discards a corrupt record via the validating decoder.
 */
class SharedPreferencesSudokuGameStore(context: Context) : SudokuGameStore {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): SudokuGame? =
        decodePersistedSudokuGame(preferences.getString(KEY_GAME, null))

    override fun save(game: SudokuGame) {
        val committed = preferences.edit()
            .putString(KEY_GAME, game.toPersistedJson().toString())
            .commit()
        check(committed) { "Sudoku game publication unavailable" }
    }

    override fun clear() {
        val committed = preferences.edit().remove(KEY_GAME).commit()
        check(committed) { "Sudoku game removal unavailable" }
    }

    private companion object {
        const val PREFERENCES_NAME = "lavasec_sudoku"
        const val KEY_GAME = "game"
    }
}
