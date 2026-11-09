package com.lavasec.app.foundation.chained

/** Pinned iOS ChainedSetupPolicy: configuration admission is independent of engine eligibility. */
enum class ChainedConfigurationIssue(val message: String) {
    MISSING_CONFIGURATION("Add a WireGuard configuration in VPN chaining, or turn chaining off."),
    MISSING_PRIVATE_KEY("Your WireGuard key is missing. Load the configuration again, or turn chaining off."),
    UNAVAILABLE("Your WireGuard configuration couldn't be read. Try again after unlocking your device."),
}
