package com.lavasec.app.settings

import com.lavasec.app.foundation.notifications.NotificationCategory
import com.lavasec.app.foundation.notifications.NotificationPreferences
import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.ProductPreferenceKey
import com.lavasec.app.foundation.preferences.ProductPreferences
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The outcome of one retained-preference write through the owner's admission fence. */
internal enum class PreferenceWrite {
    APPLIED,
    INVALID,
    FAILED,
    CLOSED,
}

/** The outcome of one notification-category write through the owner's admission fence. */
internal enum class NotificationWrite {
    APPLIED,
    FAILED,
    CLOSED,
}

/**
 * The live retained preferences the bridge projects: the canonical retained product
 * preference model plus the four independent notification-category toggles.
 */
internal data class ProductSettingsState(
    val preferences: ProductPreferences = ProductPreferences.canonicalDefaults(),
    val notifications: NotificationPreferences = NotificationPreferences.canonicalDefaults(),
)

/**
 * Serial, durable owner of the retained preference models (`appearance`, `look`,
 * `matchTextSize`, `textSize` and the four notification categories).
 *
 * The pure foundation models ([ProductPreferences], [NotificationPreferences]) own the
 * canonical validation and update rules; this owner only serializes writes, persists
 * through the injected port, and publishes the accepted model. It never runs an update
 * the model rejected and never publishes a value the store did not acknowledge, so a
 * failed durable write leaves the previous value authoritative.
 *
 * `haptics` and the `logs.*` retention keys are deliberately not owned here: the
 * customization owner is their durable authority and retention is delegated to
 * [com.lavasec.app.foundation.privacy.PrivacyRetention] by the command dispatcher.
 */
internal class ProductSettingsOwner(
    initialPreferences: ProductPreferences,
    initialNotifications: NotificationPreferences,
    private val persistPreferences: suspend (ProductPreferences) -> Boolean,
    private val persistNotifications: suspend (NotificationPreferences) -> Boolean,
    private val admitsLook: (requested: String, current: String) -> Boolean = { _, _ -> true },
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val admission = Mutex()
    private val lock = Any()
    private val mutableState = MutableStateFlow(
        ProductSettingsState(initialPreferences, initialNotifications),
    )

    val state: StateFlow<ProductSettingsState> = mutableState.asStateFlow()

    /** Validates through [ProductPreferences], then persists and publishes the accepted model. */
    suspend fun setPreference(key: ProductPreferenceKey, value: PreferenceValue): PreferenceWrite {
        if (closed.get()) return PreferenceWrite.CLOSED
        return admission.withLock {
            if (closed.get()) return@withLock PreferenceWrite.CLOSED
            val updated = mutableState.value.preferences.set(key, value).getOrNull()
                ?: return@withLock PreferenceWrite.INVALID
            if (key == ProductPreferenceKey.LOOK &&
                !admitsLook(updated.look.wire, mutableState.value.preferences.look.wire)) {
                return@withLock PreferenceWrite.INVALID
            }
            val persisted = try {
                persistPreferences(updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (persisted) {
                publish(mutableState.value.copy(preferences = updated))
                PreferenceWrite.APPLIED
            } else {
                PreferenceWrite.FAILED
            }
        }
    }

    /** Applies one independent notification-category toggle and persists the emitted set. */
    suspend fun setNotification(category: NotificationCategory, enabled: Boolean): NotificationWrite {
        if (closed.get()) return NotificationWrite.CLOSED
        return admission.withLock {
            if (closed.get()) return@withLock NotificationWrite.CLOSED
            val updated = mutableState.value.notifications.set(category, enabled)
            val persisted = try {
                persistNotifications(updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (persisted) {
                publish(mutableState.value.copy(notifications = updated))
                NotificationWrite.APPLIED
            } else {
                NotificationWrite.FAILED
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
    }

    private fun publish(state: ProductSettingsState) {
        synchronized(lock) {
            if (!closed.get()) mutableState.value = state
        }
    }
}
