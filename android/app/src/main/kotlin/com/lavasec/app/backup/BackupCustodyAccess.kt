package com.lavasec.app.backup

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process boundary for envelope, account binding and retained-key custody. */
private val custodyLocks = ConcurrentHashMap<Any, Mutex>()

internal suspend fun <T> BackupLocalStore.withCustodyLock(block: suspend () -> T): T =
    custodyLocks.computeIfAbsent(custodyLockIdentity) { Mutex() }.withLock { block() }
