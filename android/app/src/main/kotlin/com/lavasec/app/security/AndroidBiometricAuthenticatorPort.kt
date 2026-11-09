package com.lavasec.app.security

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import com.lavasec.app.foundation.biometrics.AndroidBiometricFeature
import com.lavasec.app.foundation.biometrics.BiometricAvailability
import com.lavasec.app.foundation.biometrics.BiometricManagerStatus
import com.lavasec.app.foundation.biometrics.BiometricPromptResult
import com.lavasec.app.foundation.biometrics.androidBiometricAvailability
import com.lavasec.app.foundation.biometrics.biometricPromptErrorFromAndroidValue
import com.lavasec.app.foundation.biometrics.onlyWeakBiometricAvailable
import com.lavasec.app.foundation.biometrics.toPromptResult
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Production [BiometricAuthenticatorPort] over the framework
 * `android.hardware.biometrics.BiometricPrompt` / `BiometricManager` (available since minSdk 28, so
 * no additional dependency is added). The framework prompt is built from a `Context` and cancelled
 * through the `CancellationSignal` passed to `authenticate`; API 30-only knobs are version-guarded
 * and API 28 reads enrollment through the legacy `FingerprintManager`.
 *
 * The prompt is hosted on the current foreground Activity when one is available ([currentActivity]),
 * so the system dialog is associated with the visible task; otherwise the application context is
 * used. No strong Activity reference is ever retained.
 *
 * The port owns no grant: it only presents the platform prompt and classifies the result into the
 * pure foundation vocabulary. Two invariants hold:
 *  - a prompt that was never presented, ran while not [BiometricAvailability.Available], or was
 *    cancelled/superseded can never resolve to [BiometricPromptResult.Success]; and
 *  - [cancel] revokes the in-flight prompt through the signal's `cancel()`, and the
 *    [BiometricAttemptGate] discards a late callback for a retired attempt.
 *
 * `isLockedOut` is derived from the last observed prompt lockout. Both `ERROR_LOCKOUT` and
 * `ERROR_LOCKOUT_PERMANENT` are cached as a bounded cooldown: a permanent lockout is cleared by the
 * user authenticating with the device credential, which this port cannot observe, so the cache
 * expires and the next attempt re-evaluates against the platform rather than blocking biometrics
 * until process restart.
 */
internal class AndroidBiometricAuthenticatorPort(
    context: Context,
    private val currentActivity: () -> Activity?,
) : BiometricAuthenticatorPort {

    private val applicationContext = context.applicationContext
    private val executor: Executor = applicationContext.mainExecutor
    private val gate = BiometricAttemptGate()
    private val lock = Any()
    private var activeSignal: CancellationSignal? = null
    private var activeContinuation: CancellableContinuation<BiometricPromptResult>? = null

    /**
     * Set when the enrollment-bound Keystore key is permanently invalidated (a new biometric was
     * enrolled): the authorization must fall back to the passcode until the user re-enables.
     */
    @Volatile
    private var keyInvalidated = false

    @Volatile
    private var lockoutUntilElapsedRealtime = 0L

    override fun availability(): BiometricAvailability = runCatching {
        androidBiometricAvailability(
            statusValue = weakStatusValue(),
            features = declaredFeatures(),
            // `onlyWeakBiometricAvailable` catches an API 30+ weak-only device; `api29CredentialOnly`
            // catches the API 29 no-arg probe's DEVICE_CREDENTIAL inclusion, which would otherwise
            // name a biometric on a PIN/password-only device.
            weakCredentialOnly = onlyWeakBiometricAvailable(strongStatusValue(), weakStatusValue()) ||
                api29CredentialOnly(),
            keyInvalidated = keyInvalidated,
        )
    }.getOrDefault(BiometricAvailability.Unavailable)

    override fun isLockedOut(): Boolean =
        lockoutUntilElapsedRealtime > SystemClock.elapsedRealtime()

    override suspend fun authenticate(purpose: String): BiometricPromptResult {
        if (!availability().isUsable) return BiometricPromptResult.AuthenticationDisabled
        val host: Context = currentActivity() ?: applicationContext
        return suspendCancellableCoroutine { continuation ->
            val attempt = gate.begin()
            val signal = CancellationSignal()
            val registered = synchronized(lock) {
                if (activeSignal != null || !gate.isCurrent(attempt)) {
                    false
                } else {
                    activeContinuation = continuation
                    activeSignal = signal
                    true
                }
            }
            if (!registered) {
                continuation.resume(BiometricPromptResult.Failed)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { revoke(attempt) }
            // Bind the prompt to an enrollment-invalidated Keystore key: a biometric enrolled after
            // this key was created can no longer unlock, forcing the passcode fallback. A
            // CryptoObject-bound key needs Class 3, so derive `strong` from the platform's actual
            // strong-biometric availability rather than from whether a cipher was created: on a
            // weak-only (Class 2) device the cipher can still be created, and binding it would
            // restrict the prompt to BIOMETRIC_STRONG, which that authenticator can never satisfy.
            val strongBiometricAvailable =
                strongStatusValue() == BiometricManagerStatus.BIOMETRIC_SUCCESS.androidValue
            val cryptoObject = if (strongBiometricAvailable) {
                biometricCipher()?.let { BiometricPrompt.CryptoObject(it) }
            } else {
                null
            }
            // `biometricCipher` records a permanently invalidated key. That must force the passcode
            // fallback rather than the unbound weak prompt, or a freshly enrolled biometric could
            // unlock the app on the first authentication after enrollment.
            if (keyInvalidated) {
                settle(attempt, BiometricPromptResult.AuthenticationDisabled)
                return@suspendCancellableCoroutine
            }
            val prompt = buildPrompt(host, purpose, attempt, strong = cryptoObject != null)
            val started = runCatching {
                if (cryptoObject != null) {
                    prompt.authenticate(cryptoObject, signal, executor, callback(attempt, requireCrypto = true))
                } else {
                    prompt.authenticate(signal, executor, callback(attempt, requireCrypto = false))
                }
            }.isSuccess
            // A prompt that could not be built/presented is a recoverable failure, never a success.
            if (!started) settle(attempt, BiometricPromptResult.Failed)
        }
    }

    override fun cancel() {
        // Invalidate and clear the live attempt synchronously, then cancel its signal. Reading the
        // attempt and the signal under ONE critical section prevents a settled attempt from
        // cancelling a fresh prompt registered between the two reads.
        val (attempt, signal) = synchronized(lock) { gate.currentAttempt() to activeSignal }
        if (attempt == 0L) return
        signal?.cancel()
        settle(attempt, BiometricPromptResult.Cancelled)
    }

    private fun buildPrompt(host: Context, purpose: String, attempt: Long, strong: Boolean): BiometricPrompt {
        val negativeText = applicationContext.getString(
            com.lavasec.app.presentation.R.string.security_enter_passcode,
        )
        val builder = BiometricPrompt.Builder(host)
            .setTitle(purpose)
            .setNegativeButton(negativeText, executor, DialogInterface.OnClickListener { _, _ ->
                // The user asked for the passcode path; resolve as fallback, never a success.
                settle(attempt, BiometricPromptResult.FallbackRequested)
            })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // A CryptoObject-bound keystore key needs Class 3; otherwise the weak (Class 2) probe
            // keeps the previously supported devices.
            builder.setAllowedAuthenticators(
                if (strong) BiometricManager.Authenticators.BIOMETRIC_STRONG
                else BiometricManager.Authenticators.BIOMETRIC_WEAK,
            )
        }
        return builder.build()
    }

    private fun callback(attempt: Long, requireCrypto: Boolean): BiometricPrompt.AuthenticationCallback =
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                // When the prompt was bound to an enrollment-invalidated key, a success without the
                // crypto object is not acceptable evidence; never fabricate a success.
                if (requireCrypto && result?.cryptoObject == null) {
                    settle(attempt, BiometricPromptResult.Failed)
                } else {
                    settle(attempt, BiometricPromptResult.Success)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                settle(attempt, biometricPromptErrorFromAndroidValue(errorCode).toPromptResult())
            }

            override fun onAuthenticationFailed() {
                // A rejected sample is non-terminal: keep waiting for a terminal callback.
            }
        }

    private fun settle(attempt: Long, result: BiometricPromptResult) {
        val continuation: CancellableContinuation<BiometricPromptResult>?
        synchronized(lock) {
            if (!gate.settle(attempt)) return
            continuation = activeContinuation
            activeContinuation = null
            activeSignal = null
        }
        recordLockout(result)
        if (continuation?.isActive == true) continuation.resume(result)
    }

    private fun revoke(attempt: Long) {
        val signal: CancellationSignal?
        synchronized(lock) {
            if (!gate.invalidate(attempt)) return
            activeContinuation = null
            signal = activeSignal
            activeSignal = null
        }
        signal?.cancel()
    }

    private fun recordLockout(result: BiometricPromptResult) {
        when (result) {
            is BiometricPromptResult.Lockout -> {
                // Both lockout forms are a bounded cooldown. A permanent lockout is cleared by the
                // user authenticating with the device credential, which this port cannot observe;
                // expiring the cache lets the next attempt re-evaluate against the platform rather
                // than blocking biometrics until the process restarts. A still-locked device simply
                // re-reports the lockout while the passcode fallback remains available.
                lockoutUntilElapsedRealtime = SystemClock.elapsedRealtime() + LOCKOUT_WINDOW_MILLIS
            }

            BiometricPromptResult.Success -> clearLockout()
            else -> Unit
        }
    }

    private fun clearLockout() {
        lockoutUntilElapsedRealtime = 0L
    }

    override fun resetEnrollmentBoundKey(): Boolean = synchronized(lock) {
        val regenerated = runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
            generateBiometricKey()
        }.isSuccess
        if (regenerated) {
            keyInvalidated = false
            clearLockout()
        }
        regenerated
    }

    private fun declaredFeatures(): Set<AndroidBiometricFeature> {
        val packageManager = applicationContext.packageManager
        return buildSet {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
                add(AndroidBiometricFeature.FINGERPRINT)
            }
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)) {
                add(AndroidBiometricFeature.FACE)
            }
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_IRIS)) {
                add(AndroidBiometricFeature.IRIS)
            }
        }
    }

    /** The weak (Class 2) probe; on API 28 it is the legacy fingerprint state. */
    private fun weakStatusValue(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            biometricManager()?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                ?: BiometricManagerStatus.BIOMETRIC_ERROR_NO_HARDWARE.androidValue

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            biometricManager()?.canAuthenticate()
                ?: BiometricManagerStatus.BIOMETRIC_ERROR_NO_HARDWARE.androidValue

        else -> legacyFingerprintStatus()
    }

    /** The strong (Class 3) probe; API levels that cannot distinguish reuse the weak value. */
    private fun strongStatusValue(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        biometricManager()?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            ?: BiometricManagerStatus.BIOMETRIC_ERROR_NO_HARDWARE.androidValue
    } else {
        weakStatusValue()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun biometricManager(): BiometricManager? =
        applicationContext.getSystemService(BiometricManager::class.java)

    /**
     * On API 29 `BiometricManager.canAuthenticate()` (the only probe before API 30) is equivalent to
     * `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`, so a device secured only by a PIN/password reports
     * success. Reclassify that as credential-only when there is no biometric feature at all, or when
     * fingerprint is the only feature and no fingerprint is enrolled. Face/iris enrollment has no
     * public per-modality API on API 29, so those fall back to the platform probe (best effort).
     */
    private fun api29CredentialOnly(): Boolean {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) return false
        val features = declaredFeatures()
        return when {
            features.isEmpty() -> true
            features == setOf(AndroidBiometricFeature.FINGERPRINT) -> !fingerprintEnrolled()
            else -> false
        }
    }

    @Suppress("DEPRECATION")
    private fun fingerprintEnrolled(): Boolean =
        applicationContext.getSystemService(FingerprintManager::class.java)
            ?.takeIf { it.isHardwareDetected }
            ?.hasEnrolledFingerprints() == true

    @Suppress("DEPRECATION")
    private fun legacyFingerprintStatus(): Int {
        // API 28 (below there is no BiometricManager) exposes no per-modality enrollment probe. A
        // declared face/iris authenticator is therefore offered optimistically so it is not
        // permanently unavailable on a face-only device; the framework BiometricPrompt decides and
        // a failure falls back to the passcode.
        val fingerprint = applicationContext.getSystemService(FingerprintManager::class.java)
        val hasFingerprintHardware = fingerprint?.isHardwareDetected == true
        val hasNonFingerprintHardware =
            declaredFeatures().any { it != AndroidBiometricFeature.FINGERPRINT }
        return when {
            hasFingerprintHardware && fingerprint!!.hasEnrolledFingerprints() ->
                BiometricManagerStatus.BIOMETRIC_SUCCESS.androidValue

            hasFingerprintHardware ->
                BiometricManagerStatus.BIOMETRIC_ERROR_NONE_ENROLLED.androidValue

            hasNonFingerprintHardware ->
                BiometricManagerStatus.BIOMETRIC_SUCCESS.androidValue

            else -> BiometricManagerStatus.BIOMETRIC_ERROR_NO_HARDWARE.androidValue
        }
    }

    /**
     * A Cipher over a Keystore key that requires user authentication and is invalidated by a new
     * biometric enrollment, or null when the platform cannot create/initialize it (a weak-only
     * device), or when the key was permanently invalidated (an enrollment change → [keyInvalidated]).
     */
    private fun biometricCipher(): Cipher? = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = (keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey) ?: generateBiometricKey()
        Cipher.getInstance(BIOMETRIC_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }.getOrElse { failure ->
        if (failure is KeyPermanentlyInvalidatedException ||
            failure.cause is KeyPermanentlyInvalidatedException
        ) {
            keyInvalidated = true
        }
        null
    }

    private fun generateBiometricKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                BIOMETRIC_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        /** Android's documented `ERROR_LOCKOUT` window: 5 failed attempts, then 30 seconds. */
        const val LOCKOUT_WINDOW_MILLIS = 30_000L

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val BIOMETRIC_KEY_ALIAS = "lavasec.biometric.gate"
        const val BIOMETRIC_TRANSFORMATION = "AES/CBC/PKCS7Padding"
    }
}
