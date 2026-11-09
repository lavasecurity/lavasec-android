package com.lavasec.app.reactnative

import java.util.concurrent.atomic.AtomicReference

/** Main publishes the actual window; IO publication guards read its atomic witness. */
internal class NativeVisitHostRegistry {
    private val current = AtomicReference<NativeVisitHost?>(null)

    fun current(): NativeVisitHost? = current.get()

    fun publish(host: NativeVisitHost): Boolean {
        if (!host.retains(null)) return false
        current.set(host)
        return true
    }

    /** A late A teardown must never clear a resumed B or borrow its matching-looking ID. */
    fun retire(owner: Any, nativeHostID: String): Boolean {
        while (true) {
            val sampled = current.get() ?: return false
            if (sampled.owner !== owner || sampled.nativeHostID != nativeHostID) return false
            if (current.compareAndSet(sampled, null)) return true
        }
    }
}
