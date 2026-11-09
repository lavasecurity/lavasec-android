package com.lavasec.app.reactnative

import kotlinx.coroutines.delay

/** Fabric may commit ready/layout after the JS command arrives. Wait only for this export owner. */
internal suspend fun <T : Any> awaitMountedShareCard(
    current: () -> Boolean,
    capture: () -> T?,
    nextFrame: suspend () -> Unit = { delay(16) },
): T? {
    repeat(21) { attempt ->
        if (!current()) return null
        capture()?.let { return it }
        if (attempt < 20) nextFrame()
    }
    return null
}
