package com.lavasec.app.foundation.backup

/** Stable identifiers for the backup-password policy checks. User copy is host-supplied, never here. */
enum class BackupPasswordRequirementID {
    MINIMUM_LENGTH,
    NUMBER,
    SYMBOL,
    MATCHES_CONFIRMATION,
}

/** One evaluated policy check and whether the current input satisfies it. */
data class BackupPasswordRequirement(
    val id: BackupPasswordRequirementID,
    val isSatisfied: Boolean,
)

/** The full policy evaluation; valid only when every requirement is satisfied. */
data class BackupPasswordValidationResult(
    val requirements: List<BackupPasswordRequirement>,
) {
    val isValid: Boolean get() = requirements.all { it.isSatisfied }
}

/**
 * Pure backup-password policy, ported from the canonical iOS `BackupPasswordPolicy`
 * (pinned at `c4658d9`): at least 8 characters, at least one decimal digit, at least one
 * punctuation/symbol character, and a non-empty password matching its confirmation.
 *
 * The canonical requirement labels are localized presentation copy and stay in the host; this
 * module exposes only the stable [BackupPasswordRequirementID]s and their satisfaction.
 */
object BackupPasswordPolicy {

    const val MINIMUM_LENGTH = 8

    fun validate(password: String, confirmation: String): BackupPasswordValidationResult =
        BackupPasswordValidationResult(
            requirements = listOf(
                BackupPasswordRequirement(
                    id = BackupPasswordRequirementID.MINIMUM_LENGTH,
                    isSatisfied = password.length >= MINIMUM_LENGTH,
                ),
                BackupPasswordRequirement(
                    id = BackupPasswordRequirementID.NUMBER,
                    isSatisfied = password.any { it.isDigit() },
                ),
                BackupPasswordRequirement(
                    id = BackupPasswordRequirementID.SYMBOL,
                    isSatisfied = password.any { it.isPunctuationOrSymbol() },
                ),
                BackupPasswordRequirement(
                    id = BackupPasswordRequirementID.MATCHES_CONFIRMATION,
                    isSatisfied = password.isNotEmpty() && password == confirmation,
                ),
            ),
        )

    /**
     * Mirrors Foundation's `CharacterSet.punctuationCharacters.union(.symbols)`: punctuation and
     * symbol categories only. Whitespace and control characters are excluded, so a space never
     * satisfies the symbol requirement.
     */
    private fun Char.isPunctuationOrSymbol(): Boolean = when (Character.getType(this)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt(),
        -> true

        else -> false
    }
}
