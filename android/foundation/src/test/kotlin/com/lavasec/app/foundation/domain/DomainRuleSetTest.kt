package com.lavasec.app.foundation.domain

import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.DomainRuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DomainRuleSetTest {
    @Test
    fun `suffix and exact rules preserve pinned matching collisions`() {
        val rules = DomainRuleSet.of(
            DomainRule.parse("ads.example.com", matchesSubdomains = true),
            DomainRule.parse("login.example.com", matchesSubdomains = false),
        )

        assertTrue(rules.contains("ads.example.com"))
        assertTrue(rules.contains("cdn.ads.example.com"))
        assertFalse(rules.contains("example.com"))
        assertTrue(rules.contains("login.example.com"))
        assertFalse(rules.contains("cdn.login.example.com"))
    }

    @Test
    fun `same domain in exact and suffix categories counts twice but all domains collapses it`() {
        val rules = DomainRuleSet.of(
            DomainRule.parse("ads.example.com", matchesSubdomains = false),
            DomainRule.parse("ads.example.com", matchesSubdomains = true),
        )

        assertEquals(2, rules.count)
        assertEquals(setOf("ads.example.com"), rules.allDomains)
        assertEquals(listOf("ads.example.com"), rules.exactDomainList)
        assertEquals(listOf("ads.example.com"), rules.suffixDomainList)
    }

    @Test
    fun `construction copies inputs and every update returns a new immutable rule set`() {
        val source = mutableListOf(DomainRule.parse("one.example.com"))
        val original = DomainRuleSet.from(source)
        source += DomainRule.parse("source-alias.example.com")
        val updated = original.adding(DomainRule.parse("two.example.com"))

        assertEquals(1, original.count)
        assertFalse(original.contains("two.example.com"))
        assertEquals(2, updated.count)
        try {
            (original.allDomains as MutableSet<String>) += "mutated.example.com"
            fail("immutable rule view accepted a mutation")
        } catch (_: UnsupportedOperationException) {
            assertEquals(setOf("one.example.com"), original.allDomains)
        }
    }

    @Test
    fun `effective count subtracts matching allow entries but not guardrail overlaps`() {
        val blocks = DomainRuleSet.of(
            DomainRule.parse("linkedin.com"),
            DomainRule.parse("www.linkedin.com"),
            DomainRule.parse("static.linkedin.com"),
            DomainRule.parse("danger.example.com"),
        )
        val allows = DomainRuleSet.of(
            DomainRule.parse("linkedin.com"),
            DomainRule.parse("danger.example.com"),
            DomainRule.parse("unblocked.example.com"),
        )
        val guardrails = DomainRuleSet.of(DomainRule.parse("danger.example.com"))

        assertEquals(2, blocks.effectiveBlockedDomainRuleCount(allows))
        assertEquals(3, blocks.effectiveBlockedDomainRuleCount(allows, guardrails))
    }

    @Test
    fun `union and protected filtering retain exact versus suffix categories`() {
        val exact = DomainRuleSet.of(DomainRule.parse("login.example.com", matchesSubdomains = false))
        val suffix = DomainRuleSet.of(DomainRule.parse("example.com", matchesSubdomains = true))

        assertEquals(2, exact.union(suffix).count)
        assertTrue(exact.filteringOutRules(matchedBy = suffix).isEmpty)
        assertEquals(exact, exact.union(DomainRuleSet.EMPTY))
    }
}
