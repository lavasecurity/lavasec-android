package com.lavasec.app.foundation.subscription

/**
 * Process-local witness for the tier under which a native edit was admitted. Identity, rather
 * than equal limits, distinguishes a lapse followed by a new grant. Never persist or expose to
 * RN. The native plan owner mints this witness and checks it again at durable publication.
 */
class FeatureLimitAdmission(val limits: FeatureLimits)
