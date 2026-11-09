"""The emulator gate must reject partial or substituted evidence, even when a runner says OK."""

import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
import android_emulator_evidence as evidence


# Independent, explicit journey identities. A class count alone must not let arbitrary method
# names replace the product journey or a cancellation/recovery case.
NEW_REQUIRED_CASES = {
    "com.lavasec.app.vpn.DeviceResolverReconnectInstrumentationTest": (
        "deviceRejectionUsesRealFailureQualifiedReconnectThenTlsFallbackAndPrimaryRecovery",
        "mismatchingDeviceWireIdsStopBeforeEncryptedFallback"),
    "com.lavasec.app.reactnative.LavaReactShareCardJourneyTest": (
        "shippingComposerRetainsFullQrAndDisclosureAcrossTenLocalesThenCancelsRealImageChooser",),
    "com.lavasec.app.reactnative.LavaReactDeviceQaCompositeJourneyTest": (
        "shippingQaCompositeCancelsCommitsOneGenerationRefusesFreeCustomAndResetsRetainedData",),
    "com.lavasec.app.reactnative.LavaReactDeviceQaCanaryJourneyTest": (
        "shippingQaCanaryNumericRefusalCancelArmReopenAndDisarmPreserveProductAuthority",),
    "com.lavasec.app.reactnative.LavaReactExploreSudokuJourneyTest": (
        "shippingExplorePlayPauseResumeLearningAndBlurBackKeepTheSameProductOwner",
        "shippingSudokuKeypadResetAndNewCancelCloseAndReopenRetainTheActualOwnerGame"),
    "com.lavasec.app.reactnative.LavaReactPasscodeJourneyTest": (
        "shippingPasscodeCancelSetupWrongCurrentBackReopenAndVerifiedRemovalUseNativeCredentialAuthority",),
    "com.lavasec.app.reactnative.LavaReactPasscodeLifecycleJourneyTest": (
        "destroyedPasscodeHostCancelsItsCommandAndReopenedReactContextAcceptsRealShareQuery",),
    "com.lavasec.app.reactnative.LavaReactSharedForegroundLifecycleJourneyTest": (
        "destroyedSharedForegroundHostCancelsPendingVisitAndReopenedContextRunsFreshLicenses",),
    "com.lavasec.app.reactnative.AndroidBundledDemoNarrationInstrumentationTest": (
        "approvedPackagedClipActuallyCompletesWithOneReleasedNativeDecoder",
        "foregroundClipCancellationSettlesFalseAndReleasesTheActualNativeDecoder"),
    "com.lavasec.app.reactnative.LavaNativePageLayoutInstrumentationTest": (
        "preAttachmentQaHostRendersActualChildrenAndUsesLatestYogaFootprint",
        "laterQaMountAndQueuedLayoutRespectBlurRecycleDetachAndDisposal"),
    "com.lavasec.app.vpn.qa.DnsLeakCanaryInstrumentationTest": (
        "debugCanaryEmitsExactPhysicalUdpOnlyForCurrentArmedEstablishedAttempt",
        "debugCanaryRefusesDisarmCancellationAndCloseAfterRealSocketPreparation",
        "stalePreparedAttemptCannotSendOrCloseSuccessorAndTransportRebindNeverRefires",
        "absentHandshakeAndRefusedProtectionNeverSendAndNoSendRetryUsesSameSession"),
    "com.lavasec.app.reactnative.LavaReactFilterReviewJourneyTest": (
        "shippingChangedActiveInactiveAndNewFilterSavesEnterVisibleReviewThenCancelOrConfirm",
        "shippingUnchangedEditAndUntouchedNewTemplateSkipVisibleReview",
        "shippingTopDomainsAndDomainHistoryAllowAndBlockTransferStagedReviewToVisibleReview",
        "shippingFullFreeBlockedAndAllowedListsReturnIntoReviewAfterControlledPlusWithoutAutoApply"),
    # #856 P2R UI coverage is carried inside existing proven journeys (see REQUIRED_CASES):
    # ProductJourneyTest (Save -> visible Review, unchanged until Confirm) and the Privacy journey
    # (Security-off background/resume survival), alongside the packaged-surface companions below.
    "com.lavasec.app.reactnative.LavaReactFilterReviewUiJourneyTest": (
        "changedFilterSaveOpensVisibleReviewWithoutDurableMutationThenBackCancelAndConfirmPersist",
        "topDomainsAndDomainHistoryContextMenuBlockAndAllowOpenTheirStandaloneReview",
        "fullFreeBlockedAndAllowedListsOpenContextualPlusThenResumeIntoOneFreshReviewWithoutApply"),
    "com.lavasec.app.reactnative.LavaReactSecurityBackgroundJourneyTest": (
        "securityOffContentAndEditDraftSurviveBackgroundResumeAndProtectedSurfaceConceals",
        "displayedProtectedFilterDraftRequiresFreshGrantAndExactMeasuredResumeBeforeReveal",),
    # Latest shared RN forms remain required packaged acceptance, including no early
    # library writes, no feedback attempt, confidential native custody and real contact.
    "com.lavasec.app.reactnative.LavaReactSharedForegroundDeviceTest": (
        "shippingSharedLibraryCreateRenameDeleteUseReviewedOwnersAndCancelWithoutEarlyWrites",
        "shippingFeedbackOptInPreviewReviewAndCancelNeverSends",
        "shippingSharedVpnNativeContentCancelAndSavePreserveConfidentialCustody",
        "shippingGuardianPhysicalContactTapAndHoldReachSharedDestinations",),
    # Actual exported ACTION_VIEW entry runs in its own freshly cleared cohort.
    "com.lavasec.app.reactnative.LavaReactExportedEntryJourneyTest": (
        "shippingValidatedExternalEntriesUseSharedReactAndRetireExactNativeReceipts",),
    "com.lavasec.app.reactnative.LavaReactSharedForegroundOnboardingDeviceTest": (
        "shippingFirstRunSharedOnboardingUsesRealPermissionsMeasuredReadyAndDurableQuad9",
        "shippingMockQaSharedOnboardingCompletesAndDismissesWithoutSavingOrSystemPermissions",),
    "com.lavasec.app.reactnative.LavaReactPrivacyJourneyTest": (
        "securityOffClearsFlagSecureAndKeepsPopulatedGuardContent",),
}
REQUIRED_CASES = {
    **NEW_REQUIRED_CASES,
    "com.lavasec.app.reactnative.LavaReactProductJourneyTest": (
        "firstRunFilterDraftRoutesGuardianAndLogClearUseTheShippingSurface",),
    "com.lavasec.app.reactnative.LavaReactGuardTunJourneyTest": (
        "freeCoreGuardServiceFiltersAllowedAndBlockedDnsThroughItsInstalledTun",),
    "com.lavasec.app.reactnative.LavaReactComponentMatrixTest": (
        "componentGalleryThemeTextAndOrientationUseRealControlsAndSemantics",
        "tenProcessLocalesRenderShippingRoutesAndNativeDateCancellation",
        "offlineNativeLicenseFlowReturnsToSharedLegalWithTheSameOwner",
        "storedAppearanceConfiguresNativeResourcesBeforeTheFirstReactFrame",
        "shippingUpgradePagerPositionsBenefitsAndBackPreserveNativeEntitlement",
        "shippingTextSizeSliderContextMenuCancelAndStepperRefusalUseActualControls"),
    "com.lavasec.app.reactnative.LavaReactFilterDraftDeviceTest": (
        "draftUndoReviewCancelAndSaveUseTheRealOwnerAndDurableConfiguration",),
    "com.lavasec.app.reactnative.LavaReactTextFieldInputConnectionTest": (
        "shippingDomainCompositionReplacementFinishSubmitAndCancelResetUseTheNativeEditor",
        "shippingSetupCodeMultilineCompositionCancelAndReopenResetUseTheNativeEditor"),
    "com.lavasec.app.reactnative.LavaReactSliderLifecycleJourneyTest": (
        "shippingSliderRealDragParentScrollCancelRotateAndRemountUseDurableOwner",
        "installedSliderDisabledPropRefusesRealInputAndReenabledDragReachesOwner"),
    "com.lavasec.app.reactnative.LavaReactLocalizedRoutesJourneyTest": (
        "tenProcessLocalesRenderFilterLibraryImportAndIdentityCancellation",
        "tenProcessLocalesRenderAccountSecurityAndPlusWithNativePasscodeCancellation"),
    "com.lavasec.app.reactnative.LavaReactActivityLaunchTest": (
        "decorationCanBeMeasuredBeforeWindowAttachment", "launcherLoadsProtectionOnFirstLaunchAndAfterReopening"),
    "com.lavasec.app.product.QrCodecDeviceTest": (
        "shareCardRendersAPngThatDecodesBackToThePayload", "cameraDecoderReadsACapturedImageFile"),
    "com.lavasec.app.backup.BackupProductionJourneyInstrumentationTest": (
        "nativeSetupRestoreAndReviewPromptsDriveTheProductionBackupJourney",
        "setupReopenReviewedRestoreAndLiveResealRetainUsableKeystoreCustody",
        "cancelledReviewWrongPhraseAndAccountRotationPublishNothing",
        "failedProductPublishRollsBackAndLeavesPreviousCustodyUsableAfterReopen",
        "lostKeyCustodyDuringAdoptionReportsRollbackFailureInsteadOfNothingApplied",
        "adoptionRefusalPreservesRetainedPreferencesBeforeTheFinalCommit",
        "setupAdmissionStorageFailureAndAccountFenceNeverFabricateUpload",
        "uploadFailureKeepsDurableLocalCopyAndAutomaticUploadUsesCurrentConfiguration",
        "cancelledDownloadAndUnsupportedPasskeyLeaveDurableStateUntouched"),
    "com.lavasec.app.backup.NativeBackupPromptLifetimeInstrumentationTest": (
        "destroyedSetupHostCancelsNativePortAndRetainedAdmissionAcceptsSuccessorCancellation",
        "destroyedRecoveryHostCancelsNativePortAndRetainedAdmissionAcceptsSuccessorCancellation"),
    "com.lavasec.app.security.SecurityKeystoreJourneyInstrumentationTest": (
        "passcodeChangeAuthenticationCancellationAndReopenUseTheRealVerifier",
        "retiringAuthenticationTurnRejectsLatePasscodeAndFreshRequestCanAuthenticate",
        "missingKeystoreKeyAndTamperedCiphertextRequireExplicitRecovery",
        "controlledBiometricCancellationNeverFallsThroughOrGrantsAProtectedAction"),
    "com.lavasec.app.vpn.CustomResolverTransportInstrumentationTest": (
        "catalogBootstrapPreservesVerifiedHostnameForAndroidDohAndDot",
        "customDohPostsCanonicalDnsAndRestoresCallerIdThroughAndroidTls",
        "customDohRejectsWrongMediaTypeThenAcceptsNextExchange",
        "cancellingCustomDohDrainsAndroidSocketAndCallOwnership",
        "retiringCustomDohEpochCannotPublishLoopbackAnswer",
        "customDotUsesTlsLengthPrefixAndRestoresCallerId",
        "customDotRejectsMismatchingWireIdRatherThanRepairingIt",
        "customDotRejectsTruncatedFrameAndReleasesAndroidSocket",
        "cancellingCustomDotWakesBlockedAndroidTlsReadBeforeTransportTimeout",
        "retiringCustomDotEpochWakesReadAndRefusesStalePublication"),
    "com.lavasec.app.vpn.DnsStampTransportInstrumentationTest": (
        "rawDohStampsPreserveTlsAuthorityPathAndSeparateSecondaryBootstrap",
        "retiringRawDohStampEpochClosesReusedSocketAndRefusesPendingAnswer",
        "rawDotStampsPreserveTlsHostnameAndServeSecondaryAfterRealPrimaryFrameFailure"),
    "com.lavasec.app.vpn.LocalDnsSocketsInstrumentationTest": (
        "occupiedTcpPortReleasesUdpAndSelectsAnOwnedPair",
        "exhaustedCollisionsFailAndCloseEveryCandidate"),
    "com.lavasec.app.vpn.PlainResolverTransportInstrumentationTest": (
        "fixedPlainTruncationRetriesTcpOnTheExactRetainedEndpoint",
        "fixedPlainUdpCancellationAndRetirementReleaseRealAndroidSockets"),
    "com.lavasec.app.vpn.NativeDoqPackagingInstrumentationTest": (
        "packagedDoqEngineLoadsSystemTrustAndEmitsHandshakeWithoutOpeningSocket",
        "packagedDoqEngineRejectsMissingTrustAndNonzeroWireIds"),
    "com.lavasec.app.vpn.NativeDoqSocketJourneyInstrumentationTest": (
        "verifiedAndroidQuicPeerRestoresCallerIdAndReleasesExactEpochResources",),
    "com.lavasec.app.vpn.chained.ChainedDnsFallbackInstrumentationTest": (
        "splitT0FailureUsesRealSavedUdpAndTcpAndCommitsOnlyAtClientWrite",
        "absentConsentAndFullModeNeverSendToRealSavedRoute",
        "retiringSplitLookupClosesActualPendingSocketAndCannotPublishLateEvidence"),
    "com.lavasec.app.vpn.chained.NativeChainedStackInstrumentationTest": (
        "fullEntryCarriesFullExitHandshakeDataAndReplyThroughTwoActualEngines",
        "splitEntryAndFullExitUseIndependentDestinationSelectedEnginesAndRestoreAddresses"),
    "com.lavasec.app.reactnative.VpnImportTransactionInstrumentationTest": (
        "cancelledOrInvalidFirstDocumentNeverPersistsRotationOrKey",
        "pendingOwnershipSurvivesNativeOwnerRecreationAndExactRecoveryRemovesConfigurationAndKey",
        "unchangedSavePublishesDurableSuccessorAndStaleRecoveryCannotEraseIt",
        "failedSaveKeepsPendingPairAndDraftRetryableThenCancelRemovesExactTransaction",
        "staleEditorOrPendingIdentityCannotRemoveAnotherImportOrCommittedReplacement",
        "failedKeystoreCleanupRetainsPendingWitnessAcrossOwnerRecreationForRetry",
        "failureAfterNativePublicationBeforeReopenCompensatesConfigurationAndKey",
        "cancellationAfterNativePublicationBeforeReopenCompletesCompensation",
        "failedReopenCompensationRetainsNativePendingTagForRecreatedOwnerRetry",
        "refusedReopenCompensationPreservesDurableSavedSuccessorAndItsKeys",
        "recreationRetainsLegacyPreferenceMarkerWithItsExactAccountAndGeneration",
        "orderedNativeDraftPreservesDistinctCredentialsThroughSaveSwapDisableAndReopen"),
    "com.lavasec.app.reactnative.CustomResolverPersistenceInstrumentationTest": (
        "nativeAuthoritativeRecordReconstructsExactCustomDohAndDotEndpoints",),
    "com.lavasec.app.data.ActivityReceiptInstrumentationTest": (
        "firstTelemetryReceiptCommitsAndReplayRemainsIdempotentAcrossReopen",),
}
PROCESS_CLASS = "com.lavasec.app.reactnative.VpnProcessDeathInstrumentationTest"
PROCESS_TEST = "persistedImportTransactionRecoversAcrossRunnerProcesses"
PHASES = ("stagePending", "recoverPending", "stageSaved", "verifySaved")
SOURCE_SHA = "a" * 40
APK_SHA = "b" * 64
CANONICAL_TARGET = "c4658d973bae05dd3b7aa84c014cb2f76ff61a0b"


def complete_record(api):
    cases = {f"{name}#{method}": "pass" for name, methods in REQUIRED_CASES.items() for method in methods}
    cases.update({f"{PROCESS_CLASS}#{PROCESS_TEST}@{phase}": "pass" for phase in PHASES})
    return {"schema": 1, "api": api, "source_sha": SOURCE_SHA, "source_dirty": False,
            "apk_sha256": APK_SHA, "signer_sha256": evidence.DEBUG_SIGNER_SHA256,
            "canonical_target": CANONICAL_TARGET, "cases": cases,
            "update_preserved_private_state": True,
            "public_ui": {"status": "pass", "api": api, "source_sha": SOURCE_SHA,
                          "apk_sha256": APK_SHA, "canonical_target": CANONICAL_TARGET,
                          "manifest_sha256": "d" * 64,
                          "frames": [{"tag": tag, "file": tag + ".png", "page": tag,
                                      "width": 360, "height": 640, "sha256": "e" * 64}
                                     for tag in ("shipping-guard", "shipping-settings", "shipping-upgrade")]}}


def status_bundle(class_name, test_name, status, total=1):
    return (f"INSTRUMENTATION_STATUS: numtests={total}\nINSTRUMENTATION_STATUS: class={class_name}\n"
            f"INSTRUMENTATION_STATUS: test={test_name}\nINSTRUMENTATION_STATUS: current=1\n"
            f"INSTRUMENTATION_STATUS_CODE: {status}\n")


def runner_log(class_name=PROCESS_CLASS, test_name=PROCESS_TEST, status=0, runner_code=-1):
    stream = "OK (1 test)" if status in (0, -3, -4) else "FAILURES!!!\nTests run: 1, Failures: 1"
    return (status_bundle(class_name, test_name, 1) + status_bundle(class_name, test_name, status)
            + f"INSTRUMENTATION_RESULT: stream=\nTime: 0.042\n\n{stream}\n\n"
            + f"INSTRUMENTATION_CODE: {runner_code}\n")


class InstrumentationTranscriptTests(unittest.TestCase):
    def test_success_needs_test_completion_and_exact_runner_completion(self):
        suite = evidence.transcript_xml(runner_log())
        cases = suite.findall("testcase")
        self.assertEqual(1, len(cases))
        self.assertEqual(PROCESS_CLASS, cases[0].get("classname"))
        self.assertEqual(PROCESS_TEST, cases[0].get("name"))
        self.assertEqual([], list(cases[0]))

    def test_phase_is_preserved_as_an_explicit_process_death_case_identity(self):
        for phase in PHASES:
            with self.subTest(phase=phase):
                case = evidence.transcript_xml(runner_log(), phase).find("testcase")
                self.assertEqual(f"{PROCESS_TEST}@{phase}", case.get("name"))

    def test_assertion_failure_and_test_error_are_never_passes(self):
        for status in (-1, -2):
            with self.subTest(status=status):
                suite = evidence.transcript_xml(runner_log(status=status))
                failed_case = suite.find("testcase")
                self.assertIsNotNone(failed_case.find("failure"))
                self.assertIn(str(status), failed_case.find("failure").get("message"))

    def test_ignored_and_assumption_failed_tests_remain_skipped(self):
        for status in (-3, -4):
            with self.subTest(status=status):
                case = evidence.transcript_xml(runner_log(status=status)).find("testcase")
                self.assertIsNotNone(case.find("skipped"))

    def test_process_abort_after_one_completed_case_still_adds_runner_error(self):
        aborted = status_bundle(PROCESS_CLASS, PROCESS_TEST, 1) + status_bundle(PROCESS_CLASS, PROCESS_TEST, 0)
        aborted += "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"
        suite = evidence.transcript_xml(aborted)
        self.assertTrue(any(case.find("error") is not None for case in suite.findall("testcase")))

    def test_missing_terminal_code_is_rejected_even_with_an_ok_summary(self):
        suite = evidence.transcript_xml(runner_log().replace("INSTRUMENTATION_CODE: -1\n", ""))
        self.assertIsNotNone(suite.find("testcase[@classname='runner']/error"))

    def test_minus_ten_cannot_match_the_success_terminal_code(self):
        suite = evidence.transcript_xml(runner_log(runner_code=-10))
        self.assertIsNotNone(suite.find("testcase[@classname='runner']/error"))

    def test_empty_success_summary_cannot_manufacture_an_executed_test(self):
        suite = evidence.transcript_xml("INSTRUMENTATION_RESULT: stream=\nOK (0 tests)\nINSTRUMENTATION_CODE: -1\n")
        self.assertIsNotNone(suite.find("testcase/error"))

    def test_an_unfinished_started_test_is_rejected_after_an_earlier_pass(self):
        partial = status_bundle(PROCESS_CLASS, PROCESS_TEST, 1, total=2) + status_bundle(PROCESS_CLASS, PROCESS_TEST, 0, total=2)
        partial += status_bundle(PROCESS_CLASS, "unfinishedSecondTest", 1, total=2)
        partial += "INSTRUMENTATION_RESULT: stream=\nOK (2 tests)\nINSTRUMENTATION_CODE: -1\n"
        suite = evidence.transcript_xml(partial)
        self.assertTrue(any(case.find("error") is not None for case in suite.findall("testcase")))

    def test_cli_writes_failed_or_skipped_xml_but_returns_nonzero(self):
        with tempfile.TemporaryDirectory() as scratch:
            source, result = Path(scratch) / "instrument.txt", Path(scratch) / "results.xml"
            for status in (-2, -3, -4):
                source.write_text(runner_log(status=status))
                with patch.object(sys, "argv", ["evidence", "transcript", "--input", str(source), "--output", str(result)]):
                    self.assertNotEqual(0, evidence.main())
                self.assertTrue(result.is_file())
                ET.parse(result)  # A CI artifact remains readable even on the failure path.


class JunitAggregationTests(unittest.TestCase):
    def write_case(self, path, status):
        suite = ET.Element("testsuite")
        case = ET.SubElement(suite, "testcase", classname=PROCESS_CLASS, name=PROCESS_TEST)
        if status != "pass":
            ET.SubElement(case, "failure" if status == "fail" else "skipped")
        ET.ElementTree(suite).write(path)

    def test_duplicate_failure_and_skip_cannot_be_overwritten_by_a_later_pass(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            for status in ("fail", "skipped"):
                self.write_case(root / "a-first.xml", status)
                self.write_case(root / "z-rerun.xml", "pass")
                self.assertEqual(status, evidence.results_under(root)[f"{PROCESS_CLASS}#{PROCESS_TEST}"])

    def test_nested_testsuites_and_nested_results_directories_are_read(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            (root / "connected" / "api28").mkdir(parents=True)
            path = root / "connected" / "api28" / "results.xml"
            path.write_text(f'<testsuites><testsuite><testcase classname="{PROCESS_CLASS}" name="{PROCESS_TEST}"/></testsuite></testsuites>')
            self.assertEqual({f"{PROCESS_CLASS}#{PROCESS_TEST}": "pass"}, evidence.results_under(root))

    def test_malformed_xml_fails_closed_instead_of_silently_dropping_a_run(self):
        with tempfile.TemporaryDirectory() as scratch:
            (Path(scratch) / "aborted.xml").write_text("<testsuite><testcase")
            with self.assertRaises(ET.ParseError):
                evidence.results_under(Path(scratch))


class CandidateEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.records = [complete_record(28), complete_record(36)]

    def assert_rejected(self, records):
        self.assertTrue(evidence.violations(records), "Incomplete evidence must not pass the emulator journey gate")

    def test_complete_clean_api28_and_api36_evidence_is_accepted(self):
        self.assertEqual([], evidence.violations(self.records))

    def test_other_required_entries_cannot_qualify_without_the_exported_entry(self):
        key = "com.lavasec.app.reactnative.LavaReactExportedEntryJourneyTest#shippingValidatedExternalEntriesUseSharedReactAndRetireExactNativeReceipts"
        for record in self.records:
            self.assertEqual(112, len(record["cases"]))
        records = copy.deepcopy(self.records)
        for record in records:
            del record["cases"][key]
            self.assertEqual(111, len(record["cases"]))
        for api in (28, 36):
            self.assertIn(f"API {api}: missing executed pass {key}", evidence.violations(records))

    def test_each_required_class_and_case_must_be_present(self):
        for class_name, methods in REQUIRED_CASES.items():
            for method in methods:
                with self.subTest(class_name=class_name, method=method):
                    records = copy.deepcopy(self.records)
                    del records[1]["cases"][f"{class_name}#{method}"]
                    self.assert_rejected(records)

    def test_same_class_case_count_cannot_be_satisfied_by_substituted_method_names(self):
        for index in (0, 1):
            for class_name, methods in REQUIRED_CASES.items():
                for method in methods:
                    with self.subTest(api=self.records[index]["api"], class_name=class_name, method=method):
                        records = copy.deepcopy(self.records)
                        key = f"{class_name}#{method}"
                        del records[index]["cases"][key]
                        records[index]["cases"][f"{class_name}#unrelatedSyntheticPass"] = "pass"
                        self.assertEqual(len(self.records[index]["cases"]), len(records[index]["cases"]))
                        self.assertIn(f"API {records[index]['api']}: missing executed pass {key}",
                                      evidence.violations(records))

    def test_a_required_skip_or_failed_case_invalidates_the_candidate(self):
        for status in ("skipped", "fail"):
            records = copy.deepcopy(self.records)
            key = next(iter(records[0]["cases"]))
            records[0]["cases"][key] = status
            self.assert_rejected(records)

    def test_new_app_and_vpn_cases_require_an_executed_pass_on_both_apis(self):
        for index in (0, 1):
            for class_name, methods in NEW_REQUIRED_CASES.items():
                for method in methods:
                    key = f"{class_name}#{method}"
                    for outcome in (None, "not-run", "skipped", "fail"):
                        with self.subTest(api=self.records[index]["api"], case=key, outcome=outcome):
                            records = copy.deepcopy(self.records)
                            if outcome is None:
                                del records[index]["cases"][key]
                            else:
                                records[index]["cases"][key] = outcome
                            self.assertIn(f"API {records[index]['api']}: missing executed pass {key}",
                                          evidence.violations(records))

    def test_previous_baseline_without_native_layout_cases_is_rejected_on_both_apis(self):
        records = copy.deepcopy(self.records)
        class_name = "com.lavasec.app.reactnative.LavaNativePageLayoutInstrumentationTest"
        keys = [f"{class_name}#{method}" for method in NEW_REQUIRED_CASES[class_name]]
        for record in records:
            for key in keys:
                del record["cases"][key]
        violations = evidence.violations(records)
        for api in (28, 36):
            for key in keys:
                self.assertIn(f"API {api}: missing executed pass {key}", violations)

    def test_previous_baseline_without_new_shipping_journeys_is_rejected_on_both_apis(self):
        # The prior complete API 28/36 inventory has the existing card, QA,
        # narration and layout cases, but none of these three shipping journeys.
        classes = (
            "com.lavasec.app.reactnative.LavaReactExploreSudokuJourneyTest",
            "com.lavasec.app.reactnative.LavaReactPasscodeJourneyTest",
        )
        keys = [f"{class_name}#{method}" for class_name in classes
                for method in NEW_REQUIRED_CASES[class_name]]
        self.assertEqual(3, len(keys))
        records = copy.deepcopy(self.records)
        for record in records:
            for key in keys:
                del record["cases"][key]
        violations = evidence.violations(records)
        for api in (28, 36):
            for key in keys:
                self.assertIn(f"API {api}: missing executed pass {key}", violations)

    def test_new_case_terminal_statuses_reach_the_gate_through_actual_parser_and_xml(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            for class_name, methods in NEW_REQUIRED_CASES.items():
                for method in methods:
                    key = f"{class_name}#{method}"
                    for status, outcome in ((0, "pass"), (-1, "fail"), (-2, "fail"),
                                            (-3, "skipped"), (-4, "skipped")):
                        with self.subTest(case=key, status=status):
                            suite = evidence.transcript_xml(runner_log(class_name, method, status))
                            ET.ElementTree(suite).write(root / "current-suite.xml")
                            actual = evidence.results_under(root)
                            self.assertEqual(outcome, actual[key])
                            records = copy.deepcopy(self.records)
                            records[1]["cases"].update(actual)
                            if outcome == "pass":
                                self.assertEqual([], evidence.violations(records))
                            else:
                                self.assertIn(f"API 36: missing executed pass {key}",
                                              evidence.violations(records))

    def test_new_case_cannot_be_substituted_by_the_same_method_in_another_runner_class(self):
        for class_name, methods in NEW_REQUIRED_CASES.items():
            for method in methods:
                with self.subTest(class_name=class_name, method=method):
                    records = copy.deepcopy(self.records)
                    key = f"{class_name}#{method}"
                    del records[0]["cases"][key]
                    records[0]["cases"][f"controlled.substitute.OtherTest#{method}"] = "pass"
                    self.assertIn(f"API 28: missing executed pass {key}", evidence.violations(records))

    def test_each_external_process_phase_must_pass_on_each_api(self):
        for index in (0, 1):
            for phase in PHASES:
                for outcome in (None, "skipped", "fail"):
                    with self.subTest(api=self.records[index]["api"], phase=phase, outcome=outcome):
                        records = copy.deepcopy(self.records)
                        key = f"{PROCESS_CLASS}#{PROCESS_TEST}@{phase}"
                        if outcome is None:
                            del records[index]["cases"][key]
                        else:
                            records[index]["cases"][key] = outcome
                        self.assert_rejected(records)

    def test_unphased_process_reconstruction_cannot_replace_four_external_phases(self):
        for record in self.records:
            record["cases"] = {key: value for key, value in record["cases"].items() if not key.startswith(PROCESS_CLASS + "#")}
            record["cases"][f"{PROCESS_CLASS}#{PROCESS_TEST}"] = "pass"
        self.assert_rejected(self.records)

    def test_dirty_source_and_missing_clean_commit_fact_are_rejected(self):
        for dirty in (True, None, "false", 0):
            records = copy.deepcopy(self.records)
            records[0]["source_dirty"] = dirty
            self.assert_rejected(records)

    def test_source_apk_signer_and_canonical_target_drift_are_rejected(self):
        for field in ("source_sha", "apk_sha256", "signer_sha256", "canonical_target"):
            with self.subTest(field=field):
                records = copy.deepcopy(self.records)
                records[1][field] = "different-artifact-identity"
                self.assert_rejected(records)

    def test_matching_artifacts_signed_with_a_different_key_still_fail(self):
        for record in self.records:
            record["signer_sha256"] = "f" * 64
        self.assert_rejected(self.records)

    def test_missing_api_duplicate_api_or_update_receipt_are_rejected(self):
        self.assert_rejected(self.records[:1])
        self.assert_rejected([complete_record(28), complete_record(28)])
        self.records[0]["update_preserved_private_state"] = False
        self.assert_rejected(self.records)

    def test_missing_failed_or_source_mismatched_public_capture_receipt_invalidates_passes(self):
        for value in (None, {"status": "fail"}, {**self.records[0]["public_ui"], "source_sha": "f" * 40}):
            with self.subTest(receipt=value):
                records = copy.deepcopy(self.records)
                records[0]["public_ui"] = value
                self.assert_rejected(records)

    def test_runner_abort_invalidates_even_otherwise_complete_passed_cases(self):
        self.records[0]["cases"]["runner#completed"] = "fail"
        self.assert_rejected(self.records)

    def test_cli_names_only_the_emulator_evidence_scope_and_returns_failure_for_drift(self):
        with tempfile.TemporaryDirectory() as scratch:
            paths = [Path(scratch) / f"api{record['api']}.json" for record in self.records]
            def run_cli():
                for path, record in zip(paths, self.records):
                    path.write_text(json.dumps(record))
                out = io.StringIO()
                with contextlib.redirect_stdout(out), patch.object(sys, "argv", ["evidence", "verify", *map(str, paths)]):
                    result = evidence.main()
                return result, out.getvalue()
            result, output = run_cli()
            self.assertEqual(0, result)
            self.assertIn("EMULATOR_JOURNEY_EVIDENCE: PASS", output)
            self.assertNotIn("M2", output)
            self.assertNotIn("M3", output)
            self.records[1]["apk_sha256"] = "c" * 64
            result, output = run_cli()
            self.assertNotEqual(0, result)
            self.assertIn("EMULATOR_JOURNEY_EVIDENCE: FAIL", output)


if __name__ == "__main__":
    unittest.main()
