package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.TunWriteFailureReason
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

internal class TunSessionWriter(
    private val stateLock: ReentrantLock,
    private val scheduler: TunQueryScheduler,
    private val packetSession: TunPacketSession,
    private val observer: TunRuntimeObserver,
    private val sealRuntime: (DnsHealthEvent.TunWriteFailed?) -> Unit,
    private val claimWriteFailure:
        (DnsHealthEvent.TunWriteFailed) -> TunWriterTermination?,
    private val claimEvidenceFailure: () -> TunWriterTermination?,
    private val finishDeferredTermination: () -> Unit,
    private val schedulerTransitioned: () -> Unit,
) : ProxyPublicationSink {
    private val writerWake = Channel<Unit>(Channel.CONFLATED)

    private var activeWriterTicket: TunQueryId? = null
    private var activeWriterThread: Thread? = null

    override fun publish(publication: ProxyPublication): Boolean {
        val ticket = activeWriterTicket ?: return false
        if (activeWriterThread !== Thread.currentThread()) return false
        return stateLock.withLock {
            scheduler.stagePublication(ticket, publication)
        }
    }

    suspend fun run() {
        while (true) {
            val claim = stateLock.withLock {
                scheduler.nextWriterClaim()
            }
            if (claim != null) schedulerTransitioned()
            if (claim == null) {
                if (writerWake.receiveCatching().getOrNull() == null) return
                continue
            }

            when (claim) {
                is TunWriterClaim.Publish -> publishAndWrite(claim)
                is TunWriterClaim.Write -> writeTicket(claim.id)
            }
        }
    }

    fun wake() {
        writerWake.trySend(Unit)
    }

    fun close() {
        writerWake.close()
    }

    private suspend fun publishAndWrite(claim: TunWriterClaim.Publish) {
        activeWriterTicket = claim.id
        activeWriterThread = Thread.currentThread()
        val published = try {
            claim.pending.publishIfCurrent()
        } catch (_: Throwable) {
            false
        } finally {
            activeWriterThread = null
            activeWriterTicket = null
        }

        if (!published) {
            stateLock.withLock {
                scheduler.failWriter(claim.id)
            }
            schedulerTransitioned()
            return
        }
        writeTicket(claim.id)
    }

    private suspend fun writeTicket(ticket: TunQueryId) {
        while (true) {
            val result = attemptWrite(ticket)
            finishDeferredTermination()
            schedulerTransitioned()
            when (result) {
                WriteResult.Complete,
                WriteResult.Suppressed,
                -> return
                WriteResult.AwaitWritable -> {
                    val readiness = try {
                        packetSession.awaitWritable()
                    } catch (_: CancellationException) {
                        sealRuntime(writeFailure(TunWriteFailureReason.WRITE_FAILED))
                        return
                    } catch (_: Throwable) {
                        sealRuntime(writeFailure(TunWriteFailureReason.WRITE_FAILED))
                        return
                    }
                    when (readiness) {
                        TunWritable.Writable -> Unit
                        TunWritable.Closed -> {
                            sealRuntime(
                                writeFailure(TunWriteFailureReason.DESCRIPTOR_CLOSED),
                            )
                            return
                        }
                        is TunWritable.Failed -> {
                            sealRuntime(writeFailure(TunWriteFailureReason.WRITE_FAILED))
                            return
                        }
                    }
                }
                is WriteResult.CommitEvidence -> {
                    val accepted = try {
                        observer.acceptTerminalEvidence(result.evidence)
                    } catch (_: Throwable) {
                        false
                    }
                    val termination = stateLock.withLock {
                        scheduler.finishEvidence(ticket, accepted)
                        if (accepted) null else claimEvidenceFailure()
                    }
                    schedulerTransitioned()
                    termination?.finish()
                    return
                }
                is WriteResult.Terminate -> {
                    result.termination?.finish()
                    return
                }
            }
        }
    }

    private fun attemptWrite(ticket: TunQueryId): WriteResult = stateLock.withLock {
        val selected = scheduler.selectWrite(ticket) ?: return@withLock WriteResult.Suppressed
        val attempt = try {
            packetSession.tryWritePacket(selected.packet)
        } catch (_: Throwable) {
            return@withLock WriteResult.Terminate(
                claimWriteFailure(
                    writeFailure(TunWriteFailureReason.WRITE_FAILED),
                ),
            )
        }
        when (attempt) {
            TunWriteAttempt.Written -> when (val commit = scheduler.commitWritten(ticket)) {
                TunWriteCommit.ReplacementComplete -> WriteResult.Complete
                is TunWriteCommit.OriginalEvidence ->
                    WriteResult.CommitEvidence(commit.evidence)
            }
            TunWriteAttempt.WouldBlock -> {
                scheduler.retainWouldBlock(ticket)
                WriteResult.AwaitWritable
            }
            TunWriteAttempt.Closed -> WriteResult.Terminate(
                claimWriteFailure(
                    writeFailure(TunWriteFailureReason.DESCRIPTOR_CLOSED),
                ),
            )
            is TunWriteAttempt.Failed,
            is TunWriteAttempt.ShortWrite,
            -> WriteResult.Terminate(
                claimWriteFailure(
                    writeFailure(TunWriteFailureReason.WRITE_FAILED),
                ),
            )
        }
    }

    private fun writeFailure(
        reason: TunWriteFailureReason,
    ) = DnsHealthEvent.TunWriteFailed(reason)

    private sealed interface WriteResult {
        data object Suppressed : WriteResult
        data object Complete : WriteResult
        data object AwaitWritable : WriteResult
        data class CommitEvidence(val evidence: TunTerminalEvidence) : WriteResult
        data class Terminate(
            val termination: TunWriterTermination?,
        ) : WriteResult
    }
}

internal fun interface TunWriterTermination {
    fun finish()
}
