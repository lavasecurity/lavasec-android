package com.lavasec.app.reactnative

import com.lavasec.app.foundation.filter.ManualRuleRejection

internal fun manualRuleRejectionMessage(reason: ManualRuleRejection): String = when (reason) {
    ManualRuleRejection.INVALID_DOMAIN -> "Enter a valid domain, such as example.com."
    ManualRuleRejection.ALREADY_ALLOWED -> "This domain is already allowed."
    ManualRuleRejection.ALREADY_BLOCKED -> "This domain is already blocked."
    ManualRuleRejection.ALLOWED_LIMIT_REACHED -> "You have reached the allowed-domain limit for your plan. Remove an entry before adding another."
    ManualRuleRejection.BLOCKED_LIMIT_REACHED -> "You have reached the blocked-domain limit for your plan. Remove an entry before adding another."
}
