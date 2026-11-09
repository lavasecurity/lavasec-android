package com.lavasec.app.reactnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.storage.CredentialFilterConfigurationRead
import com.lavasec.app.storage.CredentialFilterConfigurationStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repeatable production-owner evidence for the RN filter bridge, without an opt-in argument or
 * operator-preconfigured device. The supported UI creates a free, signed-out fixture if necessary.
 * Commands below establish bridge/owner evidence; the separate whole-app journey establishes
 * actual UI edit evidence. Each saved test rule is removed through the reviewed production path.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactFilterDraftDeviceTest {
    @Test
    fun draftUndoReviewCancelAndSaveUseTheRealOwnerAndDurableConfiguration() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            assertTrue("Background restarts need on-demand WorkManager initialization",
                journey.context.applicationContext is androidx.work.Configuration.Provider)
            androidx.work.WorkManager.getInstance(journey.application)
            val initial = journey.snapshot()
            assertFalse("Use the unprotected CI fixture", initial.getJSONObject("filters").getBoolean("locked"))
            assertFalse("No test entitlement", initial.getJSONObject("plus").getBoolean("enabled"))
            val rows = initial.getJSONObject("filters").getJSONArray("rows")
            val id = (0 until rows.length()).map(rows::getJSONObject).first { it.getBoolean("active") }.getString("id")
            fun command(type: String, payload: JSONObject = JSONObject()) =
                journey.completed(type, payload.put("id", id))
            command("filter.open")
            journey.await("opened active filter") { journey.snapshot().optJSONObject("filterSession")?.optString("id") == id }
            val baseline = journey.snapshot().getJSONObject("filterSession")
            assertFalse("Preserve a pre-existing draft", baseline.getBoolean("editing"))
            val savedBlocked = baseline.getJSONArray("savedBlocked").toString()
            val savedAllowed = baseline.getJSONArray("savedAllowed").toString()
            val domain = "bridge-draft-${UUID.randomUUID()}.invalid"
            var ownsDraft = false
            try {
                command("filter.edit")
                ownsDraft = true
                journey.await("editing owner") { journey.snapshot().getJSONObject("filterSession").getBoolean("editing") }
                val rejected = command("filter.domain", JSONObject().put("domain", "not a domain")
                    .put("decision", "blocked").put("remove", false)).getJSONObject("value")
                assertFalse(rejected.getBoolean("isAccepted"))
                assertTrue(rejected.getString("title").isNotBlank())
                assertTrue(rejected.getString("message").isNotBlank())
                command("filter.domain", JSONObject().put("domain", domain).put("decision", "blocked").put("remove", false))
                journey.await("blocked draft rule") { journey.snapshot().getJSONObject("filterSession").getJSONArray("blocked").toString().contains(domain) }
                command("filter.undoDomain", JSONObject().put("domain", domain).put("decision", "blocked"))
                journey.await("draft undo") { !journey.snapshot().getJSONObject("filterSession").getJSONArray("blocked").toString().contains(domain) }
                command("filter.domain", JSONObject().put("domain", domain).put("decision", "allowed").put("remove", false))
                journey.await("allowed draft rule") { journey.snapshot().getJSONObject("filterSession").getJSONArray("allowed").toString().contains(domain) }
                assertEquals("Weakening must stop at review", "review", command("filter.save").getString("value"))
                assertTrue(command("filter.review").getString("value").isNotBlank())
                command("filter.cancel")
                ownsDraft = false
                journey.await("cancelled owner") { !journey.snapshot().getJSONObject("filterSession").getBoolean("editing") }
                val after = journey.snapshot().getJSONObject("filterSession")
                assertEquals(savedBlocked, after.getJSONArray("blocked").toString())
                assertEquals(savedAllowed, after.getJSONArray("allowed").toString())
                assertEquals(savedBlocked, after.getJSONArray("savedBlocked").toString())
                assertEquals(savedAllowed, after.getJSONArray("savedAllowed").toString())

                command("filter.edit")
                ownsDraft = true
                command("filter.domain", JSONObject().put("domain", domain).put("decision", "blocked").put("remove", false))
                // #856 P2R: every changed draft, strengthening included, stops at the visible Review
                // boundary before any durable mutation; only the confirmed apply persists it.
                assertEquals("A changed strengthening edit stops at Review", "review", command("filter.save").getString("value"))
                val strengtheningReview = command("filter.review").getString("value")
                command("filter.apply", JSONObject().put("review", strengtheningReview))
                ownsDraft = false
                journey.await("persisted strengthening edit", 60_000) { persistedBlocked(journey, id).contains(domain) }
                val previousGeneration = journey.snapshot().getString("generation")
                journey.application.rotateCredentialGraph(previousGeneration.toLong())
                journey.await("reconstructed credential graph", 60_000) {
                    val current = journey.snapshot()
                    current.optBoolean("available") && current.optString("generation") != previousGeneration
                }
                command("filter.open")
                journey.await("saved rule after graph reconstruction") {
                    journey.snapshot().optJSONObject("filterSession")?.getJSONArray("savedBlocked")?.toString()?.contains(domain) == true
                }
                assertTrue(persistedBlocked(journey, id).contains(domain))
                assertFalse("A cancelled allowed exception must not leak into saved state",
                    journey.snapshot().getJSONObject("filterSession").getJSONArray("savedAllowed").toString().contains(domain))
            } finally {
                if (ownsDraft) command("filter.cancel")
                if (persistedBlocked(journey, id).contains(domain)) {
                    command("filter.open")
                    command("filter.edit")
                    command("filter.domain", JSONObject().put("domain", domain).put("decision", "blocked").put("remove", true))
                    assertEquals("review", command("filter.save").getString("value"))
                    val token = command("filter.review").getString("value")
                    command("filter.apply", JSONObject().put("review", token))
                    journey.await("saved fixture cleanup", 60_000) { !persistedBlocked(journey, id).contains(domain) }
                }
            }
            command("filter.open")
            journey.await("restored baseline") {
                val current = journey.snapshot().getJSONObject("filterSession")
                current.getJSONArray("savedBlocked").toString() == savedBlocked && current.getJSONArray("savedAllowed").toString() == savedAllowed
            }
            journey.assertNoFallback()
        }
    }

    private fun persistedBlocked(journey: ReactEmulatorJourney, id: String): Set<String> = runBlocking {
        val read = CredentialFilterConfigurationStore.credentialProtected(journey.context, FeatureLimits.FREE.maxFilterRules).readAuthoritative()
        assertTrue("The authoritative configuration/artifact must reopen: $read", read is CredentialFilterConfigurationRead.Available)
        checkNotNull((read as CredentialFilterConfigurationRead.Available).state.configuration.profile(id)).manualRules.blockedDomains
    }
}
