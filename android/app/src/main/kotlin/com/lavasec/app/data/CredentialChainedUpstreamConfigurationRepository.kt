package com.lavasec.app.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.foundation.chained.ChainedUpstreamRotation
import com.lavasec.app.platform.ChainedUpstreamConfigurationRepository
import com.lavasec.app.platform.ChainedUpstreamIdentity
import com.lavasec.app.platform.ChainedUpstreamKeyItemStore
import com.lavasec.app.platform.ChainedUpstreamPendingRemovalResult
import com.lavasec.app.platform.ChainedUpstreamSaveExpectation
import com.lavasec.app.platform.ChainedUpstreamSaveResult
import com.lavasec.app.platform.ChainedUpstreamSelectionRow
import com.lavasec.app.platform.ChainedUpstreamSecretCodec
import com.lavasec.app.platform.ChainedUpstreamSecretMaterial
import com.lavasec.app.platform.ChainedUpstreamSecretNaming
import com.lavasec.app.platform.ChainedUpstreamSecretStaging
import com.lavasec.app.platform.StoredChainedUpstreamConfiguration
import com.lavasec.app.platform.canonicalChainedUpstreamConfigurationFingerprint
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Credential-protected authority for the user's chained upstream.
 *
 * The non-secret configuration lives in credential-protected DataStore; the private and optional
 * pre-shared keys live in the [ChainedUpstreamKeyItemStore] (the Android Keystore in production),
 * addressed by the configuration's generation. Both halves are committed by
 * [ChainedUpstreamSecretStaging], which writes and verifies the new key before the configuration
 * publish and removes the staged key if the publish fails, so a failed write cannot lose the prior
 * configuration or key.
 *
 * At-rest posture: the DataStore file and the Keystore ciphertext live in the credential-protected
 * (default) storage area; the AES key never leaves AndroidKeyStore. Chained mode cannot start
 * before first unlock (the engine needs the credential graph), so requiring credential/after-first-
 * unlock storage is legitimate and mirrors iOS `AfterFirstUnlockThisDeviceOnly`.
 *
 * [providedStore] is injectable so a process-wide store can be shared across credential generations
 * (DataStore's `FileStorage` rejects a second active instance on the same file), and so tests can
 * drive the store directly.
 *
 * ## One rotation lock per backing store
 *
 * The credential graph can construct several repository instances around one process-wide store.
 * A per-instance lock would let two `save` calls read the same generation, so the later-to-finish
 * one would pre-clear the generation the first had just committed and then, on its own failure,
 * leave the committed configuration without its key. The rotation lock is therefore shared per
 * backing store (see [rotationLockFor]), so the whole stage/verify/publish/sweep sequence is
 * serialized across instances.
 *
 * `save` reads the authoritative record directly and returns
 * [ChainedUpstreamSaveResult.AuthoritativeReadFailed] when that read fails; only the UI-facing
 * [storedConfiguration] flow degrades a read error to "not configured".
 */
internal class CredentialChainedUpstreamConfigurationRepository(
    credentialProtectedContext: Context,
    private val keyItemStore: ChainedUpstreamKeyItemStore,
    storeName: String = CHAINED_STORE_NAME,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    providedStore: DataStore<Preferences>? = null,
) : ChainedUpstreamConfigurationRepository {
    private val storeFile = credentialProtectedContext.preferencesDataStoreFile(storeName)
    private val store: DataStore<Preferences> = providedStore ?: PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { storeFile },
    )
    // Keyed by the shared DataStore instance when one is provided, else by the DataStore file, so
    // every repository instance addressing the same store holds the same lock.
    private val mutationMutex: Mutex = rotationLockFor(providedStore ?: storeFile)
    private val staging = ChainedUpstreamSecretStaging(keyItemStore)
    private val preferences: Flow<Preferences> = store.data.catch { error ->
        if (error is IOException || error is CorruptionException) {
            emit(androidx.datastore.preferences.core.emptyPreferences())
        } else {
            throw error
        }
    }

    override val storedConfiguration: Flow<StoredChainedUpstreamConfiguration?> =
        preferences.map(ChainedUpstreamConfigurationCodec::decode)

    override suspend fun loadConfiguration(): StoredChainedUpstreamConfiguration? =
        ChainedUpstreamConfigurationCodec.decode(preferences.first())

    /** Exact non-secret metadata under the SAME cross-instance lock as every rotation/removal. */
    internal suspend fun <T> withChainedDnsMetadata(
        block: suspend (StoredChainedUpstreamConfiguration?) -> T,
    ): T = mutationMutex.withLock {
        val raw = store.data.first()
        val actual = ChainedUpstreamConfigurationCodec.decode(raw)
        check(actual != null || raw.asMap().isEmpty()) { "Chained metadata is unavailable" }
        block(actual)
    }

    override suspend fun configurationIssue(): ChainedConfigurationIssue? = mutationMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val raw = store.data.first()
                val stored = ChainedUpstreamConfigurationCodec.decode(raw)
                if (stored == null) {
                    return@withContext if (raw.asMap().isEmpty()) ChainedConfigurationIssue.MISSING_CONFIGURATION
                        else ChainedConfigurationIssue.UNAVAILABLE
                }
                if (stored.pendingImportId != null) return@withContext ChainedConfigurationIssue.UNAVAILABLE
                val account = ChainedUpstreamSecretNaming.keyAccount(stored.identity.generation)
                if (account !in keyItemStore.accounts()) return@withContext ChainedConfigurationIssue.MISSING_PRIVATE_KEY
                val bytes = keyItemStore.load(account) ?: return@withContext ChainedConfigurationIssue.UNAVAILABLE
                val material = ChainedUpstreamSecretCodec.decodeAndZeroize(bytes, stored.identity.generation)
                    ?: return@withContext ChainedConfigurationIssue.UNAVAILABLE
                val coherent = material.rowCount == stored.configuration.orderedHops.size
                material.close()
                if (coherent) null else ChainedConfigurationIssue.UNAVAILABLE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ChainedConfigurationIssue.UNAVAILABLE
            }
        }
    }

    override suspend fun save(
        rotation: ChainedUpstreamRotation,
        expectation: ChainedUpstreamSaveExpectation,
    ): ChainedUpstreamSaveResult = saveSelection(
        listOf(ChainedUpstreamSelectionRow(rotation.configuration.withoutEntryHop(), null, rotation)), expectation,
    )

    override suspend fun saveWhileCurrent(
        rotation: ChainedUpstreamRotation,
        expectation: ChainedUpstreamSaveExpectation,
        isCurrent: () -> Boolean,
    ): ChainedUpstreamSaveResult = saveSelectionGuarded(
        listOf(ChainedUpstreamSelectionRow(rotation.configuration.withoutEntryHop(), null, rotation)),
        expectation, isCurrent,
    )

    override suspend fun saveSelection(
        rows: List<ChainedUpstreamSelectionRow>,
        expectation: ChainedUpstreamSaveExpectation,
    ): ChainedUpstreamSaveResult = saveSelectionGuarded(rows, expectation, null)

    override suspend fun saveSelectionWhileCurrent(
        rows: List<ChainedUpstreamSelectionRow>,
        expectation: ChainedUpstreamSaveExpectation,
        isCurrent: () -> Boolean,
    ): ChainedUpstreamSaveResult = saveSelectionGuarded(rows, expectation, isCurrent)

    private class RetiredNativeVisit : Exception()

    private suspend fun saveSelectionGuarded(
        rows: List<ChainedUpstreamSelectionRow>,
        expectation: ChainedUpstreamSaveExpectation,
        isCurrent: (() -> Boolean)?,
    ): ChainedUpstreamSaveResult {
        val requestedRows = rows.toList()
        return mutationMutex.withLock {
            val rows = requestedRows
            // The secret backend is synchronous (AndroidKeyStore generation/encryption and
            // SharedPreferences commit), so run the whole rotation on IO rather than on the
            // caller's thread.
            withContext(Dispatchers.IO) {
                // Authoritative read for the mutation path: a read failure is NOT mapped to empty
                // preferences, or an existing generation 1 would look unconfigured and its live key
                // would be pre-deleted before the new key is proven. Fail closed instead.
                val committedPreferences = try {
                    store.data.first()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@withContext ChainedUpstreamSaveResult.AuthoritativeReadFailed
                }
                val committed = try {
                    ChainedUpstreamConfigurationCodec.decode(committedPreferences)
                } catch (_: Exception) {
                    return@withContext ChainedUpstreamSaveResult.AuthoritativeReadFailed
                }
                if (committed == null && committedPreferences.asMap().isNotEmpty()) {
                    // Unknown/corrupt metadata may still reference a live key generation. Do not
                    // stage generation 1 over it; explicit removal must resolve the unreadable record.
                    return@withContext ChainedUpstreamSaveResult.AuthoritativeReadFailed
                }
                // Enforce the caller's expectation INSIDE the serialization, after the authoritative
                // read: a concurrent commit that won the race is never overwritten.
                when (expectation) {
                    ChainedUpstreamSaveExpectation.AnyCommitted -> Unit
                    ChainedUpstreamSaveExpectation.Absent ->
                        if (committed != null) return@withContext ChainedUpstreamSaveResult.Superseded

                    is ChainedUpstreamSaveExpectation.AbsentPendingImport ->
                        if (committed != null) return@withContext ChainedUpstreamSaveResult.Superseded

                    is ChainedUpstreamSaveExpectation.Generation ->
                        if (committed?.identity?.generation != expectation.generation) {
                            return@withContext ChainedUpstreamSaveResult.Superseded
                        }
                }
                if (isCurrent?.invoke() == false) return@withContext ChainedUpstreamSaveResult.Superseded
                if (rows.size !in 1..2 || rows.mapNotNull { it.originalIndex }.distinct().size != rows.count { it.originalIndex != null }) {
                    return@withContext ChainedUpstreamSaveResult.Rejected(ChainedUpstreamSecretStaging.Failure.INVALID_SELECTION)
                }
                val configuration = try {
                    if (rows.size == 2) rows.last().configuration.withEntryHop(rows.first().configuration) else rows.single().configuration
                } catch (_: IllegalArgumentException) {
                    return@withContext ChainedUpstreamSaveResult.Rejected(ChainedUpstreamSecretStaging.Failure.INVALID_SELECTION)
                }
                val originalRows = committed?.configuration?.orderedHops.orEmpty()
                val retained = if (rows.any { it.replacement == null }) readStoredSecretsLocked(committed) else null
                val privateKeys = mutableListOf<ByteArray>()
                val presharedKeys = mutableListOf<ByteArray?>()
                try {
                    for (row in rows) {
                        val replacement = row.replacement
                        if (replacement != null) {
                            if (replacement.configuration.withoutEntryHop().named(row.configuration.displayName).enabled(row.configuration.isEnabled) != row.configuration) {
                                return@withContext ChainedUpstreamSaveResult.Rejected(ChainedUpstreamSecretStaging.Failure.INVALID_SELECTION)
                            }
                            privateKeys.add(replacement.decodedPrivateKey())
                            presharedKeys.add(replacement.decodedPresharedKey())
                        } else {
                            val index = row.originalIndex
                            if (index == null || retained == null || index !in originalRows.indices || retained.rowCount != originalRows.size ||
                                originalRows[index].withoutEntryHop().named(row.configuration.displayName).enabled(row.configuration.isEnabled) != row.configuration) {
                                return@withContext ChainedUpstreamSaveResult.Rejected(ChainedUpstreamSecretStaging.Failure.MISSING_REFERENCED_SECRET)
                            }
                            privateKeys.add(retained.rowPrivateKeyCopy(index))
                            presharedKeys.add(retained.rowPresharedKeyCopy(index))
                        }
                    }
                val fingerprint =
                    canonicalChainedUpstreamConfigurationFingerprint(configuration)
                var publishedGeneration: Long? = null
                var visitRetired = false
                val result = staging.replaceRows(
                    committedGeneration = committed?.identity?.generation,
                    privateKeys = privateKeys,
                    presharedKeys = presharedKeys,
                ) { generation ->
                    if (isCurrent?.invoke() == false) {
                        visitRetired = true
                        return@replaceRows false
                    }
                    val next = StoredChainedUpstreamConfiguration(
                        configuration = configuration,
                        identity = ChainedUpstreamIdentity(generation, fingerprint),
                        savedAtEpochMillis = now(),
                        pendingImportId = (expectation as? ChainedUpstreamSaveExpectation.AbsentPendingImport)?.editorId,
                    )
                    val updated = try {
                        store.edit { prefs ->
                            if (isCurrent?.invoke() == false) throw RetiredNativeVisit()
                            ChainedUpstreamConfigurationCodec.encode(prefs, next)
                        }
                    } catch (_: RetiredNativeVisit) {
                        visitRetired = true
                        return@replaceRows false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // The atomic edit did not commit; the staged key is removed by the protocol.
                        return@replaceRows false
                    }
                    // The edit returned, so the configuration is durable. The read-back is a hard
                    // invariant check, NOT a retry signal: a mismatch is a codec bug, and reporting
                    // it as "not committed" would have the protocol delete a key the committed
                    // configuration now names. Letting it propagate leaves an unreferenced key
                    // rather than a torn pair.
                    check(
                        ChainedUpstreamConfigurationCodec.decode(updated) == next,
                    ) {
                        "committed chained configuration did not read back"
                    }
                    publishedGeneration = generation
                    true
                }
                when (result) {
                    is ChainedUpstreamSecretStaging.Result.Published -> {
                        val generation = checkNotNull(publishedGeneration) {
                            "published rotation must report its generation"
                        }
                        ChainedUpstreamSaveResult.Committed(
                            ChainedUpstreamIdentity(generation, fingerprint),
                        )
                    }
                    is ChainedUpstreamSecretStaging.Result.Rejected ->
                        if (visitRetired) ChainedUpstreamSaveResult.Superseded
                        else ChainedUpstreamSaveResult.Rejected(result.failure)
                }
                } finally {
                    privateKeys.forEach { it.fill(0) }
                    presharedKeys.forEach { it?.fill(0) }
                    retained?.close()
                }
            }
        }
    }

    override suspend fun usableSecretsGeneration(): Long? = mutationMutex.withLock {
        readSecretsGenerationLocked()
    }

    override suspend fun loadSecrets(): ChainedUpstreamSecretMaterial? = mutationMutex.withLock {
        readSecretsLocked()
    }

    /**
     * The committed rotation's usable key generation, or `null` when none decrypts.
     *
     * Reading the key item runs AndroidKeyStore decryption, so it runs on IO. The status read pairs
     * this generation with its configuration read; the material is verified and immediately
     * zeroized, so no decrypted arrays stay resident between status refreshes.
     */
    private suspend fun readSecretsGenerationLocked(): Long? =
        withContext(Dispatchers.IO) {
            val stored = ChainedUpstreamConfigurationCodec.decode(preferences.first())
                ?: return@withContext null
            val account = ChainedUpstreamSecretNaming.keyAccount(stored.identity.generation)
            val value = runCatching { keyItemStore.load(account) }.getOrNull()
                ?: return@withContext null
            val material =
                ChainedUpstreamSecretCodec.decodeAndZeroize(value, stored.identity.generation)
                    ?: return@withContext null
            val coherent = material.rowCount == stored.configuration.orderedHops.size
            material.close()
            if (coherent) stored.identity.generation else null
        }

    /**
     * Reads and decodes the committed rotation's secret half under the mutation lock.
     *
     * Reading the key item runs AndroidKeyStore decryption and a synchronous preferences read, so
     * it runs on IO. [loadSecrets] is the authoritative engine read; [usableSecretsGeneration]
     * performs the same decrypt but discards the material.
     */
    private suspend fun readSecretsLocked(): ChainedUpstreamSecretMaterial? =
        withContext(Dispatchers.IO) {
            val stored = ChainedUpstreamConfigurationCodec.decode(preferences.first())
                ?: return@withContext null
            val material = readStoredSecretsLocked(stored) ?: return@withContext null
            val active = stored.configuration.orderedHops.indices.filter { stored.configuration.orderedHops[it].isEnabled }
            val keys = active.map(material::rowPrivateKeyCopy)
            val psks = active.map(material::rowPresharedKeyCopy)
            try { ChainedUpstreamSecretMaterial.createRows(stored.identity.generation, keys, psks) }
            finally { keys.forEach { it.fill(0) }; psks.forEach { it?.fill(0) }; material.close() }
        }

    private suspend fun readStoredSecretsLocked(stored: StoredChainedUpstreamConfiguration?): ChainedUpstreamSecretMaterial? =
        withContext(Dispatchers.IO) {
            if (stored == null) return@withContext null
            val account = ChainedUpstreamSecretNaming.keyAccount(stored.identity.generation)
            val value = runCatching { keyItemStore.load(account) }.getOrNull()
                ?: return@withContext null
            // `decodeAndZeroize` takes ownership of the plaintext blob and clears it (and the
            // codec's own temporaries) before returning.
            val material = ChainedUpstreamSecretCodec.decodeAndZeroize(value, stored.identity.generation) ?: return@withContext null
            if (material.rowCount != stored.configuration.orderedHops.size) { material.close(); null } else material
        }

    override suspend fun deleteSecrets() {
        mutationMutex.withLock { deleteSecretsLocked() }
    }

    override suspend fun removeAll() {
        mutationMutex.withLock { removeAllLocked() }
    }

    override suspend fun removeIfGeneration(generation: Long): ChainedUpstreamPendingRemovalResult =
        removeIfGenerationGuarded(generation, null)

    override suspend fun removeIfGenerationWhileCurrent(
        generation: Long,
        isCurrent: () -> Boolean,
    ): ChainedUpstreamPendingRemovalResult = removeIfGenerationGuarded(generation, isCurrent)

    private suspend fun removeIfGenerationGuarded(
        generation: Long,
        isCurrent: (() -> Boolean)?,
    ): ChainedUpstreamPendingRemovalResult = mutationMutex.withLock {
        val stored = try {
            val raw = store.data.first()
            val decoded = ChainedUpstreamConfigurationCodec.decode(raw)
            if (decoded == null && raw.asMap().isNotEmpty()) return@withLock ChainedUpstreamPendingRemovalResult.AuthoritativeReadFailed
            decoded
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withLock ChainedUpstreamPendingRemovalResult.AuthoritativeReadFailed }
        if (stored?.identity?.generation != generation) return@withLock ChainedUpstreamPendingRemovalResult.Superseded
        if (isCurrent?.invoke() == false) return@withLock ChainedUpstreamPendingRemovalResult.Superseded
        try { removeAllLocked(isCurrent); ChainedUpstreamPendingRemovalResult.Removed }
        catch (_: RetiredNativeVisit) { ChainedUpstreamPendingRemovalResult.Superseded }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ChainedUpstreamPendingRemovalResult.Failed("Chained configuration could not be removed") }
    }

    override suspend fun removePendingImport(
        editorId: String,
        identity: ChainedUpstreamIdentity,
    ): ChainedUpstreamPendingRemovalResult = mutationMutex.withLock {
        val stored = try {
            // No UI fallback: a failed authoritative read must keep the pending evidence intact.
            val raw = store.data.first()
            val decoded = ChainedUpstreamConfigurationCodec.decode(raw)
            if (decoded == null && raw.asMap().isNotEmpty()) {
                return@withLock ChainedUpstreamPendingRemovalResult.AuthoritativeReadFailed
            }
            decoded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock ChainedUpstreamPendingRemovalResult.AuthoritativeReadFailed
        }
        if (stored?.identity != identity || stored.pendingImportId != editorId) {
            return@withLock ChainedUpstreamPendingRemovalResult.Superseded
        }
        try {
            // Pending records cannot activate. Remove the key first, keeping the atomic pending
            // witness until the whole cleanup succeeds; a key/delete or metadata failure therefore
            // retains the exact retry target rather than stranding unreferenced secret items.
            deleteSecretsLocked()
            val removed = store.edit { it.clear() }
            if (removed.asMap().isNotEmpty()) throw IOException("Pending VPN record remained")
            ChainedUpstreamPendingRemovalResult.Removed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ChainedUpstreamPendingRemovalResult.Failed("Pending VPN import could not be removed")
        }
    }

    private suspend fun removeAllLocked(isCurrent: (() -> Boolean)? = null) {
        var configurationRemoved = false
        try {
            val committed = store.edit { prefs ->
                if (isCurrent?.invoke() == false) throw RetiredNativeVisit()
                prefs.clear()
            }
            configurationRemoved = committed.asMap().isEmpty()
        } catch (retired: RetiredNativeVisit) {
            throw retired
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Failed publication must preserve the prior configuration AND its credentials.
        }
        if (!configurationRemoved) throw IOException("Chained configuration could not be removed")
        deleteSecretsLocked()
    }

    private suspend fun deleteSecretsLocked() = withContext(Dispatchers.IO) {
        val accounts = try {
            keyItemStore.accounts()
        } catch (_: Exception) {
            throw IOException("Chained keys could not be inspected")
        }
        var removed = true
        accounts.filter { ChainedUpstreamSecretNaming.generationOrNull(it) != null }
            .forEach { account ->
                if (!runCatching { keyItemStore.delete(account) }.getOrDefault(false)) removed = false
            }
        val noneRemaining = runCatching {
            keyItemStore.accounts().none { ChainedUpstreamSecretNaming.generationOrNull(it) != null }
        }.getOrDefault(false)
        if (!removed || !noneRemaining) throw IOException("Chained keys could not be removed")
    }

    companion object {
        const val CHAINED_STORE_NAME = "lavasec_chained_upstream"

        // One lock per backing store, shared by every repository instance in this process.
        private val rotationLocks = ConcurrentHashMap<Any, Mutex>()

        /**
         * The process-wide rotation lock for [storeKey].
         *
         * [storeKey] is the shared [DataStore] instance when one is injected, otherwise the
         * DataStore [java.io.File], so instances that address the same store share one lock and the
         * stage/verify/publish/sweep sequence cannot interleave.
         */
        internal fun rotationLockFor(storeKey: Any): Mutex =
            rotationLocks.computeIfAbsent(storeKey) { Mutex() }
    }
}

/**
 * Field-by-field Preferences encoding for one committed chained upstream.
 *
 * The non-secret configuration is stored as individual primitive preferences (not a serialized blob)
 * so a corrupt field fails closed at decode rather than yielding a partially-parsed value. The
 * generation and canonical fingerprint are stored beside it; the secret half is NOT here.
 *
 * A decode that cannot rebuild a valid [ChainedUpstreamConfiguration] returns `null`: a tightened
 * validator across an app update can make a previously-valid stored configuration non-decodable, and
 * the honest state for the caller is "re-enter the upstream".
 */
internal object ChainedUpstreamConfigurationCodec {
    private const val SCHEMA_VERSION = 2
    private const val LIST_SEPARATOR = "\n"

    private val schemaVersion = intPreferencesKey("schema_version")
    private val generation = longPreferencesKey("configuration_generation")
    private val fingerprint = stringPreferencesKey("configuration_fingerprint")
    private val savedAtEpochMillis = longPreferencesKey("saved_at_epoch_millis")
    private val endpointHost = stringPreferencesKey("endpoint_host")
    private val endpointPort = intPreferencesKey("endpoint_port")
    private val peerPublicKey = stringPreferencesKey("peer_public_key")
    private val clientAddress = stringPreferencesKey("client_address")
    private val allowedIPs = stringPreferencesKey("allowed_ips")
    private val persistentKeepalive = intPreferencesKey("persistent_keepalive")
    private val interfaceMTU = intPreferencesKey("interface_mtu")
    private val dnsAddresses = stringPreferencesKey("dns_addresses")
    private val pendingImportId = stringPreferencesKey("pending_import_id")
    private val rowCount = intPreferencesKey("row_count")

    fun decode(preferences: Preferences): StoredChainedUpstreamConfiguration? {
        if (preferences[schemaVersion] == 3) return decodeRows(preferences)
        if (preferences.asMap().isEmpty()) return null
        val version = preferences[schemaVersion]
        if (version != 1 && version != SCHEMA_VERSION) return null
        val pendingId = if (version == SCHEMA_VERSION) preferences[pendingImportId] else null
        if (pendingId != null && pendingId.isBlank()) return null
        val decodedGeneration = preferences[generation]?.takeIf { it > 0L } ?: return null
        val decodedFingerprint = preferences[fingerprint]
            ?.takeIf { it.length == 64 && it.all(::isLowerHexDigit) }
            ?: return null
        val host = preferences[endpointHost] ?: return null
        val port = preferences[endpointPort] ?: return null
        val peerKey = preferences[peerPublicKey] ?: return null
        val address = preferences[clientAddress] ?: return null
        val keepalive = preferences[persistentKeepalive] ?: return null
        val configuration = try {
            ChainedUpstreamConfiguration.create(
                endpointHost = host,
                endpointPort = port,
                peerPublicKey = peerKey,
                clientAddress = address,
                allowedIPs = decodeList(preferences[allowedIPs]),
                persistentKeepaliveSeconds = keepalive,
                interfaceMTU = preferences[interfaceMTU],
                dnsAddresses = decodeList(preferences[dnsAddresses]),
            )
        } catch (_: IllegalArgumentException) {
            return null
        }
        // The stored identity must describe the stored content. The fields and fingerprint are
        // written in one atomic edit, so a mismatch means corruption or a codec bug; fail closed
        // rather than hand back a record whose identity lies about it.
        if (canonicalChainedUpstreamConfigurationFingerprint(configuration) != decodedFingerprint) {
            return null
        }
        return StoredChainedUpstreamConfiguration(
            configuration = configuration,
            identity = ChainedUpstreamIdentity(decodedGeneration, decodedFingerprint),
            savedAtEpochMillis = preferences[savedAtEpochMillis],
            pendingImportId = pendingId,
        )
    }

    fun encode(preferences: MutablePreferences, stored: StoredChainedUpstreamConfiguration) {
        preferences.clear()
        preferences[schemaVersion] = 3
        preferences[generation] = stored.identity.generation
        preferences[fingerprint] = stored.identity.fingerprint
        stored.savedAtEpochMillis?.let { preferences[savedAtEpochMillis] = it }
        stored.pendingImportId?.let { preferences[pendingImportId] = it }
        val rows = stored.configuration.orderedHops
        preferences[rowCount] = rows.size
        rows.forEachIndexed { index, row -> encodeRow(preferences, index, row.withoutEntryHop()) }
    }

    private fun decodeRows(preferences: Preferences): StoredChainedUpstreamConfiguration? {
        val count = preferences[rowCount]?.takeIf { it in 1..2 } ?: return null
        val decodedGeneration = preferences[generation]?.takeIf { it > 0L } ?: return null
        val decodedFingerprint = preferences[fingerprint] ?: return null
        if (decodedFingerprint.length != 64 || !decodedFingerprint.all(::isLowerHexDigit)) return null
        val pending = preferences[pendingImportId]
        if (pending != null && pending.isBlank()) return null
        val rows = (0 until count).map { decodeRow(preferences, it) ?: return null }
        val configuration = try { if (count == 2) rows.last().withEntryHop(rows.first()) else rows.single() }
            catch (_: IllegalArgumentException) { return null }
        if (canonicalChainedUpstreamConfigurationFingerprint(configuration) != decodedFingerprint) return null
        return StoredChainedUpstreamConfiguration(configuration, ChainedUpstreamIdentity(decodedGeneration, decodedFingerprint), preferences[savedAtEpochMillis], pending)
    }

    private fun decodeRow(preferences: Preferences, index: Int): ChainedUpstreamConfiguration? {
        val prefix = "row_${index}_"
        return try {
            ChainedUpstreamConfiguration.create(
                endpointHost = preferences[stringPreferencesKey(prefix + "endpoint_host")] ?: return null,
                endpointPort = preferences[intPreferencesKey(prefix + "endpoint_port")] ?: return null,
                peerPublicKey = preferences[stringPreferencesKey(prefix + "peer_public_key")] ?: return null,
                clientAddress = preferences[stringPreferencesKey(prefix + "client_address")] ?: return null,
                allowedIPs = decodeList(preferences[stringPreferencesKey(prefix + "allowed_ips")]),
                persistentKeepaliveSeconds = preferences[intPreferencesKey(prefix + "persistent_keepalive")] ?: return null,
                interfaceMTU = preferences[intPreferencesKey(prefix + "interface_mtu")],
                dnsAddresses = decodeList(preferences[stringPreferencesKey(prefix + "dns_addresses")]),
                displayName = preferences[stringPreferencesKey(prefix + "display_name")] ?: return null,
                isEnabled = preferences[booleanPreferencesKey(prefix + "enabled")] ?: return null,
            )
        } catch (_: IllegalArgumentException) { null }
    }

    private fun encodeRow(preferences: MutablePreferences, index: Int, configuration: ChainedUpstreamConfiguration) {
        val prefix = "row_${index}_"
        preferences[stringPreferencesKey(prefix + "endpoint_host")] = configuration.endpointHost
        preferences[intPreferencesKey(prefix + "endpoint_port")] = configuration.endpointPort
        preferences[stringPreferencesKey(prefix + "peer_public_key")] = configuration.peerPublicKey
        preferences[stringPreferencesKey(prefix + "client_address")] = configuration.clientAddress
        preferences[stringPreferencesKey(prefix + "allowed_ips")] = configuration.allowedIPs.joinToString(LIST_SEPARATOR)
        preferences[intPreferencesKey(prefix + "persistent_keepalive")] = configuration.persistentKeepaliveSeconds
        configuration.interfaceMTU?.let { preferences[intPreferencesKey(prefix + "interface_mtu")] = it }
        preferences[stringPreferencesKey(prefix + "dns_addresses")] = configuration.dnsAddresses.joinToString(LIST_SEPARATOR)
        preferences[stringPreferencesKey(prefix + "display_name")] = configuration.displayName
        preferences[booleanPreferencesKey(prefix + "enabled")] = configuration.isEnabled
    }

    /** Kept for reading schema 1/2; new writes always publish the whole ordered selection. */
    internal fun encodeLegacy(preferences: MutablePreferences, stored: StoredChainedUpstreamConfiguration) {
        preferences.clear()
        val configuration = stored.configuration
        preferences[schemaVersion] = SCHEMA_VERSION
        preferences[generation] = stored.identity.generation
        preferences[fingerprint] = stored.identity.fingerprint
        stored.savedAtEpochMillis?.let { preferences[savedAtEpochMillis] = it }
        stored.pendingImportId?.let { preferences[pendingImportId] = it }
        preferences[endpointHost] = configuration.endpointHost
        preferences[endpointPort] = configuration.endpointPort
        preferences[peerPublicKey] = configuration.peerPublicKey
        preferences[clientAddress] = configuration.clientAddress
        preferences[allowedIPs] = configuration.allowedIPs.joinToString(LIST_SEPARATOR)
        preferences[persistentKeepalive] = configuration.persistentKeepaliveSeconds
        configuration.interfaceMTU?.let { preferences[interfaceMTU] = it }
        preferences[dnsAddresses] = configuration.dnsAddresses.joinToString(LIST_SEPARATOR)
    }

    private fun decodeList(value: String?): List<String> =
        value?.takeIf { it.isNotEmpty() }?.split(LIST_SEPARATOR) ?: emptyList()
}

private fun isLowerHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f'
