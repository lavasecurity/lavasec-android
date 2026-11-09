package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.ResolverFailureReason
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/** Protects a raw DoH socket from VPN recursion before it can connect. */
fun interface DohSocketProtector {
    fun protect(socket: Socket): Boolean
}

/** Binds a protected raw DoH socket to the one captured underlying network. */
fun interface DohSocketBinder {
    fun bind(socket: Socket, network: Network)
}

/** Typed failure emitted when the protected raw DoH socket boundary cannot be established. */
class ProtectedDohSocketException(
    val reason: ResolverFailureReason,
    cause: Throwable? = null,
) : IOException(reason.name, cause)

/** Test-only observations around materialization and the one physical-close operation. */
internal class DohSocketLifecycleHooks(
    val materialize: (Socket) -> Unit,
    val beforePhysicalClose: (Socket) -> Unit,
)

/**
 * Creates only unconnected raw TCP sockets that are owned by one engine epoch.
 *
 * A caller may connect the returned socket only after this factory has registered it, protected
 * it from VPN recursion, bound it to the exact captured network, and performed the final epoch
 * ownership gate.
 */
class ProtectedDohSocketFactory private constructor(
    private val epoch: EngineEpoch,
    private val network: Network,
    private val registry: ResolverResourceRegistry,
    private val protector: DohSocketProtector,
    private val binder: DohSocketBinder,
    private val lifecycleHooks: DohSocketLifecycleHooks?,
) : SocketFactory() {
    constructor(
        epoch: EngineEpoch,
        network: Network,
        registry: ResolverResourceRegistry,
        protector: DohSocketProtector,
        binder: DohSocketBinder,
    ) : this(epoch, network, registry, protector, binder, null)

    override fun createSocket(): Socket {
        val socket = try {
            RegistryTrackingSocket(registry, lifecycleHooks)
        } catch (failure: Throwable) {
            throw ProtectedDohSocketException(ResolverFailureReason.SOCKET_FAILED, failure)
        }
        val registered = try {
            registry.registerIfCurrent(socket, epoch)
        } catch (failure: Throwable) {
            throw ProtectedDohSocketException(ResolverFailureReason.SOCKET_FAILED, failure)
        }
        if (!registered) {
            // ResolverResourceRegistry consumes a rejected resource, including its physical close.
            throw ProtectedDohSocketException(ResolverFailureReason.STALE_EPOCH)
        }

        val protected = try {
            protector.protect(socket)
        } catch (failure: Throwable) {
            release(socket)
            throw ProtectedDohSocketException(ResolverFailureReason.PROTECT_FAILED, failure)
        }
        if (!protected) {
            release(socket)
            throw ProtectedDohSocketException(ResolverFailureReason.PROTECT_FAILED)
        }
        try {
            binder.bind(socket, network)
        } catch (failure: Throwable) {
            release(socket)
            throw ProtectedDohSocketException(ResolverFailureReason.BIND_FAILED, failure)
        }
        val current = try {
            registry.isCurrent(epoch)
        } catch (failure: Throwable) {
            release(socket)
            throw ProtectedDohSocketException(ResolverFailureReason.STALE_EPOCH, failure)
        }
        if (!current) {
            release(socket)
            throw ProtectedDohSocketException(ResolverFailureReason.STALE_EPOCH)
        }
        return socket
    }

    override fun createSocket(host: String?, port: Int): Socket = preconnectedSocketForbidden()

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = preconnectedSocketForbidden()

    override fun createSocket(host: InetAddress?, port: Int): Socket = preconnectedSocketForbidden()

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = preconnectedSocketForbidden()

    private fun release(socket: RegistryTrackingSocket) {
        val ownershipTransferred = try {
            registry.unregister(socket)
        } catch (_: Throwable) {
            false
        }
        closeQuietly {
            if (ownershipTransferred) socket.closeAfterOwnershipTransfer() else socket.close()
        }
    }

    private fun closeQuietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // The selected typed failure wins; no later connection is permitted after cleanup.
        }
    }

    private fun preconnectedSocketForbidden(): Nothing =
        throw UnsupportedOperationException("preconnected DoH sockets are forbidden")

    internal companion object {
        fun withHooks(
            epoch: EngineEpoch,
            network: Network,
            registry: ResolverResourceRegistry,
            protector: DohSocketProtector,
            binder: DohSocketBinder,
            hooks: DohSocketLifecycleHooks,
        ): ProtectedDohSocketFactory =
            ProtectedDohSocketFactory(epoch, network, registry, protector, binder, hooks)
    }
}

/** The raw socket and registry cooperate so every ownership path reaches one physical close. */
private class RegistryTrackingSocket(
    private val registry: ResolverResourceRegistry,
    private val lifecycleHooks: DohSocketLifecycleHooks?,
) : Socket() {
    private val closeStarted = AtomicBoolean(false)

    init {
        try {
            lifecycleHooks?.materialize?.invoke(this) ?: bind(InetSocketAddress(0))
            check(isBound && !isConnected) { "DoH socket must be materialized and unconnected" }
        } catch (failure: Throwable) {
            closeAfterConstructionFailure(failure)
            throw failure
        }
    }

    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        try {
            registry.unregister(this)
        } catch (_: Throwable) {
            // A terminal close must proceed even while registry ownership reporting fails.
        } finally {
            physicalClose()
        }
    }

    fun closeAfterOwnershipTransfer() {
        if (!closeStarted.compareAndSet(false, true)) return
        physicalClose()
    }

    private fun closeAfterConstructionFailure(failure: Throwable) {
        try {
            closeAfterOwnershipTransfer()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }

    private fun physicalClose() {
        var hookFailure: Throwable? = null
        try {
            lifecycleHooks?.beforePhysicalClose?.invoke(this)
        } catch (failure: Throwable) {
            hookFailure = failure
        }
        try {
            super.close()
        } catch (closeFailure: Throwable) {
            if (hookFailure != null) hookFailure.addSuppressed(closeFailure) else throw closeFailure
        }
        hookFailure?.let { throw it }
    }
}
