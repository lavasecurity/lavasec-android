package com.lavasec.app.foundation.filter

/** Minimal lookup contract shared by in-memory rules and mapped artifact tables. */
interface DomainRuleLookup {
    val count: Int
    fun containsNormalized(normalizedDomain: String): Boolean
}
