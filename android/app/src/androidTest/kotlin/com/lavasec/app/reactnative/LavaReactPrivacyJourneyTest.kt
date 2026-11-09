package com.lavasec.app.reactnative

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real installed app on an emulator: Security fully off clears `FLAG_SECURE` (the shared
 * concealment opt-in, resolved D4) and the populated Guard/Settings surface survives an ordinary
 * task background/resume. Warm-resume continuity itself is pinned by the RN store lifecycle suite
 * (`tests/app-store-privacy-continuity.test.ts`); this journey is the device-observable half of the
 * same opt-in.
 *
 * The Security-on app-lock cover sub-case was removed: with only a non-app-unlock protected surface
 * armed, the shipping `ReactAppLockView` policy admits the foreground (`isAuthorized(APP_UNLOCK)` is
 * trivially true when APP_UNLOCK itself is not protected) and only covers while backgrounded, so a
 * foreground cover assertion was not a valid, deterministic product expectation.
 */
@RunWith(AndroidJUnit4::class)
class LavaReactPrivacyJourneyTest {
    @Test
    fun securityOffClearsFlagSecureAndKeepsPopulatedGuardContent() {
        ReactEmulatorJourney().use { journey ->
            journey.launch()
            journey.ensureConfigured()
            val security = journey.snapshot().getJSONObject("security")
            // Precondition: this fixture runs without a credential or a protected surface.
            assertFalse("Journey precondition: Security is off", security.getBoolean("hasAuthenticationMethod"))
            assertTrue("Journey precondition: no protected surfaces", security.getJSONArray("protectedSurfaces").length() == 0)
            val flags = journey.requireActivity().window.attributes.flags
            assertTrue(
                "Security-off must not keep an always-on FLAG_SECURE",
                flags and WindowManager.LayoutParams.FLAG_SECURE == 0,
            )
            // Native product readiness does not make the asynchronous React accessibility
            // tree stable. Establish the populated baseline with the same bounded read
            // used after resume, before sending any background action.
            journey.await("Security-off Guard and Settings content populated") {
                journey.visible("Guard") && journey.visible("Settings")
            }
            // Security off: the populated content survives a real task background/resume.
            journey.background()
            journey.resume()
            journey.waitLabel("Guard")
            journey.waitLabel("Settings")
            assertTrue("Security-off must not conceal after resume",
                journey.requireActivity().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
            assertFalse("Security-off must not leave a credential",
                journey.snapshot().getJSONObject("security").getBoolean("hasAuthenticationMethod"))
            println("LAVA_UI_PRIVACY_JOURNEY security=off flag_secure=cleared content=populated background=survived")
        }
    }
}
