package com.lavasec.app.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class LavaIconsTest {
    @Test
    fun `semantic role inventory is complete and stable`() {
        assertEquals(
            setOf(
                "LavaGuard", "LavaFilters", "LavaActivity", "LavaSettings", "LavaTune",
                "LavaDomainHistory", "LavaNetworkActivity", "LavaBlock", "LavaAllowed",
                "LavaPause", "LavaForward",
            ),
            setOf(
                LavaIcons.Guard.name,
                LavaIcons.Filters.name,
                LavaIcons.Activity.name,
                LavaIcons.Settings.name,
                LavaIcons.Tune.name,
                LavaIcons.DomainHistory.name,
                LavaIcons.NetworkActivity.name,
                LavaIcons.Blocked.name,
                LavaIcons.Allowed.name,
                LavaIcons.Pause.name,
                LavaIcons.Forward.name,
            ),
        )
    }

    @Test
    fun `settings gear and onboarding tune are different native roles`() {
        assertNotEquals(LavaIcons.Settings.name, LavaIcons.Tune.name)
        assertNotSame(LavaIcons.Settings, LavaIcons.Tune)
    }
}
