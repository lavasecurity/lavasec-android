package com.lavasec.app.foundation.feedback

/**
 * Domain/account-data/identifier redaction for user-authored bug-report text,
 * per the infra bug-report data-minimization plan (2026-05-25): the report body
 * must never carry a domain, an account identifier, or an email unless a
 * separate explicit-consent contract authorizes it. Free text is always
 * redacted; the explicitly authorized attachments (local Activity export,
 * diagnostics) travel through their own consent gates.
 *
 * The redaction replaces every recognized token with a fixed marker so the
 * review screen can honestly show the user exactly what will leave the device,
 * and no raw value ever reaches the composed report body.
 */
object FeedbackRedactor {
    private const val DOMAIN_MARKER = "[domain]"
    private const val EMAIL_MARKER = "[email]"
    private const val ID_MARKER = "[id]"

    fun redact(text: String): String {
        var result = text
        // Emails before domains: "user@example.com" must collapse to one
        // [email] marker, not a partially redacted domain remainder.
        result = EMAIL.replace(result, EMAIL_MARKER)
        // URLs first so the scheme and path survive around the redacted host.
        result = URL.replace(result) { match -> "${match.groupValues[1]}$DOMAIN_MARKER${match.groupValues[3]}" }
        // Bare hostnames and IP literals.
        result = HOSTNAME.replace(result, DOMAIN_MARKER)
        result = IPV4.replace(result, DOMAIN_MARKER)
        // Long digit runs are account/report identifiers, never human prose.
        result = LONG_DIGITS.replace(result, ID_MARKER)
        return result
    }

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val URL = Regex("(https?://)([A-Za-z0-9.-]+)(/[^\\s]*)", RegexOption.IGNORE_CASE)
    // Hostname detection must not depend on a TLD allowlist: any valid suffix is
    // redacted, so a bare domain like "customer.example.nl" is caught too. The
    // final label is purely alphabetic and 2-63 characters (the hostname rule),
    // preceded by at least one dotted label; "i.e." and "e.g." stay human prose.
    private val HOSTNAME = Regex(
        "(?<![A-Za-z0-9])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+" +
            "[A-Za-z]{2,63}(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val IPV4 = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b")
    private val LONG_DIGITS = Regex("\\b\\d{6,}\\b")
}
