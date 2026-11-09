"""Contracts for the shipping RN host and native product adapter."""

import hashlib
import json
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from ci.verify_android_bundle import REQUIRED_REGISTRATIONS


ROOT = Path(__file__).resolve().parents[1]
RN = ROOT / "android" / "react-native"
MODULE = (
    ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "lavasec"
    / "app"
    / "reactnative"
)


class AndroidReactNativeContractTest(unittest.TestCase):
    def read(self, path: Path) -> str:
        self.assertTrue(path.is_file(), f"missing {path}")
        return path.read_text(encoding="utf-8")

    def test_private_query_retirement_precedes_suspendable_log_mutations(self) -> None:
        bridge = self.read(MODULE / "NativeProductBridge.kt")
        clear = bridge.split("private suspend fun clearLocalLogs(kind: String): String {", 1)[1].split(
            "private suspend fun exportLocalLogs", 1
        )[0]
        validation = clear.index('if (kind !in LOG_CLEAR_CONFIRMATIONS)')
        retirement = clear.index("displayClearRevision.incrementAndGet()")
        first_mutation = clear.index("product.clearLocalActivity(")
        self.assertLess(validation, retirement)
        self.assertLess(retirement, first_mutation)
        # Both pre-clear reads and reads started during a multi-owner clear are retired.
        self.assertGreater(clear.rindex("displayClearRevision.incrementAndGet()"), clear.index("product.clearGuardProgress()"))
        self.assertLess(clear.index("} finally {"), clear.rindex("displayClearRevision.incrementAndGet()"))
        query = bridge.split("private suspend fun executeQueryCommand(", 1)[1].split(
            "private fun queryReadAuthority()", 1
        )[0]
        self.assertIn("NativeQueryReadFence(captured).read", query)
        for owner in ("activity", "domains", "network", "stats"):
            self.assertIn(f"queryOwner.{owner}(", query)
        policy = self.read(MODULE / "NativeQueryReadFence.kt")
        self.assertLess(policy.index("val outcome = query()"), policy.rindex("rejection(current())"))
        self.assertIn("current.displayClearRevision != captured.displayClearRevision", policy)

    def test_js_package_pins_the_supported_react_native_release(self) -> None:
        package = json.loads(self.read(RN / "package.json"))
        self.assertTrue(package["private"])
        self.assertEqual("0.87.1", package["dependencies"]["react-native"])
        self.assertEqual("19.2.3", package["dependencies"]["react"])
        # Must match react-native 0.87.1's own engine range or `npm ci` emits
        # EBADENGINE / the lockfile root advertises a range RN rejects.
        self.assertEqual(
            "^22.13.0 || ^24.3.0 || >= 26.0.0",
            package["engines"]["node"],
        )
        lock = json.loads(self.read(RN / "package-lock.json"))
        self.assertEqual(
            package["engines"]["node"],
            lock["packages"][""]["engines"]["node"],
        )
        for name in ("index.js", "metro.config.js", "babel.config.js", "app.json"):
            self.assertTrue((RN / name).is_file(), f"missing {name}")

    def test_entry_screen_and_typed_native_surface_are_declared(self) -> None:
        app_json = json.loads(self.read(RN / "app.json"))
        self.assertEqual("LavasecApp", app_json["name"])
        index = self.read(RN / "index.js")
        self.assertIn("AppRegistry.registerComponent", index)
        spec = self.read(RN / "src" / "NativeLavaApp.ts")
        for member in (
            "getChainedStatus(): Promise<LavaChainedStatus>",
            "setChainedEnabled(enabled: boolean): Promise<LavaChainedToggleResult>",
            "ready: boolean",
            "reason: string | null",
            "enabled: boolean",
            "active: boolean",
            "protection: 'off' | 'paused' | 'running'",
            "installedMode: 'chained' | 'dnsOnly' | 'none'",
            "canEnable: boolean",
            "configured: boolean",
            "eligible: boolean",
            "parseChainedStatus",
            "parseChainedToggleResult",
            "subscribeChainedActive",
            "subscribeChainedProtection",
            "subscribeChainedComposition",
            "CHAINED_ACTIVE_EVENT",
            "CHAINED_PROTECTION_EVENT",
            "CHAINED_COMPOSITION_EVENT",
            "chainedReasonCopy",
            "CHAINED_REASON_KEY_UNAVAILABLE",
            "CHAINED_REASON_PREFERENCE_FAILED",
        ):
            with self.subTest(member=member):
                self.assertIn(member, spec)
        # The wrapper must validate before it trusts native (non-optimistic).
        self.assertIn("typeof enabled !== 'boolean'", spec)
        # The unreadable-key and preference-write codes are stable wire values shared with the
        # native module, and the copy mapper must cover them.
        self.assertIn("CHAINED_REASON_KEY_UNAVAILABLE = 'chained-upstream-key-unavailable'", spec)
        self.assertIn("CHAINED_REASON_PREFERENCE_FAILED = 'chained-preference-write-failed'", spec)

    def test_bundle_script_targets_the_packaged_asset(self) -> None:
        package = json.loads(self.read(RN / "package.json"))
        bundle = package["scripts"]["bundle:android"]
        self.assertIn("--platform android", bundle)
        self.assertIn("--entry-file index.js", bundle)
        self.assertIn(
            "--bundle-output ../app/src/main/assets/index.android.bundle",
            bundle,
        )

    def test_native_module_reports_authoritative_status_and_is_engine_backed(self) -> None:
        module = self.read(MODULE / "LavaAppModule.kt")
        self.assertIn('const val NAME = "NativeLavaApp"', module)
        self.assertRegex(module, r"@ReactMethod\s+fun getChainedStatus\(")
        self.assertRegex(module, r"@ReactMethod\s+fun setChainedEnabled\(")
        # The real surface reads the per-generation composition and the live VPN bridge; it is no
        # longer a self-contained stub.
        self.assertIn("LavasecApplication", module)
        self.assertIn("ChainedComposition", module)
        self.assertIn("LavaVpnServiceClient", module)
        # No optimistic success: both methods resolve the authoritative status / toggle result and
        # a failed preference write is reported, never swallowed into a success.
        self.assertNotIn("promise.resolve(null)", module)
        self.assertIn("REASON_NOT_CONFIGURED = \"not configured\"", module)
        self.assertIn("REASON_UNAVAILABLE = \"native module unavailable\"", module)
        self.assertIn("REASON_PREFERENCE_FAILED = \"chained-preference-write-failed\"", module)
        # The unreadable-key code is a stable wire value the JS copy mapper must cover.
        self.assertIn("REASON_KEY_UNAVAILABLE = \"chained-upstream-key-unavailable\"", module)
        self.assertIn("toggleResult(accepted", module)
        # The preference-independent enable flag drives the control; a disabled-by-user status is
        # not a surface that cannot be toggled.
        self.assertIn('putBoolean("canEnable"', module)
        # The live-runtime flag stops the UI claiming chained traffic when a DNS-only fallback
        # is carrying it.
        self.assertIn('putBoolean("active"', module)
        # The protection mode stops the UI rendering an off/paused protection as recovering.
        self.assertIn('putString("protection"', module)
        self.assertIn("runtimeModeCode", module)
        # The installed runtime mode distinguishes a settled DNS-only fallback from an in-progress
        # chained startup, and is read from the SAME snapshot as `active` so a chained/DNS-only
        # transition cannot be reported as `active = true` with a DNS-only mode.
        self.assertIn('putString("installedMode"', module)
        self.assertIn("LavaVpnRuntimeBridge.chainedActivity.value", module)
        self.assertIn("activity.active", module)
        self.assertIn("activity.mode.code", module)
        # No independent single-field reads, which could straddle a transition and disagree.
        self.assertNotIn("LavaVpnRuntimeBridge.chainedActive", module)
        self.assertNotIn("installedRuntimeMode", module)
        # The native module pushes live chained-activity changes so a settling screen refreshes.
        self.assertIn("CHAINED_ACTIVE_EVENT", module)
        self.assertIn("DeviceEventManagerModule.RCTDeviceEventEmitter", module)
        # The module also pushes protection-mode changes (`off`/`paused`/`running`), which can
        # occur with no chained-activity publication (start/awaiting-network, or a pause).
        self.assertIn('CHAINED_PROTECTION_EVENT = "chainedProtectionChanged"', module)
        self.assertIn("emitChainedProtection", module)
        self.assertIn("LavaVpnRuntimeBridge.state", module)
        self.assertIn("distinctUntilChanged", module)
        # The module pushes composition availability, so a screen opened before the async unlocked
        # graph is built corrects itself once the composition is installed (or rotates).
        self.assertIn('CHAINED_COMPOSITION_EVENT = "chainedCompositionChanged"', module)
        self.assertIn("emitChainedComposition", module)
        self.assertIn(".chainedComposition", module)
        # The module observes the versioned activity publications, so a false→false fallback is
        # still an emission the screen can refresh on.
        self.assertIn("LavaVpnRuntimeBridge.chainedActivity", module)
        # Readiness refusals cross as stable user-facing codes, never device-log identifiers.
        self.assertIn("userFacingReasonCode", module)
        # A downstream blocker outranks disabled-by-user when enabling is impossible.
        self.assertIn("blockingReadinessRefusal", module)
        # The composition gate is consulted, not inferred.
        self.assertIn("composition.status()", module)
        self.assertIn("composition.setEnabled(", module)
        # Rapid toggles are serialized so an enable-then-disable cannot commit (and reload) out of
        # order and leave the durable preference / running mode contrary to the last action.
        self.assertIn("ChainedPreferenceMutationSerializer", module)
        self.assertIn("preferenceMutations.submit {", module)
        # A preference-triggered reload is bound to the service generation observed at enqueue, so
        # a stop queued behind the read cannot be overridden by a restart.
        self.assertIn("LavaVpnRuntimeBridge.serviceGeneration", module)
        self.assertIn(".reload(generation)", module)
        # A failed reload dispatch (service teardown / OEM background-start refusal) is retained and
        # retried by a later status read or toggle, so the live runtime converges on the committed
        # preference instead of the UI claiming chaining is off while the installed chained TUN
        # keeps routing.
        self.assertIn("ChainedPreferenceReloadReconciliation", module)
        self.assertIn("preferenceReload.request()", module)
        self.assertIn("preferenceReload.reconcileOnce(", module)

    def test_chained_upstream_screen_and_role_components_are_declared(self) -> None:
        screen = self.read(RN / "src" / "ChainedUpstreamScreen.tsx")
        for member in (
            "export function ChainedUpstreamScreen(",
            "getChainedStatus",
            "setChainedEnabled",
            "subscribeChainedActive",
            "presentChainedUpstream",
            "onNavigateBack",
        ):
            with self.subTest(member=member):
                self.assertIn(member, screen)
        self.assertIn('testID="chainedUpstream.toggle"', screen)
        self.assertIn("accessibilityHint", screen)
        # A stale refresh must not overwrite a newer one: refreshes are sequence-guarded.
        self.assertIn("refreshSequence", screen)
        # The screen refreshes on protection-mode and composition-availability changes too, not only
        # chained activity.
        self.assertIn("subscribeChainedProtection", screen)
        self.assertIn("subscribeChainedComposition", screen)
        # Honest states: unavailable / not configured / recovering.
        model = self.read(RN / "src" / "chainedUpstreamViewModel.ts")
        for state in (
            "'loading'",
            "'error'",
            "'unavailable'",
            "'notConfigured'",
            "'inactive'",
            "'paused'",
            "'fellBack'",
            "'recovering'",
            "'ready'",
        ):
            with self.subTest(state=state):
                self.assertIn(state, model)
        self.assertIn("CHAINED_REASON_RECOVERING", model)
        # Not-configured must be evaluated before the eligibility gate.
        self.assertIn("CHAINED_REASON_NOT_CONFIGURED", model)
        # An already-enabled user keeps a live (off-capable) control even when the configuration
        # is removed or eligibility lapses: both the not-configured and ineligible branches
        # preserve the preference state so the sticky enable can never be stranded.
        self.assertIn("toggleValue: status.enabled", model)
        self.assertGreaterEqual(model.count("toggleDisabled: status.enabled ? busy : true"), 2)
        # A readiness refusal is rendered through the copy mapper, never as a raw log identifier.
        self.assertIn("chainedReasonCopy", model)
        configured_check = model.index("!status.configured")
        eligible_check = model.index("!status.eligible")
        self.assertLess(configured_check, eligible_check)
        # The not-configured branch itself preserves the preference before the eligibility gate.
        self.assertLess(model.index("toggleValue: status.enabled", configured_check), eligible_check)
        # A missing native bridge reports configured=false too, so it must be
        # evaluated before the configured check (unavailable, not not-configured).
        self.assertIn("CHAINED_REASON_UNAVAILABLE", model)
        unavailable_check = model.index("status.reason === CHAINED_REASON_UNAVAILABLE")
        self.assertLess(unavailable_check, configured_check)
        # An already-enabled upstream whose enabling gate regressed must name the durable blocker
        # BEFORE any operational state, so an unusable upstream is not rendered as
        # "recovering"/"fell back" indefinitely; the toggle stays live for disable.
        blocker_check = model.index("!status.canEnable")
        operational_check = model.index("status.protection === 'off'")
        self.assertLess(blocker_check, operational_check)

    def test_android_back_press_and_window_insets_are_wired(self) -> None:
        app = self.read(RN / "App.tsx")
        self.assertIn("BackHandler.addEventListener", app)
        self.assertIn("hardwareBackPress", app)
        self.assertIn("if (chained) { closeChained(); return true; }", app)
        # Android insets come from StatusBar height; safe-area-context is not
        # resolvable because the host avoids the stock autolinking plugin.
        self.assertIn("androidTopInset", app)
        insets = self.read(RN / "src" / "windowInsets.ts")
        self.assertIn("StatusBar.currentHeight", insets)
        self.assertIn("react-native-safe-area-context", insets)
        self.assertTrue((RN / "tests" / "app.test.tsx").is_file())

    def test_android_components_mirror_the_role_contract(self) -> None:
        android = self.read(RN / "src" / "components.android.tsx")
        for member in ("LavaText", "LavaCard", "LavaToggleRow", "LavaActionButton", "Pressable"):
            with self.subTest(member=member):
                self.assertIn(member, android)
        self.assertIn('accessibilityRole="switch"', android)
        # Non-Android platforms must not silently borrow the Android mapping.
        fallback = self.read(RN / "src" / "components.tsx")
        self.assertIn("Android implementation only", fallback)

    def test_app_wires_the_screen_and_exposes_a_navigation_entry(self) -> None:
        app = self.read(RN / "App.tsx")
        self.assertIn("ChainedUpstreamScreen", app)
        self.assertIn("useProduct", app)
        self.assertIn("type Tab = 'guard' | 'filters' | 'activity' | 'settings'", app)
        self.assertIn('testID="nav.chainedUpstream"', app)
        routes = self.read(RN / "src" / "routes.ts")
        self.assertIn("export type AppRoute", routes)
        self.assertIn("'chainedUpstream'", routes)

    def test_jest_component_tests_are_wired(self) -> None:
        package = json.loads(self.read(RN / "package.json"))
        self.assertIn("jest", package["scripts"]["test"])
        for dep in (
            "@react-native/jest-preset",
            "@testing-library/react-native",
            "jest",
            "react-test-renderer",
        ):
            with self.subTest(dep=dep):
                self.assertIn(dep, package["devDependencies"])
        jest_config = self.read(RN / "jest.config.cjs")
        self.assertIn("@react-native/jest-preset", jest_config)
        self.assertIn("defaultPlatform: 'android'", jest_config)
        self.assertTrue((RN / "tests" / "chained-upstream-screen.test.tsx").is_file())
        self.assertTrue((RN / "tests" / "native-lava-app.test.ts").is_file())
        self.assertTrue((RN / "tests" / "chained-upstream-view-model.test.ts").is_file())
        tsconfig = self.read(RN / "tsconfig.json")
        self.assertIn("tests/**/*.tsx", tsconfig)

    def test_package_registers_the_module_and_activity_hosts_it(self) -> None:
        package = self.read(MODULE / "LavaAppPackage.kt")
        self.assertIn("BaseReactPackage", package)
        self.assertIn("LavaAppModule.NAME", package)
        activity = self.read(MODULE / "LavaReactActivity.kt")
        self.assertIn("ReactActivity", activity)
        self.assertIn('COMPONENT_NAME = "LavasecApp"', activity)
        manifest = self.read(
            ROOT / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
        )
        self.assertIn('android:name=".reactnative.LavaReactActivity"', manifest)
        ns = "{http://schemas.android.com/apk/res/android}"
        activities = ET.fromstring(manifest).find("application").findall("activity")
        hosts = {item.get(ns + "name"): item for item in activities}
        self.assertEqual(2, len(activities))
        self.assertEqual({".MainActivity", ".reactnative.LavaReactActivity"}, set(hosts))
        play_manifest = self.read(ROOT / "android/app/src/play/AndroidManifest.xml")
        play_activities = ET.fromstring(play_manifest).find("application").findall("activity")
        play_hosts = {item.get(ns + "name"): item for item in play_activities}
        self.assertEqual(2, len(play_activities))
        self.assertEqual({
            ".account.AppleOAuthBrowserActivity",
            ".account.AppleOAuthCallbackActivity",
        }, set(play_hosts))
        # FOSS inherits only the shared foreground hosts; OAuth entry points belong to Play.
        self.assertTrue(set(hosts).isdisjoint(play_hosts))
        hosts.update(play_hosts)
        browser = hosts[".account.AppleOAuthBrowserActivity"]
        self.assertEqual({
            ns + "name": ".account.AppleOAuthBrowserActivity",
            ns + "exported": "false",
            ns + "excludeFromRecents": "true",
            ns + "theme": "@android:style/Theme.Translucent.NoTitleBar",
        }, browser.attrib)
        self.assertEqual(0, len(browser))
        callback = hosts[".account.AppleOAuthCallbackActivity"]
        self.assertEqual({
            ns + "name": ".account.AppleOAuthCallbackActivity",
            ns + "exported": "true",
            ns + "excludeFromRecents": "true",
            ns + "noHistory": "true",
            ns + "theme": "@android:style/Theme.Translucent.NoTitleBar",
        }, callback.attrib)
        self.assertEqual(1, len(callback))
        self.assertEqual("intent-filter", callback[0].tag)
        self.assertEqual({}, callback[0].attrib)
        self.assertEqual([
            ("action", {ns + "name": "android.intent.action.VIEW"}),
            ("category", {ns + "name": "android.intent.category.DEFAULT"}),
            ("category", {ns + "name": "android.intent.category.BROWSABLE"}),
            ("data", {
                ns + "scheme": "${applicationId}.auth",
                ns + "host": "oauth",
                ns + "pathPrefix": "/apple/",
            }),
        ], [(item.tag, item.attrib) for item in callback[0]])
        self.assertTrue(all(len(item) == 0 for item in callback[0]))
        react = hosts[".reactnative.LavaReactActivity"]
        self.assertEqual("true", react.get(ns + "exported"))
        self.assertEqual("@style/Theme.AppCompat.DayNight.NoActionBar", react.get(ns + "theme"))
        filters = react.findall("intent-filter")
        self.assertEqual(1, len(filters))
        self.assertEqual([
            ("action", {ns + "name": "android.intent.action.MAIN"}),
            ("category", {ns + "name": "android.intent.category.LAUNCHER"}),
        ], [(item.tag, item.attrib) for item in filters[0]])
        self.assertFalse(any(
            item.get(ns + "name") == "android.intent.category.LAUNCHER"
            for item in hosts[".MainActivity"].iter("category")
        ))

    def test_gradle_and_ci_wire_the_pinned_artifacts_and_bundle(self) -> None:
        catalog = self.read(ROOT / "android" / "gradle" / "libs.versions.toml")
        self.assertIn('reactNative = "0.87.1"', catalog)
        self.assertIn(
            'react-android = { module = "com.facebook.react:react-android", '
            'version.ref = "reactNative" }',
            catalog,
        )
        self.assertIn(
            'hermes-android = { module = "com.facebook.hermes:hermes-android", '
            'version.ref = "hermes" }',
            catalog,
        )
        app_build = self.read(ROOT / "android" / "app" / "build.gradle.kts")
        self.assertIn("implementation(libs.react.android)", app_build)
        self.assertIn("implementation(libs.hermes.android)", app_build)
        workflow = self.read(ROOT / ".github" / "workflows" / "android.yml")
        # Gradle owns the bundle consumed by native packaging. The independent
        # Linux UI tier also bundles to validate JS-only changes, but its output
        # must never substitute for Gradle's source/lock-bound bundle.
        self.assertIn(
            "uses: actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020 # v4.4.0",
            workflow,
        )
        self.assertIn("node-version: '22'", workflow)
        self.assertNotIn("Install and bundle React Native JS", workflow)
        self.assertIn("Verify React Native types and behavior", workflow)
        self.assertNotIn("npm ci --ignore-scripts --no-audit --no-fund", workflow)
        ui = workflow.split("\n  lava-ui:\n", 1)[1].split("\n  unit-tests:\n", 1)[0]
        packaging = workflow.split("\n  build:\n", 1)[1].split("\n  minified-startup:\n", 1)[0]
        self.assertIn("npm run bundle:android", ui)
        self.assertIn("needs.changes.outputs.docs_only != 'true'", ui)
        self.assertNotIn("npm run bundle:android", packaging)
        self.assertIn("./gradlew", packaging)

    def test_gradle_bundle_task_is_wired_into_the_build_graph(self) -> None:
        app_build = self.read(ROOT / "android" / "app" / "build.gradle.kts")
        settings = self.read(ROOT / "android" / "settings.gradle.kts")
        self.assertIn(
            'tasks.register<BundleReactNativeJsTask>("bundleReactNativeJs")',
            app_build,
        )
        self.assertRegex(
            app_build,
            r'tasks\.named\("preBuild"\)\s*\{\s*dependsOn\(bundleReactNativeJs\)',
        )
        # The APK/AAB asset merge tasks must consume the bundle, not race it.
        self.assertIn(
            'it.name.matches(Regex("merge(Play|Foss)(Debug|Release)Assets"))',
            app_build,
        )
        self.assertRegex(
            app_build,
            r'configureEach\s*\{\s*dependsOn\(bundleReactNativeJs\)',
        )
        self.assertIn(
            'layout.projectDirectory.dir("../react-native")',
            app_build,
        )
        self.assertIn(
            'layout.projectDirectory.file("src/main/assets/index.android.bundle")',
            app_build,
        )
        # Fresh checkout bootstraps the locked JS graph, and Node-less builds
        # no-op only against a source-matched bundle. `--include=dev` keeps the
        # CLI/Metro/Babel devDependencies even under NODE_ENV=production or
        # npm_config_omit=dev.
        self.assertIn('"--include=dev"', settings)
        self.assertIn('environment("NODE_ENV", "development")', settings)
        self.assertIn('"ci", "--include=dev", "--ignore-scripts", "--no-audit", "--no-fund"', settings)
        self.assertLess(settings.index('"--include=dev"'), settings.index('autolinkLibrariesFromCommand('))
        self.assertLess(settings.index('assertNormalExitValue()'), settings.index('stamp.writeText('))
        self.assertIn('stamp.readText().trim() != digest', settings)
        self.assertIn('MessageDigest.getInstance("SHA-256")', settings)
        self.assertNotIn('npmCommand("ci"', app_build)
        self.assertIn(
            "Node.js unavailable; keeping the existing React Native bundle",
            app_build,
        )
        # Platform-aware Node probe: Windows resolves node.exe (and launchers),
        # never only a literal `node` file.
        self.assertIn('listOf("node.exe", "node.cmd", "node.bat", "node")', app_build)
        self.assertRegex(app_build, r'os\.name[^\n]*win')
        # Platform-aware npm launcher: Windows runs npm through cmd /c.
        self.assertIn('listOf("cmd", "/c", "npm") + args', app_build)
        self.assertIn('commandLine(npmCommand("run", "bundle:android"))', app_build)
        # Locked install is keyed to package-lock.json, so a changed lock can
        # never be served by stale node_modules.
        self.assertIn('"node_modules/.lavasec-lock-hash"', app_build)
        self.assertIn("MessageDigest", app_build)
        self.assertIn("val installRequired =", app_build)
        self.assertIn("installedHash != lockHash", app_build)
        # Settings installs dependencies before autolinking discovers native projects.
        # preBuild never removes those sources during compilation. It fails closed if
        # the lock changes after discovery, and records a bundle stamp only on success.
        self.assertIn("dependencies changed after settings discovery", app_build)
        bundle_call = app_build.index('npmCommand("run", "bundle:android")')
        digest_compute = app_build.index("val bundleDigest = sha256(output)")
        lock_stamp_write = app_build.index("lockStamp.writeText(lockHash)")
        source_stamp_write = app_build.index("hashFile.writeText(")
        self.assertLess(bundle_call, digest_compute)
        self.assertLess(bundle_call, lock_stamp_write)
        self.assertLess(digest_compute, source_stamp_write)
        # A prebuilt bundle is accepted only when it matches the current JS
        # sources + lock AND its bytes still match the recorded bundle digest;
        # otherwise the Node-less path must fail.
        self.assertIn("bundleSourceHash", app_build)
        self.assertIn("private fun sourceHash(): String", app_build)
        self.assertIn("storedSourceHash == currentSourceHash", app_build)
        self.assertIn("storedBundleDigest == sha256(output)", app_build)
        self.assertIn("val bundleDigest = sha256(output)", app_build)
        self.assertIn(r'"$currentSourceHash\n$bundleDigest\n"', app_build)
        self.assertIn("did not produce", app_build)
        self.assertIn(
            "does not match the current JS sources",
            app_build,
        )

    def test_bundle_sources_cover_the_full_production_graph(self) -> None:
        app_build = self.read(ROOT / "android" / "app" / "build.gradle.kts")
        # The production entry reaches the Android `src/**` tree and the synced
        # canonical tree. Both must be bundle inputs, or the Node-less accept
        # path could reuse a stale bundle after only a canonical pin bump that
        # touched nothing under `src/**` (the F04 boundary note in
        # `src/ui/tokens.ts`). Pin each exactly once so a later edit cannot
        # silently drop one.
        self.assertEqual(1, app_build.count('"src/**"'))
        self.assertEqual(1, app_build.count('"canonical/**"'))
        self.assertEqual(1, app_build.count('"platform/**"'))
        self.assertIn('fileTree(reactNativePackageDir)', app_build)

    def test_release_is_minified_and_shrunk_and_keeps_registration(self) -> None:
        app_build = self.read(ROOT / "android" / "app" / "build.gradle.kts")
        # The candidate is R8-minified and resource-shrunk, so the release
        # artifact proves registration survives shrinking instead of only the
        # debug assembly.
        self.assertIn("isMinifyEnabled = true", app_build)
        self.assertIn("isShrinkResources = true", app_build)
        self.assertIn('"proguard-rules.pro"', app_build)
        rules = self.read(ROOT / "android" / "app" / "proguard-rules.pro")
        for keep in (
            "com.lavasec.app.reactnative.LavaAppModule",
            "com.lavasec.app.reactnative.LavaAppPackage",
            "com.lavasec.app.reactnative.Lava*Manager",
        ):
            self.assertIn(keep, rules)

    def test_pinned_registration_names_match_the_host_sources(self) -> None:
        module = self.read(MODULE / "LavaAppModule.kt")
        package = self.read(MODULE / "LavaAppPackage.kt")
        views = self.read(MODULE / "LavaPlatformViews.kt")
        field_view = self.read(MODULE / "LavaTextFieldManager.kt")
        card_views = self.read(MODULE / "LavaShareCardViews.kt")
        credential_view = self.read(MODULE / "LavaBackupCredentialView.kt")
        passcode_view = self.read(MODULE / "LavaPasscodeFormView.kt")
        native_page = self.read(MODULE / "components" / "LavaNativePageView.kt")
        approved_views = {
            "LavaTextFieldManager": "LavaTextField",
            "LavaDecorationManager": "LavaDecoration",
            "LavaSliderManager": "LavaSlider",
            "LavaNativePageManager": "LavaNativePage",
            "LavaShareCardSurfaceManager": "LavaShareCardSurface",
            "LavaShareQrManager": "LavaShareQr",
            "LavaBackupCredentialManager": "LavaBackupCredential",
            "LavaPasscodeFormManager": "LavaPasscodeForm",
        }
        approved_names = {"NativeLavaApp", *approved_views.values()}
        # The bundle verifier's DEX allowlist must be the exact JS-facing names
        # the host registers: the module NAME plus each view manager's getName().
        # Pin the approved names independently so coordinated source/allowlist
        # additions or renames cannot silently broaden the release check.
        names = set(re.findall(r'const val NAME = "([^"]+)"', module))
        names |= set(re.findall(r'override fun getName\(\) = "([^"]+)"', views))
        names |= set(re.findall(r'override fun getName\(\) = "([^"]+)"', field_view))
        names |= set(re.findall(r'override fun getName\(\) = "([^"]+)"', card_views))
        names |= set(re.findall(r'override fun getName\(\) = "([^"]+)"', credential_view))
        names |= set(re.findall(r'override fun getName\(\) = "([^"]+)"', passcode_view))
        names |= set(re.findall(r'const val NAME = "([^"]+)"', native_page))
        names |= set(re.findall(r'override fun getName\(\) = "([^"]+)"', package))
        self.assertIn("override fun getName(): String = NAME", module)
        self.assertIn("override fun getName(): String = NAME", native_page)
        self.assertEqual(approved_names, names)
        # The release verifier also preserves the class names used by the
        # native-screen restoration factory; these are not JS registrations.
        restored_fragments = {
            "Lcom/swmansion/rnscreens/ScreenFragment;",
            "Lcom/swmansion/rnscreens/ScreenStackFragment;",
        }
        self.assertEqual(approved_names | restored_fragments, set(REQUIRED_REGISTRATIONS))

        # A name in an unregistered source file is insufficient: the additive
        # native package must construct exactly these managers.
        registrations = package.split("override fun createViewManagers", 1)[1]
        registrations = registrations.split("override fun getModule", 1)[0]
        expression = re.sub(r"\s+", "", registrations.split("=", 1)[1])
        self.assertEqual(
            "listOf(" + ",".join(f"{manager}()" for manager in approved_views) + ")",
            expression,
        )

        # Android uses the owned legacy managers; the frozen iOS counterparts
        # use codegen. Both platform specs must retain the same approved name,
        # and their bytes must match the canonical provenance manifest.
        manifest = json.loads(self.read(RN / "scripts" / "canonical-source.manifest.json"))
        for name in approved_views.values():
            for suffix, factory, classification in (
                ("android.tsx", "requireNativeComponent", "overlay"),
                ("ios.ts", "codegenNativeComponent", "ios-platform"),
            ):
                # Android's native credential leaf has no upstream iOS spec; the
                # iOS backup host owns its separate native fields.
                if name in {"LavaBackupCredential", "LavaPasscodeForm"} and suffix == "ios.ts":
                    continue
                relative = f"specs/{name}NativeComponent.{suffix}"
                entries = [entry for entry in manifest["files"] if entry["path"] == relative]
                self.assertEqual(1, len(entries), relative)
                self.assertEqual(classification, entries[0]["class"], relative)
                spec = self.read(RN / "canonical" / relative)
                self.assertEqual(
                    [name],
                    re.findall(rf"{factory}(?:<[^>]+>)?\(\s*['\"]([^'\"]+)['\"]", spec),
                    relative,
                )
                encoded = (RN / "canonical" / relative).read_bytes()
                self.assertEqual(len(encoded), entries[0]["bytes"], relative)
                self.assertEqual(hashlib.sha256(encoded).hexdigest(), entries[0]["sha256"], relative)

    def test_readme_documents_node_prerequisite(self) -> None:
        readme = self.read(ROOT / "README.md")
        self.assertIn("^22.13.0 || ^24.3.0 || >= 26.0.0", readme)
        self.assertIn("bundleReactNativeJs", readme)
        self.assertIn("package-lock.json", readme)

    def test_application_host_implements_react_application(self) -> None:
        application = self.read(
            ROOT
            / "android"
            / "app"
            / "src"
            / "main"
            / "kotlin"
            / "com"
            / "lavasec"
            / "app"
            / "LavasecApplication.kt"
        )
        self.assertIn("Application(), ReactApplication", application)
        self.assertRegex(application, r"override val reactHost:\s*ReactHost\b")
        # Generated PackageList registers the RN core plus the autolinked screen,
        # safe-area and SVG packages. Lava's own package remains additive.
        self.assertIn("com.facebook.react.PackageList(this).packages.apply { add(LavaAppPackage()) }", application)
        app_build = self.read(ROOT / "android" / "app" / "build.gradle.kts")
        self.assertIn("autolinkLibrariesWithApp()", app_build)

    def test_reload_is_bound_to_the_service_generation(self) -> None:
        module = self.read(MODULE / "LavaAppModule.kt")
        # The module binds the reload to the service generation observed at enqueue; a stop queued
        # behind the read retires that generation and the service no-ops the stale reload. A
        # `Stopping`-only snapshot policy cannot close that queueing window.
        self.assertIn("LavaVpnRuntimeBridge.serviceGeneration", module)
        self.assertIn(".reload(generation)", module)
        policy = self.read(
            ROOT
            / "android"
            / "platform-contracts"
            / "src"
            / "main"
            / "kotlin"
            / "com"
            / "lavasec"
            / "app"
            / "platform"
            / "ProtectionController.kt"
        )
        self.assertIn("val isLiveForReload: Boolean", policy)
        # `Stopping` must be in the not-live arm of that policy, not the live one.
        property_index = policy.index("val isLiveForReload: Boolean")
        stopping_arm = policy.index("Stopping,", property_index)
        protected_arm = policy.index("is Protected,", property_index)
        self.assertLess(stopping_arm, protected_arm)


if __name__ == "__main__":
    unittest.main()
