import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
import android_emulator_evidence as emulator_evidence


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "android.yml"
WIREGUARD_WORKFLOW = ROOT / ".github" / "workflows" / "wireguard-core-android.yml"
DEPENDABOT = ROOT / ".github" / "dependabot.yml"
DEVICE_WORKFLOW = ROOT / ".github" / "workflows" / "android-device.yml"
JOURNEY_SCRIPT = ROOT / "scripts" / "run-android-emulator-journeys.sh"
DOQ_CLASS = "com.lavasec.app.vpn.NativeDoqSocketJourneyInstrumentationTest"
DOQ_METHOD = "verifiedAndroidQuicPeerRestoresCallerIdAndReleasesExactEpochResources"
TALKBACK_CLASS = "com.lavasec.app.reactnative.LavaReactTalkBackJourneyTest"
SHARED_SETUP_CLASS = "com.lavasec.app.reactnative.LavaReactSharedForegroundOnboardingDeviceTest"
EXPORTED_ENTRY_CLASS = "com.lavasec.app.reactnative.LavaReactExportedEntryJourneyTest"
EXPORTED_ENTRY_METHOD = "shippingValidatedExternalEntriesUseSharedReactAndRetireExactNativeReceipts"
APP_EXCLUDED = ",".join(("com.lavasec.app.reactnative.LavaReactProductJourneyTest",
                         SHARED_SETUP_CLASS, EXPORTED_ENTRY_CLASS, emulator_evidence.PROCESS_CLASS, TALKBACK_CLASS))


def _executable_source(source: str) -> str:
    """Do not let a command mentioned only in a YAML/shell comment satisfy a contract."""
    return re.sub(r"(?m)^\s*#.*(?:\n|$)", "", source).replace("\\\n", " ")


def _retention_days(source: str) -> list[str]:
    """Every `retention-days:` value in a workflow, matched as a whole value (not a prefix)."""
    return re.findall(r"(?m)^\s*retention-days:\s*(\d+)\s*$", source)


def _job(source: str, name: str) -> str:
    match = re.search(r"(?m)^  " + re.escape(name) + r":\n", source)
    if match is None:
        raise AssertionError("missing job " + name)
    tail = source[match.end():]
    next_job = re.search(r"(?m)^  [a-zA-Z][a-zA-Z0-9_-]*:\n", tail)
    return tail[:next_job.start()] if next_job else tail


class AndroidWorkflowContractTest(unittest.TestCase):
    def read(self, path: Path) -> str:
        self.assertTrue(path.is_file(), f"missing {path}")
        return path.read_text(encoding="utf-8")

    def test_engine_scope_handles_large_diffs_and_fails_closed_on_diff_errors(self) -> None:
        from ci.android_ci_scope import classify
        scope = _job(self.read(WIREGUARD_WORKFLOW), "scope")
        self.assertIn('git show "$BASE:ci/android_ci_scope.py"', scope)
        self.assertIn("engine_required=true", scope)  # unavailable base policy runs fully
        files = ["ThirdParty/doq-core/src/lib.rs"] + [f"docs/{n}.md" for n in range(5000)]
        for event in ("pull_request", "push"):
            self.assertTrue(classify(files, event=event, repository="lavasecurity/lavasec-android")["engine_required"])
            self.assertTrue(classify(None, event=event, repository="lavasecurity/lavasec-android")["engine_required"])
            self.assertFalse(classify(["docs/only.md"], event=event, repository="lavasecurity/lavasec-android")["engine_required"])

    def test_triggers_permissions_and_concurrency_are_bounded(self) -> None:
        for path, prefix in ((WORKFLOW, "android-ci"), (DEVICE_WORKFLOW, "android-device"), (WIREGUARD_WORKFLOW, "wireguard-core-android")):
            source = self.read(path)
            for event in ("opened", "synchronize", "reopened", "labeled", "unlabeled", "ready_for_review", "converted_to_draft"):
                self.assertIn(event, source)
            self.assertIn("permissions:\n  contents: read", source)
            self.assertIn(prefix + "-${{ github.event_name == 'push' && github.run_id || github.ref }}", source)
            self.assertIn("cancel-in-progress: true", source)
            self.assertNotIn("paths-ignore:", source)
            self.assertIn("github.event.pull_request.draft != true", source)

    def test_internal_and_public_jobs_have_explicit_runner_trust_boundaries(self) -> None:
        for path in sorted((ROOT / ".github/workflows").glob("*.yml")):
            source = self.read(path)
            for line in source.splitlines():
                if not line.lstrip().startswith("runs-on:"):
                    continue
                with self.subTest(workflow=path.name, declaration=line):
                    self.assertNotIn("vars.", line)
                    self.assertNotIn("self-hosted", line)
                    if "ubuntu-latest" in line and "${{" in line:
                        self.assertIn("github.repository == 'lavasecurity/lavasec-android-internal'", line)
                        self.assertIn("head.repo.full_name == github.repository", line)
                        self.assertIn("user.login != 'dependabot[bot]'", line)
                    elif "ubuntu-latest" in line:
                        self.assertEqual("codeql.yml", path.name)
                        self.assertIn("github.repository == 'lavasecurity/lavasec-android'", source)
                    else:
                        self.assertIn("ubicloud-standard-", line)
        self.assertIn("Require a trusted internal branch", self.read(WORKFLOW))

    def test_builds_use_the_fixed_toolchain_and_clean_checkout(self) -> None:
        source = _job(self.read(WORKFLOW), "build")
        self.assertEqual(
            1,
            source.count(
                "uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4.3.1"
            ),
        )
        self.assertEqual(1, source.count("persist-credentials: false"))
        self.assertEqual(1, source.count("clean: true"))
        self.assertEqual(
            1,
            source.count(
                "uses: actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9 # v4.8.0"
            ),
        )
        self.assertEqual(1, source.count("java-version: '21'"))
        self.assertNotIn("java-version: '17'", source)
        self.assertEqual(
            1,
            source.count(
                "uses: gradle/actions/setup-gradle@748248ddd2a24f49513d8f472f81c3a07d4d50e1 # v4.4.4"
            ),
        )
        self.assertEqual(
            1,
            source.count(
                "uses: android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407 # v3.2.2"
            ),
        )
        self.assertEqual(1, source.count('"platforms;android-36"'))
        self.assertEqual(1, source.count('"build-tools;36.0.0"'))
        self.assertEqual(1, source.count('"ndk;27.2.12479018"'))
        self.assertEqual(1, source.count('"cmake;3.30.5"'))

    def test_optimized_startup_uses_the_real_release_apk_and_api34(self) -> None:
        source = _executable_source(self.read(WORKFLOW))
        self.assertIn(":app:bundlePlayRelease :app:assemblePlayRelease", source)
        startup = source.split("\n  minified-startup:", 1)[1]
        for required in ("needs: [changes, build]", "\'ubicloud-standard-4\' || \'ubuntu-latest\'",
                         "persist-credentials: false", "java-version: '21'",
                         "system-images;android-34;google_apis;x86_64",
                         'test -c /dev/kvm', 'sha256sum --check app-release.apk.sha256',
                         'python3 ci/android_minified_startup.py --evidence',
                         'if: ${{ always() }}', 'retention-days: 1'):
            self.assertIn(required, startup)
        for forbidden in ("continue-on-error", "secrets.", "environment:"):
            self.assertNotIn(forbidden, startup)
        self.assertIn("android/app/build/outputs/apk/play/release/app-play-release-unsigned.apk", source)
        self.assertIn("application-debuggable", source)
        self.assertIn("--ks-key-alias androiddebugkey", source)

    def test_tiers_preserve_complete_verification_coverage(self) -> None:
        source = self.read(WORKFLOW)
        repo, unit, compile, build = (_job(source, n) for n in ("repo-checks", "unit-tests", "app-compile", "build"))
        for command in ("python3 -m unittest discover -s ci -p 'test_*.py'", "scripts/verify-android-foundation"):
            self.assertIn(command, repo)
            self.assertEqual(1, source.count(command))
        for task in ("testDebugUnitTest", ":app:testPlayDebugUnitTest", ":app:testFossDebugUnitTest"):
            self.assertIn(task, unit)
        for task in ("lintDebug", ":app:lintPlayDebug", ":app:lintFossDebug", ":app:assemblePlayDebug", ":app:assembleFossDebug", ":vpn:assembleDebugAndroidTest", ":app:assemblePlayDebugAndroidTest"):
            self.assertIn(task, compile)
        for task in (":app:bundlePlayRelease", ":app:assemblePlayRelease", ":app:bundleFossRelease", ":app:assembleFossRelease"):
            self.assertIn(task, build)
        self.assertIn("-PlavaDebugKeystore=$HOME/.android/debug.keystore", build)
        self.assertIn("needs: [changes, repo-checks, lava-ui, unit-tests, app-compile]", source)

    def test_rn_tests_and_native_build_precede_packaged_asset_checks(self) -> None:
        source = self.read(WORKFLOW)
        for command in (
            "bash scripts/prepare-wireguard-core-android.sh",
            "npm ci --include=dev --ignore-scripts --no-audit --no-fund",
            "npm test",
            "python3 ci/verify_android_bundle.py",
            "--js-bundle android/app/src/main/assets/index.android.bundle",
        ):
            self.assertIn(command, source)
        self.assertLess(source.index("npm test"), source.index("./gradlew"))
        self.assertLess(source.index("./gradlew"), source.index("python3 ci/verify_android_bundle.py"))

    def test_packaged_asset_check_selects_each_release_edition(self) -> None:
        source = _executable_source(self.read(WORKFLOW))
        assets = source.split("- name: Verify packaged runtime assets", 1)[1].split(
            "- name: Audit FOSS resolved dependencies", 1)[0]
        self.assertIn("for edition in play foss; do", assets)
        command = next(line.strip() for line in assets.splitlines()
                       if line.strip().startswith("python3 ci/verify_android_bundle.py "))
        arguments = shlex.split(command)
        self.assertEqual(1, arguments.count("--edition"))
        self.assertEqual("$edition", arguments[arguments.index("--edition") + 1])
        self.assertEqual("android/app/build/outputs/bundle/${edition}Release/app-${edition}-release.aab",
                         arguments[2])

    def test_full_build_retains_required_checks_in_separate_ordered_steps(self) -> None:
        source = _executable_source(self.read(WORKFLOW))
        repo, ui, build = (_job(source, n) for n in ("repo-checks", "lava-ui", "build"))
        self.assertNotIn("./gradlew", repo)
        self.assertIn("needs.changes.outputs.docs_only == 'true'", repo)
        for contract in ("ci.test_android_ci_scope", "ci.test_android_workflow.AndroidWorkflowContractTest", "ci.test_android_react_native_contract"):
            self.assertIn(contract, repo)
        self.assertIn("python3 -m unittest discover -s ci -p 'test_*.py'", repo)
        self.assertIn("npm test", ui)
        self.assertIn("npm run bundle:android", ui)
        self.assertLess(build.index("./gradlew"), build.index("python3 ci/verify_android_bundle.py"))
        self.assertLess(build.index("python3 ci/verify_android_bundle.py"), build.index("- name: Upload debug APK"))
        for name in ("repo-checks", "lava-ui", "unit-tests", "app-compile", "build"):
            job = _job(source, name)
            self.assertIn("!cancelled()", job)
            self.assertIn("needs.changes.result != 'success'", job)
        for name in ("repo-checks", "lava-ui", "unit-tests", "app-compile"):
            self.assertIn("needs." + name.replace("-", "-") + ".result != 'success'", build)

    def test_instrumentation_executes_both_supported_api_boundaries_without_secrets(self) -> None:
        source = self.read(ROOT / ".github/workflows/android-device.yml")
        for required in (
            "api: [28, 36]",
            "\'ubicloud-standard-4\' || \'ubuntu-latest\'",
            "boot_deadline=$((SECONDS + 300))",
            "shell getprop sys.boot_completed",
            "if: ${{ always() }}",
            "android/*/build/outputs/androidTest-results/connected/**",
            "retention-days: 1",
        ):
            self.assertIn(required, source)
        for forbidden in ("secrets.", "id-token:", "environment:", "continue-on-error:"):
            self.assertNotIn(forbidden, source)
        executable = _executable_source(source)
        self.assertRegex(executable, r'(?m)^\s*(?:ANDROID_JOURNEY_BUDGET_SECONDS=(?:2400|3000|3600|4500) )?(?:timeout --kill-after=[1-9][0-9]*s [1-9][0-9]*m )?bash scripts/run-android-emulator-journeys\.sh\s*$')
        self.assertIn("ANDROID_PREBUILT_APK_DIR: ${{ runner.temp }}/android-emulator-candidate", executable)
        self.assertIn("python3 ci/android_emulator_evidence.py verify", executable)
        self.assertIn("needs: instrumentation", executable)

    def test_api_total_budgets_are_bounded_and_preserve_failure_exit(self) -> None:
        source = _executable_source(self.read(DEVICE_WORKFLOW))
        self.assertIn('timeout-minutes: ${{ matrix.api == 28 && 95 || 70 }}', source)
        start = source.index('if [[ "${{ matrix.api }}" == "28" ]]; then')
        branch = source[start:source.index("\n          fi", start) + len("\n          fi")]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = root / "timeout"
            executable.write_text('#!/bin/sh\nprintf "%s\\n" "$ANDROID_JOURNEY_BUDGET_SECONDS" "$@" > "$BUDGET_TRACE"\nexit 23\n')
            executable.chmod(0o700)
            for api, duration in ((28, "75m"), (36, "50m")):
                with self.subTest(api=api):
                    trace = root / f"api-{api}.txt"
                    environment = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"], BUDGET_TRACE=str(trace))
                    result = subprocess.run(["bash", "-c", branch.replace("${{ matrix.api }}", str(api))],
                                            env=environment, text=True, capture_output=True, timeout=5)
                    self.assertEqual(23, result.returncode, result.stderr)
                    self.assertEqual([str(int(duration[:-1])*60), "--kill-after=10s", duration, "bash", "scripts/run-android-emulator-journeys.sh"],
                                     trace.read_text().splitlines())

    def test_both_ci_build_lanes_select_the_committed_public_debug_keystore_explicitly(self) -> None:
        for path in (WORKFLOW, DEVICE_WORKFLOW):
            with self.subTest(workflow=path.name):
                source = _executable_source(self.read(path))
                decoded = 'base64 -d ci/lavasec-debug.keystore.b64 > "$HOME/.android/debug.keystore"'
                self.assertIn(decoded, source)
                command = next(line.strip() for line in source.splitlines()
                               if "gradlew" in line and "lavaDebugKeystore" in line)
                self.assertIn("-PlavaDebugKeystore=$HOME/.android/debug.keystore", shlex.split(command, comments=True))
                self.assertLess(source.index(decoded), source.index(command))
        build = self.read(ROOT / "android/app/build.gradle.kts")
        debug = build.split("buildTypes {", 1)[1].split("release {", 1)[0]
        self.assertIn('providers.gradleProperty("lavaDebugKeystore").orNull', debug)
        self.assertIn('signingConfig = signingConfigs.getByName("debug")', debug)
        self.assertIn("storeFile = file(path)", debug)

    def test_avd_creation_and_launch_share_explicit_home_and_fail_fast(self) -> None:
        source = self.read(ROOT / ".github/workflows/android-device.yml")
        create = source.split("- name: Install the emulator SDK and create the AVD", 1)[1]
        create, launch = create.split("- name: Execute app and VPN instrumentation on the emulator", 1)
        for step in (create, launch):
            self.assertIn("ANDROID_USER_HOME: ${{ runner.temp }}/android-user", step)
            self.assertIn("ANDROID_AVD_HOME: ${{ runner.temp }}/android-user/avd", step)
        self.assertIn('--path "$ANDROID_AVD_HOME/lavasec.avd"', create)
        self.assertIn('test -s "$ANDROID_AVD_HOME/lavasec.ini"', create)
        self.assertIn('-list-avds | grep -Fx lavasec', create)
        self.assertIn('if ! kill -0 "$emulator_pid"', launch)
        self.assertIn('cat "$evidence/emulator.log"', launch)

    def test_emulator_bootstrap_and_cleanup_use_hard_adb_deadlines_and_strict_boot_success(self) -> None:
        source = _executable_source(self.read(DEVICE_WORKFLOW))
        launch = source.split("- name: Execute app and VPN instrumentation on the emulator", 1)[1].split("- name: Retain instrumentation results", 1)[0]
        direct_adb = re.findall(r'(?m)^\s*(?:adb\b[^\n]*|timeout\b[^\n]*\badb\b[^\n]*)', launch)
        self.assertEqual(1, len(direct_adb), "All bootstrap/diagnostic adb calls must use the shared bound")
        self.assertRegex(direct_adb[0], r'timeout --kill-after=[1-9][0-9]*s "\$duration" adb "\$@"')
        for command in ("start-server", "shell getprop sys.boot_completed", "shell input keyevent 82", 'shell settings put global "$setting" 0', "logcat -d", "pull /sdcard/Android/data/com.lavasec.app.debug/files/launcher-evidence"):
            self.assertRegex(launch, rf'adb_with_timeout [1-9][0-9]*s {re.escape(command)}')
        self.assertIn('if boot_property="$(adb_with_timeout', launch)
        self.assertIn('if [[ "$(printf \'%s\' "$boot_property" | tr -d \'\\r\')" == 1 ]] && (( SECONDS < boot_deadline )); then break; fi', launch)
        self.assertIn('printf \'adb boot query failed (exit %s)\\n\'', launch)
        self.assertIn('trap collect_emulator_diagnostics EXIT', launch)
        self.assertLess(launch.index('trap collect_emulator_diagnostics'), launch.index('start-server'))
        ordered = ('start-server', 'shell getprop sys.boot_completed', 'shell input keyevent 82', 'shell settings put global', 'bash scripts/run-android-emulator-journeys.sh')
        for before, after in zip(ordered, ordered[1:]):
            self.assertLess(launch.index(before), launch.index(after))
        for log in ("adb-start-server.log", "boot-query.log", "adb-device-setup.log", "launcher-pull.log", "android-runtime-crashes.log"):
            self.assertIn(log, launch)
        logcat_calls = re.findall(r'(?m)^\s*adb_with_timeout [^\n]*\blogcat\b[^\n]*', launch)
        self.assertEqual(4, len(logcat_calls))
        self.assertEqual('adb_with_timeout 15s logcat -d -v threadtime AndroidRuntime:E \'*:S\' >"$evidence/android-runtime-crashes.log" 2>&1 || true',
                         logcat_calls[1].strip())
        self.assertEqual('adb_with_timeout 15s logcat -d -v raw LavaForegroundEntry:W \'*:S\' >"$evidence/shared-foreground-entry-refusals.log" 2>&1 || true',
                         logcat_calls[2].strip())
        self.assertRegex(logcat_calls[0].strip(), r"^adb_with_timeout 15s logcat -d -v raw ReactNativeJS:I '\*:S'\s+\| python3 ci/android_navigation_trace\.py --output \"\$evidence/navigation-removal-trace\.log\" \|\| true$")
        self.assertRegex(logcat_calls[3].strip(), r"^adb_with_timeout 15s logcat -d -v threadtime\s+'\*:E'\s+\| python3 ci/android_native_mount_trace\.py --output \"\$evidence/native-mount-trace\.json\" \|\| true$")
        self.assertIn("'*:E'", launch)
        self.assertIn('| python3 ci/android_native_mount_trace.py --output "$evidence/native-mount-trace.json" || true', launch)
        self.assertNotIn("logcat.txt", launch)
        self.assertNotIn("System.out", launch)
        self.assertRegex(launch, r'(?m)^\s*ANDROID_JOURNEY_BUDGET_SECONDS=(?:2400|3000|3600|4500) timeout --kill-after=[1-9][0-9]*s [1-9][0-9]*m bash scripts/run-android-emulator-journeys\.sh\s*$')
        self.assertLess(launch.index('Android emulator boot verified'), launch.index('shell input keyevent 82'))
        self.assertLess(launch.index('starting the prebuilt app journeys'), launch.index('bash scripts/run-android-emulator-journeys.sh'))

    def test_navigation_trace_capture_executes_actual_bounded_scalar_projection(self) -> None:
        source = self.read(DEVICE_WORKFLOW)
        body = source.split("collect_navigation_removal_trace() {\n", 1)[1].split("\n          }\n", 1)[0]
        helper = self.read(ROOT / "ci/android_navigation_trace.py")
        self.assertIn("readline(8193)", helper)
        self.assertIn("deque(maxlen=LIMIT)", helper)
        self.assertIn("ci/android_navigation_trace.py", body)
        self.assertIn("ReactNativeJS:I '*:S'", body)
        trace = {"events": 1, "commits": 9, "setCalls": 2, "setChanges": 1, "additions": 1,
                 "removals": 0, "notifyCalls": 3, "forwardedNotifications": 1, "nativeRouteCount": 2,
                 "publishedObjectChanged": True, "publishedContextChanged": True,
                 "publishedRouteSetChanged": False, "nativeStateChanged": False,
                 "ownedRegistrations": 1, "ownedProtectedRoutes": 1}
        boundaries = ("guard-parent", "guard-routes", "settings-parent", "settings-routes")
        rows = ["unrelated Javascript private-value=never-retain", "unrelated-prefix LavaNavigationRemovalTrace {}",
                "LavaNavigationRemovalTrace " + "x" * 9000, "LavaNavigationRemovalTrace {bad JSON}"]
        for malformed in ({"events": True}, {"events": 0}, {"events": 257}, {"commits": "private-value"},
                          {"nativeStateChanged": "private-value"}, {"boundary": "private-value"}, {"boundary": ["guard-parent"]},
                          {"boundary": {"secret": "never-retain"}}):
            rows.append("LavaNavigationRemovalTrace " + json.dumps({**trace, "boundary": "guard-parent", **malformed}))
        for boundary in boundaries:
            for index in range(300):
                rows.append("LavaNavigationRemovalTrace " + json.dumps({**trace, "boundary": boundary,
                    "events": min(index + 1, 256), "commits": index, "routes": ["private-route-value"], "params": {"secret": "never-retain"}}))
        script = """set -euo pipefail
        evidence="$1"
        fixture="$2"
        adb_with_timeout() {
          test "$*" = "15s logcat -d -v raw ReactNativeJS:I *:S"
          cat "$fixture"
          return "${DIAGNOSTIC_EXIT:-0}"
        }
        collect_navigation_removal_trace() {
        """ + textwrap.dedent(body) + "\n}\ncollect_navigation_removal_trace\n"
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch); fixture = root / "controlled-js-input"
            fixture.write_text("\n".join(rows) + "\n")
            for exit_code in ("0", "7"):
                result = subprocess.run(["bash", "-c", script, "trace-contract", str(root), str(fixture)],
                    env=dict(os.environ, DIAGNOSTIC_EXIT=exit_code), cwd=ROOT, capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", result.stdout)
                retained = (root / "navigation-removal-trace.log").read_text()
                self.assertNotIn("private", retained); self.assertNotIn("never-retain", retained)
                self.assertNotIn("params", retained); self.assertNotIn("routes", retained.replace("guard-routes", "").replace("settings-routes", ""))
                parsed = [json.loads(line.removeprefix("LavaNavigationRemovalTrace ")) for line in retained.splitlines()]
                self.assertEqual(1024, len(parsed))
                for boundary in boundaries:
                    scoped = [item for item in parsed if item["boundary"] == boundary]
                    self.assertEqual(256, len(scoped))
                    self.assertEqual(list(range(44, 300)), [item["commits"] for item in scoped])
                self.assertTrue(all(set(item) == set(trace) | {"boundary"} for item in parsed))

    def test_all_android_jobs_prepare_the_app_native_toolchain(self) -> None:
        for path in (WORKFLOW, DEVICE_WORKFLOW):
            source = _executable_source(self.read(path))
            self.assertIn('"ndk;27.2.12479018" "cmake;3.30.5"', source)
            wrapper = re.search(r"(?m)^\s*(?:\./|android/)gradlew\b", source)
            self.assertIsNotNone(wrapper, f"missing Gradle invocation in {path.name}")
            self.assertLess(source.index('"cmake;3.30.5"'), wrapper.start())
        bootstrap = self.read(ROOT / "scripts/prepare-wireguard-core-android.sh")
        self.assertIn('sdkmanager "ndk;27.2.12479018"', bootstrap)

    def test_device_job_budgets_disk_for_emulator_and_gradle(self) -> None:
        source = self.read(ROOT / ".github/workflows/android-device.yml")
        self.assertIn("\'ubicloud-standard-4\' || \'ubuntu-latest\'", source)
        cleanup = source.index("- name: Reserve hosted-runner disk space for Android")
        self.assertLess(cleanup, source.index("- name: Set up Android SDK"))
        self.assertIn("rm -rf ThirdParty/wireguard-core/target", source)
        self.assertLess(source.index("bash scripts/prepare-wireguard-core-android.sh"),
                        source.index("rm -rf ThirdParty/wireguard-core/target"))
        self.assertIn("disk.dataPartition.size=2G", source)
        self.assertIn('tee "$evidence/disk-before-boot.txt"', source)

    def test_emulator_apis_consume_one_owned_clean_candidate_without_rebuilding(self) -> None:
        source = _executable_source(self.read(DEVICE_WORKFLOW))
        candidate, rest = source.split("  instrumentation:", 1)
        matrix, gate = rest.split("  emulator-evidence:", 1)
        self.assertIn("  candidate-build:", candidate)
        for job in (candidate, matrix):
            self.assertIn("ubuntu-latest", job)
            self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", job)
            self.assertIn("\'ubicloud-standard-4\' || \'ubuntu-latest\'", job)
        self.assertEqual(1, len(re.findall(r"(?m)^\s*android/gradlew\b", source)))
        for task in (":app:assemblePlayDebug", ":app:assemblePlayDebugAndroidTest", ":vpn:assembleDebugAndroidTest"):
            self.assertIn(task, candidate)
        self.assertIn('if [[ -n "$(git status --porcelain)" ]]; then', candidate)
        self.assertLess(candidate.index('git status --porcelain'), candidate.index('git rev-parse HEAD >'))
        for module, variant, source_name, candidate_name in (
            ("app", "play/debug", "app-play-debug.apk", "app-debug.apk"),
            ("app", "androidTest/play/debug", "app-play-debug-androidTest.apk", "app-debug-androidTest.apk"),
            ("vpn", "androidTest/debug", "vpn-debug-androidTest.apk", "vpn-debug-androidTest.apk"),
        ):
            self.assertIn(f'cp android/{module}/build/outputs/apk/{variant}/{source_name} "$candidate/{candidate_name}"', candidate)
        self.assertIn('git rev-parse HEAD > "$candidate/source-sha.txt"', candidate)
        self.assertEqual(1, source.count("cargo build --locked --release --example android-doq-peer"))
        self.assertIn("working-directory: ThirdParty/doq-core", candidate)
        self.assertLess(candidate.index("bash scripts/prepare-wireguard-core-android.sh"),
                        candidate.index("cargo build --locked --release --example android-doq-peer"))
        for source_path, filename in (
            ("target/release/examples/android-doq-peer", "android-doq-peer"),
            ("tests/fixtures/resolver.crt", "resolver.crt"),
            ("tests/fixtures/resolver.key", "resolver.key"),
        ):
            self.assertIn(f'cp ThirdParty/doq-core/{source_path} "$candidate/{filename}"', candidate)
        self.assertIn("name: android-emulator-candidate", candidate)
        self.assertIn("if-no-files-found: error", candidate)
        self.assertIn("needs: candidate-build", matrix)
        self.assertIn("name: android-emulator-candidate", matrix)
        self.assertIn("path: ${{ runner.temp }}/android-emulator-candidate", matrix)
        self.assertIn("ANDROID_PREBUILT_APK_DIR: ${{ runner.temp }}/android-emulator-candidate", matrix)
        for build_input in ("gradlew", "setup-gradle", "setup-node", "npm ", "cargo build", "prepare-wireguard", "ndk;", "cmake;", "lavaDebugKeystore"):
            self.assertNotIn(build_input, matrix)
        self.assertIn("/dev/kvm", matrix)
        self.assertIn("api: [28, 36]", matrix)
        self.assertIn("python3 ci/android_emulator_evidence.py verify", gate)
        self.assertEqual(["1", "1"], _retention_days(source))

    def test_optimized_exact_host_peer_proof_cannot_pass_zero_tests_or_replace_android_exchange(self):
        source = self.read(DEVICE_WORKFLOW)
        identity = "transport_tests::android_host_peer_configuration_round_trips_exact_android_query"
        self.assertIn("cargo test --locked --release --lib " + identity + " -- --exact", source)
        self.assertIn("grep -Fx 'test " + identity + " ... ok'", source)
        self.assertIn("1 passed; 0 failed; 0 ignored; 0 measured", source)
        self.assertLess(source.index("cargo test --locked --release --lib"),
                        source.index("cargo build --locked --release --example android-doq-peer"))
        self.assertIn('cp "$RUNNER_TEMP/android-doq-peer-release-proof.txt" "$candidate/android-doq-peer-release-proof.txt"', source)
        self.assertIn("run_doq_peer || journey_status=1", JOURNEY_SCRIPT.read_text())
        abort_proof = "cargo run --locked --release --example crypto-release-regression"
        self.assertIn(abort_proof, self.read(WIREGUARD_WORKFLOW))
        self.assertLess(source.index(abort_proof),
                        source.index("cargo build --locked --release --example android-doq-peer"))

    def test_foss_release_audit_uses_resolved_graph_manifest_and_actual_dex(self):
        source = self.read(WORKFLOW)
        self.assertIn(':app:dependencies --configuration fossReleaseRuntimeClasspath', source)
        self.assertIn('python3 ci/verify_foss_boundary.py', source)
        self.assertIn('--bundle android/app/build/outputs/bundle/fossRelease/app-foss-release.aab', source)
        self.assertIn('--bundletool "$bundletool"', source)
        self.assertIn('a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29', source)
        self.assertIn('foss-boundary.json', source)

    def test_ci_is_build_only_and_has_no_release_credentials(self) -> None:
        source = self.read(WORKFLOW).lower()
        forbidden = (
            "id-token:",
            "environment:",
            "secrets.",
            "android_upload_",
            "google-github-actions/auth",
            "fastlane",
            "gradle-play-publisher",
            "androidpublisher",
            "upload_to_play_store",
            "play_track",
        )
        for token in forbidden:
            with self.subTest(token=token):
                self.assertNotIn(token, source)

    def test_apk_and_unsigned_bundle_retention_is_cost_bounded(self) -> None:
        source = self.read(WORKFLOW)
        self.assertEqual(
            6,
            source.count(
                "uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2"
            ),
        )
        # CI artifacts are ephemeral: a multi-day window accrues GitHub Actions
        # artifact-storage charges for every PR build. Match the exact value (not a
        # numeric prefix) so 10/11/19 cannot slip past the one-day bound.
        self.assertEqual(["1"] * 6, _retention_days(source))
        self.assertEqual(1, source.count("android-verification-diagnostics"))
        diagnostics = source.split("- name: Retain failed unit test and lint diagnostics", 1)[1].split("- name:", 1)[0]
        self.assertIn("if: ${{ failure() }}", diagnostics)
        self.assertIn("android/*/build/test-results/**", diagnostics)
        self.assertEqual(1, source.count("android/app/build/outputs/apk/*/debug/*.apk"))
        self.assertEqual(1, source.count("android/app/build/outputs/bundle/*Release/*.aab"))
        self.assertEqual(1, source.count("lavasec-android-debug-apk"))
        self.assertEqual(1, source.count("lavasec-android-release-unsigned-aab"))

    def test_effective_public_config_is_source_bound_and_retained_in_both_build_lanes(self) -> None:
        full = self.read(WORKFLOW)
        device = self.read(DEVICE_WORKFLOW).split("  instrumentation:", 1)[0]
        collector = "python3 ci/android_public_config_evidence.py --root . --variants"
        self.assertIn(collector + " playDebug playRelease fossDebug fossRelease", full)
        self.assertIn(collector + " playDebug", device)
        self.assertLess(full.index("./gradlew"), full.index(collector))
        self.assertLess(full.index(collector), full.index("- name: Upload debug APK"))
        for folder in ("apk/$edition/debug", "bundle/${edition}Release"):
            path = f"android/app/build/outputs/{folder}/android-public-configuration.json"
            self.assertIn(f'cp "$RUNNER_TEMP/android-public-configuration.json" "{path}"', full)
        for task in (":app:testPlayDebugUnitTest", ":app:testFossDebugUnitTest", ":app:lintPlayDebug", ":app:lintFossDebug", ":app:assembleFossDebug", ":app:bundleFossRelease", ":app:assembleFossRelease"):
            self.assertIn(task, full)
        self.assertIn('for edition in play foss; do', full)
        self.assertLess(device.index("android/gradlew"), device.index(collector))
        self.assertLess(device.index('git rev-parse HEAD >'), device.index(collector))
        self.assertIn('--output "$candidate/android-public-configuration.json"', device)
        self.assertNotIn("--variants release", device)

    def test_wireguard_engine_artifact_retention_is_cost_bounded(self) -> None:
        source = self.read(WIREGUARD_WORKFLOW)
        self.assertEqual(
            2,
            source.count(
                "uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2"
            ),
        )
        # Both native engines retain only one day of rebuildable ABI artifacts.
        self.assertEqual(["1", "1"], _retention_days(source))
        self.assertEqual(1, source.count("lavasec-wireguard-core-android"))
        self.assertEqual(1, source.count("lavasec-doq-core-android"))
        self.assertEqual(1, source.count("android/vpn/src/main/jniLibs/**/liblavasec_doq_core.so"))
        self.assertEqual(
            1,
            source.count(
                "android/vpn/src/main/jniLibs/**/liblavasec_wireguard_core.so"
            ),
        )

    def test_every_third_party_action_is_pinned_to_a_full_commit(self) -> None:
        source = self.read(WORKFLOW)
        uses = re.findall(
            r"(?m)^\s*uses:\s+([^\s#]+)(?:\s+#\s*(v[^\s]+))?\s*$",
            source,
        )
        self.assertTrue(uses)
        for reference, version in uses:
            with self.subTest(reference=reference):
                self.assertRegex(reference, r"^[^@\s]+@[0-9a-f]{40}$")
                self.assertRegex(version, r"^v[0-9]+(?:\.[0-9]+)+$")

    def test_dependabot_covers_actions_and_validates_optional_gradle_entry(self) -> None:
        source = self.read(DEPENDABOT)
        self.assertIn("package-ecosystem: github-actions", source)
        self.assertIn('directory: "/"', source)
        if "package-ecosystem: gradle" in source:
            self.assertIn('directory: "/android"', source)


class EmulatorJourneyScriptContractTest(unittest.TestCase):
    """Execute the shipping shell orchestration; Android/tool invocations are controlled ports."""

    def setUp(self) -> None:
        self.scratch = tempfile.TemporaryDirectory(prefix="android workflow contract ")
        self.addCleanup(self.scratch.cleanup)
        # The real driver resolves __file__ before its git -C source guard. On
        # macOS /var is a symlink to /private/var; give every controlled port the
        # same canonical fixture root without weakening that exact source check.
        self.root = Path(self.scratch.name).resolve()
        for directory in ("scripts", "ci", "android/react-native/scripts", "tools",
                          "sdk/platform-tools", "sdk/build-tools/36.0.0",
                          "android/app/build/outputs/apk/play/debug",
                          "android/app/build/outputs/apk/androidTest/play/debug",
                          "android/vpn/build/outputs/apk/androidTest/debug",
                          "ThirdParty/doq-core/tests/fixtures", "android/vpn/src/androidTest/assets/doq"):
            (self.root / directory).mkdir(parents=True)
        shutil.copyfile(JOURNEY_SCRIPT, self.root / "scripts/run-android-emulator-journeys.sh")
        shutil.copyfile(ROOT / "ci/android_emulator_evidence.py", self.root / "ci/android_emulator_evidence.py")
        shutil.copyfile(ROOT / "ci/android_public_ui_evidence.py", self.root / "ci/android_public_ui_evidence.py")
        shutil.copyfile(ROOT / "ci/android_doq_peer.py", self.root / "ci/android_doq_peer.py")
        for filename in ("android_default_app_recovery.py", "android_navigation_trace.py", "android_native_mount_trace.py"):
            shutil.copyfile(ROOT / "ci" / filename, self.root / "ci" / filename)
        for filename in ("resolver.crt", "resolver.key"):
            shutil.copyfile(ROOT / "ThirdParty/doq-core/tests/fixtures" / filename,
                            self.root / "ThirdParty/doq-core/tests/fixtures" / filename)
        shutil.copyfile(ROOT / "android/vpn/src/androidTest/assets/doq/resolver.crt",
                        self.root / "android/vpn/src/androidTest/assets/doq/resolver.crt")
        (self.root / "android/react-native/scripts/canonical-source.manifest.json").write_text(
            json.dumps({"canonical": {"revision": "c4658d973bae05dd3b7aa84c014cb2f76ff61a0b"}}))
        self.apk = self.root / "android/app/build/outputs/apk/play/debug/app-play-debug.apk"
        self.apk.write_bytes(b"controlled app APK bytes")
        (self.root / "android/app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk").write_bytes(b"controlled test APK bytes")
        (self.root / "android/vpn/build/outputs/apk/androidTest/debug/vpn-debug-androidTest.apk").write_bytes(b"controlled VPN test APK bytes")
        self.keystore = self.root / "public preview keystore"
        self.keystore.write_bytes(b"controlled Gradle signing input")
        self.commands = self.root / "commands.jsonl"
        self.logcat = self.root / "controlled-logcat-buffer"
        self.logcat.write_text("LAVA_UI_MATRIX_BEGIN cell=stale-prior-source\nprivate stale log\n")
        self.env = dict(os.environ, ANDROID_SERIAL="emulator-contract",
                        ANDROID_SDK_ROOT=str(self.root / "sdk"),
                        ANDROID_PREVIEW_KEYSTORE=str(self.keystore),
                        COMMAND_LOG=str(self.commands), FIXTURE_ROOT=str(self.root),
                        REQUIRED_JOURNEYS=json.dumps(emulator_evidence.REQUIRED_CASES),
                        SIGNER=emulator_evidence.DEBUG_SIGNER_SHA256,
                        ANDROID_CANARY_PEER_ALLOW_SUDO="0")
        self.env["PATH"] = str(self.root / "tools") + os.pathsep + self.env.get("PATH", "")
        (self.root / "tools/python3").symlink_to(sys.executable)
        # This is a controlled subprocess/protocol port, never a QUIC peer or a
        # claimed Android exchange. The actual driver/parser/cleanup run unchanged.
        self.doq_peer_source = "#!" + sys.executable + "\n" + textwrap.dedent(r'''
            import argparse, json, os, signal, sys, time
            from pathlib import Path
            parser = argparse.ArgumentParser()
            for flag in ('cert', 'key', 'max-duration-ms'):
                parser.add_argument('--' + flag, required=True)
            args = parser.parse_args()
            assert args.max_duration_ms == '120000'
            root = Path(os.environ['FIXTURE_ROOT'])
            marker = root / 'doq-instrument-called'
            marker.unlink(missing_ok=True)
            (root / 'doq-peer.pid').write_text(str(os.getpid()))
            def event(operation):
                with open(os.environ['COMMAND_LOG'], 'a') as log:
                    log.write(json.dumps({'tool': 'doq-peer', 'args': [operation], 'api': os.environ['API_VALUE']}) + '\n')
            def terminate(signum, frame):
                raise SystemExit(0)
            signal.signal(signal.SIGTERM, terminate)
            event('start')
            failure = os.environ.get('FAIL_DOQ_PEER')
            try:
                print(json.dumps({'event': 'ready', 'address': '0.0.0.0' if failure == 'readiness' else '127.0.0.1',
                                  'port': 49152, 'max_duration_ms': 120000}), flush=True)
                deadline = time.monotonic() + 20
                while not marker.exists():
                    if time.monotonic() > deadline: sys.exit(8)
                    time.sleep(0.005)
                query = bytes([0, 37, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 11]) + b'android-doq' + bytes([7]) + b'invalid' + bytes([0, 0, 1, 0, 1])
                reply = bytearray(query); reply[4:6] = bytes.fromhex('8183')
                print(json.dumps({'event': 'query', 'alpn': 'doq', 'sni': 'resolver.test', 'stream': 0, 'query_fin': True,
                                  'query_wire_hex': query.hex(), 'reply_wire_hex': reply.hex(),
                                  'host_observed_peer': '127.0.0.1:32145', 'source_scope': 'NAT-translated-host-loopback'}), flush=True)
                print(json.dumps({'event': 'complete', 'accepted_queries': 1, 'response_queued_with_fin': True,
                                  'scope': 'host-loopback-NAT-peer'}), flush=True)
                sys.exit(7 if failure == 'exit' else 0)
            finally:
                event('stop')
        ''')
        self.local_peer = self.root / "source bound local DoQ peer"
        self.peer_bundle(self.local_peer)
        # This controlled lifecycle port never binds UDP53 or claims a received
        # datagram. Real capture/control semantics have separate helper tests.
        (self.root / "scripts/android-canary-udp-peer.py").write_text(textwrap.dedent(r'''
            import argparse, json, os, socketserver, sys
            parser = argparse.ArgumentParser()
            for flag in ('ready', 'observations', 'owner-uid', 'owner-gid'):
                parser.add_argument('--' + flag, required=True)
            args = parser.parse_args()
            def event(operation):
                with open(os.environ['COMMAND_LOG'], 'a') as log:
                    log.write(json.dumps({'tool': 'canary-peer', 'args': [operation], 'api': os.environ['API_VALUE']}) + '\n')
            event('start')
            failure = os.environ.get('FAIL_CANARY_PEER')
            if failure == 'bind':
                sys.stderr.write('Host cannot bind required loopback UDP53 (controlled EACCES)\n')
                sys.exit(1)
            class Control(socketserver.StreamRequestHandler):
                def handle(self):
                    request = json.loads(self.rfile.readline())
                    assert request == {'token': 'c' * 64, 'op': 'shutdown'}
                    event('shutdown')
                    self.wfile.write(b'{"ok":true}\n')
            with socketserver.TCPServer(('127.0.0.1', 0), Control) as server:
                with open(args.ready, 'w') as output:
                    json.dump({'protocol': 0 if failure == 'readiness' else 1,
                               'controlPort': server.server_address[1], 'token': 'c' * 64,
                               'udpAddress': '127.0.0.1', 'udpPort': 53}, output)
                open(args.observations, 'x').close()
                if failure == 'readiness': sys.exit(1)
                server.timeout = 15
                server.handle_request()
        '''))
        fake = "#!" + sys.executable + "\n" + textwrap.dedent(r'''
            import hashlib, io, json, os, struct, subprocess, sys, tarfile, zlib
            from pathlib import Path
            import xml.etree.ElementTree as ET
            tool, args = Path(sys.argv[0]).name, sys.argv[1:]
            with open(os.environ["COMMAND_LOG"], "a") as output:
                output.write(json.dumps({"tool": tool, "args": args, "api": os.environ["API_VALUE"]}) + "\n")
            root = Path(os.environ["FIXTURE_ROOT"])
            witness = root / "private-update-witness"
            logcat = root / "controlled-logcat-buffer"
            def emit_matrix(origin):
                with logcat.open("a") as output:
                    output.write(f"LAVA_UI_MATRIX_BEGIN cell=current-{os.environ['API_VALUE']}-{origin}\n")
                    output.write("private-session-token=synthetic-do-not-retain\n")
                    output.write("unrelated-prefix LAVA_UI_MATRIX_BEGIN cell=synthetic-do-not-retain\n")
                    output.write("LAVA_UI_INVALID-MARKER synthetic-do-not-retain\n")
                    if os.environ.get("EMIT_EDITABLE_DIAGNOSTICS") == "1":
                        for marker in ("LAVA_JOURNEY_EDITABLE_FIELDS", "LAVA_JOURNEY_EDITABLE_FIELD",
                                       "LAVA_JOURNEY_EDITABLE_ANCESTOR", "LAVA_JOURNEY_SET_TEXT_TARGET",
                                       "LAVA_JOURNEY_SET_TEXT_RESULT", "LAVA_ONBOARDING_MEASUREMENT",
                                       "LAVA_LOCALIZED_ACCOUNT_BACK"):
                            output.write(f"{marker} stage=current-{os.environ['API_VALUE']}-{origin} label=Search Device QA metadata=synthetic-redacted\n")
                        for marker in ("LAVA_JOURNEY_EDITABLE_FIELD_EXTRA", "LAVA_JOURNEY_EDITABLE_PAYLOAD",
                                       "LAVA_JOURNEY_SET_TEXT_TARGET_EXTRA", "LAVA_JOURNEY_SET_TEXT_VALUE",
                                       "LAVA_JOURNEY_CLICK_RESULT", "unrelated-prefix LAVA_JOURNEY_EDITABLE_FIELD",
                                       "LAVA_ONBOARDING_MEASUREMENT_EXTRA", "LAVA_LOCALIZED_ACCOUNT_BACK_PAYLOAD",
                                       "unrelated-prefix LAVA_ONBOARDING_MEASUREMENT"):
                            output.write(f"{marker} entered-value=synthetic-do-not-retain\n")
                    output.write(f"LAVA_UI_MATRIX_END cell=current-{os.environ['API_VALUE']}-{origin}\n")
            methods = json.loads(os.environ["REQUIRED_JOURNEYS"])
            product = "com.lavasec.app.reactnative.LavaReactProductJourneyTest"
            shared_setup = "com.lavasec.app.reactnative.LavaReactSharedForegroundOnboardingDeviceTest"
            exported_entry = "com.lavasec.app.reactnative.LavaReactExportedEntryJourneyTest"
            death = "com.lavasec.app.reactnative.VpnProcessDeathInstrumentationTest"
            doq = "com.lavasec.app.vpn.NativeDoqSocketJourneyInstrumentationTest"
            doq_method = "verifiedAndroidQuicPeerRestoresCallerIdAndReleasesExactEpochResources"
            talkback = "com.lavasec.app.reactnative.LavaReactTalkBackJourneyTest"
            if tool == "adb":
                assert args[:2] == ["-s", "emulator-contract"]
                args = args[2:]
                if args[:2] == ["shell", "getprop"]:
                    print(os.environ.get("QEMU_VALUE", "1") if args[2] == "ro.kernel.qemu" else os.environ["API_VALUE"])
                elif args[:3] == ["exec-out", "run-as", "com.lavasec.app.debug"]:
                    names = ["manifest.json", "shipping-guard.png", "shipping-settings.png", "shipping-upgrade.png"]
                    assert args[3:] == ["tar", "-C", "cache/predevice-public-screenshots", "-cf", "-", *names]
                    if os.environ.get("FAIL_PUBLIC_CAPTURE") == "1": sys.exit(7)
                    def chunk(kind, data):
                        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
                    image = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 360, 640, 8, 6, 0, 0, 0))
                             + chunk(b"IDAT", zlib.compress((b"\x00" + b"\x80\x90\xa0\xff" * 360) * 640)) + chunk(b"IEND", b""))
                    frames = [{"tag": name[:-4], "file": name, "page": name[:-4], "width": 360, "height": 640,
                               "sha256": hashlib.sha256(image).hexdigest()} for name in names[1:]]
                    payloads = {"manifest.json": json.dumps({"schema": 1, "api": int(os.environ["API_VALUE"]), "frames": frames}).encode(),
                                **{name: image for name in names[1:]}}
                    buffer = io.BytesIO()
                    with tarfile.open(fileobj=buffer, mode="w") as archive:
                        for name, payload in payloads.items():
                            member = tarfile.TarInfo(name); member.size = len(payload)
                            archive.addfile(member, io.BytesIO(payload))
                    sys.stdout.buffer.write(buffer.getvalue())
                elif args[:3] == ["shell", "pm", "path"]:
                    sys.exit(1)  # The initial managed-emulator install has not happened yet.
                elif args[0] == "install":
                    if args[-1].endswith("vpn-debug-androidTest.apk") and os.environ.get("FAIL_VPN_INSTALL") == "1": sys.exit(7)
                    if witness.exists() and os.environ.get("LOSE_UPDATE") == "1": witness.unlink()
                    print("Success")
                elif args[:3] == ["shell", "run-as", "com.lavasec.app.debug"]:
                    if "cat" in args:
                        if not witness.exists(): sys.exit(1)
                        print(witness.read_text())
                elif args[0] == "shell" and len(args) == 2 and "echo emulator-synthetic-update" in args[1]:
                    witness.write_text("emulator-synthetic-update")
                elif args[:3] == ["shell", "pm", "clear"]:
                    witness.unlink(missing_ok=True)
                    print("Success")
                elif args[:3] == ["shell", "am", "force-stop"]:
                    pass
                elif args == ["logcat", "-c"]:
                    assert witness.exists(), "logcat clear must follow successful update preservation"
                    if os.environ.get("FAIL_LOGCAT_CLEAR") == "1": sys.exit(7)
                    logcat.write_text("")
                elif args == ["logcat", "-d", "-v", "raw", "ReactNativeJS:I", "*:S"]:
                    if os.environ.get("TRACE_CAPTURE_FAIL") == "1": sys.exit(7)
                    trace_input = root / "controlled-navigation-trace"
                    if trace_input.exists(): print(trace_input.read_text(), end="")
                elif args == ["logcat", "-d", "-v", "threadtime", "AndroidRuntime:E", "*:S"]:
                    crash_phase = root / "controlled-crash-phase"
                    phase_label = crash_phase.read_text() if crash_phase.exists() else "original-app"
                    print("FATAL EXCEPTION: controlled-" + phase_label)
                    print("Maximum update depth exceeded: controlled original failure")
                elif args[:4] == ["logcat", "-d", "-v", "threadtime"]:
                    # Model liblog's first-colon split and first priority character.
                    # "unknown:SurfaceMountingManager:E" is unknown:S, not the RN tag.
                    levels = {"V": 2, "D": 3, "I": 4, "W": 5, "E": 6, "F": 7, "S": 8}
                    filters = {}
                    for rule in args[4:]:
                        tag, colon, priority = rule.partition(":")
                        if not tag or colon and (not priority or priority[0].upper() not in levels): sys.exit(22)
                        filters[tag] = levels[priority[0].upper()] if colon else 2
                    def emit_error(tag, priority, message):
                        if levels[priority] >= filters.get(tag, filters.get("*", 3)):
                            print("10-09 02:10:48.400 5885 5885 " + priority + " " + tag + ": " + message)
                    if os.environ.get("MOUNT_CAPTURE_FAIL") == "1": sys.exit(7)
                    mount_witness = {"schema": 1, "child": 67148, "attemptedParent": 67746, "previousParent": 66500,
                               "mainThread": True, "currentContext": True, "sameWindow": True, "resolved": True, "status": "resolved",
                               "childChain": [{"tag": 67148, "class": "ReactViewGroup", "marker": "other", "parentIndex": -1},
                                              {"tag": 66500, "class": "ReactViewGroup", "marker": "sheet.native-footer", "parentIndex": 0}],
                               "attemptedParentChain": [{"tag": 67746, "class": "ReactViewGroup", "marker": "sheet.pinned-header", "parentIndex": 0}],
                               "childTruncated": False, "attemptedParentTruncated": False}
                    emit_error("LavaFabricMountWitness", "E", "ParentWitness " + json.dumps(mount_witness))
                    emit_error("unknown:SurfaceMountingManager", "E", "java.lang.IllegalStateException: addViewAt: cannot insert view [67148] into parent [67746]: View already has a parent: [66500] Parent: ReactViewGroup View: ReactViewGroup")
                    emit_error("AndroidRuntime", "E", "java.lang.IllegalStateException: addViewAt: failed to insert view [67148] into parent [67746] at index 9")
                    emit_error("unknown:SurfaceMountingManager", "E", "private hierarchy do-not-retain")
                    emit_error("AndroidRuntime", "E", "private user content do-not-retain")
                    emit_error("AndroidRuntime", "F", "private unrelated fatal do-not-retain")
                    emit_error("UnrelatedPrivateTag", "E", "private unrelated do-not-retain")
                    emit_error("unknown:SurfaceMountingManager", "I", "private informational do-not-retain")
                elif args == ["logcat", "-d", "-v", "raw", "System.out:I", "*:S"]:
                    print(logcat.read_text(), end="")
                    if os.environ.get("FAIL_LOGCAT_DUMP") == "1": sys.exit(7)
                elif args[:3] == ["shell", "am", "instrument"]:
                    assert "-w" in args and "-r" in args
                    app_runner = "com.lavasec.app.debug.test/androidx.test.runner.AndroidJUnitRunner"
                    vpn_runner = "com.lavasec.app.vpn.test/androidx.test.runner.AndroidJUnitRunner"
                    assert args[-1] in (app_runner, vpn_runner)
                    if "class" in args:
                        class_name = args[args.index("class") + 1]
                        if class_name == doq + '#' + doq_method:
                            assert args[-1] == vpn_runner
                            assert args[args.index('doqPeerHost') + 1] == '10.0.2.2'
                            assert args[args.index('doqPeerPort') + 1] == '49152'
                            assert 'canaryPeerControlToken' not in args
                            (root / 'doq-instrument-called').touch()
                            phase, cases = 'doq', [(doq, doq_method)]
                        elif "#" in class_name:
                            assert args[-1] == app_runner
                            selected_class, selected_method = class_name.split("#", 1)
                            assert selected_class not in (product, shared_setup, exported_entry, death, talkback)
                            assert not selected_class.startswith("com.lavasec.app.vpn.")
                            assert selected_method in methods[selected_class]
                            phase, cases = "recovery", [(selected_class, selected_method)]
                            if os.environ.get("DRAIN_RECOVERY_STDIN") == "1":
                                # Real adb shell starts a stdin-reading thread even
                                # for a command whose device-side body ignores input.
                                # This child must receive EOF, never the selector queue.
                                drained = sys.stdin.buffer.read()
                                with (root / "controlled-recovery-stdin.jsonl").open("a") as output:
                                    output.write(json.dumps({"selector": class_name, "drainedBytes": len(drained)}) + "\n")
                        else:
                            assert args[-1] == app_runner
                            assert class_name in (product, shared_setup, exported_entry, death)
                            if class_name == death:
                                phase = args[args.index("parityProcessDeath") + 1]
                                cases = [(death, "persistedImportTransactionRecoversAcrossRunnerProcesses")]
                            else:
                                assert "parityProcessDeath" not in args
                                phase = "shared-setup" if class_name == shared_setup else "exported-entry" if class_name == exported_entry else "product"
                                cases = [(class_name, name) for name in methods[class_name]
                                         if class_name + "#" + name != os.environ.get("OMIT_CASE")]
                    else:
                        vpn = args[-1] == vpn_runner
                        phase = "vpn" if vpn else "app"
                        assert args[args.index("notClass") + 1] == (doq if vpn else product + "," + shared_setup + "," + exported_entry + "," + death + "," + talkback)
                        cases = [(class_name, name) for class_name, names in methods.items()
                                 if class_name not in (product, shared_setup, exported_entry, death, doq, talkback) and (class_name.startswith("com.lavasec.app.vpn.") == vpn)
                                 for name in names if class_name + "#" + name != os.environ.get("OMIT_CASE")]
                    if phase == "app" and os.environ.get("APP_ABORT_CASE"):
                        active = os.environ["APP_ABORT_CASE"]
                        omitted = set(json.loads(os.environ.get("APP_OMIT_RECOVERY", "[]")))
                        cases = [item for item in cases if "#".join(item) != active and "#".join(item) not in omitted] + [tuple(active.split("#", 1))]
                    status = -2 if phase == os.environ.get("FAIL_PHASE") else 0
                    if os.environ.get("EMIT_PHASE_FAILURE_TRACE") == "1":
                        value = {key: 1 for key in ("events", "commits", "setCalls", "setChanges", "additions", "removals", "notifyCalls", "forwardedNotifications", "nativeRouteCount")}
                        value.update({key: False for key in ("publishedObjectChanged", "publishedContextChanged", "publishedRouteSetChanged", "nativeStateChanged")})
                        value.update(boundary="guard-parent", commits={"product": 117, "app": 239}.get(phase, 7), privateText="do-not-retain")
                        (root / "controlled-navigation-trace").write_text("LavaNavigationRemovalTrace " + json.dumps(value) + "\n")
                        (root / "controlled-crash-phase").write_text(phase)
                    if os.environ.get("EMIT_SETUP_DIAGNOSTICS") == "1":
                        if phase == "shared-setup":
                            with logcat.open("a") as output:
                                output.write(f"LAVA_ONBOARDING_MEASUREMENT page=5 api={os.environ['API_VALUE']} size=320x640\n")
                                output.write("LAVA_ONBOARDING_MEASUREMENT_EXTRA private-value=synthetic-do-not-retain\n")
                                output.write("unrelated-prefix LAVA_ONBOARDING_MEASUREMENT private-value=synthetic-do-not-retain\n")
                                output.write("private-setup-copy=synthetic-do-not-retain\n")
                        elif phase == "product":
                            # Model the small API28 ring buffer losing early records.
                            logcat.write_text("")
                    if phase == "app": emit_matrix("app")
                    failed = status != 0
                    for class_name, name in cases:
                        if phase == "app" and class_name + "#" + name == os.environ.get("APP_ABORT_CASE"):
                            print(f"INSTRUMENTATION_STATUS: class={class_name}\nINSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 1")
                            print("INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0")
                            sys.exit(0)
                        case_status = -2 if class_name.endswith(".DnsLeakCanaryInstrumentationTest") and "canaryPeerControlPort" not in args else status
                        failed = failed or case_status != 0
                        for code in (1, case_status):
                            print(f"INSTRUMENTATION_STATUS: class={class_name}\nINSTRUMENTATION_STATUS: test={name}")
                            print(f"INSTRUMENTATION_STATUS_CODE: {code}")
                    print(f"INSTRUMENTATION_RESULT: stream=\nOK ({len(cases)} tests)" if not failed else "INSTRUMENTATION_RESULT: stream=\nFAILURES!!!")
                    print("INSTRUMENTATION_CODE: -1")
                    if phase == "recovery":
                        with (root / "controlled-navigation-trace").open("w") as output:
                            value = {key: 1 for key in ("events", "commits", "setCalls", "setChanges", "additions", "removals", "notifyCalls", "forwardedNotifications", "nativeRouteCount")}
                            value.update({key: False for key in ("publishedObjectChanged", "publishedContextChanged", "publishedRouteSetChanged", "nativeStateChanged")})
                            value.update(boundary="guard-parent", commits=999, privateText="do-not-retain")
                            output.write("LavaNavigationRemovalTrace " + json.dumps(value) + "\n")
                        if os.environ.get("RECOVERY_TRANSPORT_FAIL") == "1": sys.exit(124)
                    if phase == os.environ.get("ADB_EXIT_PHASE"): sys.exit(7)
                else:
                    raise AssertionError("unexpected controlled adb invocation")
            elif tool == "timeout":
                assert args[0] == "--kill-after=5s"
                duration = int(args[1].removesuffix("s"))
                assert 1 <= duration <= 180
                try:
                    completed = subprocess.run(args[2:], timeout=duration)
                    sys.exit(completed.returncode)
                except subprocess.TimeoutExpired:
                    sys.exit(124)
            elif tool == "gradlew":
                assert "-PlavaDebugKeystore=" + os.environ["ANDROID_PREVIEW_KEYSTORE"] in args
                assert ":vpn:connectedDebugAndroidTest" in args and ":app:connectedPlayDebugAndroidTest" in args
                assert "--continue" in args
                assert '-Pandroid.testInstrumentationRunnerArguments.notClass=' + product + ',' + shared_setup + ',' + exported_entry + ',' + death + ',' + talkback + ',' + doq in args
                emit_matrix("connected")
                for module in ("app", "vpn"):
                    results = root / f"android/{module}/build/outputs/androidTest-results/connected"
                    results.mkdir(parents=True, exist_ok=True)
                    suite = ET.Element("testsuite")
                    for class_name, names in methods.items():
                        if class_name in (product, shared_setup, exported_entry, death, doq, talkback) or (class_name.startswith("com.lavasec.app.vpn.") != (module == "vpn")):
                            continue
                        for name in names:
                            if class_name + "#" + name != os.environ.get("OMIT_CASE"):
                                case = ET.SubElement(suite, "testcase", classname=class_name, name=name)
                                if class_name.endswith(".DnsLeakCanaryInstrumentationTest") and not any(
                                        arg.startswith("-Pandroid.testInstrumentationRunnerArguments.canaryPeerControlPort=") for arg in args):
                                    ET.SubElement(case, "failure", message="controlled host peer was unavailable")
                    ET.ElementTree(suite).write(results / "connected.xml")
                if os.environ.get("REBUILD_APK") == "1":
                    (root / "android/app/build/outputs/apk/play/debug/app-play-debug.apk").write_bytes(b"different APK rebuilt after shipping journeys")
                if os.environ.get("FAIL_CONNECTED") == "1": sys.exit(1)
            elif tool == "apksigner":
                assert args[:2] == ["verify", "--print-certs"]
                print("Signer #1 certificate SHA-256 digest: " + os.environ["SIGNER"])
            elif tool == "git":
                if args[:1] == ["-C"]:
                    assert args[1] == str(root)
                    args = args[2:]
                assert args in (["rev-parse", "HEAD"], ["status", "--porcelain"])
                if args[0] == "rev-parse": print("a" * 40)
            elif tool == "uname":
                assert args == ["-s"]
                print("Linux")  # Controlled host identity only; never changes host policy.
            elif tool == "sudo":
                assert args[:3] == ["-n", "--", "python3"]
                assert args[3] == str(root / "scripts/android-canary-udp-peer.py")
                os.execvp(args[2], args[2:])  # No elevation; executable launch-contract port.
            else:
                raise AssertionError("unexpected controlled tool")
        ''')
        for name in ("sdk/platform-tools/adb", "sdk/build-tools/36.0.0/apksigner", "android/gradlew", "tools/git", "tools/uname", "tools/sudo", "tools/timeout"):
            target = self.root / name
            target.write_text(fake)
            target.chmod(0o755)

    def run_script(self, api=28, **changes):
        if "ANDROID_DOQ_PEER_DIR" not in changes and "ANDROID_PREBUILT_APK_DIR" not in changes:
            changes["ANDROID_DOQ_PEER_DIR"] = str(self.local_peer)
        env = dict(self.env, API_VALUE=str(api), ANDROID_JOURNEY_EVIDENCE=str(self.root / f"evidence-{api}"), **changes)
        return subprocess.run(["bash", str(self.root / "scripts/run-android-emulator-journeys.sh")],
                              cwd=self.root, env=env, text=True, capture_output=True, timeout=30)

    def events(self):
        return [json.loads(line) for line in self.commands.read_text().splitlines()]

    def test_invalid_or_wrong_api_extended_budget_refuses_before_app_mutation(self):
        for api, budget in ((28, "0"), (28, "4501"), (28, "2400+1"), (28, "3000"), (36, "3600"), (36, "4500")):
            with self.subTest(api=api, budget=budget):
                result = self.run_script(api=api, ANDROID_JOURNEY_BUDGET_SECONDS=budget)
                self.assertNotEqual(0, result.returncode)
                events = self.events()
                self.assertFalse(any(event["tool"] == "gradlew" for event in events))
                self.assertFalse(any(event["tool"] == "adb" and event["args"][2:4] == ["install", "-r"] for event in events))
                self.assertFalse(any(event["tool"] == "adb" and event["args"][2:5] == ["shell", "pm", "clear"] for event in events))

    def test_api36_fifty_minute_budget_executes_the_complete_journey(self):
        result = self.run_script(api=36, ANDROID_JOURNEY_BUDGET_SECONDS="3000")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue((self.root / "evidence-36/emulator-evidence.json").exists())

    def test_api28_seventy_five_minute_budget_executes_the_complete_journey(self):
        result = self.run_script(api=28, ANDROID_JOURNEY_BUDGET_SECONDS="4500")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue((self.root / "evidence-28/emulator-evidence.json").exists())

    def test_late_first_failure_capture_uses_admitted_api_budget(self):
        source = (self.root / "scripts/run-android-emulator-journeys.sh").read_text()
        start = source.index("capture_original_failure() {")
        function = source[start:source.index("\n}\n", start) + 3]
        for budget, expected in ((4500, 0), (3600, 0), (3000, 0), (2400, 1)):
            with self.subTest(budget=budget):
                results = self.root / f"late-capture-{budget}"
                results.mkdir()
                script = "\n".join(("set -euo pipefail", f"root={shlex.quote(str(self.root))}",
                    f"results_dir={shlex.quote(str(results))}",
                    f"adb_cmd=({shlex.quote(str(self.root / 'sdk/platform-tools/adb'))} -s emulator-contract)",
                    f"journey_budget_seconds={budget}", "SECONDS=2450", function, "capture_original_failure app"))
                result = subprocess.run(["bash", "-c", script], cwd=self.root,
                    env=dict(self.env, API_VALUE="28"), text=True, capture_output=True, timeout=10)
                self.assertEqual(expected, result.returncode, result.stderr)
                self.assertEqual(expected == 0, (results / "failure-app-complete").exists())
                self.assertEqual(expected == 0, (results / "failure-app-native-mount-trace.json").exists())

    def assert_shared_setup_isolated_before_product(self, events):
        adb = [event["args"][2:] for event in events if event["tool"] == "adb"]
        clears = [i for i, args in enumerate(adb) if args[:3] == ["shell", "pm", "clear"]]
        self.assertEqual(3, len(clears), "Only the three independent fresh-data cohorts may erase synthetic app state")
        self.assertTrue(all(adb[i] == ["shell", "pm", "clear", "com.lavasec.app.debug"] for i in clears))
        selected = [(i, args[args.index("class") + 1]) for i, args in enumerate(adb)
                    if args[:3] == ["shell", "am", "instrument"] and "class" in args]
        setup = [i for i, class_name in selected if class_name == SHARED_SETUP_CLASS]
        product = [i for i, class_name in selected if class_name == "com.lavasec.app.reactnative.LavaReactProductJourneyTest"]
        exported = [i for i, class_name in selected if class_name == EXPORTED_ENTRY_CLASS]
        self.assertEqual(1, len(setup)); self.assertEqual(1, len(exported)); self.assertEqual(1, len(product))
        self.assertLess(clears[0], setup[0])
        self.assertEqual(["logcat", "-d", "-v", "raw", "System.out:I", "*:S"], adb[setup[0] + 1],
                         "First-run measurements must be captured before later suites can evict them")
        diagnostic_calls = [["logcat", "-d", "-v", "raw", "ReactNativeJS:I", "*:S"],
                            ["logcat", "-d", "-v", "threadtime", "AndroidRuntime:E", "*:S"],
                            ["logcat", "-d", "-v", "threadtime", "*:E"]]
        # Positive phases have no intervening calls; failed phases may capture
        # only these exact immutable snapshots before their original fresh clear.
        for begin, finish in ((setup[0] + 2, clears[1]), (exported[0] + 1, clears[2])):
            actual = adb[begin:finish]
            self.assertIn(actual, ([], diagnostic_calls[:1], diagnostic_calls[:2], diagnostic_calls),
                          "Only phase-specific failure snapshots may precede the original fresh-data clearance")
        self.assertEqual(clears[1] + 1, exported[0], "Exported entry must start with independently fresh app data")
        self.assertEqual(clears[2] + 1, product[0], "The original product journey must start with fresh app data")

    def peer_bundle(self, bundle, source_sha="a" * 40):
        bundle.mkdir(exist_ok=True)
        (bundle / "android-doq-peer").write_text(self.doq_peer_source)
        (bundle / "android-doq-peer").chmod(0o755)
        for filename in ("resolver.crt", "resolver.key"):
            shutil.copyfile(self.root / "ThirdParty/doq-core/tests/fixtures" / filename, bundle / filename)
        (bundle / "source-sha.txt").write_text(source_sha + "\n")

    def prebuilt_bundle(self, source_sha="a" * 40):
        bundle = self.root / "single prebuilt candidate"
        bundle.mkdir(exist_ok=True)
        (bundle / "app-debug.apk").write_bytes(b"shared prebuilt app APK bytes")
        (bundle / "app-debug-androidTest.apk").write_bytes(b"shared prebuilt app tests")
        (bundle / "vpn-debug-androidTest.apk").write_bytes(b"shared prebuilt VPN tests")
        self.peer_bundle(bundle, source_sha)
        # Downloaded artifacts retain bytes but not their executable mode. The
        # admitted prebuilt phase must restore the peer mode before real Popen.
        (bundle / "android-doq-peer").chmod(0o644)
        return bundle

    def test_actual_script_records_update_process_phases_and_same_candidate_for_both_apis(self):
        records = []
        for api in (28, 36):
            result = self.run_script(api)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            evidence_root = self.root / f"evidence-{api}"
            record = json.loads((evidence_root / "emulator-evidence.json").read_text())
            self.assertEqual(api, record["api"])
            self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), record["apk_sha256"])
            self.assertEqual(self.apk.read_bytes(), (evidence_root / "app-debug.apk").read_bytes())
            self.assertTrue(record["update_preserved_private_state"])
            for phase in emulator_evidence.PROCESS_PHASES:
                key = f"{emulator_evidence.PROCESS_CLASS}#{emulator_evidence.PROCESS_TEST}@{phase}"
                self.assertEqual("pass", record["cases"][key])
            events = [event for event in self.events() if event["api"] == str(api)]
            self.assert_shared_setup_isolated_before_product(events)
            adb = [event["args"][2:] for event in events if event["tool"] == "adb"]
            self.assertLess(next(i for i, args in enumerate(adb) if "cat" in args),
                            adb.index(["logcat", "-c"]))
            self.assertLess(adb.index(["logcat", "-c"]),
                            next(i for i, args in enumerate(adb) if args[:3] == ["shell", "pm", "clear"]))
            self.assertEqual([["logcat", "-c"], *([["logcat", "-d", "-v", "raw", "System.out:I", "*:S"]] * 2)],
                             [args for args in adb if args[0] == "logcat"])
            self.assertLess(next(i for i, event in enumerate(events) if event["tool"] == "gradlew"),
                            max(i for i, event in enumerate(events) if event["tool"] == "adb" and event["args"][2:4] == ["logcat", "-d"]))
            self.assertEqual(f"LAVA_UI_MATRIX_BEGIN cell=current-{api}-connected\n"
                             f"LAVA_UI_MATRIX_END cell=current-{api}-connected\n",
                             (evidence_root / "rendered-matrix-measurements.log").read_text())
            runner_events = [args for args in adb if args[:3] in (["shell", "am", "instrument"], ["shell", "am", "force-stop"])]
            sequence = [args[args.index("parityProcessDeath") + 1] if "parityProcessDeath" in args
                        else "stop" if "force-stop" in args else "doq" if "doqPeerPort" in args
                        else "shared-setup" if SHARED_SETUP_CLASS in args else "exported-entry" if EXPORTED_ENTRY_CLASS in args else "product" for args in runner_events]
            self.assertEqual(["shared-setup", "exported-entry", "product", "stagePending", "stop", "recoverPending", "stageSaved", "stop", "verifySaved", "doq"], sequence)
            gradle = next(event["args"] for event in events if event["tool"] == "gradlew")
            self.assertIn("-PlavaDebugKeystore=" + str(self.keystore), gradle)
            self.assertIn("-Pandroid.testInstrumentationRunnerArguments.notClass=" + APP_EXCLUDED + "," + DOQ_CLASS, gradle)
            records.append(record)
        self.assertEqual([], emulator_evidence.violations(records))

    def test_refused_public_capture_preserves_executed_cases_but_fails_qualification(self):
        for prebuilt in (False, True):
            inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())} if prebuilt else {}
            result = self.run_script(28, FAIL_PUBLIC_CAPTURE="1", **inputs)
            self.assertNotEqual(0, result.returncode)
            record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
            self.assertEqual({"status": "fail"}, record["public_ui"])
            self.assertEqual("fail", record["cases"]["runner#execution"])
            for class_name, methods in emulator_evidence.REQUIRED_CASES.items():
                for method in methods:
                    self.assertEqual("pass", record["cases"][class_name + "#" + method])
            self.assertTrue(any("public shipping-page captures" in problem
                                for problem in emulator_evidence.violations([record])))

    def test_prebuilt_bundle_runs_both_raw_suites_with_identical_apks_without_gradle(self):
        bundle = self.prebuilt_bundle()
        records = []
        for api in (28, 36):
            result = self.run_script(api, ANDROID_PREBUILT_APK_DIR=str(bundle))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            evidence_root = self.root / f"evidence-{api}"
            record = json.loads((evidence_root / "emulator-evidence.json").read_text())
            records.append(record)
            self.assertEqual("a" * 40, record["source_sha"])
            self.assertEqual(hashlib.sha256((bundle / "app-debug.apk").read_bytes()).hexdigest(), record["apk_sha256"])
            self.assertEqual((bundle / "app-debug.apk").read_bytes(), (evidence_root / "app-debug.apk").read_bytes())
            events = [event for event in self.events() if event["api"] == str(api)]
            self.assertFalse(any(event["tool"] == "gradlew" for event in events))
            self.assert_shared_setup_isolated_before_product(events)
            adb = [event["args"][2:] for event in events if event["tool"] == "adb"]
            installed = [args[-1] for args in adb if args[0] == "install"]
            self.assertEqual([str(bundle / filename) for filename in
                              ("app-debug.apk", "app-debug.apk", "app-debug-androidTest.apk", "vpn-debug-androidTest.apk")], installed)
            calls = [args for args in adb if args[:3] == ["shell", "am", "instrument"]]
            self.assertEqual(10, len(calls))
            app = next(args for args in calls if "notClass" in args and args[-1].startswith("com.lavasec.app.debug.test/"))
            self.assertEqual(APP_EXCLUDED,
                             app[app.index("notClass") + 1])
            self.assertEqual("com.lavasec.app.debug.test/androidx.test.runner.AndroidJUnitRunner", app[-1])
            vpn = next(args for args in calls if "notClass" in args and args[-1].startswith("com.lavasec.app.vpn.test/"))
            self.assertNotIn("class", vpn)
            self.assertEqual(DOQ_CLASS, vpn[vpn.index("notClass") + 1])
            self.assertTrue(record["update_preserved_private_state"])
            self.assertLess(adb.index(app), max(i for i, args in enumerate(adb)
                                               if args == ["logcat", "-d", "-v", "raw", "System.out:I", "*:S"]))
            self.assertEqual(f"LAVA_UI_MATRIX_BEGIN cell=current-{api}-app\n"
                             f"LAVA_UI_MATRIX_END cell=current-{api}-app\n",
                             (evidence_root / "rendered-matrix-measurements.log").read_text())
        self.assertEqual([], emulator_evidence.violations(records))

    def test_shared_setup_failure_keeps_isolation_and_all_independent_evidence(self):
        for prebuilt in (False, True):
            for failure in ("FAIL_PHASE", "ADB_EXIT_PHASE"):
                with self.subTest(prebuilt=prebuilt, failure=failure):
                    inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())} if prebuilt else {}
                    before = len(self.events()) if self.commands.exists() else 0
                    result = self.run_script(**inputs, **{failure: "shared-setup"})
                    self.assertNotEqual(0, result.returncode)
                    self.assert_shared_setup_isolated_before_product(self.events()[before:])
                    evidence_root = self.root / "evidence-28"
                    record = json.loads((evidence_root / "emulator-evidence.json").read_text())
                    self.assertEqual("fail", record["cases"]["runner#execution"])
                    # A passing transcript cannot erase a failed adb exit. A failed
                    # testcase cannot be replayed in the ordinary app cohort.
                    expected_setup = "fail" if failure == "FAIL_PHASE" else "pass"
                    for class_name, methods in emulator_evidence.REQUIRED_CASES.items():
                        for method in methods:
                            self.assertEqual(expected_setup if class_name == SHARED_SETUP_CLASS else "pass",
                                             record["cases"][f"{class_name}#{method}"])
                    for phase in emulator_evidence.PROCESS_PHASES:
                        self.assertEqual("pass", record["cases"][f"{emulator_evidence.PROCESS_CLASS}#{emulator_evidence.PROCESS_TEST}@{phase}"])
                    current_results = max(evidence_root.glob("journey-results.*"), key=lambda path: path.stat().st_mtime_ns)
                    setup_xml = ET.parse(current_results / "shared-setup.xml")
                    self.assertEqual(set(emulator_evidence.REQUIRED_CASES[SHARED_SETUP_CLASS]),
                                     {case.get("name") for case in setup_xml.iter("testcase")
                                      if case.get("classname") == SHARED_SETUP_CLASS})
                    completed = [case for case in setup_xml.iter("testcase")
                                 if case.get("classname") == "runner" and case.get("name") == "completed"]
                    self.assertEqual(1 if failure == "FAIL_PHASE" else 0, len(completed))
                    if completed:
                        self.assertIsNotNone(completed[0].find("error"))
                        self.assertEqual("fail", record["cases"]["runner#completed"])
                    all_setup = [case for path in current_results.rglob("*.xml") for case in ET.parse(path).iter("testcase")
                                 if case.get("classname") == SHARED_SETUP_CLASS]
                    self.assertEqual(len(emulator_evidence.REQUIRED_CASES[SHARED_SETUP_CLASS]), len(all_setup))

    def recovery_fixture(self):
        from ci.android_default_app_recovery import DEFAULT_CASES
        from ci.test_android_navigation_trace import row, wire
        active = "com.lavasec.app.reactnative.LavaReactComponentMatrixTest#shippingUpgradePagerPositionsBenefitsAndBackPreserveNativeEntitlement"
        missing = list(DEFAULT_CASES[-2:])
        (self.root / "controlled-navigation-trace").write_bytes(b"".join(wire(row(commits=i, events=i % 256 + 1)) for i in range(320)))
        return active, missing, {"APP_ABORT_CASE": active, "APP_OMIT_RECOVERY": json.dumps(missing),
                                 "ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())}

    def test_failed_original_app_recovers_only_never_advertised_methods_and_keeps_first_failure(self):
        active, missing, inputs = self.recovery_fixture()
        result = self.run_script(**inputs, DRAIN_RECOVERY_STDIN="1")
        self.assertNotEqual(0, result.returncode)
        events = self.events(); self.assert_shared_setup_isolated_before_product(events)
        adb = [event["args"][2:] for event in events if event["tool"] == "adb"]
        selected = [args[args.index("class") + 1] for args in adb if args[:3] == ["shell", "am", "instrument"] and "class" in args]
        recovered = [key for key in selected if key in missing]
        self.assertEqual(missing, recovered); self.assertEqual(len(recovered), len(set(recovered)))
        stdin_rows = [json.loads(line) for line in (self.root / "controlled-recovery-stdin.jsonl").read_text().splitlines()]
        self.assertEqual(missing, [row["selector"] for row in stdin_rows])
        self.assertEqual([0] * len(missing), [row["drainedBytes"] for row in stdin_rows])
        self.assertNotIn(active, selected)
        app_index = next(i for i, args in enumerate(adb) if args[:3] == ["shell", "am", "instrument"] and "class" not in args and args[-1].startswith("com.lavasec.app.debug.test/"))
        self.assertFalse(any(args[:2] == ["install", "-r"] or args[:3] in (["shell", "pm", "clear"], ["shell", "am", "force-stop"]) for args in adb[app_index + 1:]))
        evidence_root = self.root / "evidence-28"
        current = max(evidence_root.glob("journey-results.*"), key=lambda path: path.stat().st_mtime_ns)
        plan = json.loads((current / "app-recovery-plan.json").read_text())
        self.assertEqual(missing, plan["selectors"])
        self.assertEqual(plan["original_txt_sha256"], hashlib.sha256((evidence_root / "app.txt").read_bytes()).hexdigest())
        self.assertEqual(plan["original_xml_sha256"], hashlib.sha256((current / "app.xml").read_bytes()).hexdigest())
        first_tail = (current / "failure-app-navigation-removal-trace.log").read_text()
        values = [json.loads(line.removeprefix("LavaNavigationRemovalTrace ")) for line in first_tail.splitlines()]
        self.assertEqual(list(range(64, 320)), [value["commits"] for value in values])
        self.assertNotIn("999", first_tail)
        self.assertIn("controlled original failure", (current / "failure-app-android-runtime-crashes.log").read_text())
        record = json.loads((evidence_root / "emulator-evidence.json").read_text())
        for key in missing: self.assertEqual("pass", record["cases"][key])
        self.assertNotIn(active, record["cases"])
        self.assertEqual("fail", record["cases"]["runner#completed"])
        self.assertEqual("fail", record["cases"]["runner#execution"])
        self.assertTrue(record["update_preserved_private_state"])
        self.assertEqual(hashlib.sha256((Path(inputs["ANDROID_PREBUILT_APK_DIR"]) / "app-debug.apk").read_bytes()).hexdigest(), record["apk_sha256"])
        self.assertTrue(any("failed runner#" in item for item in emulator_evidence.violations([record])))
        deadlines = [event["args"] for event in events if event["tool"] == "timeout" and "instrument" in event["args"]]
        self.assertEqual(2, len(deadlines)); self.assertTrue(all(args[:2] == ["--kill-after=5s", "180s"] for args in deadlines))

    def test_native_mount_capture_filters_colon_tags_with_valid_error_priority(self):
        expected = ["logcat", "-d", "-v", "threadtime", "*:E"]
        for source in (JOURNEY_SCRIPT, DEVICE_WORKFLOW):
            lines = re.findall(r"logcat -d -v threadtime ([^\n]+)", _executable_source(source.read_text()))
            selected = [line for line in lines if "*:E" in line]
            self.assertEqual(1, len(selected), source)
            self.assertEqual(expected[4:], shlex.split(selected[0].split("|", 1)[0]))
            self.assertNotIn("unknown:SurfaceMountingManager:E", _executable_source(source.read_text()))
        environment = dict(self.env, API_VALUE="28")
        old = subprocess.run([str(self.root / "sdk/platform-tools/adb"), "-s", "emulator-contract", *expected[:4],
                              "unknown:SurfaceMountingManager:E", "SurfaceMountingManager:E",
                              "LavaFabricMountWitness:E", "AndroidRuntime:E", "*:S"],
                             env=environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, old.returncode)
        self.assertNotIn(" E unknown:SurfaceMountingManager:", old.stdout,
                         "The old rule parses as unknown:S and cannot select the colon-bearing RN tag")
        actual = subprocess.run([str(self.root / "sdk/platform-tools/adb"), "-s", "emulator-contract", *expected],
                                env=environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, actual.returncode)
        self.assertIn(" E unknown:SurfaceMountingManager:", actual.stdout)
        self.assertIn(" F AndroidRuntime:", actual.stdout)
        self.assertNotIn("informational", actual.stdout)
        output = self.root / "controlled-error-priority-mount.json"
        parsed = subprocess.run([sys.executable, str(ROOT / "ci/android_native_mount_trace.py"), "--output", str(output)],
                                input=actual.stdout, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, parsed.returncode, parsed.stderr)
        receipt = json.loads(output.read_text())
        self.assertEqual(["parent-witness", "already-parent", "insert-failed"], [event["kind"] for event in receipt["events"]])
        self.assertNotIn("do-not-retain", output.read_text())
        self.assertEqual(0o600, output.stat().st_mode & 0o777)

    def test_each_failed_original_phase_keeps_immutable_tail_before_later_processes(self):
        active, missing, inputs = self.recovery_fixture()
        result = self.run_script(**inputs, FAIL_PHASE="product", EMIT_PHASE_FAILURE_TRACE="1")
        self.assertNotEqual(0, result.returncode)
        events = self.events(); self.assert_shared_setup_isolated_before_product(events)
        adb = [event["args"][2:] for event in events if event["tool"] == "adb"]
        product_index = next(i for i, args in enumerate(adb) if "class" in args and args[args.index("class") + 1] == "com.lavasec.app.reactnative.LavaReactProductJourneyTest")
        self.assertEqual(["logcat", "-d", "-v", "raw", "ReactNativeJS:I", "*:S"], adb[product_index + 1])
        self.assertEqual(["logcat", "-d", "-v", "threadtime", "AndroidRuntime:E", "*:S"], adb[product_index + 2])
        self.assertEqual(["logcat", "-d", "-v", "threadtime", "*:E"], adb[product_index + 3])
        self.assertIn("parityProcessDeath", adb[product_index + 4], "All failed product snapshots must precede the next original invocation")
        current = max((self.root / "evidence-28").glob("journey-results.*"), key=lambda path: path.stat().st_mtime_ns)
        for phase, commits in (("product", 117), ("app", 239)):
            trace = current / ("failure-" + phase + "-navigation-removal-trace.log")
            values = [json.loads(line.removeprefix("LavaNavigationRemovalTrace ")) for line in trace.read_text().splitlines()]
            self.assertEqual([commits], [value["commits"] for value in values])
            self.assertNotIn("do-not-retain", trace.read_text())
            self.assertEqual(0o600, trace.stat().st_mode & 0o777)
            fatal = current / ("failure-" + phase + "-android-runtime-crashes.log")
            self.assertIn("FATAL EXCEPTION: controlled-" + phase, fatal.read_text())
            self.assertEqual(0o600, fatal.stat().st_mode & 0o777)
            mount_path = current / ("failure-" + phase + "-native-mount-trace.json")
            mount = json.loads(mount_path.read_text())
            self.assertEqual(1, mount["schema"])
            self.assertEqual(["parent-witness", "already-parent", "insert-failed"], [event["kind"] for event in mount["events"]])
            self.assertEqual([66500, 66500, None], [event["previousParent"] for event in mount["events"]])
            self.assertEqual([None, 9], [event["index"] for event in mount["events"][1:]])
            self.assertTrue(mount["events"][0]["resolved"])
            self.assertEqual(-1, mount["events"][0]["childChain"][0]["parentIndex"])
            self.assertEqual("sheet.native-footer", mount["events"][0]["childChain"][1]["marker"])
            self.assertNotIn("do-not-retain", mount_path.read_text())
            self.assertEqual(0o600, mount_path.stat().st_mode & 0o777)
            self.assertTrue((current / ("failure-" + phase + "-complete")).exists())
        selected = [args[args.index("class") + 1] for args in adb if "instrument" in args and "class" in args]
        self.assertEqual(missing, [key for key in selected if key in missing])
        record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
        self.assertEqual("fail", record["cases"]["runner#execution"])
        for method in emulator_evidence.REQUIRED_CASES["com.lavasec.app.reactnative.LavaReactProductJourneyTest"]:
            self.assertEqual("fail", record["cases"]["com.lavasec.app.reactnative.LavaReactProductJourneyTest#" + method])
        # Later supplemental process changed the ring buffer to 999, never either
        # closed original snapshot or its original txt/XML failure facts.
        self.assertIn("999", (self.root / "controlled-navigation-trace").read_text())
        self.assertIn("FAILURES!!!", (self.root / "evidence-28/product.txt").read_text())

    def test_recovery_capture_or_transport_refusal_never_retries_or_opens_competing_runner(self):
        for option in ("TRACE_CAPTURE_FAIL", "MOUNT_CAPTURE_FAIL", "RECOVERY_TRANSPORT_FAIL"):
            with self.subTest(option=option):
                active, missing, inputs = self.recovery_fixture()
                before = len(self.events()) if self.commands.exists() else 0
                result = self.run_script(**inputs, **{option: "1"})
                self.assertNotEqual(0, result.returncode)
                events = self.events()[before:]
                selected = [event["args"][event["args"].index("class") + 1] for event in events if event["tool"] == "adb" and "instrument" in event["args"] and "class" in event["args"]]
                self.assertEqual([] if option in ("TRACE_CAPTURE_FAIL", "MOUNT_CAPTURE_FAIL") else missing[:1], [key for key in selected if key in missing])
                self.assertNotIn(active, selected)
                record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
                self.assertEqual("fail", record["cases"]["runner#execution"])
                self.assertNotIn(missing[-1], record["cases"])

    def test_connected_failure_has_no_raw_start_witness_and_cannot_authorize_recovery(self):
        result = self.run_script(FAIL_CONNECTED="1")
        self.assertNotEqual(0, result.returncode)
        events = self.events()
        self.assertFalse(any(event["tool"] == "timeout" for event in events))
        self.assert_shared_setup_isolated_before_product(events)
        current = max((self.root / "evidence-28").glob("journey-results.*"), key=lambda path: path.stat().st_mtime_ns)
        self.assertFalse((current / "app-recovery-plan.json").exists())
        self.assertEqual([], list(current.glob("recovery-*.xml")))

    def test_exported_entry_failure_keeps_its_fresh_cohort_and_all_independent_evidence(self):
        key = EXPORTED_ENTRY_CLASS + "#" + EXPORTED_ENTRY_METHOD
        for prebuilt in (False, True):
            for failure in ("FAIL_PHASE", "ADB_EXIT_PHASE"):
                with self.subTest(prebuilt=prebuilt, failure=failure):
                    inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())} if prebuilt else {}
                    before = len(self.events()) if self.commands.exists() else 0
                    result = self.run_script(**inputs, **{failure: "exported-entry"})
                    self.assertNotEqual(0, result.returncode)
                    self.assert_shared_setup_isolated_before_product(self.events()[before:])
                    evidence_root = self.root / "evidence-28"
                    record = json.loads((evidence_root / "emulator-evidence.json").read_text())
                    self.assertEqual("fail", record["cases"]["runner#execution"])
                    self.assertEqual("fail" if failure == "FAIL_PHASE" else "pass", record["cases"][key])
                    self.assertTrue(record["update_preserved_private_state"])
                    candidate = self.apk if not prebuilt else Path(inputs["ANDROID_PREBUILT_APK_DIR"]) / "app-debug.apk"
                    self.assertEqual(hashlib.sha256(candidate.read_bytes()).hexdigest(), record["apk_sha256"])
                    for class_name, methods in emulator_evidence.REQUIRED_CASES.items():
                        if class_name != EXPORTED_ENTRY_CLASS:
                            for method in methods:
                                self.assertEqual("pass", record["cases"][class_name + "#" + method])
                    for phase in emulator_evidence.PROCESS_PHASES:
                        self.assertEqual("pass", record["cases"][f"{emulator_evidence.PROCESS_CLASS}#{emulator_evidence.PROCESS_TEST}@{phase}"])
                    current = max(evidence_root.glob("journey-results.*"), key=lambda path: path.stat().st_mtime_ns)
                    cases = [case for path in current.rglob("*.xml") for case in ET.parse(path).iter("testcase")
                             if case.get("classname") == EXPORTED_ENTRY_CLASS]
                    self.assertEqual([EXPORTED_ENTRY_METHOD], [case.get("name") for case in cases])
                    self.assertTrue((current / "exported-entry.xml").is_file())
                    self.assertTrue((evidence_root / "exported-entry.txt").is_file())
                    self.assertTrue(any("failed runner#execution" in error for error in emulator_evidence.violations([record])))

    def test_omitted_exported_entry_cannot_borrow_prior_xml_or_default_cohort_passes(self):
        key = EXPORTED_ENTRY_CLASS + "#" + EXPORTED_ENTRY_METHOD
        for prebuilt in (False, True):
            with self.subTest(prebuilt=prebuilt):
                inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())} if prebuilt else {}
                initial = self.run_script(**inputs)
                self.assertEqual(0, initial.returncode, initial.stdout + initial.stderr)
                before = len(self.events())
                current_result = self.run_script(OMIT_CASE=key, **inputs)
                # Omitting the sole exported method creates an empty phase. The
                # existing transcript parser must refuse it, not report success.
                self.assertNotEqual(0, current_result.returncode)
                self.assert_shared_setup_isolated_before_product(self.events()[before:])
                record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
                self.assertEqual("fail", record["cases"]["runner#execution"])
                self.assertEqual("fail", record["cases"]["runner#completed"])
                self.assertNotIn(key, record["cases"])
                self.assertIn("API 28: missing executed pass " + key, emulator_evidence.violations([record]))
                self.assertTrue(record["update_preserved_private_state"])
                for class_name, methods in emulator_evidence.REQUIRED_CASES.items():
                    if class_name != EXPORTED_ENTRY_CLASS:
                        for method in methods:
                            self.assertEqual("pass", record["cases"][class_name + "#" + method])

    def test_first_run_measurements_survive_later_buffer_eviction_without_reusing_prior_results(self):
        source = JOURNEY_SCRIPT.read_text()
        early_capture = source[source.index('if ! python3 - "${adb_cmd[@]}"'):source.index('PY_CAPTURE\nthen')]
        self.assertIn('capture_output=True, text=True, timeout=15', early_capture)
        self.assertNotIn('print(result.stderr)', early_capture)
        for api in (28, 36):
            for prebuilt in (False, True):
                previous = None
                for failure in (None, "FAIL_PHASE", "ADB_EXIT_PHASE"):
                    with self.subTest(api=api, prebuilt=prebuilt, failure=failure):
                        inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())} if prebuilt else {}
                        if failure:
                            inputs[failure] = "shared-setup"
                        self.logcat.write_text("LAVA_ONBOARDING_MEASUREMENT page=0 stale-prior-source\n")
                        before = len(self.events()) if self.commands.exists() else 0
                        result = self.run_script(api, EMIT_SETUP_DIAGNOSTICS="1", **inputs)
                        self.assertEqual(0 if failure is None else 1, result.returncode, result.stdout + result.stderr)
                        self.assert_shared_setup_isolated_before_product(self.events()[before:])
                        evidence_root = self.root / f"evidence-{api}"
                        current = max(evidence_root.glob("journey-results.*"), key=lambda path: path.stat().st_mtime_ns)
                        retained = current / "shared-setup-measurements.log"
                        expected = f"LAVA_ONBOARDING_MEASUREMENT page=5 api={api} size=320x640\n"
                        self.assertEqual(expected, retained.read_text())
                        final = (evidence_root / "rendered-matrix-measurements.log").read_text()
                        self.assertNotIn("LAVA_ONBOARDING_MEASUREMENT", final)
                        self.assertNotIn("synthetic-do-not-retain", retained.read_text())
                        self.assertNotIn("stale-prior-source", retained.read_text())
                        if previous is not None:
                            self.assertEqual(expected, previous.read_text(), "A later run cannot overwrite first-run evidence")
                        previous = retained

    def test_prebuilt_source_mismatch_stops_before_install_or_data_mutation(self):
        bundle = self.prebuilt_bundle("b" * 40)
        result = self.run_script(ANDROID_PREBUILT_APK_DIR=str(bundle))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Prebuilt candidate source does not match this checkout", result.stderr)
        events = self.events()
        adb = [event["args"][2:] for event in events if event["tool"] == "adb"]
        self.assertEqual([["shell", "getprop", "ro.kernel.qemu"]], adb)
        self.assertFalse(any(event["tool"] == "gradlew" for event in events))
        self.assertFalse((self.root / "evidence-28/emulator-evidence.json").exists())

    def test_separate_doq_phase_uses_real_driver_and_one_fresh_result_before_general_suite(self):
        for prebuilt in (False, True):
            with self.subTest(prebuilt=prebuilt):
                bundle = self.prebuilt_bundle() if prebuilt else self.local_peer
                inputs = {"ANDROID_PREBUILT_APK_DIR": str(bundle)} if prebuilt else {}
                before = len(self.events()) if self.commands.exists() else 0
                evidence_root = self.root / "evidence-28"
                prior_results = set(evidence_root.glob("journey-results.*"))
                result = self.run_script(**inputs)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                events = self.events()[before:]
                peer_events = [event["args"] for event in events if event["tool"] == "doq-peer"]
                self.assertEqual([["start"], ["stop"]], peer_events)
                installed = next(i for i, event in enumerate(events) if event["tool"] == "adb" and
                                 "install" in event["args"] and event["args"][-1].endswith("vpn-debug-androidTest.apk"))
                launched = next(i for i, event in enumerate(events) if event["tool"] == "doq-peer")
                doq = next((i, event["args"]) for i, event in enumerate(events) if event["tool"] == "adb" and
                           DOQ_CLASS + "#" + DOQ_METHOD in event["args"])
                completed = next(i for i, event in enumerate(events) if event["tool"] == "doq-peer" and event["args"] == ["stop"])
                general = next(i for i, event in enumerate(events) if event["tool"] == "gradlew" or
                               (event["tool"] == "adb" and "notClass" in event["args"]))
                self.assertLess(installed, launched); self.assertLess(launched, doq[0]); self.assertLess(completed, general)
                self.assertIn("-r", doq[1])
                self.assertEqual("10.0.2.2", doq[1][doq[1].index("doqPeerHost") + 1])
                self.assertEqual("49152", doq[1][doq[1].index("doqPeerPort") + 1])
                self.assertNotIn("canaryPeerControlToken", doq[1])
                record = json.loads((evidence_root / "emulator-evidence.json").read_text())
                self.assertEqual("pass", record["cases"][DOQ_CLASS + "#" + DOQ_METHOD])
                self.assertFalse(any(case.startswith(TALKBACK_CLASS + "#") for case in record["cases"]))
                new_results = set(evidence_root.glob("journey-results.*")) - prior_results
                self.assertEqual(1, len(new_results))
                current_results = new_results.pop()
                metadata = json.loads((current_results / "doq/doq-peer-evidence.json").read_text())
                self.assertEqual("pass", metadata["status"])
                self.assertEqual("a" * 40, metadata["source_sha"])
                self.assertEqual(hashlib.sha256((bundle / "android-doq-peer").read_bytes()).hexdigest(), metadata["peer_sha256"])
                self.assertEqual(hashlib.sha256((bundle / "resolver.crt").read_bytes()).hexdigest(), metadata["certificate_sha256"])
                self.assertEqual(["query", "complete"], [receipt["event"] for receipt in metadata["peer_receipts"]])
                cases = [case for path in current_results.rglob("*.xml") for case in ET.parse(path).iter("testcase")
                         if case.get("classname") == DOQ_CLASS]
                self.assertEqual([DOQ_METHOD], [case.get("name") for case in cases])
                with self.assertRaises(ProcessLookupError):
                    os.kill(int((self.root / "doq-peer.pid").read_text()), 0)

    def test_missing_or_unbound_doq_inputs_fail_without_hiding_independent_suites(self):
        for prebuilt in (False, True):
            for failure in ("directory", "binary", "certificate", "source"):
                with self.subTest(prebuilt=prebuilt, failure=failure):
                    bundle = self.prebuilt_bundle() if prebuilt else self.local_peer
                    self.peer_bundle(bundle)
                    inputs = {"ANDROID_PREBUILT_APK_DIR": str(bundle)} if prebuilt else {}
                    if failure == "directory":
                        inputs["ANDROID_DOQ_PEER_DIR"] = str(self.root / "absent peer directory") if prebuilt else ""
                    elif failure == "binary":
                        (bundle / "android-doq-peer").unlink()
                    elif failure == "certificate":
                        (bundle / "resolver.crt").write_bytes(b"unapproved certificate")
                    else:
                        # The APK source witness stays valid; only the separately
                        # supplied peer is wrong. A peer mismatch must not bail out
                        # before the independent suites collect their real results.
                        bad_peer = self.root / "wrong source peer"
                        self.peer_bundle(bad_peer, "b" * 40)
                        inputs["ANDROID_DOQ_PEER_DIR"] = str(bad_peer)
                    before = len(self.events()) if self.commands.exists() else 0
                    result = self.run_script(**inputs)
                    self.assertNotEqual(0, result.returncode)
                    events = self.events()[before:]
                    self.assertFalse(any(event["tool"] == "doq-peer" for event in events))
                    self.assertFalse(any(DOQ_CLASS + "#" + DOQ_METHOD in event["args"] for event in events))
                    self.assertTrue(any(event["tool"] == "gradlew" or "notClass" in event["args"] for event in events))
                    record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
                    self.assertEqual("fail", record["cases"]["runner#execution"])
                    self.assertNotIn(DOQ_CLASS + "#" + DOQ_METHOD, record["cases"])
                    for class_name, methods in emulator_evidence.REQUIRED_CASES.items():
                        if class_name != DOQ_CLASS:
                            for method in methods:
                                self.assertEqual("pass", record["cases"][class_name + "#" + method])

    def test_doq_readiness_native_and_peer_failures_remain_failed_and_reap_before_independent_suites(self):
        for prebuilt in (False, True):
            for failure in ("readiness", "native", "adb-exit", "exit"):
                with self.subTest(prebuilt=prebuilt, failure=failure):
                    inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())} if prebuilt else {}
                    changes = {"FAIL_DOQ_PEER": failure} if failure in ("readiness", "exit") else {
                        "FAIL_PHASE" if failure == "native" else "ADB_EXIT_PHASE": "doq"}
                    before = len(self.events()) if self.commands.exists() else 0
                    result = self.run_script(**inputs, **changes)
                    self.assertNotEqual(0, result.returncode)
                    events = self.events()[before:]
                    self.assertEqual([["start"], ["stop"]], [event["args"] for event in events if event["tool"] == "doq-peer"])
                    stopped = next(i for i, event in enumerate(events) if event["tool"] == "doq-peer" and event["args"] == ["stop"])
                    general = next(i for i, event in enumerate(events) if event["tool"] == "gradlew" or "notClass" in event["args"])
                    self.assertLess(stopped, general)
                    native_calls = [event["args"] for event in events if DOQ_CLASS + "#" + DOQ_METHOD in event["args"]]
                    self.assertEqual(0 if failure == "readiness" else 1, len(native_calls))
                    record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
                    self.assertEqual("fail", record["cases"]["runner#execution"])
                    self.assertEqual("fail", record["cases"]["runner#doq-peer-execution"])
                    if failure == "readiness":
                        self.assertNotIn(DOQ_CLASS + "#" + DOQ_METHOD, record["cases"])
                    else:
                        self.assertEqual("fail" if failure == "native" else "pass", record["cases"][DOQ_CLASS + "#" + DOQ_METHOD])
                    with self.assertRaises(ProcessLookupError):
                        os.kill(int((self.root / "doq-peer.pid").read_text()), 0)

    def test_failed_vpn_test_install_does_not_launch_peer_and_keeps_app_or_connected_suite(self):
        for prebuilt in (False, True):
            with self.subTest(prebuilt=prebuilt):
                inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())} if prebuilt else {}
                before = len(self.events()) if self.commands.exists() else 0
                result = self.run_script(FAIL_VPN_INSTALL="1", **inputs)
                self.assertNotEqual(0, result.returncode)
                events = self.events()[before:]
                vpn_installs = [event["args"][2:] for event in events if event["tool"] == "adb" and
                                event["args"][2:3] == ["install"] and event["args"][-1].endswith("vpn-debug-androidTest.apk")]
                self.assertEqual(1, len(vpn_installs), "A failed VPN test install must not fallback or retry")
                self.assertEqual(["install", "--no-streaming", "-r", "-t"], vpn_installs[0][:-1])
                self.assertFalse(any(event["tool"] == "doq-peer" for event in events))
                self.assertTrue(any(event["tool"] == "gradlew" or "notClass" in event["args"] for event in events))
                record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
                self.assertEqual("fail", record["cases"]["runner#execution"])
                self.assertNotIn(DOQ_CLASS + "#" + DOQ_METHOD, record["cases"])

    def test_ready_host_peer_arguments_reach_only_vpn_and_cleanup_removes_private_token(self):
        for prebuilt in (False, True):
            with self.subTest(prebuilt=prebuilt):
                inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())} if prebuilt else {}
                before = len(self.events()) if self.commands.exists() else 0
                result = self.run_script(**inputs)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                events = self.events()[before:]
                peer_events = [event["args"] for event in events if event["tool"] == "canary-peer"]
                self.assertEqual([["start"], ["shutdown"]], peer_events)
                starts = next(i for i, event in enumerate(events) if event["tool"] == "canary-peer")
                verified = next(i for i, event in enumerate(events) if "verifySaved" in event["args"])
                self.assertLess(verified, starts)
                if prebuilt:
                    calls = [event["args"] for event in events if event["tool"] == "adb" and "instrument" in event["args"]]
                    vpn = next(args for args in calls if "notClass" in args and args[-1].startswith("com.lavasec.app.vpn.test/"))
                    self.assertTrue(1024 <= int(vpn[vpn.index("canaryPeerControlPort") + 1]) <= 65535)
                    self.assertEqual("c" * 64, vpn[vpn.index("canaryPeerControlToken") + 1])
                    self.assertTrue(all("canaryPeerControlToken" not in args for args in calls if args is not vpn))
                else:
                    gradle = next(event["args"] for event in events if event["tool"] == "gradlew")
                    self.assertTrue(any(arg.startswith("-Pandroid.testInstrumentationRunnerArguments.canaryPeerControlPort=") for arg in gradle))
                    self.assertIn("-Pandroid.testInstrumentationRunnerArguments.canaryPeerControlToken=" + "c" * 64, gradle)
                evidence_root = self.root / "evidence-28"
                self.assertEqual([], list(evidence_root.rglob("canary-peer-ready.json")))
                self.assertTrue(list(evidence_root.glob("canary-udp-peer-observations-*.jsonl")))

    def test_failed_peer_bind_or_readiness_is_retained_while_independent_suites_continue(self):
        for prebuilt in (False, True):
            for failure in ("bind", "readiness"):
                with self.subTest(prebuilt=prebuilt, failure=failure):
                    inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())} if prebuilt else {}
                    result = self.run_script(FAIL_CANARY_PEER=failure, **inputs)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("canary cases will fail and independent suites will continue", result.stderr)
                    record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
                    self.assertEqual("fail", record["cases"]["runner#execution"])
                    for class_name, methods in emulator_evidence.REQUIRED_CASES.items():
                        expected = "fail" if class_name.endswith(".DnsLeakCanaryInstrumentationTest") else "pass"
                        for method in methods:
                            self.assertEqual(expected, record["cases"][f"{class_name}#{method}"])
                    self.assertEqual([], list((self.root / "evidence-28").rglob("canary-peer-ready.json")))

    def test_privileged_peer_launch_requires_exact_owned_disposable_linux_ci_scope(self):
        for repository, actions, authorized in (("lavasecurity/lavasec-android-internal", True, True),
                                                ("lavasecurity/lavasec-android", True, True),
                                                ("untrusted/lavasec-android", True, False),
                                                ("lavasecurity/lavasec-android", False, False)):
            with self.subTest(repository=repository, actions=actions, authorized=authorized):
                before = len(self.events()) if self.commands.exists() else 0
                result = self.run_script(ANDROID_CANARY_PEER_ALLOW_SUDO="1", GITHUB_ACTIONS="true" if actions else "false",
                                         GITHUB_REPOSITORY=repository)
                events = self.events()[before:]
                launches = [event["args"] for event in events if event["tool"] == "sudo"]
                if authorized:
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertEqual(1, len(launches))
                    self.assertEqual(["-n", "--", "python3", str(self.root / "scripts/android-canary-udp-peer.py")], launches[0][:4])
                    self.assertIn("--owner-uid", launches[0]); self.assertIn("--owner-gid", launches[0])
                else:
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual([], launches)
                    self.assertFalse(any(event["tool"] == "canary-peer" for event in events))
                    self.assertIn("Refusing privileged canary peer", result.stderr)

    def test_nonzero_adb_exit_cannot_be_hidden_by_a_passing_transcript(self):
        for prebuilt in (False, True):
            with self.subTest(prebuilt=prebuilt):
                inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())} if prebuilt else {}
                result = self.run_script(ADB_EXIT_PHASE="recoverPending", **inputs)
                self.assertNotEqual(0, result.returncode)
                evidence_root = self.root / "evidence-28"
                transcript = (evidence_root / "recoverPending.txt").read_text()
                self.assertIn("OK (1 tests)", transcript)
                record = json.loads((evidence_root / "emulator-evidence.json").read_text())
                failed_phase = f"{emulator_evidence.PROCESS_CLASS}#{emulator_evidence.PROCESS_TEST}@recoverPending"
                self.assertEqual("pass", record["cases"][failed_phase])
                self.assertEqual("fail", record["cases"]["runner#execution"])
                self.assertEqual("pass", record["cases"][f"{emulator_evidence.PROCESS_CLASS}#{emulator_evidence.PROCESS_TEST}@verifySaved"])
                self.assertTrue(any("failed runner#execution" in error for error in emulator_evidence.violations([record])))

    def test_prior_passed_xml_cannot_fill_a_missing_case_in_this_run(self):
        class_name = "com.lavasec.app.reactnative.CustomResolverPersistenceInstrumentationTest"
        missing = f"{class_name}#{emulator_evidence.REQUIRED_CASES[class_name][0]}"
        for prebuilt in (False, True):
            with self.subTest(prebuilt=prebuilt):
                inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle())} if prebuilt else {}
                initial = self.run_script(**inputs)
                self.assertEqual(0, initial.returncode, initial.stdout + initial.stderr)
                # Seed both historical collection layouts. Current results must
                # come exclusively from the new run, including native XML.
                for directory in ("evidence-28/journey-results/app", "android/app/build/outputs/androidTest-results/connected"):
                    old = self.root / directory
                    old.mkdir(parents=True, exist_ok=True)
                    (old / "prior.xml").write_text(
                        f'<testsuite><testcase classname="{class_name}" name="{emulator_evidence.REQUIRED_CASES[class_name][0]}"/></testsuite>')
                current = self.run_script(OMIT_CASE=missing, **inputs)
                self.assertEqual(0, current.returncode, current.stdout + current.stderr)
                record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
                self.assertNotIn(missing, record["cases"])
                self.assertTrue(any(f"missing executed pass {missing}" in error for error in emulator_evidence.violations([record])))

    def test_non_emulator_selection_stops_before_install_or_any_data_reset(self):
        result = self.run_script(QEMU_VALUE="0")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not an emulator", result.stderr)
        self.assertEqual(1, len(self.events()))

    def test_failed_update_preservation_stops_before_clear_or_instrumentation(self):
        result = self.run_script(LOSE_UPDATE="1")
        self.assertNotEqual(0, result.returncode)
        events = self.events()
        self.assertFalse(any("clear" in event["args"] or "instrument" in event["args"] for event in events))
        self.assertFalse(any(event["tool"] == "adb" and event["args"][2:3] == ["logcat"] for event in events))
        self.assertFalse((self.root / "evidence-28/emulator-evidence.json").exists())

    def test_matrix_capture_excludes_stale_and_unrelated_logs_even_after_suite_failure(self):
        for prebuilt in (False, True):
            with self.subTest(prebuilt=prebuilt):
                self.logcat.write_text("LAVA_UI_MATRIX_BEGIN cell=stale-prior-source\nprivate stale log\n")
                inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle()), "FAIL_PHASE": "app"} if prebuilt else {"FAIL_CONNECTED": "1"}
                result = self.run_script(**inputs)
                self.assertNotEqual(0, result.returncode)
                evidence_root = self.root / "evidence-28"
                origin = "app" if prebuilt else "connected"
                self.assertEqual(f"LAVA_UI_MATRIX_BEGIN cell=current-28-{origin}\n"
                                 f"LAVA_UI_MATRIX_END cell=current-28-{origin}\n",
                                 (evidence_root / "rendered-matrix-measurements.log").read_text())
                retained_text = "\n".join(path.read_text() for path in evidence_root.rglob("*")
                                          if path.is_file() and path.suffix in (".log", ".txt", ".xml", ".json"))
                self.assertNotIn("stale-prior-source", retained_text)
                self.assertNotIn("synthetic-do-not-retain", retained_text)
                record = json.loads((evidence_root / "emulator-evidence.json").read_text())
                self.assertEqual("fail", record["cases"]["runner#execution"])

    def test_only_current_redacted_editable_records_are_retained_after_failure_on_both_apis(self):
        markers = ["LAVA_JOURNEY_EDITABLE_FIELDS", "LAVA_JOURNEY_EDITABLE_FIELD",
                   "LAVA_JOURNEY_EDITABLE_ANCESTOR", "LAVA_JOURNEY_SET_TEXT_TARGET",
                   "LAVA_JOURNEY_SET_TEXT_RESULT", "LAVA_ONBOARDING_MEASUREMENT",
                   "LAVA_LOCALIZED_ACCOUNT_BACK"]
        for api in (28, 36):
            for prebuilt in (False, True):
                with self.subTest(api=api, prebuilt=prebuilt):
                    self.logcat.write_text("LAVA_JOURNEY_EDITABLE_FIELD stage=stale-prior-source\nprivate stale log\n")
                    inputs = {"ANDROID_PREBUILT_APK_DIR": str(self.prebuilt_bundle()), "FAIL_PHASE": "app"} if prebuilt else {"FAIL_CONNECTED": "1"}
                    result = self.run_script(api, EMIT_EDITABLE_DIAGNOSTICS="1", **inputs)
                    self.assertNotEqual(0, result.returncode)
                    evidence_root = self.root / f"evidence-{api}"
                    origin = "app" if prebuilt else "connected"
                    lines = (evidence_root / "rendered-matrix-measurements.log").read_text().splitlines()
                    expected = [f"LAVA_UI_MATRIX_BEGIN cell=current-{api}-{origin}"]
                    expected.extend(f"{marker} stage=current-{api}-{origin} label=Search Device QA metadata=synthetic-redacted"
                                    for marker in markers)
                    expected.append(f"LAVA_UI_MATRIX_END cell=current-{api}-{origin}")
                    self.assertEqual(expected, lines)
                    retained = "\n".join(lines)
                    self.assertNotIn("stale-prior-source", retained)
                    self.assertNotIn("synthetic-do-not-retain", retained)
                    record = json.loads((evidence_root / "emulator-evidence.json").read_text())
                    self.assertEqual("fail", record["cases"]["runner#execution"])

    def test_logcat_clear_failure_stops_before_synthetic_reset_or_suites(self):
        result = self.run_script(FAIL_LOGCAT_CLEAR="1")
        self.assertNotEqual(0, result.returncode)
        events = self.events()
        self.assertTrue(any(event["tool"] == "adb" and event["args"][2:] == ["logcat", "-c"] for event in events))
        self.assertFalse(any(event["tool"] == "adb" and event["args"][2:5] in
                             (["shell", "pm", "clear"], ["shell", "am", "instrument"]) for event in events))
        self.assertFalse(any(event["tool"] == "gradlew" for event in events))
        self.assertFalse((self.root / "evidence-28/emulator-evidence.json").exists())

    def test_logcat_dump_nonzero_exit_is_retained_even_with_valid_matrix_lines(self):
        result = self.run_script(FAIL_LOGCAT_DUMP="1")
        self.assertNotEqual(0, result.returncode)
        evidence_root = self.root / "evidence-28"
        self.assertIn("cell=current-28-connected", (evidence_root / "rendered-matrix-measurements.log").read_text())
        record = json.loads((evidence_root / "emulator-evidence.json").read_text())
        self.assertEqual("fail", record["cases"]["runner#execution"])

    def test_failed_process_phase_is_retained_while_remaining_suites_execute(self):
        result = self.run_script(FAIL_PHASE="recoverPending")
        self.assertNotEqual(0, result.returncode)
        events = self.events()
        self.assertTrue(any("stageSaved" in event["args"] for event in events))
        self.assertTrue(any(event["tool"] == "gradlew" for event in events))
        record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
        failed = f"{emulator_evidence.PROCESS_CLASS}#{emulator_evidence.PROCESS_TEST}@recoverPending"
        self.assertEqual("fail", record["cases"][failed])
        self.assertEqual("fail", record["cases"]["runner#execution"])
        self.assertTrue(any(f"failed {failed}" in error for error in emulator_evidence.violations([record])))

    def test_failed_product_still_collects_every_process_phase_and_native_suite(self):
        result = self.run_script(FAIL_PHASE="product")
        self.assertNotEqual(0, result.returncode)
        record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
        product = "com.lavasec.app.reactnative.LavaReactProductJourneyTest"
        self.assertEqual("fail", record["cases"][f"{product}#{emulator_evidence.REQUIRED_CASES[product][0]}"])
        for phase in emulator_evidence.PROCESS_PHASES:
            self.assertEqual("pass", record["cases"][f"{emulator_evidence.PROCESS_CLASS}#{emulator_evidence.PROCESS_TEST}@{phase}"])
        for class_name, methods in emulator_evidence.REQUIRED_CASES.items():
            if class_name != product:
                for method in methods:
                    self.assertEqual("pass", record["cases"][f"{class_name}#{method}"])

    def test_connected_command_failure_cannot_be_hidden_by_passed_xml(self):
        result = self.run_script(FAIL_CONNECTED="1")
        self.assertNotEqual(0, result.returncode)
        record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
        self.assertEqual("fail", record["cases"]["runner#execution"])
        self.assertTrue(any("failed runner#execution" in error for error in emulator_evidence.violations([record])))

    def test_local_gradle_cannot_replace_the_candidate_after_shipping_journeys(self):
        result = self.run_script(REBUILD_APK="1")
        self.assertNotEqual(0, result.returncode)
        record = json.loads((self.root / "evidence-28/emulator-evidence.json").read_text())
        self.assertEqual("fail", record["cases"]["runner#execution"])
        self.assertTrue(any("failed runner#execution" in error for error in emulator_evidence.violations([record])))


if __name__ == "__main__":
    unittest.main()
