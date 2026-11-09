#!/usr/bin/env python3
"""Record actual APK/test identities and verify the API 28/36 emulator journey evidence.

This gate covers the controlled emulator scenarios below. It does not waive the F25
ledger, approve product deviations, or establish physical/signed/live-service parity.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEBUG_SIGNER_SHA256 = "f6e634fd05bce67cdfe3544dfd236957e9b21a9c1f417872360cfd33722fd9c6"
REQUIRED_CASES = {
    # #856 P2R production UI coverage: the packaged React surface drives the same Review gate the
    # command-level LavaReactFilterReviewJourneyTest pins.
    #  - ProductJourneyTest: a changed filter's Save opens the visible Review with the durable
    #    library unchanged until Confirm, and the Cancel/Discard path preserves it.
    #  - FilterReviewUiJourneyTest: the real UI Save -> visible Review, the domain-history
    #    ContextMenu Block/Allow hand-off into the token-owned Review, and the full-free-list
    #    contextual Plus resume back into exactly one Review with no automatic apply.
    #  - SecurityBackgroundJourneyTest: an in-progress filter-edit draft survives a real task
    #    background/resume, and an opted-in protected surface conceals it through the native
    #    app-lock cover without unmounting the workflow.
    #  - PrivacyJourneyTest: Security-off content survives a real task background/resume.
    "com.lavasec.app.reactnative.LavaReactProductJourneyTest": (
        "firstRunFilterDraftRoutesGuardianAndLogClearUseTheShippingSurface",),
    "com.lavasec.app.reactnative.LavaReactGuardTunJourneyTest": (
        "freeCoreGuardServiceFiltersAllowedAndBlockedDnsThroughItsInstalledTun",),
    # The raw app suite executes these in addition to the first-run journey. Requiring
    # exact terminal identities prevents a filtered, ignored or omitted suite from passing.
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
    "com.lavasec.app.reactnative.LavaReactComponentMatrixTest": (
        "componentGalleryThemeTextAndOrientationUseRealControlsAndSemantics",
        "tenProcessLocalesRenderShippingRoutesAndNativeDateCancellation",
        "offlineNativeLicenseFlowReturnsToSharedLegalWithTheSameOwner",
        "storedAppearanceConfiguresNativeResourcesBeforeTheFirstReactFrame",
        "shippingUpgradePagerPositionsBenefitsAndBackPreserveNativeEntitlement",
        "shippingTextSizeSliderContextMenuCancelAndStepperRefusalUseActualControls"),
    "com.lavasec.app.reactnative.LavaReactFilterDraftDeviceTest": (
        "draftUndoReviewCancelAndSaveUseTheRealOwnerAndDurableConfiguration",),
    # #856 P2R: visible Review gate on changed filter drafts and Allow/Block domain actions,
    # plus the controlled full-free-list contextual Plus return into Review.
    "com.lavasec.app.reactnative.LavaReactFilterReviewJourneyTest": (
        "shippingChangedActiveInactiveAndNewFilterSavesEnterVisibleReviewThenCancelOrConfirm",
        "shippingUnchangedEditAndUntouchedNewTemplateSkipVisibleReview",
        "shippingTopDomainsAndDomainHistoryAllowAndBlockTransferStagedReviewToVisibleReview",
        "shippingFullFreeBlockedAndAllowedListsReturnIntoReviewAfterControlledPlusWithoutAutoApply",),
    # #856 P2R UI companions: the same Review gate driven through the packaged React surface
    # (visible Review sheet, domain-row ContextMenu accessibility actions, contextual Plus resume)
    # plus the Security-off/on background-resume draft-continuity journey.
    "com.lavasec.app.reactnative.LavaReactFilterReviewUiJourneyTest": (
        "changedFilterSaveOpensVisibleReviewWithoutDurableMutationThenBackCancelAndConfirmPersist",
        "topDomainsAndDomainHistoryContextMenuBlockAndAllowOpenTheirStandaloneReview",
        "fullFreeBlockedAndAllowedListsOpenContextualPlusThenResumeIntoOneFreshReviewWithoutApply",),
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
    # D5/#818: Security fully off clears FLAG_SECURE and keeps populated Guard content.
    "com.lavasec.app.reactnative.LavaReactPrivacyJourneyTest": (
        "securityOffClearsFlagSecureAndKeepsPopulatedGuardContent",),
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
    "com.lavasec.app.vpn.DeviceResolverReconnectInstrumentationTest": (
        "deviceRejectionUsesRealFailureQualifiedReconnectThenTlsFallbackAndPrimaryRecovery",
        "mismatchingDeviceWireIdsStopBeforeEncryptedFallback"),
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
    # These belong to the separate debug VPN test APK, with controlled protection/
    # chain admission. A send receipt is not a shipping Plus handshake or physical leak verdict.
    "com.lavasec.app.vpn.qa.DnsLeakCanaryInstrumentationTest": (
        "debugCanaryEmitsExactPhysicalUdpOnlyForCurrentArmedEstablishedAttempt",
        "debugCanaryRefusesDisarmCancellationAndCloseAfterRealSocketPreparation",
        "stalePreparedAttemptCannotSendOrCloseSuccessorAndTransportRebindNeverRefires",
        "absentHandshakeAndRefusedProtectionNeverSendAndNoSendRetryUsesSameSession"),
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
PROCESS_PHASES = ("stagePending", "recoverPending", "stageSaved", "verifySaved")


def transcript_xml(text: str, phase: str = "") -> ET.Element:
    """Runner success alone is insufficient: retain every test's terminal status."""
    suite = ET.Element("testsuite", name="Android instrumentation")
    bundle = {}
    active = set()
    for line in text.splitlines():
        field = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
        if field:
            bundle[field[1]] = field[2].strip()
        code = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if code and int(code[1]) == 1 and "class" in bundle and "test" in bundle:
            active.add((bundle["class"], bundle["test"]))
        if code and int(code[1]) != 1:
            if "class" in bundle and "test" in bundle:
                active.discard((bundle["class"], bundle["test"]))
                name = bundle["test"] + ("@" + phase if phase else "")
                case = ET.SubElement(suite, "testcase", classname=bundle["class"], name=name)
                status = int(code[1])
                if status in (-3, -4):
                    ET.SubElement(case, "skipped", message=f"instrumentation status {status}")
                elif status != 0:
                    ET.SubElement(case, "failure", message=f"instrumentation status {status}").text = text
            bundle = {}
    # am instrument can exit zero despite an assertion failure or crashed process.
    terminal = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", text, re.MULTILINE)
    summary = re.search(r"^OK \((\d+) tests?\)\s*$", text, re.MULTILINE)
    finished = terminal == ["-1"] and summary and int(summary[1]) == len(suite.findall("testcase"))
    if not finished or active or not suite.findall("testcase"):
        case = ET.SubElement(suite, "testcase", classname="runner", name=phase or "completed")
        ET.SubElement(case, "error", message="runner aborted or no completed tests").text = text
    return suite


def results_under(root: Path) -> dict[str, str]:
    cases = {}
    severity = {"pass": 0, "skipped": 1, "fail": 2}
    for path in sorted(root.rglob("*.xml")):
        tree = ET.parse(path)
        for case in tree.iter("testcase"):
            key = f"{case.get('classname', '')}#{case.get('name', '')}"
            status = "fail" if case.find("failure") is not None or case.find("error") is not None else (
                "skipped" if case.find("skipped") is not None else "pass")
            if severity[status] >= severity.get(cases.get(key, "pass"), 0):
                cases[key] = status
    return cases


if __package__:
    from .android_public_ui_evidence import EvidenceError, receipt, receipt_valid
else:
    from android_public_ui_evidence import EvidenceError, receipt, receipt_valid


def violations(records: list[dict]) -> list[str]:
    problems = []
    apis = [record.get("api") for record in records]
    if sorted(apis) != [28, 36]:
        problems.append("need exactly one evidence record for API 28 and API 36")
    for field in ("source_sha", "apk_sha256", "signer_sha256", "canonical_target"):
        values = {record.get(field) for record in records}
        if len(values) != 1 or not all(values):
            problems.append(f"candidate {field} differs or is missing")
    for record in records:
        api = record.get("api")
        if record.get("source_dirty") is not False:
            problems.append(f"API {api}: source is not a clean committed candidate")
        if record.get("signer_sha256") != DEBUG_SIGNER_SHA256:
            problems.append(f"API {api}: APK does not use the stable preview signer")
        if record.get("update_preserved_private_state") is not True:
            problems.append(f"API {api}: install/update preservation is not evidenced")
        if not receipt_valid(record.get("public_ui"), record):
            problems.append(f"API {api}: public shipping-page captures are missing or mismatched")
        cases = record.get("cases", {})
        for key, status in cases.items():
            if status == "fail":
                problems.append(f"API {api}: failed {key}")
        for class_name, methods in REQUIRED_CASES.items():
            for method in methods:
                key = f"{class_name}#{method}"
                if cases.get(key) != "pass":
                    problems.append(f"API {api}: missing executed pass {key}")
        for phase in PROCESS_PHASES:
            key = f"{PROCESS_CLASS}#{PROCESS_TEST}@{phase}"
            if cases.get(key) != "pass":
                problems.append(f"API {api}: missing process-death phase {phase}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    transcript = sub.add_parser("transcript")
    transcript.add_argument("--input", type=Path, required=True)
    transcript.add_argument("--output", type=Path, required=True)
    transcript.add_argument("--phase", default="")
    record = sub.add_parser("record")
    record.add_argument("--api", type=int, required=True)
    record.add_argument("--apk", type=Path, required=True)
    record.add_argument("--results", type=Path, required=True)
    record.add_argument("--output", type=Path, required=True)
    record.add_argument("--apksigner", required=True)
    verify = sub.add_parser("verify")
    verify.add_argument("records", type=Path, nargs="+")
    args = parser.parse_args()
    if args.mode == "transcript":
        suite = transcript_xml(args.input.read_text(), args.phase)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(suite).write(args.output, encoding="utf-8", xml_declaration=True)
        return int(any(case.find("failure") is not None or case.find("error") is not None or
                       case.find("skipped") is not None for case in suite))
    if args.mode == "record":
        signing = subprocess.check_output([args.apksigner, "verify", "--print-certs", str(args.apk)], text=True)
        signer = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", signing)
        if signer is None:
            raise ValueError("APK signer identity missing")
        manifest = json.loads((ROOT / "android/react-native/scripts/canonical-source.manifest.json").read_text())
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()
        with args.apk.open("rb") as artifact:
            digest = hashlib.file_digest(artifact, "sha256").hexdigest()
        evidence = {
            "schema": 1, "scope": "controlled emulator journey evidence", "api": args.api,
            "source_sha": sha, "source_dirty": bool(dirty), "apk_sha256": digest,
            "signer_sha256": signer[1].lower(), "canonical_target": manifest["canonical"]["revision"],
            "cases": results_under(args.results),
            "update_preserved_private_state": (args.results / "update-preserved.txt").is_file()
                and (args.results / "update-preserved.txt").read_text().strip() == "emulator-synthetic-update",
            "limitations": ["No physical/OEM qualification", "No release/Play/live-service qualification",
                            "Backup cloud/product boundaries and biometric result use controlled fixtures",
                            "Resolver peers use controlled loopback or captured local-network TLS/UDP/TCP fixtures",
                            "Controlled socket authorization does not qualify a physical VPN or live resolver service",
                            "Product gaps need separate disposition"],
        }
        try:
            evidence["public_ui"] = receipt(args.results / "public-ui", args.api, sha, digest,
                                            manifest["canonical"]["revision"])
        except (EvidenceError, OSError):
            evidence["public_ui"] = {"status": "fail"}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n")
        print(f"Recorded API {args.api}: {len(evidence['cases'])} test outcomes; APK {digest}")
        return 0
    errors = violations([json.loads(path.read_text()) for path in args.records])
    print("EMULATOR_JOURNEY_EVIDENCE: " + ("FAIL" if errors else "PASS"))
    for error in errors:
        print(f"  - {error}")
    return int(bool(errors))


if __name__ == "__main__":
    sys.exit(main())
