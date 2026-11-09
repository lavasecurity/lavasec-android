package com.lavasec.app.foundation.flows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFlowInventoryTest {

    @Test
    fun `canonical wire names exactly match the pinned iOS allowlist`() {
        assertEquals(
            linkedSetOf(
                "account",
                "backupSetup",
                "backupRestore",
                "passcode",
                "automation",
                "import",
                "importCode",
                "importScan",
                "licenses",
                "feedback",
                "customDNS",
                "phoneQA",
            ),
            NativeFlow.wireNames,
        )
    }

    @Test
    fun `every canonical flow is classified as bridge callable`() {
        assertEquals(12, NativeFlow.entries.size)
        for (flow in NativeFlow.entries) {
            assertEquals(
                NativeFlowClassification.BridgeCallable(flow),
                NativeFlowInventory.classify(flow.wireName),
            )
            assertTrue(NativeFlowInventory.isCanonicalFlowName(flow.wireName))
            assertFalse(NativeFlowInventory.isInternalPresentationName(flow.wireName))
        }
    }

    @Test
    fun `release and debug availability match the pin`() {
        // `phoneQA` is allowlisted but the release guard rejects it; every other canonical flow is
        // present in all builds.
        assertEquals(
            NativeFlow.wireNames - setOf("phoneQA"),
            NativeFlowInventory.availableWireNames(NativeFlowBuildConfiguration.RELEASE),
        )
        assertEquals(
            NativeFlow.wireNames,
            NativeFlowInventory.availableWireNames(NativeFlowBuildConfiguration.DEBUG),
        )
        assertEquals(
            NativeFlow.wireNames,
            NativeFlowInventory.availableWireNames(NativeFlowBuildConfiguration.QA),
        )
    }

    @Test
    fun `an ordinary flow is available in every build`() {
        for (configuration in NativeFlowBuildConfiguration.entries) {
            assertEquals(
                NativeFlowValidation.Available(NativeFlow.ACCOUNT),
                NativeFlowInventory.validate("account", configuration),
            )
        }
    }

    @Test
    fun `the QA flow is unavailable in release and available in debug and qa`() {
        assertEquals(
            NativeFlowValidation.Unavailable(NativeFlowUnavailableReason.QA_FLOW_IN_RELEASE),
            NativeFlowInventory.validate("phoneQA", NativeFlowBuildConfiguration.RELEASE),
        )
        assertEquals(
            NativeFlowValidation.Available(NativeFlow.PHONE_QA),
            NativeFlowInventory.validate("phoneQA", NativeFlowBuildConfiguration.DEBUG),
        )
        assertEquals(
            NativeFlowValidation.Available(NativeFlow.PHONE_QA),
            NativeFlowInventory.validate("phoneQA", NativeFlowBuildConfiguration.QA),
        )
    }

    @Test
    fun `internal presentations are classified but never bridge callable`() {
        for (presentation in NativeFlowInternalPresentation.entries) {
            assertEquals(
                NativeFlowClassification.InternalPresentation(presentation),
                NativeFlowInventory.classify(presentation.wireName),
            )
            assertTrue(NativeFlowInventory.isInternalPresentationName(presentation.wireName))
            assertFalse(NativeFlowInventory.isCanonicalFlowName(presentation.wireName))
            for (configuration in NativeFlowBuildConfiguration.entries) {
                assertEquals(
                    NativeFlowValidation.Unavailable(NativeFlowUnavailableReason.INTERNAL_PRESENTATION),
                    NativeFlowInventory.validate(presentation.wireName, configuration),
                )
            }
        }
    }

    @Test
    fun `the internal presentations are exactly the native-only routes`() {
        assertEquals(
            linkedSetOf(
                "preparation",
                "deepLinkImport",
                "phoneQASheet",
                "createFilter",
                "renameFilter",
                "deleteFilters",
                "share",
                "vpnConfiguration",
                "customBlocklist",
            ),
            NativeFlowInternalPresentation.wireNames,
        )
    }

    @Test
    fun `canonical and internal vocabularies are disjoint`() {
        assertTrue(
            (NativeFlow.wireNames intersect NativeFlowInternalPresentation.wireNames).isEmpty(),
        )
    }

    @Test
    fun `unknown flow names are rejected`() {
        assertNull(NativeFlowInventory.classify("notAFlow"))
        assertEquals(
            NativeFlowValidation.Unknown,
            NativeFlowInventory.validate("notAFlow", NativeFlowBuildConfiguration.RELEASE),
        )
        assertEquals(
            NativeFlowValidation.Unknown,
            NativeFlowInventory.validate("", NativeFlowBuildConfiguration.QA),
        )
    }

    @Test
    fun `qa tools build flag follows the iOS preprocessor pair`() {
        assertFalse(NativeFlowBuildConfiguration.RELEASE.qaToolsEnabled)
        assertTrue(NativeFlowBuildConfiguration.DEBUG.qaToolsEnabled)
        assertTrue(NativeFlowBuildConfiguration.QA.qaToolsEnabled)
    }
}
