package com.lavasec.app.foundation.preferences

/**
 * The wire value union the canonical iOS `settings.set` command accepts
 * (`value: string | boolean | number` in `ReactNative/app/contract.ts`).
 *
 * Android keeps the union closed and typed so the [ProductPreferences.set]
 * dispatch can mirror iOS's per-key type checks without accepting an
 * `Any`/JSON blob.
 */
sealed interface PreferenceValue {
    data class Bool(val value: Boolean) : PreferenceValue

    data class IntValue(val value: Int) : PreferenceValue

    data class Text(val value: String) : PreferenceValue
}

/** The value shape a preference key accepts. */
enum class PreferenceValueType {
    BOOLEAN,
    INTEGER,
    TEXT,
}

val PreferenceValue.type: PreferenceValueType
    get() = when (this) {
        is PreferenceValue.Bool -> PreferenceValueType.BOOLEAN
        is PreferenceValue.IntValue -> PreferenceValueType.INTEGER
        is PreferenceValue.Text -> PreferenceValueType.TEXT
    }

/** A `settings.set` key outside the canonical retained set. */
class UnknownPreferenceKeyException(key: String) :
    IllegalArgumentException("Unknown product preference key: $key")

/** A value rejected because its shape or domain does not match the key. */
class InvalidPreferenceValueException(key: String, detail: String) :
    IllegalArgumentException("Invalid value for product preference \"$key\": $detail")
