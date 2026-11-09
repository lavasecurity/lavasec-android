package com.lavasec.app.reactnative

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.util.UUID

/** User-requested copy only. Neither the phrase nor a clipboard payload crosses React. */
internal object NativeRecoveryClipboard {
    internal const val RETENTION_MS = 600_000L
    private const val OWNER = "com.lavasec.recovery.copy"
    fun copy(context: Context, phrase: String) {
        val clipboard = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val nonce = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(context.getString(com.lavasec.app.R.string.backup_restore_hint), phrase)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
            putString(OWNER, nonce)
        }
        clipboard.setPrimaryClip(clip)
        // Best effort while this process lives. Android owns clipboard access/retention; it has
        // no iOS localOnly/expiration contract. Never clear a subsequently copied item.
        Handler(Looper.getMainLooper()).postDelayed({
            if (clipboard.primaryClipDescription?.extras?.getString(OWNER) == nonce) clipboard.clearPrimaryClip()
        }, RETENTION_MS)
    }
}
