package com.lavasec.app.platform

/**
 * Names, framing, and generation addressing for the chained-upstream secret half.
 *
 * The observable configuration is persisted as one generation-stamped record; the private key (and
 * its optional pre-shared key) is addressed by that same generation. This is the Android mirror of
 * the iOS `ChainedUpstreamSecretNaming` scheme, kept in the contracts module because both the
 * credential-protected writer and any future reader must agree on the account spelling — one
 * implementation shared is one chance for the framing to drift, and a drifted account name is a key
 * that reads back absent.
 *
 * Strict round-trip: only lowercase hex with no padding parses, so [keyAccount] has exactly one
 * spelling per generation and a sweep cannot be tricked into treating `.../0a` as a different item
 * from `.../a`.
 */
object ChainedUpstreamSecretNaming {
    const val KEY_ACCOUNT_PREFIX = "chained-upstream-key/"

    /** The key item account for [generation]. Generations are positive and monotonic. */
    fun keyAccount(generation: Long): String {
        require(generation > 0L) { "chained key generation must be positive" }
        return KEY_ACCOUNT_PREFIX + java.lang.Long.toHexString(generation)
    }

    /** The generation [account] addresses, or `null` when it is not one of ours. */
    fun generationOrNull(account: String): Long? {
        if (!account.startsWith(KEY_ACCOUNT_PREFIX)) return null
        val digits = account.removePrefix(KEY_ACCOUNT_PREFIX)
        if (digits.isEmpty()) return null
        val value = digits.toLongOrNull(radix = 16) ?: return null
        if (value <= 0L) return null
        // Round-trip strict: rejects uppercase, padding, a sign, and any other second spelling.
        return value.takeIf { keyAccount(it) == account }
    }

    /**
     * The key accounts that are NOT the committed generation.
     *
     * A pure function taking the committed generation as an argument rather than reading it: a
     * sweep that re-derives its own target is the interleave that deletes the live key. Callers
     * hold the repository's mutation lock.
     */
    fun orphanedKeyAccounts(accounts: List<String>, committed: Long): List<String> =
        accounts.filter { account ->
            val addressed = generationOrNull(account) ?: return@filter false
            addressed != committed
        }
}

/**
 * The generation-addressed secret half, as a seam.
 *
 * A protocol and not a direct Keystore call because the live AndroidKeyStore is not exercisable on
 * a host JVM, and the properties that matter here are behavioural — that an interrupted rotation
 * leaves a consistent pair, that a sweep never deletes the committed key, that delete removes every
 * generation.
 *
 * [add] is ADDITIVE, never an upsert: the commit protocol depends on a new key landing WITHOUT
 * disturbing the currently committed one. An upsert at a colliding account would replace a key a
 * live configuration still names.
 */
interface ChainedUpstreamKeyItemStore {
    /** Store [value] at [account]. Returns false if the account already holds an item or on failure. */
    fun add(value: ByteArray, account: String): Boolean

    /** The bytes at [account], or `null` when no item exists or it cannot be read. */
    fun load(account: String): ByteArray?

    /** Remove [account]. A missing item is not an error. Returns false only on failure. */
    fun delete(account: String): Boolean

    /** Every account this store holds, in no particular order. */
    fun accounts(): List<String>
}

/**
 * One rotation's secret half, in memory only through explicit copies.
 *
 * The private key and the optional pre-shared key live here as owned byte arrays; every read hands
 * back a fresh [privateKeyCopy] / [presharedKeyCopy] so a caller cannot hold a reference into the
 * store's memory. `toString` is redacted so a careless interpolation cannot log the material.
 */
class ChainedUpstreamSecretMaterial private constructor(
    val generation: Long,
    private val privateKeyBytes: ByteArray,
    private val presharedKeyBytes: ByteArray?,
    private val entryMaterial: ChainedUpstreamSecretMaterial? = null,
) {
    val hasPresharedKey: Boolean get() = presharedKeyBytes != null

    /** A fresh copy of the 32-byte private key. */
    fun privateKeyCopy(): ByteArray = privateKeyBytes.copyOf()

    /** A fresh copy of the 32-byte pre-shared key, or `null` when none is stored. */
    fun presharedKeyCopy(): ByteArray? = presharedKeyBytes?.copyOf()

    val rowCount: Int get() = if (entryMaterial == null) 1 else 2
    fun rowPrivateKeyCopy(index: Int): ByteArray {
        require(index in 0 until rowCount)
        return if (entryMaterial != null && index == 0) entryMaterial.privateKeyCopy() else privateKeyCopy()
    }
    fun rowPresharedKeyCopy(index: Int): ByteArray? {
        require(index in 0 until rowCount)
        return if (entryMaterial != null && index == 0) entryMaterial.presharedKeyCopy() else presharedKeyCopy()
    }

    /**
     * Zeroizes this material's owned key bytes.
     *
     * [ChainedUpstreamSecretMaterial] cannot zeroize on GC, so a consumer that copied the material
     * into an engine session must close it once the copy has been transferred, or the arrays linger
     * until the collector runs. The material must not be read after [close].
     */
    fun close() {
        privateKeyBytes.fill(0)
        presharedKeyBytes?.fill(0)
        entryMaterial?.close()
    }

    override fun toString(): String =
        "ChainedUpstreamSecretMaterial(generation=$generation, psk=$hasPresharedKey)"

    companion object {
        /** The WireGuard private and pre-shared key width, matching the engine ABI. */
        const val KEY_BYTE_COUNT = 32

        /** A validated material, or `null` when a key is the wrong width or the generation is invalid. */
        fun create(
            generation: Long,
            privateKey: ByteArray,
            presharedKey: ByteArray?,
        ): ChainedUpstreamSecretMaterial? {
            if (generation <= 0L) return null
            if (privateKey.size != KEY_BYTE_COUNT) return null
            if (presharedKey != null && presharedKey.size != KEY_BYTE_COUNT) return null
            return ChainedUpstreamSecretMaterial(
                generation = generation,
                privateKeyBytes = privateKey.copyOf(),
                presharedKeyBytes = presharedKey?.copyOf(),
            )
        }

        fun createRows(generation: Long, privateKeys: List<ByteArray>, presharedKeys: List<ByteArray?>): ChainedUpstreamSecretMaterial? {
            if (privateKeys.size !in 1..2 || privateKeys.size != presharedKeys.size) return null
            val exit = create(generation, privateKeys.last(), presharedKeys.last()) ?: return null
            if (privateKeys.size == 1) return exit
            val entry = create(generation, privateKeys.first(), presharedKeys.first()) ?: run { exit.close(); return null }
            return ChainedUpstreamSecretMaterial(generation, exit.privateKeyBytes, exit.presharedKeyBytes, entry)
        }
    }
}

/**
 * The one key item's byte layout: `[32-byte private key][1-byte presence flag][32-byte PSK?]`.
 *
 * A byte layout and not a JSON envelope, because the value is persisted as Keystore ciphertext and
 * a fixed layout is the shortest thing that carries both secrets and the presence bit. A new write
 * is always 33 bytes (flag `0x00`) or 65 (flag `0x01`). A bare 32-byte value written before PSK
 * support is READ-ONLY compatible and decodes to `presharedKey == null`.
 *
 * An unrecognised layout is REFUSED (a `null` decode) rather than silently truncated to a 32-byte
 * prefix with a dropped PSK: a truncated-but-plausible key aimed at the wrong peer is the class of
 * silent failure the write boundary exists to stop, and a value this codec did not produce must not
 * be admitted as key material.
 */
object ChainedUpstreamSecretCodec {
    private const val PRIVATE_KEY_BYTES = ChainedUpstreamSecretMaterial.KEY_BYTE_COUNT
    private const val PRESHARED_KEY_BYTES = ChainedUpstreamSecretMaterial.KEY_BYTE_COUNT
    private const val ABSENT_FLAG: Byte = 0x00
    private const val PRESENT_FLAG: Byte = 0x01
    private val CHAIN_HEADER = byteArrayOf(0x4c, 0x56, 0x43, 0x02)

    /** One atomic key item retains both independent credentials in their saved row order. */
    fun encodeRows(privateKeys: List<ByteArray>, presharedKeys: List<ByteArray?>): ByteArray? {
        if (privateKeys.size !in 1..2 || privateKeys.size != presharedKeys.size) return null
        if (privateKeys.size == 1) return encode(privateKeys.single(), presharedKeys.single())
        val rows = privateKeys.indices.map { encode(privateKeys[it], presharedKeys[it]) ?: return null }
        return try {
            CHAIN_HEADER + byteArrayOf(rows[0].size.toByte()) + rows[0] + byteArrayOf(rows[1].size.toByte()) + rows[1]
        } finally { rows.forEach { it.fill(0) } }
    }

    /** Encodes the two secrets into one key item value, or `null` when a key is the wrong width. */
    fun encode(privateKey: ByteArray, presharedKey: ByteArray?): ByteArray? {
        if (privateKey.size != PRIVATE_KEY_BYTES) return null
        if (presharedKey != null && presharedKey.size != PRESHARED_KEY_BYTES) return null
        val out = ByteArray(PRIVATE_KEY_BYTES + 1 + (presharedKey?.size ?: 0))
        System.arraycopy(privateKey, 0, out, 0, PRIVATE_KEY_BYTES)
        if (presharedKey != null) {
            out[PRIVATE_KEY_BYTES] = PRESENT_FLAG
            System.arraycopy(presharedKey, 0, out, PRIVATE_KEY_BYTES + 1, PRESHARED_KEY_BYTES)
        } else {
            out[PRIVATE_KEY_BYTES] = ABSENT_FLAG
        }
        return out
    }

    /**
     * Decodes one key item value at [generation], or `null` when the value is not a layout this
     * codec produced (a truncated, overlong, or otherwise unrecognised item).
     *
     * The temporary key copies this creates are zeroized before returning, because [value] is a
     * plaintext secret blob: leaving them for the collector would violate the zeroization boundary
     * the caller closes the returned material to hold.
     */
    fun decode(value: ByteArray, generation: Long): ChainedUpstreamSecretMaterial? {
        if (value.size > PRIVATE_KEY_BYTES + 1 + PRESHARED_KEY_BYTES) {
            if (!value.take(CHAIN_HEADER.size).toByteArray().contentEquals(CHAIN_HEADER)) return null
            val firstSize = value.getOrNull(4)?.toInt()?.and(0xff) ?: return null
            if (firstSize != 33 && firstSize != 65) return null
            val secondOffset = 5 + firstSize
            val secondSize = value.getOrNull(secondOffset)?.toInt()?.and(0xff) ?: return null
            if (secondSize != 33 && secondSize != 65 || value.size != secondOffset + 1 + secondSize) return null
            val firstBytes = value.copyOfRange(5, secondOffset)
            val secondBytes = value.copyOfRange(secondOffset + 1, value.size)
            val entry = decodeAndZeroize(firstBytes, generation) ?: run { secondBytes.fill(0); return null }
            val exit = decodeAndZeroize(secondBytes, generation) ?: run { entry.close(); return null }
            val keys = listOf(entry.privateKeyCopy(), exit.privateKeyCopy())
            val psks = listOf(entry.presharedKeyCopy(), exit.presharedKeyCopy())
            return try { ChainedUpstreamSecretMaterial.createRows(generation, keys, psks) }
            finally { keys.forEach { it.fill(0) }; psks.forEach { it?.fill(0) }; entry.close(); exit.close() }
        }
        val privateKey: ByteArray
        val presharedKey: ByteArray?
        when {
            value.size == PRIVATE_KEY_BYTES -> {
                privateKey = value.copyOf()
                presharedKey = null
            }
            value.size == PRIVATE_KEY_BYTES + 1 && value[PRIVATE_KEY_BYTES] == ABSENT_FLAG -> {
                privateKey = value.copyOf(PRIVATE_KEY_BYTES)
                presharedKey = null
            }
            value.size == PRIVATE_KEY_BYTES + 1 + PRESHARED_KEY_BYTES &&
                value[PRIVATE_KEY_BYTES] == PRESENT_FLAG -> {
                privateKey = value.copyOfRange(0, PRIVATE_KEY_BYTES)
                presharedKey = value.copyOfRange(
                    PRIVATE_KEY_BYTES + 1,
                    PRIVATE_KEY_BYTES + 1 + PRESHARED_KEY_BYTES,
                )
            }
            else -> return null
        }
        return try {
            ChainedUpstreamSecretMaterial.create(generation, privateKey, presharedKey)
        } finally {
            privateKey.fill(0)
            presharedKey?.fill(0)
        }
    }

    /**
     * Decodes [value] and zeroizes it, transferring ownership of the plaintext blob.
     *
     * A key item loaded from the secure store is a plaintext secret; the caller (the repository)
     * must not leave it resident. [value] must not be used after this call.
     */
    fun decodeAndZeroize(value: ByteArray, generation: Long): ChainedUpstreamSecretMaterial? =
        try {
            decode(value, generation)
        } finally {
            value.fill(0)
        }
}

/**
 * The staged replacement protocol for a chained-upstream secret rotation.
 *
 * The observable configuration and the secret are separate stores, so the commit protocol is what
 * makes an interrupted rotation leave a consistent pair rather than a torn one:
 *
 * ```
 * G = committed + 1
 * 1. add the key item at account keyAccount(G)      ADDITIVE — the committed pair is untouched
 * 2. read it back and compare                       VERIFY — a half-written item is not published
 * 3. publish the configuration at G                  THE COMMIT POINT (atomic)
 * 4. delete every other key account                  idempotent, best-effort
 * ```
 *
 * Every interruption leaves a reader with either the old generation (step 1/2 failed, or step 3
 * failed and the staged key was removed) or the new generation (step 3 succeeded; the sweep is
 * harmless residue). The configuration is published last, so a failed publish cannot lose the prior
 * configuration or its key — this type's whole reason for existing.
 *
 * The generation is MONOTONIC (`committed + 1`), consistent with the credential configuration
 * authority, rather than the freshly drawn stamp iOS uses: on Android the generation is a local
 * ordering token, and a monotonic value gives one canonical account spelling without an entropy
 * dependency.
 */
class ChainedUpstreamSecretStaging(
    private val keyItems: ChainedUpstreamKeyItemStore,
) {
    /** The outcome of one staged replacement. */
    sealed interface Result {
        /** The configuration publish committed at [generation]; older keys were swept. */
        data class Published(val generation: Long) : Result

        /** Nothing was published: the prior committed pair is intact. */
        data class Rejected(val failure: Failure) : Result
    }

    enum class Failure {
        /** The generation space was exhausted; nothing was written. */
        GENERATION_EXHAUSTED,

        /** A supplied key did not have the WireGuard width; nothing was written. */
        MALFORMED_SECRET,

        /** A retained row cannot borrow another row's credentials or fabricate missing custody. */
        MISSING_REFERENCED_SECRET,
        INVALID_SELECTION,

        /** The new key item could not be added; the committed key is untouched. */
        KEY_WRITE_FAILED,

        /** The new key item did not read back identical; the staged item was removed. */
        KEY_VERIFY_FAILED,

        /** The configuration publish failed; the staged key was removed. */
        PUBLISH_FAILED,
    }

    /**
     * Stages [privateKey] and the optional [presharedKey] at the next generation, then calls
     * [publish] with that generation. [publish] is the commit point: it must atomically and durably
     * persist the non-secret configuration at the supplied generation and return `true` only after
     * that read-back succeeded.
     *
     * @param committedGeneration the generation currently committed, or `null` on first save.
     */
    suspend fun replace(
        committedGeneration: Long?,
        privateKey: ByteArray,
        presharedKey: ByteArray?,
        publish: suspend (generation: Long) -> Boolean,
    ): Result {
        val encoded = ChainedUpstreamSecretCodec.encode(privateKey, presharedKey)
            ?: return Result.Rejected(Failure.MALFORMED_SECRET)
        return replaceEncoded(committedGeneration, encoded, publish)
    }

    suspend fun replaceRows(
        committedGeneration: Long?, privateKeys: List<ByteArray>, presharedKeys: List<ByteArray?>,
        publish: suspend (generation: Long) -> Boolean,
    ): Result {
        val encoded = ChainedUpstreamSecretCodec.encodeRows(privateKeys, presharedKeys)
            ?: return Result.Rejected(Failure.MALFORMED_SECRET)
        return replaceEncoded(committedGeneration, encoded, publish)
    }

    private suspend fun replaceEncoded(committedGeneration: Long?, encoded: ByteArray, publish: suspend (Long) -> Boolean): Result {
        return try {
        val generation = try {
            Math.addExact(committedGeneration ?: 0L, 1L)
        } catch (_: ArithmeticException) {
            return Result.Rejected(Failure.GENERATION_EXHAUSTED)
        }
        val account = ChainedUpstreamSecretNaming.keyAccount(generation)

        // The target generation is strictly newer than the committed one, so any item already at
        // this account is residue from an interrupted earlier rotation (killed after the key write,
        // before the publish). Clear it first: with monotonic generations the same target is
        // re-minted next time, and an additive add would otherwise fail forever against the
        // orphan. The committed key can never live here, so nothing live is at risk.
        runCatching { keyItems.delete(account) }
        if (!keyItems.add(encoded, account)) {
            return Result.Rejected(Failure.KEY_WRITE_FAILED)
        }
        val readBack = keyItems.load(account)
        val verified = readBack?.contentEquals(encoded) == true
        readBack?.fill(0)
        if (!verified) {
            runCatching { keyItems.delete(account) }
            return Result.Rejected(Failure.KEY_VERIFY_FAILED)
        }
        if (!publish(generation)) {
            runCatching { keyItems.delete(account) }
            return Result.Rejected(Failure.PUBLISH_FAILED)
        }
        sweep(committedGeneration = generation)
        Result.Published(generation)
        } finally { encoded.fill(0) }
    }

    /**
     * Best-effort removal of every generation-addressed key that is not [committedGeneration].
     *
     * The rotation is already committed by the time this runs, so a failed sweep cannot fail the
     * call without reporting a successful write as an error; the orphan is harmless (no
     * configuration names it).
     */
    private fun sweep(committedGeneration: Long) {
        val accounts = runCatching { keyItems.accounts() }.getOrNull() ?: return
        ChainedUpstreamSecretNaming.orphanedKeyAccounts(accounts, committedGeneration)
            .forEach { account -> runCatching { keyItems.delete(account) } }
    }
}
