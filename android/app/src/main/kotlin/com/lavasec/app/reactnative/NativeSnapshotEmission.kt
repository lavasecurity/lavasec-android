package com.lavasec.app.reactnative

import org.json.JSONObject

/** Observer refreshes can repeat the same facts from several owners in one service transition.
 * Skip only an identical full snapshot (apart from its publication counter). Command receipts
 * still emit their exact frame. No timer, delayed revocation, or selected-field approximation. */
internal class NativeSnapshotEmission {
    private var previous: String? = null

    @Synchronized fun emit(snapshot: String, commandReceipt: Boolean = false, deliver: (String) -> Unit): Boolean {
        val facts = JSONObject(snapshot).apply { remove("revision") }.toString()
        if (!commandReceipt && previous == facts) return false
        deliver(snapshot)
        previous = facts
        return true
    }
}
