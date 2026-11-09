import hashlib
import json
import runpy
import shutil
import subprocess
import tempfile
import tomllib
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT_PATH = ROOT / "contracts" / "android-foundation.json"


class AndroidFoundationContractTest(unittest.TestCase):
    def read(self, relative_path: str) -> str:
        path = ROOT / relative_path
        self.assertTrue(path.is_file(), f"missing {path}")
        return path.read_text(encoding="utf-8")

    def chained_path_registration_body(self, runtime: str) -> str:
        start = "    private fun armChainedPathRegistration(owner: ChainedTunRuntimeOwner) {"
        end = "    private fun retireChainedPathRegistration() {"
        self.assertIn(start, runtime)
        self.assertIn(end, runtime)
        begin = runtime.index(start)
        return runtime[begin:runtime.index(end, begin)]

    def verifier_fixture(self, directory: str) -> Path:
        fixture = Path(directory)
        shutil.copytree(
            ROOT / "android",
            fixture / "android",
            ignore=shutil.ignore_patterns(".gradle", ".cxx", "build", "node_modules"),
        )
        shutil.copytree(ROOT / "contracts", fixture / "contracts")
        (fixture / "release").mkdir()
        shutil.copyfile(ROOT / "release/android-version.properties", fixture / "release/android-version.properties")
        return fixture

    def run_verifier(self, root: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(ROOT / "scripts" / "verify-android-foundation"), "--root", str(root)],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

    def run_presentation_dependency_configuration(
        self,
        root: Path,
    ) -> subprocess.CompletedProcess[str]:
        # Not --offline: these synthetic projects share the machine Gradle
        # cache, but a cold hosted-runner cache must be allowed to resolve
        # the Android Gradle plugin from the declared repositories.
        return subprocess.run(
            [
                str(root / "android" / "gradlew"),
                "-p",
                str(root / "android"),
                "--no-daemon",
                ":presentation:dependencies",
                "--configuration",
                "debugRuntimeClasspath",
            ],
            cwd=root,
            check=False,
            capture_output=True,
            text=True,
            timeout=240,
        )

    def run_app_compile(self, root: Path) -> subprocess.CompletedProcess[str]:
        # Not --offline; see run_presentation_dependency_configuration.
        return subprocess.run(
            [
                str(root / "android" / "gradlew"),
                "-p",
                str(root / "android"),
                "--no-daemon",
                ":app:compilePlayDebugKotlin",
            ],
            cwd=root,
            check=False,
            capture_output=True,
            text=True,
            timeout=240,
        )

    def mutate(
        self,
        fixture: Path,
        relative_path: str,
        old: str,
        new: str,
        replace_all: bool = False,
    ) -> None:
        path = fixture / relative_path
        source = path.read_text(encoding="utf-8")
        self.assertIn(old, source)
        mutated = source.replace(old, new) if replace_all else source.replace(old, new, 1)
        path.write_text(mutated, encoding="utf-8")

    def assert_verifier_rejects(
        self,
        relative_path: str,
        old: str,
        new: str,
        error_fragment: str,
        replace_all: bool = False,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            self.mutate(fixture, relative_path, old, new, replace_all=replace_all)
            result = self.run_verifier(fixture)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(error_fragment, result.stderr)

    def test_foundation_contract_is_exact(self) -> None:
        expected = {
            "applicationId": "com.lavasec.app",
            "namespace": "com.lavasec.app",
            "moduleNamespaces": {
                "foundation": "com.lavasec.app.foundation",
                "product": "com.lavasec.app.product",
                "platformContracts": "com.lavasec.app.platform",
                "vpn": "com.lavasec.app.vpn",
                "presentation": "com.lavasec.app.presentation",
                "designSystem": "com.lavasec.app.designsystem",
                "testSupport": "com.lavasec.app.testsupport",
            },
            "debugApplicationIdSuffix": ".debug",
            "minSdk": 28,
            "compileSdk": 36,
            "targetSdk": 36,
            "agp": "9.3.0",
            "gradle": "9.5.0",
            "java": 17,
            "kotlin": "2.4.10",
            "composeBom": "2026.06.00",
        }

        self.assertTrue(CONTRACT_PATH.is_file(), f"missing {CONTRACT_PATH}")
        self.assertEqual(expected, json.loads(CONTRACT_PATH.read_text()))

    def test_chained_outage_accounting_uses_a_monotonic_clock(self) -> None:
        owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedTunRuntimeOwner.kt"
        )
        # The injected `nowSeconds` feeds the outage budget and liveness policies. A wall clock can
        # jump backward (reported unusable) or forward (consuming the whole budget) during an NTP
        # sync or manual change, surrendering a healthy session to DNS-only; elapsed real time is
        # monotonic.
        self.assertIn("SystemClock.elapsedRealtime()", owner)
        self.assertNotIn("System.currentTimeMillis() / 1_000L", owner)

    def test_chained_start_retires_the_awaiting_network_registration(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        # Both successful install paths must retire the awaiting-network callback. The DNS-only
        # path already does; the chained path must too, or the callback stays registered for the
        # whole chained session and every capability/link-properties update launches a no-op retry.
        # The chained path then arms the default-network PATH callback, which is the one that must
        # stay registered for the live session so a roam rebinds instead of blackholing.
        self.assertIn(
            "retireNetworkRegistration()\n"
            "        // ...but a live chained session DOES need path transitions",
            runtime,
        )
        self.assertIn("armChainedPathRegistration(owner)", runtime)
        registration = self.chained_path_registration_body(runtime)
        # Carry the installed owner, not only its Network: capture-floor refresh and
        # registration publication must refuse an owner that retired during setup.
        self.assertIn("ChainedCaptureFloorRefreshGate(owner, owner.installedPlan.dnsCaptureFloor)", registration)
        self.assertIn(
            "val registrationRetired = lock.withLock {\n"
            "            if (closed || superseded || chainedOwners.currentOrNull() !== owner) true\n"
            "            else { chainedPathRegistration = registration; false }\n"
            "        }",
            registration,
        )
        self.assertIn(
            "if (registrationRetired) {\n"
            "            gate.retire()\n"
            "            runCatching { registration?.close() }\n"
            "            return\n"
            "        }",
            registration,
        )

    def test_chained_start_reports_session_health(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ExactSessionHealthOwner.kt"
        )
        # The chained path installs no DNS engine, so it must create its own health owner and
        # report connected/recovery transitions through it; otherwise the accumulator stays
        # `isConnected = false` and an otherwise Protected session is RECONNECT_NEEDED.
        self.assertIn("healthOwner = ExactSessionHealthOwner(", runtime)
        self.assertIn("chainedHealthEngineIdentity", runtime)
        self.assertIn("publishChainedConnection", runtime)
        self.assertIn("fun recordConnectionChanged(", owner)

    def test_preference_reload_is_bound_to_the_service_generation(self) -> None:
        service = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/LavaVpnService.kt"
        )
        command_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/VpnServiceCommandOwner.kt"
        )
        controller = self.read(
            "android/platform-contracts/src/main/kotlin/com/lavasec/app/platform/"
            "ProtectionController.kt"
        )
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        # A preference-triggered reload carries the observed service generation, and the command
        # owner no-ops it when that generation is no longer installed (a stop queued behind the
        # read retired it), so protection cannot restart after the user turned it off.
        self.assertIn("EXTRA_EXPECTED_SERVICE_GENERATION", service)
        self.assertIn("VpnServiceCommand.Reload(", service)
        self.assertIn("vpnReloadIsAdmitted", command_owner)
        self.assertIn("fun vpnReloadIsAdmitted(", controller)
        # A generation-rejected reload must be a harmless no-op, not a terminal start: the command
        # owner reports it as `Superseded` and `onStartCommand` treats that as non-terminal, so the
        # newer runtime's foreground service is not finished and closed.
        self.assertIn("Superseded", command_owner)
        self.assertIn("fun isTerminalStart(", command_owner)
        self.assertIn("isTerminalStart(commandOwner.handle(command))", service)
        # No runtime left (an ACTION_STOP retired it) is terminal: the reload finishes the start so
        # the service stops instead of lingering for a sticky restart to re-enable protection.
        self.assertIn("?: return VpnCommandResult.NotSticky", command_owner)
        # A start whose asynchronous preflight has not committed yet has no runtime either, but it is
        # live and tracked as a first-class `pending` attempt: a generation-bound reload arriving in
        # that window must be admitted against the pending owner's generation and run a fresh start,
        # because the candidate may already hold credentials opened under the preference that has
        # since changed. The fresh preflight re-reads the durable gate, and the new start's commit
        # closes the superseded candidate while fencing on the pending identity it replaced.
        self.assertIn("val attempt = pending", command_owner)
        self.assertIn(
            "vpnReloadIsAdmitted(expectedServiceGeneration, attempt.owner.sessionGeneration)",
            command_owner,
        )
        # The superseded candidate is claimed under the monitor but its blocking close is scheduled
        # for after the monitor is released (`deferClose`), so a chained teardown cannot block the
        # service thread that delivers Stop/Revoke/Start.
        self.assertIn("deferClose(attempt?.candidate)", command_owner)
        self.assertIn("return start(StartOrigin.Reload)", command_owner)
        # The in-flight attempt is a first-class pending owner with its own terminal latch, so
        # teardown can close its candidate and a terminal event published during startup is held
        # until adoption instead of being rejected as a stale owner.
        self.assertIn("private class PendingRuntime(", command_owner)
        self.assertIn("private var pendingTerminal: ProtectionFailure? = null", command_owner)
        # The internal pause-expiry rebuild is bound to its runtime's service generation too, so a
        # stop or newer start that retired it makes the queued reload a no-op.
        self.assertIn("expectedServiceGeneration", service)
        self.assertIn("requestRuntimeRebuild(request.owner.sessionGeneration)", runtime)

    def test_async_controls_are_serialized_and_closed_outside_the_monitor(self) -> None:
        owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/VpnServiceCommandOwner.kt"
        )
        # Finding 1: pause/resume controls are serialized by one lock and fenced by a monotonic
        # sequence, so on a multithreaded dispatcher the last delivered control wins and an older
        # one cannot retire a runtime a newer control already succeeded on.
        self.assertIn("private val controlLock = ReentrantLock()", owner)
        self.assertIn("sequence == latestControlSequence", owner)
        self.assertIn("controlLock.withLock {", owner)
        # Finding 2: a control failure claims the exact runtime under the monitor, then closes it
        # and finishes the service outside it (the close can join the chained engine).
        self.assertIn("runCatching { current.close() }", owner)
        self.assertIn("exitServiceIfUnowned()", owner)
        # Findings 4 and 6: the in-flight candidate is a first-class attempt that carries the
        # deferred Pause/Resume intent, is claimed under the monitor, and is closed after it.
        self.assertIn("val superseded = supersedePending()", owner)
        # A Pause/Resume delivered against the installed runtime but not yet applied is parked and
        # inherited by the next start (newest wins), so a Start/Reload that replaces the owner before
        # the launched control runs does not silently drop the user's intent.
        self.assertIn("private var latestControlIntent: DeferredControl? = null", owner)
        self.assertIn("takeOutstandingControl()", owner)
        self.assertIn(
            "listOfNotNull(inheritedControl, parkedControl).maxByOrNull { it.sequence }",
            owner,
        )
        self.assertIn(
            "val attempt = PendingRuntime(owner, candidate, predecessor, deferredControl, supersededChain)",
            owner,
        )
        self.assertIn("deferredControl?.let { deferred ->", owner)
        # A superseded in-flight candidate is handed forward to its successor and closed only after
        # the successor is live, so a candidate that already claimed the device interface is not
        # torn down while its replacement is still preflighting (the iOS "session stays until
        # replaced" mapping). Its own launcher must not close it once transferred.
        self.assertIn("supersededChain.forEach { runCatching { it.close() } }", owner)
        self.assertIn("if (!installed && !attempt.transferred.get())", owner)
        # An accepted control/reconnect failure retires the exact owner under the monitor, so the
        # process-wide chained activity must be cleared INSIDE that critical section: the deferred
        # `current.close()` runs after `currentOwner` is nulled, so its owner-fenced clear is a
        # no-op and a stale `active = true`/CHAINED snapshot would survive protection going Failed.
        self.assertIn(
            "currentOwner = null\n"
            "                    // The exact-owner retirement is accepted under this monitor",
            owner,
        )
        self.assertGreaterEqual(owner.count("clearChainedActivity()"), 4)

    def test_ios_port_hardening_for_chained_recovery(self) -> None:
        tun_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedTunRuntimeOwner.kt"
        )
        adapters = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedProductionAdapters.kt"
        )
        driver = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/ChainedOutageDriver.kt"
        )
        data_path = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/ChainedDataPath.kt"
        )
        engine_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/ChainedEngineOwner.kt"
        )
        # The first handshake is forced in `ChainedTunRuntimeOwner.start()`, AFTER the control plane
        # opened its startup-termination window, not inside `establish`: a synchronous
        # PEER_SOCKET_FAILED during the first send was otherwise discarded and an already-failed
        # full-route runtime adopted. Anchor on the CALL SITE (both lines), not the explanatory
        # comment that also mentions `dataPath.start()`.
        self.assertIn("dataPath.start()\n        runtime.start()", tun_owner)
        self.assertNotIn("driver.adopt(dataPath)\n            dataPath.start()", tun_owner)
        # The recovery attempt re-resolves a hostname endpoint on the CURRENT underlay, so a roam
        # with split-horizon DNS / endpoint failover reaches the new address instead of the one
        # resolved at establishment.
        self.assertIn(
            "ChainedEndpointResolver.resolve(underlay, endpointHost, endpointPort)",
            tun_owner,
        )
        # The timer scheduler is released on retirement, so a rebuild does not leak a thread.
        self.assertIn("timers.shutdown()", tun_owner)
        self.assertIn("override fun shutdown()", adapters)
        self.assertIn("scheduler.shutdownNow()", adapters)
        # The outage deadline is anchored to the first failure: a re-`beginOutage` mid-outage must
        # not re-arm it at `now + MAXIMUM_BLACKHOLE_SECONDS` and extend the blackhole.
        self.assertIn("if (!wasAlreadyTiming || outageDeadline == null)", driver)
        # The predecessor's FUTURE lifecycle operations are fenced before the candidate can claim the
        # interface, so a queued internal reconnect cannot `establish()` after the candidate and
        # deactivate its TUN; the predecessor keeps its installed tunnel for make-before-break.
        command_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/VpnServiceCommandOwner.kt"
        )
        self.assertIn("predecessor?.supersede()", command_owner)
        # The replacement channel and its generation are published as ONE snapshot, so a receive-loop
        # pass can never pair the new generation with the old (closed) channel and report
        # PEER_SOCKET_FAILED during a routine rebind.
        self.assertIn("channelSnapshot = ChannelSnapshot(previous.generation + 1L, replacement)", data_path)
        self.assertIn("if (snapshot !== channelSnapshot) continue", data_path)
        # Retiring an attempt publishes a non-established state and drops its parked packets, so the
        # status stops claiming "chained active" and a stale packet is not flushed onto a later one.
        self.assertIn("mutableState.value = ChainedEngineState.Starting", engine_owner)
        self.assertIn("queue.removeAll()", engine_owner)

    def test_chained_establish_claim_is_fenced_by_ownership(self) -> None:
        tun_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedTunRuntimeOwner.kt"
        )
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        # Finding 1: the final route-claiming TUN establishment is fenced on the runtime's single
        # serial confinement. Because establishing an Android VPN interface deactivates the existing
        # one, a candidate that a Stop/newer Start superseded during the non-cancellable session
        # allocation must not tear down a tunnel it no longer owns: the pre-claim check refuses the
        # claim, and the post-claim `resolveRouteClaim` atomically retains (does NOT close) an
        # interface claimed just before the supersession so the successor replaces it without a gap.
        self.assertIn("engineQueue: ChainedEngineQueue,", tun_owner)
        self.assertIn("operationGeneration: Long,", tun_owner)
        establishment = tun_owner.index("ChainedTunEstablishment.establish(")
        # A prepared engine may outlive ownership, the selected underlay, or its
        # captured DNS membership. All three checks must precede the TUN claim;
        # refusal releases prepared resources without claiming a replacement TUN.
        guard = (
            "if (!engineQueue.isEstablishAllowed(operationGeneration) || currentNetwork() != underlay ||\n"
            "                runCatching { capturedResolverAddresses(underlay)?.toList() }.getOrNull() != capturedDnsAddresses\n"
            "            ) {\n"
            "                retireWitness()\n"
            "                runCatching { session.close() }\n"
            "                runCatching { peerSocket.close() }\n"
            "                return null\n"
            "            }"
        )
        self.assertIn(guard, tun_owner)
        self.assertLess(tun_owner.index(guard), establishment)
        self.assertIn(
            "engineQueue.resolveRouteClaim(operationGeneration, established.session)",
            tun_owner,
        )
        retained = tun_owner.index("ChainedClaimOutcome.RETAINED -> {")
        installed = tun_owner.index("ChainedClaimOutcome.INSTALLED -> Unit", retained)
        # The retained branch releases the prepared engine/socket/witness but keeps the claimed TUN
        # for the successor; only a closed queue (REFUSED) closes the freshly claimed session.
        self.assertNotIn("established.session.close()", tun_owner[retained:installed])
        self.assertIn("engineQueue = engineQueue,", runtime)
        self.assertIn("operationGeneration = operationGeneration,", runtime)

    def test_deferred_runtime_closes_run_off_the_service_thread(self) -> None:
        owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/VpnServiceCommandOwner.kt"
        )
        # Finding 2: a claimed runtime's blocking close is dispatched to a teardown scope
        # (production `Dispatchers.IO`) rather than drained inline on the service thread that called
        # `onStartCommand`, where a slow JNI/engine teardown could ANR the service. The teardown
        # scope has its own Job so `close`'s `scope.cancel()` cannot cancel a queued close.
        self.assertIn("private val teardownScope =", owner)
        self.assertIn("teardownScope.launch {", owner)
        self.assertIn("teardownDispatcher", owner)
        self.assertNotIn("runCatching { next.close() }", owner)

    def test_deferred_controls_share_the_control_ordering(self) -> None:
        owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/VpnServiceCommandOwner.kt"
        )
        # Finding 3: a Pause/Resume deferred by a pending start carries the sequence assigned at
        # delivery and is applied through the same `controlLock`/`latestControlSequence` path as an
        # installed-runtime control, so a later control delivered after adoption cannot be
        # overwritten by the older deferred intent.
        self.assertIn("private class DeferredControl(", owner)
        self.assertIn("val deferred = DeferredControl(++latestControlSequence, action)", owner)
        self.assertIn("return start(StartOrigin.Reload, afterAdopt = deferred)", owner)
        self.assertIn("private fun applyControl(", owner)
        self.assertIn("applyControl(owner, candidate, deferred.sequence, deferred.action)", owner)

    def test_chained_engine_is_allocated_before_the_route_claiming_tun(self) -> None:
        tun_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedTunRuntimeOwner.kt"
        )
        # Finding 3: Android allows only one active VPN interface, so `establish()` must be the LAST
        # fallible step. The witness and the ~50 MB JNI session are allocated before it, so the
        # predecessor stays active until the candidate is fully ready and a racing Start/Reload
        # cannot find itself with no usable underlay.
        witness = tun_owner.index("ChainedWitnessFirstAllocation.run(")
        session_allocation = tun_owner.index("sessionFactory.create()")
        establishment = tun_owner.index("ChainedTunEstablishment.establish(")
        self.assertLess(witness, establishment)
        self.assertLess(session_allocation, establishment)

    def test_preflight_that_needs_a_network_reenters_awaiting_recovery(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        gates = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedLifecycleGates.kt"
        )
        # Finding 7: a preflight that reached the credential read with no eligible underlay stashes
        # a NetworkUnavailable marker instead of leaving an empty stash.
        self.assertIn("ChainedPreflightState.NetworkUnavailable", runtime)
        # Start re-enters awaiting-network recovery when a network appears, rather than consuming
        # the empty stash as a permanent DNS-only decision with no later chained retry.
        self.assertIn("fun chainedStartRequiresAwaitingNetwork(", gates)
        self.assertIn("preflightNetworkUnavailable: Boolean", gates)
        # The helper expects `eligibleNetworkAvailable`, NOT its inverse. Passing `network == null`
        # made every start with an available network enter awaiting-network recovery, so no TUN was
        # installed until an availability callback ran. Pin the call site to `network != null`.
        self.assertIn(
            "chainedStartRequiresAwaitingNetwork(network != null, preflightNeedsNetwork)",
            runtime,
        )
        self.assertNotIn(
            "chainedStartRequiresAwaitingNetwork(network == null, preflightNeedsNetwork)",
            runtime,
        )

    def test_candidate_start_is_fenced_against_a_concurrent_teardown(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/VpnServiceCommandOwner.kt"
        )
        # A concurrent Stop/onDestroy can close the candidate after `start` passed its initial
        # check but while it still installs its route-claiming TUN. The DNS path must register the
        # runtime atomically with a `closed` re-check and release everything it created when the
        # close won, and the command owner must re-check ownership after `candidate.start()` before
        # it treats the candidate as installed.
        # The post-establish DNS claim is resolved atomically: a supersession that landed during the
        # non-cancellable `establish()` retains the claimed TUN (it is not closed out from under the
        # successor), and a closed queue refuses it.
        self.assertIn(
            "when (engineQueue.resolveRouteClaim(operationGeneration, packetSession)) {",
            runtime,
        )
        self.assertIn("ChainedClaimOutcome.RETAINED -> return", runtime)
        # Registration is atomic with the current-operation check (`supersede()` takes the same
        # `lock`): a supersession that landed keeps the claimed interface (retained, not closed) and
        # refuses to register/start the stale runtime; a close releases the un-started driver.
        self.assertIn(
            "val installed = lock.withLock {\n"
            "            when {\n"
            "                closed -> false\n"
            "                !engineQueue.isEstablishAllowed(operationGeneration) -> {\n"
            "                    engineQueue.retainRouteClaim(packetSession)\n"
            "                    false\n"
            "                }\n"
            "\n"
            "                else -> {\n"
            "                    runtime = bounded\n"
            "                    true\n"
            "                }\n"
            "            }\n"
            "        }",
            runtime,
        )
        self.assertIn(
            "if (lock.withLock { closed }) {\n"
            "                bounded.requestClose()\n"
            "                runBlocking { runCatching { bounded.join() } }\n"
            "            }\n"
            "            exactEpoch.close()\n"
            "            exactAttachment.close()\n"
            "            return",
            runtime,
        )
        self.assertIn(
            "val adopted = synchronized(this@VpnServiceCommandOwner) {\n"
            "                    val attempt = pending",
            owner,
        )
        self.assertIn("if (!adopted) return@launch", owner)
        # Adoption (and the owner re-check) happens only after `candidate.start()`, so a teardown
        # that closed the candidate during start leaves it unadopted and `installed` false.
        self.assertLess(
            owner.index("candidate.start()"),
            owner.index("val adopted = synchronized(this@VpnServiceCommandOwner)"),
        )

    def test_chained_owner_registration_is_fenced_against_a_concurrent_teardown(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        # `ChainedTunRuntimeOwner.establish` allocates the TUN, the peer socket, and the ~50 MB
        # engine over a slow window. A concurrent Stop/onDestroy supersedes the operation and
        # captures an empty chained-owner slot during it, so the live owner must be registered
        # atomically with a current-generation re-check; when the confinement won, the startup
        # binding is retired and the owner closed so the caller's fallback builds DNS-only instead of
        # leaving a route-claiming owner behind.
        self.assertIn(
            "val installed = lock.withLock {\n"
            "            if (!engineQueue.isEstablishAllowed(operationGeneration)) {\n"
            "                false\n"
            "            } else {\n"
            "                chainedOwners.install(owner)\n"
            "                awaitingNetwork = false\n"
            "                selected = network\n"
            "                true\n"
            "            }\n"
            "        }",
            runtime,
        )
        self.assertIn(
            "chainedStartup.retire()\n"
            "            owner.handOffRouteClaim(engineQueue)\n"
            "            return false",
            runtime,
        )
        # The shared anchors are claimed under the same `closed` gate: creating them after a close
        # captured an empty slot would leak them past the teardown that already ran.
        self.assertIn("if (!ensureRuntimeAnchors()) return false", runtime)
        self.assertIn(
            "private fun ensureRuntimeAnchors(): Boolean = lock.withLock {\n"
            "        if (closed) return@withLock false",
            runtime,
        )

    def test_terminal_chained_shutdown_is_latched_under_the_reconnect_serialization(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        gate = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedTerminalShutdownGate.kt"
        )
        # A claimed hard failure latches the shutdown while holding the reconnect serialization,
        # and the reconnect path no-ops while it is pending, so a stale terminal transition cannot
        # close a replacement the reconnect installed in the teardown gap.
        self.assertIn("chainedTerminalShutdown.mark()", runtime)
        self.assertIn("chainedTerminalShutdown.isPending()", runtime)
        self.assertIn("fun isPending(): Boolean", gate)

    def test_status_rereads_the_configuration_when_the_key_generation_is_null(self) -> None:
        composition = self.read(
            "android/platform-contracts/src/main/kotlin/com/lavasec/app/platform/"
            "ChainedComposition.kt"
        )
        # A null secret generation can mean `removeAll()` raced the read; status must re-read the
        # configuration and retry rather than evaluate the removed configuration as a missing key
        # (which would report "re-enter the key" instead of "not configured").
        self.assertIn("val reread = configurationRepository.loadConfiguration()", composition)
        self.assertIn("if (reread == stored)", composition)

    def test_chained_handoff_rechecks_detachment_after_the_slow_resolve(self) -> None:
        composition = self.read(
            "android/platform-contracts/src/main/kotlin/com/lavasec/app/platform/"
            "ChainedComposition.kt"
        )
        graphs = self.read(
            "android/app/src/main/kotlin/com/lavasec/app/ProductionApplicationGraphs.kt"
        )
        # A graph rotation can detach the attachment while resolveActivation() performs its
        # DataStore/Keystore reads; the handoff must recheck detachment and close the secret half
        # instead of returning a retired generation's material to the engine.
        self.assertIn("fun resolveChainedHandoff(", composition)
        self.assertIn("resolveChainedHandoff(", graphs)

    def test_chained_peer_is_resolved_before_the_route_claiming_tun(self) -> None:
        owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedTunRuntimeOwner.kt"
        )
        # The mode-specific TUN claims the device's routes the instant it is established and has no
        # driver until the owner starts, so a slow/timed-out hostname lookup must finish FIRST; on
        # resolution failure the TUN is never established and the caller stays on DNS-only. The
        # resolution now branches on whether the selected underlay matches the preflight network
        # (finding 4021942640), so the null-refusal is on the whole `if` expression (`} ?: return
        # null`), still before the establishment.
        resolver_call = owner.index("ChainedEndpointResolver.resolve(")
        establishment_call = owner.index("ChainedTunEstablishment.establish(")
        self.assertLess(resolver_call, establishment_call)
        resolved_refusal = owner.index("} ?: return null", resolver_call)
        self.assertLess(resolved_refusal, establishment_call)

    def test_replacement_preflights_before_retiring_the_predecessor(self) -> None:
        owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/VpnServiceCommandOwner.kt"
        )
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        tun_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedTunRuntimeOwner.kt"
        )
        # On a reload the predecessor must still own its tunnel while the replacement resolves the
        # chained endpoint (preflight) and starts. Adoption happens only once the candidate is live,
        # and the predecessor is retired after that — a make-before-break handoff with no tunnel gap.
        preflight = owner.index("candidate.preflight()")
        start = owner.index("candidate.start()", preflight)
        self.assertLess(preflight, start)
        adoption = owner.index("runtime = candidate", start)
        self.assertLess(start, adoption)
        predecessor_close = owner.index("runCatching { predecessor?.close() }", adoption)
        self.assertLess(adoption, predecessor_close)
        self.assertLess(start, predecessor_close)
        # The preflight resolves through the selected network and the runtime reuses that address.
        self.assertIn("suspend fun preflight() = Unit", owner)
        self.assertIn("override suspend fun preflight()", runtime)
        self.assertIn("ChainedEndpointResolver.resolve(network.network, endpointHost, endpointPort)", runtime)
        self.assertIn("resolvedPeerAddress: InetSocketAddress? = null", tun_owner)

    def test_failed_preflight_and_terminal_mode_fence(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        gates = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedLifecycleGates.kt"
        )
        owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/VpnServiceCommandOwner.kt"
        )
        service = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/LavaVpnService.kt"
        )
        # A failed preflight is remembered (Refused) and start falls back to DNS-only without
        # retrying the hostname lookup in the no-tunnel gap.
        self.assertIn("ChainedPreflightState.Refused(network)", runtime)
        self.assertIn("chainedAttemptAdmittedAfterPreflight(", runtime)
        self.assertIn("fun chainedAttemptAdmittedAfterPreflight(", gates)
        # A missing (NOT_ATTEMPTED) preflight is only admitted when the endpoint is an IP literal and
        # needs no underlay resolver; a hostname would re-run the lookup after the predecessor is
        # retired, so the gate refuses it and start falls back to the unchanged DNS-only runtime.
        self.assertIn(
            "chainedAttemptAdmittedAfterPreflight(outcome, resolutionRequired)",
            runtime,
        )
        self.assertIn("ChainedUpstreamConfigurationValidator.isIPLiteral(it)", runtime)
        self.assertIn("resolutionRequired: Boolean", gates)
        self.assertIn("ChainedPeerPreflightOutcome.NOT_ATTEMPTED -> !resolutionRequired", gates)
        # The preflight re-resolves on a changed underlay snapshot (bounded) so the stashed
        # network/address pair is internally consistent, and discards the snapshot entirely when the
        # underlay disappears so a stale address is never handed to start.
        self.assertIn("MAX_PREFLIGHT_RESOLUTION_RETRIES", runtime)
        self.assertIn("chainedEndpointPreflightResolution(", runtime)
        self.assertIn("ChainedEndpointPreflightResolution.UNDERLAY_LOST", runtime)
        self.assertIn("fun chainedEndpointPreflightResolution(", gates)
        self.assertIn("!networkAvailable -> ChainedEndpointPreflightResolution.UNDERLAY_LOST", gates)
        # The credentials opened by the preflight are carried into the start so the post-retirement
        # gap does not perform a second Keystore read; a start that never ran a preflight re-opens,
        # but one whose preflight reached the credential read and carried nothing does NOT — that
        # read is the slow step the carry exists to skip, and re-running it after the predecessor is
        # retired leaves a no-tunnel gap (and would even admit an IP-literal attempt).
        self.assertIn("private var chainedPreflightCredentials: ChainedVpnCredentials?", runtime)
        self.assertIn("private var chainedPreflightAttempted = false", runtime)
        self.assertIn(
            "carriedCredentials != null -> carriedCredentials",
            runtime,
        )
        self.assertIn("preflightAttempted -> null", runtime)
        self.assertIn("else -> chainedCredentialsForInitialRuntime()", runtime)
        self.assertIn(
            "preflightAttempted = chainedPreflightAttempted.also { chainedPreflightAttempted = false }",
            runtime,
        )
        self.assertIn("chainedPreflightAttempted = true", runtime)
        self.assertIn("(preflightAttempted && credentials == null)", runtime)
        self.assertIn("chainedPreflightCredentials = chainedPreflightCredentials.also {", runtime)
        # The lookup runs on an IO dispatcher, never the main thread from onStartCommand.
        self.assertRegex(runtime, r"private suspend fun resolveChainedPeer\([\s\S]*?withContext\(Dispatchers\.IO\)")
        # The replacement preflight/commit runs asynchronously off the service thread, so a slow
        # lookup cannot block onStartCommand; production wires the IO dispatcher.
        self.assertIn("pendingJob = scope.launch {", owner)
        self.assertIn("candidate.preflight()", owner)
        self.assertIn("scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)", service)
        # A reconnect targets an installed runtime whose internal chained preflight can also block
        # on DNS/Keystore, so it is dispatched on the same scope instead of inline on the service
        # thread, and its failure is fenced on the runtime/owner identity it started with.
        self.assertIn("VpnServiceCommand.Reconnect -> reconnect()", owner)
        self.assertIn("private fun reconnect(): VpnCommandResult", owner)
        self.assertIn(
            "if (closed || runtime !== current || currentOwner !== owner) {",
            owner,
        )
        # The DNS completion's mode clear is part of the exact-owner terminal retirement, under the
        # command owner's monitor, so a concurrent Start cannot interleave and be overwritten.
        self.assertIn("clearChainedActivity()", owner)
        self.assertIn("private val clearChainedActivity: () -> Unit", owner)
        self.assertIn("clearChainedActivity = {", service)

    def test_internal_starts_preflight_the_chained_endpoint(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        awaiting = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedAwaitingNetworkPolicy.kt"
        )
        # The command owner preflights its own starts, but the runtime's internal recovery and
        # reconnect paths reach start without one. They must preflight too, or a hostname endpoint is
        # refused as NOT_ATTEMPTED and chaining silently degrades to DNS-only with no later attempt.
        self.assertIn("ChainedAwaitingNetworkPolicy.requiresEndpointPreflight(action)", runtime)
        self.assertIn("private suspend fun preflightChainedEndpoint(): SelectedVpnNetwork?", runtime)
        self.assertIn("fun requiresEndpointPreflight(", awaiting)
        self.assertIn("ChainedAwaitingNetworkAction.StartInitialRuntime -> true", awaiting)
        # An explicit reconnect resolves the endpoint BEFORE dropping a live chained predecessor, so
        # the lookup never runs in the no-tunnel gap.
        perform = runtime.index("private suspend fun performReconnect(")
        preflight = runtime.index("preflightChainedEndpoint()", perform)
        predecessor_close = runtime.index("closeChainedForRebuild()", preflight)
        self.assertLess(preflight, predecessor_close)

    def test_chained_io_never_runs_under_the_reconnect_serialization(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        # The endpoint lookup and the preflight that carries credentials are suspending, so the
        # attempt is cancellable, and they run BEFORE the confined commit is submitted: a concurrent
        # Stop/close never waits on a slow resolver (holding the serial owner across the lookup made
        # cancellation unable to interrupt the nested runBlocking).
        self.assertIn("private suspend fun resolveChainedPeer(", runtime)
        self.assertIn("withContext(Dispatchers.IO)", runtime)
        self.assertIn("private suspend fun preflightChainedEndpoint(", runtime)
        perform = runtime.index("private suspend fun performReconnect(")
        preflight = runtime.index("preflightChainedEndpoint()", perform)
        serialized = runtime.index("engineQueue.run {", preflight)
        self.assertLess(preflight, serialized)
        retry = runtime.index("private suspend fun retryAwaitingNetwork()")
        retry_preflight = runtime.index("preflightChainedEndpoint()", retry)
        retry_serialized = runtime.index(
            "engineQueue.run { operationGeneration ->", retry_preflight
        )
        self.assertLess(retry_preflight, retry_serialized)
        # Teardown does NOT wait for the confinement's in-flight operation: `close()` is called from
        # the service main thread, and a confined transition can hold the serial owner across the
        # witness teardown, TUN setup, and the ~50 MB engine allocation. It latches `closed` and
        # captures its resources under `lock` only (the install paths re-check the confinement
        # generation atomically and self-clean), then submits the engine join OUTSIDE the caller in
        # `finishClose`.
        self.assertNotIn("engineQueue.run { beginClose() }", runtime)
        self.assertIn("override fun close() {", runtime)
        self.assertIn("val owned = beginClose() ?: return", runtime)
        self.assertIn("engineQueue.close()", runtime)
        self.assertIn("engineQueue.enqueue { finishClose(owned) }", runtime)
        self.assertIn("private fun beginClose(): ProductionRuntimeCloseOwnership? = lock.withLock {", runtime)
        self.assertIn("private fun finishClose(", runtime)
        finish = runtime.index("private fun finishClose(")
        self.assertIn("runBlocking { owned.runtime?.closeAndJoin() }", runtime[finish:])

    def test_close_fences_mode_clear_and_serialized_commit_to_the_current_owner(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        command_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/VpnServiceCommandOwner.kt"
        )
        # Finding 1: `close()` no longer waits for the confinement, so an in-flight commit can now
        # race the latch. The bounded-engine replacement must abort on the superseded/closed
        # generation instead of asserting, and must release a freshly allocated engine when the
        # close won.
        self.assertIn(
            "if (!started || closed) return",
            runtime,
        )
        self.assertIn(
            "if (!engineQueue.isEstablishAllowed(operationGeneration)) {\n"
            "            runCatching { next.close() }\n"
            "            return\n"
            "        }",
            runtime,
        )
        # Finding 2: `finishClose` no longer publishes NONE unconditionally; it asks the command
        # owner to clear only when the runtime being closed is still its current owner, so a
        # superseded candidate's `finally { candidate.close() }` cannot clobber the replacement's
        # DNS_ONLY/CHAINED mode.
        finish = runtime.index("private fun finishClose(")
        close_body = runtime[finish : runtime.index("private fun publishProtection(", finish)]
        self.assertIn("request.clearChainedActivityWhenCurrent(request.owner)", close_body)
        self.assertNotIn("publishChainedActivity(false, ChainedRuntimeMode.NONE)", close_body)
        self.assertIn(
            "val clearChainedActivityWhenCurrent: (VpnServiceOwner) -> Unit = {},",
            command_owner,
        )
        self.assertIn(
            "clearChainedActivityWhenCurrent = { targetOwner ->\n"
            "                        synchronized(this@VpnServiceCommandOwner) {\n"
            "                            if (!closed && currentOwner === targetOwner) {\n"
            "                                clearChainedActivity()\n"
            "                            }\n"
            "                        }\n"
            "                    },",
            command_owner,
        )
        # The accepted terminal retirement still clears under the monitor (the stop path relies on
        # the fenced close callback, which shares the same current-owner check).
        self.assertIn("clearChainedActivity()", command_owner)
        # A failed start nulls `currentOwner` before the candidate's `close()` runs, so its fenced
        # callback would be a no-op; `fail` must clear any mode the failed/replaced runtime published
        # itself, under the monitor.
        self.assertIn(
            "if (currentOwner === owner) {\n"
            "            currentOwner = null\n",
            command_owner,
        )

    def test_rebind_confirmation_requires_post_swap_peer_evidence(self) -> None:
        driver = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/ChainedOutageDriver.kt"
        )
        engine_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/ChainedEngineOwner.kt"
        )
        # Finding 4020704482: an idle established session reads `hasHandshake` true with no
        # unanswered send whether or not the replacement socket ever carried a byte, so the old
        # handshake-age test cleared the confirmation on the first tick after a swap and a dead
        # replacement bypassed the 3 s fallback. Confirmation now requires an AUTHENTICATED peer
        # datagram newer than the swap (the sample is read-and-clear and the driver drains it
        # immediately before the swap), the Android form of the iOS
        # `sawAuthenticatedPeerDatagram`.
        self.assertIn("val sawAuthenticatedPeerDatagram: Boolean = false,", driver)
        self.assertIn("if (sample.sawAuthenticatedPeerDatagram) {", driver)
        self.assertNotIn("if (sample.hasHandshake) {", driver)
        self.assertIn("livenessSawAuthenticatedPeerDatagram = true", engine_owner)
        self.assertIn(
            "sawAuthenticatedPeerDatagram = livenessSawAuthenticatedPeerDatagram,",
            engine_owner,
        )
        self.assertIn("livenessSawAuthenticatedPeerDatagram = false", engine_owner)

    def test_default_network_callback_only_reports_a_real_identity_change(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        gates = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/ChainedLifecycleGates.kt"
        )
        tun_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/ChainedTunRuntimeOwner.kt"
        )
        # Finding 4020704485: `registerDefaultNetworkCallback` immediately reports the
        # already-current default network through `onAvailable`; treating that initial snapshot as
        # an interface change drove an immediate rebind, and before the first handshake
        # `adoptChannel` returned NO_CURRENT_SESSION and the driver shut the fresh session into the
        # retry ladder. Only a different `Network` identity is a change, the Android form of the
        # iOS `ChainedEligibleInterfaceStash`.
        self.assertIn("val identity = ChainedDefaultNetworkIdentity(establishmentNetwork)", runtime)
        self.assertIn(
            "override fun onAvailable(network: Network) = callback(true, identity.observed(network))",
            runtime,
        )
        self.assertIn("identity.observed(network)", runtime)
        self.assertNotIn(
            "override fun onAvailable(network: Network) = callback(true, true)",
            runtime,
        )
        self.assertIn("internal class ChainedDefaultNetworkIdentity", gates)
        self.assertIn("fun observed(identity: Any?): Boolean", gates)
        # Finding 4021623299: the tracker must be seeded with the underlay the chained socket was
        # established on. An empty tracker treats the callback's first snapshot as the baseline, so
        # a default-network change landing between the socket bind and that first delivery reports
        # no change, skips the rebind, and pins the peer socket to the stale network. The owner
        # exposes the captured underlay and the runtime seeds the tracker with it.
        self.assertIn("internal class ChainedDefaultNetworkIdentity(initial: Any? = null) {", gates)
        self.assertIn("private var known: Any? = initial", gates)
        self.assertIn("val underlayNetwork: Network,", tun_owner)
        self.assertIn("val underlay = currentNetwork() ?: network", tun_owner)
        self.assertIn("armChainedPathRegistration(owner)", runtime)
        registration = self.chained_path_registration_body(runtime)
        self.assertIn(
            "observeDefaultNetworkPath(owner.underlayNetwork) { satisfied, interfacesChanged ->",
            registration,
        )
        # A queued callback from this registration cannot rebind a successor or a
        # closed/superseded runtime. Seeding still uses this exact owner's underlay.
        self.assertIn(
            "val admitted = lock.withLock { !closed && !superseded && chainedOwners.currentOrNull() === owner }\n"
            "                if (admitted) owner.pathChanged(satisfied, interfacesChanged)",
            registration,
        )
        # A handoff can deliver the replacement's satisfied callbacks before the old default's
        # `onLost`; the tracker holds the replacement, so a stale loss must not be reported as an
        # unsatisfied path (which would quiesce a live session). `onLost` is gated on the tracked
        # identity, and the tracker exposes a non-mutating `isCurrent`.
        self.assertIn("fun isCurrent(identity: Any?): Boolean", gates)
        self.assertIn("if (identity.isCurrent(network)) callback(false, false)", runtime)
        self.assertNotIn(
            "override fun onLost(network: Network) = callback(false, false)",
            runtime,
        )

    def test_a_superseded_runtime_rejects_future_lifecycle_establishment(self) -> None:
        runtime = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt"
        )
        # Finding 4021176694: the engine-queue generation bump alone is bypassable — a later
        # `ChainedEngineQueue.run()` mints a fresh valid token and the recovery paths reject only
        # `closed` — so a queued predecessor reconnect/awaiting-network retry/termination recovery
        # could still `establish()` a TUN over the successor's just-adopted interface. The
        # monotonic latch is checked by every lifecycle entry point before any work, while the
        # already-installed tunnel is left untouched for make-before-break.
        self.assertIn("private var superseded = false", runtime)
        self.assertIn(
            "lock.withLock {\n            superseded = true\n            engineQueue.supersede()\n        }",
            runtime,
        )
        self.assertIn(
            "private fun lifecycleAdmitted(): Boolean = lock.withLock { !superseded && !closed }",
            runtime,
        )
        # Count only call SITES: the declaration also contains the token, so a
        # `count(...) >= 6` bound let one entry point lose its guard (5 sites + declaration = 6)
        # while still passing, contradicting the every-entry-point invariant. Pin all seven
        # standalone guards, including the DNS-observation consumer.
        self.assertEqual(7, runtime.count("if (!lifecycleAdmitted())"))
        observe = runtime.index("private fun observeRunningDnsNetwork(")
        consumer_guard = runtime.index("if (!lifecycleAdmitted()) break", observe)
        queue = runtime.index("engineQueue.run refresh@", observe)
        refresh_guard = runtime.index(
            "if (!lifecycleAdmitted() || !engineQueue.isEstablishAllowed(generation)) return@refresh",
            queue,
        )
        snapshot = runtime.index("val next = observation.snapshot()", queue)
        self.assertLess(consumer_guard, queue)
        self.assertLess(refresh_guard, snapshot)
        # The reconnect guard must precede the teardown, or a queued reconnect would close the
        # retained tunnel the successor is about to replace.
        perform = runtime.index("private fun performReconnectSerialized(")
        guard = runtime.index("if (!lifecycleAdmitted()) return", perform)
        teardown = runtime.index("closeChainedForRebuild()", perform)
        self.assertLess(guard, teardown)

    def test_clean_session_stop_retires_only_its_own_witness(self) -> None:
        credentials = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/ChainedVpnCredentials.kt"
        )
        tun_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/ChainedTunRuntimeOwner.kt"
        )
        store = self.read(
            "android/platform-contracts/src/main/kotlin/com/lavasec/app/platform/"
            "ChainedDeviceEligibilityStore.kt"
        )
        backoff = self.read(
            "android/platform-contracts/src/main/kotlin/com/lavasec/app/platform/"
            "ChainedMemoryKillBackoff.kt"
        )
        graphs = self.read("android/app/src/main/kotlin/com/lavasec/app/ProductionApplicationGraphs.kt")
        # Finding 4021176701: closing a predecessor unconditionally cleared the store's single
        # witness slot even when a make-before-break candidate had recorded a newer one, leaving a
        # live chained runtime unattributable. The clean stop now carries the owning start epoch
        # and the store clears only that EXACT witness — the Android form of the iOS
        # `settleTermination(owningLifecycleID:)` fence.
        self.assertIn(
            "fun markChainedSessionStoppedCleanly(nowEpochMillis: Long, owningStartEpochMillis: Long)",
            credentials,
        )
        self.assertIn("owningStartEpochMillis: Long,", store)
        self.assertIn(
            "activeChainedSessionStartEpochMillis =\n"
            "                record.activeChainedSessionStartEpochMillis.takeIf { preserve },",
            backoff,
        )
        # Finding 4021942644 plus the rebase-orphan follow-up: the fence matches the EXACT ownership
        # token, never wall-clock ordering AND never the rebasable correlation timestamp. An
        # ordering predicate (`it > owningStart`) drops a successor witness stamped BELOW the
        # predecessor's by a backward clock move; matching the correlation timestamp orphans the
        # owner's own witness after a corrected-past read rebased it (the runtime writes the token,
        # the reconciler rebases only the timestamp).
        # Finding 4048065706: a fallback to the rebased correlation timestamp cannot be correct, so
        # a legacy record without a token is preserved rather than matched against a value the owner
        # never wrote.
        self.assertNotIn("?: record.activeChainedSessionStartEpochMillis", backoff)
        self.assertIn(
            "val preserve = record.activeChainedSessionOwnerToken != owningStartEpochMillis",
            backoff,
        )
        self.assertIn(
            "activeChainedSessionOwnerToken =\n"
            "                record.activeChainedSessionOwnerToken.takeIf { preserve },",
            backoff,
        )
        self.assertNotIn("it > owningStartEpochMillis", backoff)
        self.assertIn("witnessStartEpochMillis = checkNotNull(witnessStartEpochMillis.get())", tun_owner)
        self.assertIn("owningStartEpochMillis,", graphs)
        # Finding 4021284983: closing the owner while a replacement is inside the synchronous,
        # non-cancellable `sessionFactory.create()` must not retire the witness (a low-memory kill
        # from the remaining ~50 MB work would go unattributed) or zeroize the key arrays the
        # factory still holds by reference. The clean stop defers both until every started build on
        # the off-queue `sessionBuilds` scope has returned.
        self.assertIn("ChainedBuildSettlement.run(", tun_owner)
        self.assertIn("awaitBuilds = { buildsJob?.join() }", tun_owner)
        self.assertIn("retireWitness = ::retireWitness,", tun_owner)
        self.assertIn("releaseSecrets = credentials::close,", tun_owner)

    def test_chained_review_round_peer_socket_and_underlay_resolution(self) -> None:
        tun_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedTunRuntimeOwner.kt"
        )
        # Finding 4021942636: `Network.bindSocket` throwing must not leak the `DatagramSocket`.
        # Retain the candidate outside the BIND try (so the catch can close it) while guarding the
        # CONSTRUCTOR in its own try: a `DatagramSocket()` failure (fd exhaustion — the very failure
        # this guards) must travel the refusal funnel as `null`, not escape and leak the opened key
        # arrays / a just-allocated session while triaging as a permanent surrender.
        self.assertIn("val candidate = try {", tun_owner)
        self.assertIn("DatagramSocket()", tun_owner)
        self.assertIn("runCatching { candidate.close() }", tun_owner)
        self.assertNotIn("DatagramSocket().also { candidate ->", tun_owner)
        # Finding 4021942640: if the selected underlay differs from the network the preflight
        # resolved on, re-resolve the endpoint on the underlay the socket actually binds to.
        self.assertIn("val underlay = currentNetwork() ?: network", tun_owner)
        self.assertIn("if (underlay == network) {", tun_owner)
        self.assertIn("network = underlay,", tun_owner)

    def test_rebind_channel_build_runs_off_the_engine_queue(self) -> None:
        driver = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/ChainedOutageDriver.kt"
        )
        tun_owner = self.read(
            "android/vpn/src/main/kotlin/com/lavasec/app/vpn/chained/"
            "ChainedTunRuntimeOwner.kt"
        )
        # Finding 4022018493: `makeChannel` starts the off-queue build and reports through an
        # ownership-fenced delivery, so a blocking `Network.getAllByName` never holds the one
        # serial engine queue while the full-route TUN is pinned to the dead path.
        self.assertIn("fun makeChannel(delivery: ChainedChannelDelivery): Boolean", driver)
        self.assertIn("internal interface ChainedChannelDelivery", driver)
        self.assertIn("fun adopt(channel: ChainedUpstreamChannel): Boolean", driver)
        self.assertIn("fun failed()", driver)
        self.assertIn("override fun makeChannel(delivery: ChainedChannelDelivery): Boolean", tun_owner)
        # The production build runs on the off-queue `sessionBuilds` scope, not inline.
        make_channel = tun_owner.index("override fun makeChannel(delivery: ChainedChannelDelivery)")
        launch = tun_owner.index("sessionBuilds.launch {", make_channel)
        self.assertLess(make_channel, launch)
        self.assertIn("delivery.adopt(channel)", tun_owner)
        # The fence: a channel delivered after a newer rebind/outage/suspension is refused and
        # the source closes it. The delivery hops back onto the engine queue (`runSerial`), since
        # the production build runs off-queue.
        self.assertIn("if (pendingRebind != rebindSerial) return@runSerial false", driver)
        self.assertIn("engineQueue.runSerial {", driver)
        self.assertIn("runCatching { channel.close() }", tun_owner)
        # Finding 4048065691: the ladder build selects its underlay off-queue, so a transition that
        # lands between that selection and adoption leaves the fresh socket on the pre-transition
        # path. Adoption compares the path epoch stamped when the build started and clears the
        # coalesced recovery debt only while it still matches.
        self.assertIn("private var pathEpoch: Long = 0L", driver)
        self.assertIn("pathEpoch += 1L", driver)
        self.assertIn("val buildPathEpoch = pathEpoch", driver)
        self.assertIn("if (pathEpoch == buildPathEpoch) {", driver)
        self.assertIn("pendingSettledRecovery = false", driver)

    def test_verifier_rejects_stale_namespace_in_a_fixture(self) -> None:
        verifier = ROOT / "scripts" / "verify-android-foundation"
        self.assertTrue(verifier.is_file(), f"missing {verifier}")
        stale_namespace = "app." + "lavasecurity.android"
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory)
            stale = fixture / "android" / "app" / "build.gradle.kts"
            stale.parent.mkdir(parents=True)
            stale.write_text(f'namespace = "{stale_namespace}"\n')
            result = subprocess.run(
                [str(verifier), "--root", str(fixture), "--stale-only"],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
        self.assertNotEqual(0, result.returncode)
        self.assertIn(stale_namespace, result.stderr)

    def test_settings_declares_only_the_canonical_modules(self) -> None:
        settings = self.read("android/settings.gradle.kts")
        includes = {
            item.strip().strip('"')
            for line in settings.splitlines()
            if line.strip().startswith("include(")
            for item in line.strip()[len("include(") : -1].split(",")
        }
        self.assertEqual(
            {
                ":app",
                ":foundation",
                ":product",
                ":platform-contracts",
                ":vpn",
                ":presentation",
                ":design-system",
                ":test-support",
            },
            includes,
        )

    def test_settings_uses_only_google_and_maven_central_repositories(self) -> None:
        settings = self.read("android/settings.gradle.kts")
        self.assertNotIn("gradlePluginPortal", settings)
        self.assertEqual(2, settings.count("google()"))
        self.assertEqual(2, settings.count("mavenCentral()"))

    def test_verifier_rejects_noncanonical_module_set(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            settings = fixture / "android" / "settings.gradle.kts"
            settings.write_text(settings.read_text() + '\ninclude(":unexpected")\n')
            result = self.run_verifier(fixture)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("exactly the canonical modules", result.stderr)

    def test_verifier_rejects_every_presentation_product_dependency_form(self) -> None:
        relative = "android/presentation/build.gradle.kts"
        mutations = {
            "direct": '\nimplementation(project(":product"))\n',
            "multiline": (
                "\nimplementation(\n"
                "    project(\n"
                '        ":product",\n'
                "    ),\n"
                ")\n"
            ),
            "add-form": (
                "\nadd(\n"
                '    "implementation",\n'
                "    project(\n"
                '        ":product",\n'
                "    ),\n"
                ")\n"
            ),
            "alias": (
                '\nval target = ":product"\n'
                "implementation(project(target))\n"
            ),
            "interpolation": (
                '\nval module = "product"\n'
                'implementation(project(":$module"))\n'
            ),
            "map": (
                '\nimplementation(dependencies.project(mapOf("path" to ":product")))\n'
            ),
            "path-expression": (
                '\nval paths = mapOf("activity" to ":product")\n'
                'implementation(project(paths.getValue("activity")))\n'
            ),
        }

        for name, payload in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                path = fixture / relative
                source = path.read_text(encoding="utf-8")
                path.write_text(
                    source.replace("dependencies {\n", "dependencies {\n" + payload, 1),
                    encoding="utf-8",
                )
                result = self.run_verifier(fixture)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("presentation product dependency", result.stderr)

    def test_verifier_accepts_literal_presentation_project_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_verifier(self.verifier_fixture(directory))

        self.assertEqual(0, result.returncode, result.stderr)

    def test_nonliteral_presentation_project_mutations_compile_then_fail_closed(self) -> None:
        payload = (
            '\nval target = ":product"\n'
            'val module = "product"\n'
            'val paths = mapOf("activity" to ":product")\n'
            "implementation(project(target))\n"
            'implementation(project(":$module"))\n'
            'implementation(dependencies.project(mapOf("path" to ":product")))\n'
            'implementation(project(paths.getValue("activity")))\n'
        )
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            path = fixture / "android/presentation/build.gradle.kts"
            source = path.read_text(encoding="utf-8")
            path.write_text(
                source.replace("dependencies {\n", "dependencies {\n" + payload, 1),
                encoding="utf-8",
            )

            configured = self.run_presentation_dependency_configuration(fixture)
            verified = self.run_verifier(fixture)

        self.assertEqual(0, configured.returncode, configured.stderr)
        self.assertNotEqual(0, verified.returncode)
        self.assertIn("presentation product dependency", verified.stderr)

    def test_project_function_reference_mutation_compiles_then_fails_closed(self) -> None:
        payload = (
            "\nval forbiddenProject: (Map<String, *>) -> Any = dependencies::project\n"
            'implementation(forbiddenProject(mapOf("path" to ":product")))\n'
        )
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            path = fixture / "android/presentation/build.gradle.kts"
            source = path.read_text(encoding="utf-8")
            path.write_text(
                source.replace("dependencies {\n", "dependencies {\n" + payload, 1),
                encoding="utf-8",
            )

            configured = self.run_presentation_dependency_configuration(fixture)
            verified = self.run_verifier(fixture)

        self.assertEqual(0, configured.returncode, configured.stderr)
        self.assertNotEqual(0, verified.returncode)
        self.assertIn("presentation product dependency", verified.stderr)

    def test_root_project_lookup_mutation_compiles_then_fails_closed(self) -> None:
        payload = '\nimplementation(rootProject.findProject(":product")!!)\n'
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            path = fixture / "android/presentation/build.gradle.kts"
            source = path.read_text(encoding="utf-8")
            path.write_text(
                source.replace("dependencies {\n", "dependencies {\n" + payload, 1),
                encoding="utf-8",
            )

            configured = self.run_presentation_dependency_configuration(fixture)
            verified = self.run_verifier(fixture)

        self.assertEqual(0, configured.returncode, configured.stderr)
        self.assertNotEqual(0, verified.returncode)
        self.assertIn("presentation product dependency", verified.stderr)

    def test_verifier_rejects_production_Activity_runtime_composition_identifiers(self) -> None:
        foundation = (
            "android/foundation/src/main/kotlin/com/lavasec/app/foundation/FoundationMarker.kt"
        )
        main_activity = (
            "android/app/src/main/kotlin/com/lavasec/app/MainActivity.kt"
        )
        mutations = {
            "product-port-outside-product": (
                foundation,
                "object FoundationMarker",
                "private val port: ProductActivityPort? = null\n\nobject FoundationMarker",
            ),
            "product-state-machine-outside-product": (
                foundation,
                "object FoundationMarker",
                "private val machine: ProductActivityStateMachine? = null\n\n"
                "object FoundationMarker",
            ),
            "native-host-in-MainActivity": (
                main_activity,
                "class MainActivity",
                "private val forbiddenHost: NativeDomainActionHost? = null\n\n"
                "class MainActivity",
            ),
            "coordinator-in-MainActivity": (
                main_activity,
                "class MainActivity",
                "private val forbiddenCoordinator: DomainActionCoordinator? = null\n\n"
                "class MainActivity",
            ),
        }

        for name, (relative, old, new) in mutations.items():
            with self.subTest(name=name):
                self.assert_verifier_rejects(
                    relative,
                    old,
                    new,
                    "Activity runtime composition",
                )

    def test_verifier_ignores_Activity_runtime_names_in_comments_and_literal_prose(self) -> None:
        relative = (
            "android/foundation/src/main/kotlin/com/lavasec/app/foundation/"
            "FoundationMarker.kt"
        )
        old = "object FoundationMarker"
        new = (
            "// ProductActivityPort ProductActivityStateMachine NativeDomainActionHost "
            "DomainActionCoordinator\n"
            'private const val ACTIVITY_PROSE = "ProductActivityPort '
            'ProductActivityStateMachine NativeDomainActionHost DomainActionCoordinator"\n\n'
            "object FoundationMarker"
        )

        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            self.mutate(fixture, relative, old, new)
            result = self.run_verifier(fixture)

        self.assertEqual(0, result.returncode, result.stderr)

    def test_verifier_requires_the_generation_bound_MainActivity_host(self) -> None:
        relative = "android/app/src/main/kotlin/com/lavasec/app/MainActivity.kt"
        mutations = {
            "static-activity": (
                "activityState = product.activity",
                "activityState = ActivityUiState.unavailable",
            ),
            "static-guard": (
                "guardHealth = protection.guard",
                "guardHealth = GuardProtectionHealthUi.inactive",
            ),
            "missing-activity-callback": (
                "activityQueryCallbacks = activityCallbacks",
                "activityQueryCallbacks = null",
            ),
            "missing-domain-review": (
                "domainReviewCallbacks = reviewCallbacks",
                "domainReviewCallbacks = null",
            ),
            "missing-settings-owner": (
                "privacyDataCallbacks = privacyDataCallbacks",
                "privacyDataCallbacks = null",
            ),
        }
        for name, (old, new) in mutations.items():
            with self.subTest(name=name):
                self.assert_verifier_rejects(
                    relative,
                    old,
                    new,
                    "MainActivity production host",
                )

    def test_verifier_requires_the_exact_generation_owner_lifecycle(self) -> None:
        relative = "android/app/src/main/kotlin/com/lavasec/app/MainProductViewModel.kt"
        mutations = {
            "no-latest-generation": ("factories.collectLatest", "factories.collect"),
            "no-owner-close": ("created.close()", "Unit"),
            "no-activity-state": ("created.activity?.state", "flowOf(null)"),
            "no-privacy-state": ("created.privacy?.state", "flowOf(null)"),
        }
        for name, (old, new) in mutations.items():
            with self.subTest(name=name):
                self.assert_verifier_rejects(
                    relative,
                    old,
                    new,
                    "production owner contract",
                )

    def test_verifier_requires_exact_protection_activity_evidence(self) -> None:
        mutations = {
            "no-abandoned-recovery": (
                "android/app/src/main/kotlin/com/lavasec/app/ProductionApplicationGraphs.kt",
                "database.recoverAbandonedProtectionSessions()",
                "Unit",
            ),
            "no-state-boundary": (
                "android/app/src/main/kotlin/com/lavasec/app/data/"
                "CredentialProtectionActivitySink.kt",
                "writer.recordProtectionState(",
                "writer.hashCode().let(",
            ),
            "no-heartbeat": (
                "android/app/src/main/kotlin/com/lavasec/app/data/"
                "CredentialProtectionActivitySink.kt",
                "writer.recordProtectionHeartbeat(",
                "writer.hashCode().let(",
            ),
            "no-engine-fence": (
                "android/vpn/src/main/kotlin/com/lavasec/app/vpn/VpnServiceContracts.kt",
                "evidence.engineGeneration == currentEngineGeneration",
                "evidence.engineGeneration > 0L",
            ),
            "no-reconnect-serialization": (
                "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt",
                "ChainedEngineQueue()",
                "Any()",
            ),
            "no-terminal-publication": (
                "android/vpn/src/main/kotlin/com/lavasec/app/vpn/ProductionVpnOwnedRuntime.kt",
                "request.terminalPublication.publish(",
                "runCatching(",
            ),
            "notification-write-disables-owner": (
                "android/app/src/main/kotlin/com/lavasec/app/notification/"
                "CredentialConnectivityNotificationOwner.kt",
                "if (historyStore.write(next)) history = next",
                "history = if (historyStore.write(next)) next else null",
            ),
            "no-onboarding-precondition": (
                "android/app/src/main/kotlin/com/lavasec/app/data/"
                "CredentialConfigurationRepository.kt",
                "if (requireCompletedOnboarding)",
                "if (false)",
            ),
            "cross-host-redirect": (
                "android/app/src/main/kotlin/com/lavasec/app/data/SeededSourceCache.kt",
                "fail(SeededSourceCacheFailure.REDIRECT_HOST_MISMATCH)",
                "fail(SeededSourceCacheFailure.HTTPS_REQUIRED)",
            ),
            "single-slot-navigation": (
                "android/app/src/main/kotlin/com/lavasec/app/MainProductViewModel.kt",
                "ArrayDeque<ProductNavigation>()",
                "AtomicReference<ProductNavigation?>()",
            ),
            "no-direct-boot-host": (
                "android/app/src/main/kotlin/com/lavasec/app/ProductionApplicationGraphs.kt",
                "override val vpnHost: ProductionDeviceVpnHost?",
                "val ignoredHost: ProductionDeviceVpnHost?",
            ),
            "no-device-host-registration": (
                "android/app/src/main/kotlin/com/lavasec/app/"
                "ApplicationGraphLifecycleOwner.kt",
                "attachDeviceHostIfAvailable()",
                "Unit",
            ),
            "no-unlock-off-retirement": (
                "android/app/src/main/kotlin/com/lavasec/app/LavasecApplication.kt",
                "retireProtection = vpn::stop",
                "retireProtection = {}",
            ),
            "direct-boot-host-registration-races-service": (
                "android/app/src/main/kotlin/com/lavasec/app/LavasecApplication.kt",
                "runBlocking(Dispatchers.IO) { graphs.reconcile(userUnlocked = false) }",
                "reconcileGraphs(userUnlocked = false)",
            ),
            "coherent-direct-boot-mirror-is-always-republished": (
                "android/app/src/main/kotlin/com/lavasec/app/ProductionApplicationGraphs.kt",
                "return !mirror.state.matches(persisted) || mirror.artifact != credentialArtifact",
                "return true",
            ),
            "credential-graph-verifies-direct-boot-artifact-off-io": (
                "android/app/src/main/kotlin/com/lavasec/app/ProductionApplicationGraphs.kt",
                "withContext(Dispatchers.IO) {\n"
                "                    readVerifiedDeviceProtectedSnapshotOrNull(",
                "withContext(Dispatchers.Default) {\n"
                "                    readVerifiedDeviceProtectedSnapshotOrNull(",
            ),
            "direct-boot-mirror-ignores-credential-artifact": (
                "android/app/src/main/kotlin/com/lavasec/app/ProductionApplicationGraphs.kt",
                "return !mirror.state.matches(persisted) || mirror.artifact != credentialArtifact",
                "return !mirror.state.matches(persisted)",
            ),
            "no-network-availability-owner": (
                "android/vpn/src/main/kotlin/com/lavasec/app/vpn/"
                "ProductionVpnOwnedRuntime.kt",
                "awaitingNetwork = true",
                "awaitingNetwork = false",
            ),
            "pause-expiry-clears-newer-pause": (
                "android/vpn/src/main/kotlin/com/lavasec/app/vpn/"
                "ProductionVpnOwnedRuntime.kt",
                "requestResume(expectedPause = pause)",
                "requestResume()",
            ),
            "pause-expiry-trusts-one-shot-clock": (
                "android/vpn/src/main/kotlin/com/lavasec/app/vpn/"
                "ProductionVpnOwnedRuntime.kt",
                "delay(minOf(pause.deadlineMillis - now, pauseClockRecheckMillis))",
                "delay(pause.deadlineMillis - now)",
            ),
            "privacy-opt-in-before-durable-commit": (
                "android/app/src/main/kotlin/com/lavasec/app/product/privacy/"
                "LocalPrivacyServices.kt",
                "persistConfiguration(settings)\n"
                "                durableCommitted = true\n"
                "                applyCommittedLoggingPolicy(settings)",
                "applyCommittedLoggingPolicy(settings)\n"
                "                persistConfiguration(settings)\n"
                "                durableCommitted = true",
            ),
            "privacy-network-activity-opt-in-before-durable-commit": (
                "android/app/src/main/kotlin/com/lavasec/app/product/privacy/"
                "LocalPrivacyServices.kt",
                "current.keepNetworkActivity && settings.keepNetworkActivity",
                "settings.keepNetworkActivity",
            ),
            "privacy-post-commit-policy-is-not-retried": (
                "android/app/src/main/kotlin/com/lavasec/app/product/privacy/"
                "LocalPrivacyServices.kt",
                "repeat(POST_COMMIT_POLICY_ATTEMPTS)",
                "repeat(1)",
            ),
            "privacy-policy-is-not-reconciled-on-graph-open": (
                "android/app/src/main/kotlin/com/lavasec/app/ProductionApplicationGraphs.kt",
                "activityWriter.applyLoggingPolicy(initialState.logging)",
                "activityWriter.hashCode()",
            ),
            "privacy-post-commit-failure-does-not-rotate-graph": (
                "android/app/src/main/kotlin/com/lavasec/app/MainProductViewModel.kt",
                "lastPrivacyReconciliationGeneration.getAndSet(current.graphGeneration) !=\n"
                "            current.graphGeneration",
                "current.graphGeneration !=\n"
                "            current.graphGeneration",
            ),
            "stale-privacy-reconciliation-rotates-successor": (
                "android/app/src/main/kotlin/com/lavasec/app/MainProductViewModel.kt",
                "owners.get() === current &&\n"
                "            privacy.state.value.lastMutation is",
                "owners.get() !== current &&\n"
                "            privacy.state.value.lastMutation is",
            ),
            "privacy-reconciliation-drops-owner-generation": (
                "android/app/src/main/kotlin/com/lavasec/app/MainProductViewModel.kt",
                "lastPrivacyReconciliationGeneration.getAndSet(current.graphGeneration) !=\n"
                "            current.graphGeneration\n        ) {\n"
                "            rotateCredentialGraph(current.graphGeneration)",
                "lastPrivacyReconciliationGeneration.getAndSet(current.graphGeneration) !=\n"
                "            current.graphGeneration\n        ) {\n"
                "            rotateCredentialGraph(0L)",
            ),
            "application-drops-expected-rotation-generation": (
                "android/app/src/main/kotlin/com/lavasec/app/LavasecApplication.kt",
                "graphs.rotateCredentialGraph(expectedGeneration)",
                "graphs.rotateCredentialGraph(0L)",
            ),
            "stale-rotation-retires-installed-successor": (
                "android/app/src/main/kotlin/com/lavasec/app/"
                "ApplicationGraphLifecycleOwner.kt",
                "if (credentialGraph?.graphGeneration != expectedGeneration) return@withLock",
                "if (false) return@withLock",
            ),
            "privacy-post-commit-failure-loses-durable-authority": (
                "android/app/src/main/kotlin/com/lavasec/app/product/privacy/"
                "LocalPrivacyServices.kt",
                "if (durableCommitted) {\n"
                "                PrivacyMutationResult.CommittedReconciliationRequired(message)",
                "if (false) {\n"
                "                PrivacyMutationResult.CommittedReconciliationRequired(message)",
            ),
            "runtime-unowned-during-start": (
                "android/vpn/src/main/kotlin/com/lavasec/app/vpn/"
                "VpnServiceCommandOwner.kt",
                # Adoption must publish `runtime = candidate` after the candidate is live and
                # before the predecessor is retired; removing it leaves the runtime unowned (and a
                # racing successor's predecessor would be the in-flight candidate) and the verifier
                # rejects it.
                "runtime = candidate\n                            pending = null",
                "pending = null",
            ),
            "awaiting-network-terminal-under-reconnect-lock": (
                "android/vpn/src/main/kotlin/com/lavasec/app/vpn/"
                "ProductionVpnOwnedRuntime.kt",
                "val terminal = engineQueue.run {",
                "engineQueue.run {",
            ),
            "terminal-network-registration-leak": (
                "android/vpn/src/main/kotlin/com/lavasec/app/vpn/"
                "ProductionVpnOwnedRuntime.kt",
                "networkRegistration = networkRegistration.also { networkRegistration = null }",
                "networkRegistration = null",
            ),
            "device-artifact-read-on-service-thread": (
                "android/app/src/main/kotlin/com/lavasec/app/ProductionApplicationGraphs.kt",
                "runBlocking(Dispatchers.IO) {\n            readVerifiedDeviceProtectedSnapshotOrNull",
                "readVerifiedDeviceProtectedSnapshotOrNull",
            ),
            "cold-start-verifies-full-direct-boot-artifact": (
                "android/app/src/main/kotlin/com/lavasec/app/ProductionApplicationGraphs.kt",
                "DeviceProtectedConfigurationReader(",
                "readVerifiedDeviceProtectedSnapshotOrNull(",
            ),
            "credential-host-replaces-device-after-gap": (
                "android/app/src/main/kotlin/com/lavasec/app/"
                "ApplicationGraphLifecycleOwner.kt",
                "attachCredentialHost(graph.vpnHost)",
                "attachDeviceHostIfAvailable()",
            ),
            "device-host-restored-after-credential-detach": (
                "android/app/src/main/kotlin/com/lavasec/app/"
                "ApplicationGraphLifecycleOwner.kt",
                "if (!closed) attachDeviceHostForReplacement()\n"
                "        val registration = credentialRegistration",
                "val registration = credentialRegistration\n"
                "        if (!closed) attachDeviceHostForReplacement()",
            ),
        }
        for name, (relative, old, new) in mutations.items():
            with self.subTest(name=name):
                # `no-network-availability-owner` removes `awaitingNetwork = true`; the source has
                # several legitimate sites, so remove ALL of them to test the guard regardless of
                # how many the implementation carries (a single `replace` left the bound satisfied).
                self.assert_verifier_rejects(
                    relative,
                    old,
                    new,
                    "protection activity evidence contract",
                    replace_all=name == "no-network-availability-owner",
                )

    def test_verifier_rejects_top_level_synthetic_Activity_helper(self) -> None:
        relative = "android/app/src/main/kotlin/com/lavasec/app/MainActivity.kt"
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            path = fixture / relative
            path.write_text(
                path.read_text(encoding="utf-8")
                + "\nprivate fun suppliedActivityState(): ActivityUiState = ActivityUiState(\n"
                "    overview = com.lavasec.app.presentation.activity.ActivityContentState.Unavailable,\n"
                "    topDomains = com.lavasec.app.presentation.activity.ActivityContentState.Unavailable,\n"
                "    domainHistory = com.lavasec.app.presentation.activity.ActivityContentState.Unavailable,\n"
                "    retainedDomainLogs = com.lavasec.app.presentation.activity.ActivityContentState.Unavailable,\n"
                "    historyLoadingMore = false,\n"
                "    domainAction = com.lavasec.app.presentation.activity.DomainActionUiState.Idle,\n"
                "    domainActionNotice = null,\n"
                ")\n",
                encoding="utf-8",
            )
            result = self.run_verifier(fixture)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("synthetic Activity", result.stderr)

    def test_verifier_rejects_backticked_synthetic_root_shadow(self) -> None:
        source = """\
package com.lavasec.app

import com.lavasec.app.presentation.activity.ActivityContentState
import com.lavasec.app.presentation.activity.ActivityCountSnapshot
import com.lavasec.app.presentation.activity.ActivityDateRangeUi
import com.lavasec.app.presentation.activity.ActivityLogAvailabilityUi
import com.lavasec.app.presentation.activity.ActivityOverviewSnapshot
import com.lavasec.app.presentation.activity.ActivityUiState
import com.lavasec.app.presentation.activity.DomainActionUiState
import java.time.LocalDate

private val forgedState = `ActivityUiState`(
    overview = ActivityContentState.Ready(
        `ActivityOverviewSnapshot`(
            dateRange = `ActivityDateRangeUi`(
                start = LocalDate.of(2026, 8, 1),
                endInclusive = LocalDate.of(2026, 8, 1),
                today = LocalDate.of(2026, 8, 1),
                zoneId = "Asia/Tokyo",
            ),
            counts = `ActivityCountSnapshot`(1L, 0L),
            localProtectionUptimeMillis = 1L,
            retainedDomainLogs = ActivityLogAvailabilityUi.Empty,
        ),
    ),
    topDomains = ActivityContentState.Unavailable,
    domainHistory = ActivityContentState.Unavailable,
    retainedDomainLogs = ActivityContentState.Ready(false),
    historyLoadingMore = false,
    domainAction = DomainActionUiState.Idle,
    domainActionNotice = null,
)

internal object com {
    object lavasec {
        object app {
            object presentation {
                object activity {
                    object ActivityUiState {
                        val unavailable = forgedState
                    }
                }
            }
        }
    }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            path = (
                fixture
                / "android/app/src/main/kotlin/com/lavasec/app/ForgedQualifiedState.kt"
            )
            path.write_text(source, encoding="utf-8")

            verified = self.run_verifier(fixture)

        self.assertNotEqual(0, verified.returncode)
        self.assertIn("synthetic Activity", verified.stderr)

    def test_verifier_rejects_synthetic_Activity_values_in_MainActivity(self) -> None:
        relative = "android/app/src/main/kotlin/com/lavasec/app/MainActivity.kt"
        constructors = (
            "ActivityCountSnapshot(0L, 0L)",
            'TopDomainRowUi("synthetic.example", 1L)',
            (
                "DomainHistoryRowUi(1L, 1L, \"synthetic.example\", "
                "ActivityDecisionUi.Blocked, DomainHistoryReasonUi.Default)"
            ),
            "ActivityUiState(ActivityContentState.Unavailable, ActivityContentState.Unavailable)",
        )

        for constructor in constructors:
            with self.subTest(constructor=constructor):
                self.assert_verifier_rejects(
                    relative,
                    "class MainActivity : ComponentActivity() {",
                    "class MainActivity : ComponentActivity() {\n"
                    f"    private val syntheticActivity = {constructor}",
                    "synthetic Activity",
                )

    def test_verifier_rejects_Activity_grandchild_and_broadened_deep_links(self) -> None:
        relative = (
            "android/presentation/src/main/kotlin/com/lavasec/app/presentation/"
            "ProductNavigation.kt"
        )
        activity_route = (
            '        "activity" -> ProductNavigation.forRoute(ProductDestination.Activity)'
        )
        web_route = (
            '            "/app/activity" -> '
            "ProductNavigation.forRoute(ProductDestination.Activity)"
        )
        route_for_start = (
            "    private fun routeFor(route: String?): ProductNavigation? = when (route) {"
        )
        parse_web_start = "    private fun parseWeb(uri: URI): ProductNavigation? {"
        unknown_route = (
            '        "settings" -> ProductNavigation.forRoute(ProductDestination.Settings)\n'
            "        else -> null\n"
            "    }\n\n"
            "    private const val CUSTOM_SCHEME"
        )
        mutations = {
            "custom-top-domains": (
                activity_route,
                activity_route + "\n" +
                '        "top-domains" -> '
                "ProductNavigation.forRoute(ProductDestination.Activity)",
            ),
            "web-domain-history": (
                web_route,
                web_route + "\n" +
                '            "/app/activity/domain-history" -> '
                "ProductNavigation.forRoute(ProductDestination.Activity)",
            ),
            "prefix-broadening": (
                parse_web_start,
                parse_web_start + "\n"
                '        if (uri.rawPath.startsWith("/app/activity")) return '
                "ProductNavigation.forRoute(ProductDestination.Activity)",
            ),
            "contains-broadening": (
                route_for_start,
                "    private fun routeFor(route: String?): ProductNavigation? {\n"
                '        if (route?.contains("activity") == true) return '
                "ProductNavigation.forRoute(ProductDestination.Activity)\n"
                "        return when (route) {",
            ),
            "regex-broadening": (
                route_for_start,
                "    private fun routeFor(route: String?): ProductNavigation? {\n"
                '        if (route?.matches(Regex("activity.*")) == true) return '
                "ProductNavigation.forRoute(ProductDestination.Activity)\n"
                "        return when (route) {",
            ),
            "nonnull-unknown-route": (
                unknown_route,
                '        "settings" -> ProductNavigation.forRoute(ProductDestination.Settings)\n'
                "        else -> ProductNavigation.guard\n"
                "    }\n\n"
                "    private const val CUSTOM_SCHEME",
            ),
        }

        for name, (old, new) in mutations.items():
            with self.subTest(name=name):
                self.assert_verifier_rejects(
                    relative,
                    old,
                    new,
                    "Activity deep-link",
                )

    def test_verifier_rejects_library_namespace_or_plugin_drift(self) -> None:
        namespace_mutations = {
            "foundation": "com.lavasec.app.foundation",
            "product": "com.lavasec.app.product",
            "platform-contracts": "com.lavasec.app.platform",
            "vpn": "com.lavasec.app.vpn",
            "presentation": "com.lavasec.app.presentation",
            "design-system": "com.lavasec.app.designsystem",
            "test-support": "com.lavasec.app.testsupport",
        }
        mutations = {
            **{
                f"{module}-namespace": (
                    f"{module}/build.gradle.kts",
                    f'namespace = "{namespace}"',
                    f'namespace = "{namespace}.drifted"',
                    "namespace",
                )
                for module, namespace in namespace_mutations.items()
            },
            **{
                f"{module}-plugin": (
                    f"{module}/build.gradle.kts",
                    "alias(libs.plugins.android.library)",
                    "alias(libs.plugins.android.application)",
                    "plugin",
                )
                for module in namespace_mutations
            },
        }
        for name, (relative, old, new, expected_error) in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                path = fixture / "android" / relative
                path.write_text(path.read_text().replace(old, new))
                result = self.run_verifier(fixture)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected_error, result.stderr.lower())

    def test_verifier_rejects_duplicate_module_or_extra_root_plugins(self) -> None:
        mutations = {
            "duplicate-module-plugin": (
                "android/foundation/build.gradle.kts",
                "alias(libs.plugins.android.library)",
                "alias(libs.plugins.android.library)\n    alias(libs.plugins.android.library)",
            ),
            "extra-root-plugin": (
                "android/build.gradle.kts",
                "alias(libs.plugins.android.library) apply false",
                'alias(libs.plugins.android.library) apply false\n    id("unexpected.plugin")',
            ),
        }
        for name, (relative, old, new) in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                path = fixture / relative
                path.write_text(path.read_text().replace(old, new))
                result = self.run_verifier(fixture)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("plugin", result.stderr.lower())

    def test_verifier_rejects_manifest_identity_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            manifest = fixture / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
            manifest.write_text(
                manifest.read_text().replace(".LavasecApplication", ".DifferentApplication")
            )
            result = self.run_verifier(fixture)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("manifest application", result.stderr.lower())

    def test_verifier_requires_single_top_main_activity_launch_mode(self) -> None:
        android_namespace = "http://schemas.android.com/apk/res/android"
        launch_mode_attribute = f"{{{android_namespace}}}launchMode"
        relinquish_attribute = f"{{{android_namespace}}}relinquishTaskIdentity"
        expectations = {
            "canonical-single-top": ("singleTop", "true", 0),
            "missing-launch-mode": (None, "true", 1),
            "single-task-drift": ("singleTask", "true", 1),
            "missing-task-identity-handoff": ("singleTop", None, 1),
            "retained-validator-task-identity": ("singleTop", "false", 1),
        }

        for name, (launch_mode, relinquish, expected_status) in expectations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                manifest = fixture / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
                tree = ET.parse(manifest)
                activity = next(
                    (
                        item for item in tree.getroot().findall("./application/activity")
                        if item.get(f"{{{android_namespace}}}name") == ".MainActivity"
                    ),
                    None,
                )
                self.assertIsNotNone(activity)
                if launch_mode is None:
                    activity.attrib.pop(launch_mode_attribute, None)
                else:
                    activity.set(launch_mode_attribute, launch_mode)
                if relinquish is None:
                    activity.attrib.pop(relinquish_attribute, None)
                else:
                    activity.set(relinquish_attribute, relinquish)
                tree.write(manifest, encoding="utf-8", xml_declaration=True)

                result = self.run_verifier(fixture)

                self.assertEqual(expected_status, result.returncode, result.stderr)
                if expected_status:
                    self.assertIn("manifest activity", result.stderr.lower())

    def test_production_manifest_is_private_and_fail_closed(self) -> None:
        manifest = self.read("android/app/src/main/AndroidManifest.xml")
        for token in (
            'android:allowBackup="false"',
            'android:usesCleartextTraffic="false"',
            'android:exported="false"',
            'android:grantUriPermissions="true"',
            'android:launchMode="singleTop"',
        ):
            self.assertIn(token, manifest)
        paths = self.read("android/app/src/main/res/xml/local_log_export_paths.xml")
        self.assertIn('path="local-log-exports/"', paths)
        self.assertNotIn('path="."', paths)

    def test_verifier_rejects_private_app_surface_broadening(self) -> None:
        mutations = {
            "cleartext": (
                "android/app/src/main/AndroidManifest.xml",
                'android:usesCleartextTraffic="false"',
                'android:usesCleartextTraffic="true"',
            ),
            "exported-provider": (
                "android/app/src/main/AndroidManifest.xml",
                'android:name=".files.LocalLogExportFileProvider"\n'
                '            android:authorities="${applicationId}.local-log-export"\n'
                '            android:exported="false"',
                'android:name=".files.LocalLogExportFileProvider"\n'
                '            android:authorities="${applicationId}.local-log-export"\n'
                '            android:exported="true"',
            ),
            "broad-provider-path": (
                "android/app/src/main/res/xml/local_log_export_paths.xml",
                'path="local-log-exports/"',
                'path="."',
            ),
            "exported-card-provider": (
                "android/app/src/main/AndroidManifest.xml",
                'android:authorities="${applicationId}.filter-card"\n'
                '            android:exported="false"',
                'android:authorities="${applicationId}.filter-card"\n'
                '            android:exported="true"',
            ),
            "broad-card-path": (
                "android/app/src/main/res/xml/filter_card_paths.xml",
                'path="filter-share-cards/"',
                'path="."',
            ),
            "card-class-alias": (
                "android/app/src/main/AndroidManifest.xml",
                'android:name=".files.FilterShareCardFileProvider"',
                'android:name=".files.LocalLogExportFileProvider"',
            ),
            "card-authority-drift": (
                "android/app/src/main/AndroidManifest.xml",
                'android:authorities="${applicationId}.filter-card"',
                'android:authorities="${applicationId}.all-files"',
            ),
            "exported-startup-provider": (
                "android/app/src/main/AndroidManifest.xml",
                'android:authorities="${applicationId}.androidx-startup"\n'
                '            android:exported="false"',
                'android:authorities="${applicationId}.androidx-startup"\n'
                '            android:exported="true"',
            ),
            "extra-app-component": (
                "android/app/src/main/AndroidManifest.xml",
                "        <activity\n",
                '        <service android:name=".UnexpectedService" android:exported="false" />\n'
                "        <activity\n",
            ),
        }
        for name, (relative, old, new) in mutations.items():
            with self.subTest(name=name):
                self.assert_verifier_rejects(relative, old, new, "private production surface")

    def test_verifier_rejects_vpn_service_manifest_broadening(self) -> None:
        relative = "android/vpn/src/main/AndroidManifest.xml"
        mutations = {
            "missing-bind-permission": (
                'android:permission="android.permission.BIND_VPN_SERVICE"',
                'android:permission="android.permission.INTERNET"',
            ),
            "hidden-from-system": ('android:exported="true"', 'android:exported="false"'),
            "wrong-service-type": (
                'android:foregroundServiceType="systemExempted"',
                'android:foregroundServiceType="dataSync"',
            ),
            "not-direct-boot-aware": (
                'android:directBootAware="true"',
                'android:directBootAware="false"',
            ),
            "duplicate-permission": (
                '    <uses-permission android:name="android.permission.INTERNET" />',
                '    <uses-permission android:name="android.permission.INTERNET" />\n'
                '    <uses-permission android:name="android.permission.INTERNET" />',
            ),
            "extra-component": (
                "    </application>",
                '        <receiver android:name=".UnexpectedReceiver" android:exported="false" />\n'
                "    </application>",
            ),
        }
        for name, (old, new) in mutations.items():
            with self.subTest(name=name):
                self.assert_verifier_rejects(relative, old, new, "VPN service manifest")

    def test_verifier_rejects_managed_device_surface_only_in_production_declarations(self) -> None:
        mutations = {
            "bind-device-admin-permission": (
                "manifest",
                "<application",
                '<uses-permission android:name="android.permission.BIND_DEVICE_ADMIN" />\n    <application',
            ),
            "device-admin-receiver": (
                "kotlin",
                "class MainActivity",
                "class LavaAdmin : android.app.admin.DeviceAdminReceiver()\n\nclass MainActivity",
            ),
            "device-policy-manager": (
                "kotlin",
                "class MainActivity",
                "private val managedApi = android.app.admin.DevicePolicyManager::class\n\nclass MainActivity",
            ),
            "device-policy-controller": (
                "kotlin",
                "class MainActivity",
                "private class DevicePolicyController\n\nclass MainActivity",
            ),
            "dpc-token": (
                "kotlin",
                "class MainActivity",
                'private const val DPC_MODE = "dpc"\n\nclass MainActivity',
            ),
            "dpc-camel-case-token": (
                "kotlin",
                "class MainActivity",
                "private class DpcController\n\nclass MainActivity",
            ),
            "embedded-dpc-token": (
                "kotlin",
                "class MainActivity",
                "private class LavaDpcController\n\nclass MainActivity",
            ),
            "mdm-token": (
                "kotlin",
                "class MainActivity",
                'private const val MDM_MODE = "mdm"\n\nclass MainActivity',
            ),
            "mdm-camel-case-token": (
                "kotlin",
                "class MainActivity",
                "private class MdmController\n\nclass MainActivity",
            ),
            "embedded-mdm-token": (
                "kotlin",
                "class MainActivity",
                "private class LavaMdmController\n\nclass MainActivity",
            ),
            "managed-device-token": (
                "kotlin",
                "class MainActivity",
                "private class ManagedDeviceController\n\nclass MainActivity",
            ),
            "device-owner-token": (
                "kotlin",
                "class MainActivity",
                'private const val DEVICE_OWNER_MODE = "device_owner"\n\nclass MainActivity',
            ),
            "profile-owner-token": (
                "kotlin",
                "class MainActivity",
                'private const val PROFILE_OWNER_MODE = "profile_owner"\n\nclass MainActivity',
            ),
            "managed-device-provisioning": (
                "manifest",
                "<application",
                '<uses-permission android:name="android.app.action.PROVISION_MANAGED_DEVICE" />\n'
                "    <application",
            ),
            "managed-profile-provisioning": (
                "manifest",
                "<application",
                '<uses-permission android:name="android.app.action.PROVISION_MANAGED_PROFILE" />\n'
                "    <application",
            ),
            "managed-user-provisioning": (
                "manifest",
                "<application",
                '<uses-permission android:name="android.app.action.PROVISION_MANAGED_USER" />\n'
                "    <application",
            ),
            "provisioning-mode-action": (
                "manifest",
                "<application",
                '<uses-permission android:name="android.app.action.GET_PROVISIONING_MODE" />\n'
                "    <application",
            ),
        }
        for name, (kind, old, new) in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                manifest = fixture / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
                path = (
                    manifest
                    if kind == "manifest"
                    else fixture
                    / "android"
                    / "app"
                    / "src"
                    / "main"
                    / "kotlin"
                    / "com"
                    / "lavasec"
                    / "app"
                    / "MainActivity.kt"
                )
                source = path.read_text(encoding="utf-8")
                self.assertIn(old, source)
                path.write_text(source.replace(old, new, 1), encoding="utf-8")
                result = self.run_verifier(fixture)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("managed-device", result.stderr.lower())

    def test_verifier_scans_every_production_module_manifest_and_main_source_set(self) -> None:
        mutations = {
            "non-app-kotlin": (
                "android/foundation/src/main/kotlin/com/lavasec/app/foundation/FoundationMarker.kt",
                "object FoundationMarker",
                "private val policy = android.app.admin.DevicePolicyManager::class\n\n"
                "object FoundationMarker",
            ),
            "java-main-source": (
                "android/vpn/src/main/java/com/lavasec/app/vpn/ManagedBoundary.java",
                None,
                "package com.lavasec.app.vpn;\nfinal class DeviceOwnerController {}\n",
            ),
            "non-app-manifest": (
                "android/vpn/src/main/AndroidManifest.xml",
                '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
                '    <uses-permission android:name="android.permission.BIND_DEVICE_ADMIN" />\n'
                '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
            ),
        }
        for name, (relative, old, new) in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                path = fixture / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                if old is None:
                    path.write_text(new, encoding="utf-8")
                else:
                    source = path.read_text(encoding="utf-8")
                    self.assertIn(old, source)
                    path.write_text(source.replace(old, new, 1), encoding="utf-8")

                result = self.run_verifier(fixture)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("managed-device", result.stderr.lower())

    def test_managed_device_boundary_includes_play_and_foss_production_sources(self) -> None:
        for edition in ("play", "foss"):
            with self.subTest(edition=edition), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                source = fixture / f"android/app/src/{edition}/kotlin/ManagedDeviceProbe.kt"
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("package probe\nimport android.app.admin.DevicePolicyManager\n", encoding="utf-8")
                result = self.run_verifier(fixture)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("managed-device", result.stderr)
                self.assertIn(f"src/{edition}/", result.stderr)

    def test_verifier_rejects_entity_encoded_managed_device_manifest_tokens(self) -> None:
        mutations = {
            "device-admin-receiver": (
                "<application>\n"
                '    <receiver android:name="android.app.adm&#105;n.DeviceAdm&#105;nReceiver" />\n'
                "</application>"
            ),
            "bind-device-admin": (
                '<uses-permission android:name="android.permission.BIND_DEVICE_ADM&#73;N" />'
            ),
            "device-admin-metadata": (
                "<application>\n"
                "    <meta-data\n"
                '        android:name="android.app.device_adm&#105;n"\n'
                '        android:value="true" />\n'
                "</application>"
            ),
            "device-admin-enabled-action": (
                "<application>\n"
                '    <receiver android:name=".ConsumerReceiver">\n'
                "        <intent-filter>\n"
                '            <action android:name="android.app.action.DEVICE_ADM&#73;N_ENABLED" />\n'
                "        </intent-filter>\n"
                "    </receiver>\n"
                "</application>"
            ),
        }
        for name, declaration in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                manifest = fixture / "android" / "vpn" / "src" / "main" / "AndroidManifest.xml"
                manifest.write_text(
                    '<?xml version="1.0" encoding="utf-8"?>\n'
                    '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
                    f"    {declaration}\n"
                    "</manifest>\n",
                    encoding="utf-8",
                )

                result = self.run_verifier(fixture)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("managed-device", result.stderr.lower())

    def test_verifier_preserves_kotlin_and_java_literals_when_removing_comments(self) -> None:
        mutations = {
            "kotlin-url-before-managed-api": (
                "kotlin",
                'private const val URL = "https://lavasecurity.app"; '
                "private val managedApi = android.app.admin.DevicePolicyManager::class",
            ),
            "kotlin-url-before-embedded-dpc": (
                "kotlin",
                'private const val URL = "https://lavasecurity.app"; '
                "private class LavaDpcController",
            ),
            "java-url-before-embedded-mdm": (
                "java",
                "final class CommentBoundary { "
                'private static final String URL = "https://lavasecurity.app"; '
                "private static final class LavaMdmController {} }",
            ),
            "java-text-block-escaped-delimiter": (
                "java",
                "final class CommentBoundary {\n"
                '    private static final String TEXT = """\n'
                '        \\""" https://inside"""; private static final Class<?> API = '
                "android.app.admin.DevicePolicyManager.class;\n"
                "}",
            ),
            "kotlin-block-markers-in-string": (
                "kotlin",
                'private const val MARKER = "/* LavaDpcController */"',
            ),
            "java-block-markers-in-string": (
                "java",
                "final class CommentBoundary { "
                'private static final String MARKER = "/* LavaMdmController */"; }',
            ),
            "kotlin-forbidden-plain-string": (
                "kotlin",
                'private const val MODE = "DevicePolicyManager"',
            ),
            "java-forbidden-escaped-string": (
                "java",
                "final class CommentBoundary { "
                'private static final String MODE = "escaped \\\"LavaDpcController\\\""; }',
            ),
            "kotlin-forbidden-raw-string": (
                "kotlin",
                'private const val MODE = """https://lavasecurity.app/LavaMdmController"""',
            ),
        }
        for name, (language, declaration) in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                if language == "kotlin":
                    path = (
                        fixture
                        / "android"
                        / "foundation"
                        / "src"
                        / "main"
                        / "kotlin"
                        / "com"
                        / "lavasec"
                        / "app"
                        / "foundation"
                        / "FoundationMarker.kt"
                    )
                    source = (
                        "package com.lavasec.app.foundation\n\n"
                        f"{declaration}\n\n"
                        "object FoundationMarker\n"
                    )
                else:
                    path = (
                        fixture
                        / "android"
                        / "vpn"
                        / "src"
                        / "main"
                        / "java"
                        / "com"
                        / "lavasec"
                        / "app"
                        / "vpn"
                        / "CommentBoundary.java"
                    )
                    path.parent.mkdir(parents=True, exist_ok=True)
                    source = f"package com.lavasec.app.vpn;\n{declaration}\n"
                path.write_text(source, encoding="utf-8")

                result = self.run_verifier(fixture)

                self.assertNotEqual(0, result.returncode)
                self.assertIn("managed-device", result.stderr.lower())

    def test_verifier_translates_java_unicode_escapes_before_lexing(self) -> None:
        sources = {
            "unicode-backslash-escapes-text-block-delimiter": (
                True,
                "package com.lavasec.app.vpn;\n"
                "final class CommentBoundary {\n"
                '    private static final String TEXT = """\n'
                '        \\u005c""" https://inside"""; private static final Class<?> API = '
                "android.app.admin.DevicePolicyManager.class;\n"
                "}\n",
            ),
            "unicode-managed-identifier-with-repeated-u": (
                True,
                "package com.lavasec.app.vpn;\n"
                "final class CommentBoundary {\n"
                "    private static final class LavaDp\\uuuu0063Controller {}\n"
                "}\n",
            ),
            "unicode-string-delimiters-preserve-url": (
                True,
                "package com.lavasec.app.vpn;\n"
                "final class CommentBoundary {\n"
                "    private static final String URL = \\u0022https://inside\\u0022; "
                "private static final class LavaDpcController {}\n"
                "}\n",
            ),
            "unicode-line-comment": (
                False,
                "package com.lavasec.app.vpn;\n"
                "final class CommentBoundary {\n"
                "    \\u002f\\u002f DevicePolicyManager LavaDpcController\n"
                "}\n",
            ),
            "unicode-block-comment": (
                False,
                "package com.lavasec.app.vpn;\n"
                "final class CommentBoundary {\n"
                "    \\u002f\\u002a DevicePolicyManager LavaDpcController \\u002a\\u002f\n"
                "}\n",
            ),
            "unicode-newline-ends-line-comment": (
                True,
                "package com.lavasec.app.vpn;\n"
                "final class CommentBoundary {\n"
                "    // harmless \\u000a    private static final class LavaDpcController {}\n"
                "}\n",
            ),
            "unicode-closes-block-comment": (
                True,
                "package com.lavasec.app.vpn;\n"
                "final class CommentBoundary {\n"
                "    /* harmless \\u002a\\u002f "
                "private static final class LavaDpcController {}\n"
                "}\n",
            ),
            "unicode-slashes-inside-real-string": (
                True,
                "package com.lavasec.app.vpn;\n"
                "final class CommentBoundary {\n"
                '    private static final String URL = "https:\\u002f\\u002finside"; '
                "private static final class LavaDpcController {}\n"
                "}\n",
            ),
        }
        for name, (should_reject, source) in sources.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                path = (
                    fixture
                    / "android"
                    / "vpn"
                    / "src"
                    / "main"
                    / "java"
                    / "com"
                    / "lavasec"
                    / "app"
                    / "vpn"
                    / "CommentBoundary.java"
                )
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")

                result = self.run_verifier(fixture)

                if should_reject:
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("managed-device", result.stderr.lower())
                else:
                    self.assertEqual(0, result.returncode, result.stderr)

    def test_java_unicode_translation_is_single_pass(self) -> None:
        verifier = runpy.run_path(str(ROOT / "scripts" / "verify-android-foundation"))
        translate = verifier["translate_java_unicode_escapes_once"]

        self.assertEqual("A", translate("\\uuuu0041"))
        self.assertEqual("\\u0063", translate("\\u005cu0063"))
        self.assertEqual("\\u12xz", translate("\\u12xz"))

    def test_verifier_does_not_translate_java_unicode_escapes_in_kotlin(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            path = (
                fixture
                / "android"
                / "foundation"
                / "src"
                / "main"
                / "kotlin"
                / "com"
                / "lavasec"
                / "app"
                / "foundation"
                / "FoundationMarker.kt"
            )
            path.write_text(
                "package com.lavasec.app.foundation\n\n"
                'private const val TEXT = """\\u0022\\u0022\\u0022 '
                '// DevicePolicyManager"""\n'
                "object FoundationMarker\n",
                encoding="utf-8",
            )

            result = self.run_verifier(fixture)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("managed-device", result.stderr.lower())

    def test_verifier_ignores_forbidden_prose_only_inside_real_source_comments(self) -> None:
        sources = {
            "kotlin-nested-comments": (
                "android/foundation/src/main/kotlin/com/lavasec/app/foundation/FoundationMarker.kt",
                "package com.lavasec.app.foundation\n\n"
                "private const val SLASH = '/'\n"
                "private const val STAR = '*'\n"
                "// DevicePolicyManager LavaDpcController\n"
                "/* DeviceAdminReceiver\n"
                "   /* LavaMdmController */\n"
                "   profile owner\n"
                "*/\n"
                "object FoundationMarker\n",
            ),
            "java-comments": (
                "android/vpn/src/main/java/com/lavasec/app/vpn/CommentBoundary.java",
                "package com.lavasec.app.vpn;\n"
                "// DevicePolicyManager LavaDpcController\n"
                "/* DeviceAdminReceiver LavaMdmController profile owner */\n"
                "final class CommentBoundary {\n"
                "    private final char slash = '/';\n"
                "    private final char star = '*';\n"
                "}\n",
            ),
            "java-even-backslashes-close-text-block": (
                "android/vpn/src/main/java/com/lavasec/app/vpn/CommentBoundary.java",
                "package com.lavasec.app.vpn;\n"
                "final class CommentBoundary {\n"
                '    private static final String TEXT = """\n'
                '        \\\\""";\n'
                "    // DevicePolicyManager LavaDpcController\n"
                "}\n",
            ),
            "kotlin-backslash-does-not-escape-raw-close": (
                "android/foundation/src/main/kotlin/com/lavasec/app/foundation/FoundationMarker.kt",
                "package com.lavasec.app.foundation\n\n"
                'private const val TEXT = """\\"""\n'
                "// DevicePolicyManager LavaDpcController\n"
                "object FoundationMarker\n",
            ),
        }
        for name, (relative, source) in sources.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                fixture = self.verifier_fixture(directory)
                path = fixture / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")

                result = self.run_verifier(fixture)

                self.assertEqual(0, result.returncode, result.stderr)

    def test_verifier_does_not_scan_test_prose_for_managed_device_tokens(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            prose = fixture / "android" / "app" / "src" / "test" / "ManagedBoundaryFixture.kt"
            prose.write_text(
                '// DevicePolicyManager DPC MDM device owner profile owner provisioning\n',
                encoding="utf-8",
            )
            module_test = (
                fixture
                / "android"
                / "foundation"
                / "src"
                / "test"
                / "kotlin"
                / "ManagedBoundaryFixture.kt"
            )
            module_test.parent.mkdir(parents=True, exist_ok=True)
            module_test.write_text("// LavaDpcController LavaMdmController\n", encoding="utf-8")
            docs = fixture / "docs" / "managed-boundary.md"
            docs.parent.mkdir(parents=True, exist_ok=True)
            docs.write_text("DevicePolicyManager DPC MDM device owner profile owner\n", encoding="utf-8")

            result = self.run_verifier(fixture)

        self.assertEqual(0, result.returncode, result.stderr)

    def test_verifier_rejects_release_signing_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture = self.verifier_fixture(directory)
            app = fixture / "android" / "app" / "build.gradle.kts"
            app.write_text(app.read_text() + '\nandroid { signingConfigs.create("release") }\n')
            result = self.run_verifier(fixture)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("signing", result.stderr.lower())

    def test_signing_exception_is_exact_and_only_in_the_app_debug_variant(self) -> None:
        verifier = runpy.run_path(str(ROOT / "scripts/verify-android-foundation"))
        strip = verifier["signing_source_without_public_debug"]
        allowed = verifier["PUBLIC_DEBUG_SIGNING"]
        source = f"buildTypes {{\n debug {{\n{allowed}\n }}\n release {{\n }}\n}}"
        self.assertNotIn("signingConfig", strip(source, "app"))
        for unsafe, module in (
            (source, "vpn"),
            (source.replace('getByName("debug")', 'getByName("release")'), "app"),
            (source.replace('storePassword = "android"', 'storePassword = secret'), "app"),
            (source.replace('keyAlias = "androiddebugkey"', 'keyAlias = "upload"'), "app"),
            (source.replace("debug {", "release {").replace("release {\n }", "debug {\n }"), "app"),
            (source + '\nsigningConfigs.create("release")', "app"),
        ):
            with self.subTest(module=module, source=unsafe):
                self.assertIn("signingConfig", strip(unsafe, module))

    def test_version_catalog_pins_the_fixed_toolchain(self) -> None:
        catalog_path = ROOT / "android" / "gradle" / "libs.versions.toml"
        catalog = tomllib.loads(self.read(str(catalog_path.relative_to(ROOT))))
        self.assertEqual("9.3.0", catalog["versions"]["agp"])
        self.assertEqual("2.4.10", catalog["versions"]["kotlin"])
        self.assertEqual("2026.06.00", catalog["versions"]["composeBom"])
        self.assertEqual("1.13.0", catalog["versions"]["activityCompose"])
        self.assertEqual("1.10.2", catalog["versions"]["coroutines"])
        self.assertEqual("4.13.2", catalog["versions"]["junit"])
        self.assertEqual("1.7.0", catalog["versions"]["androidxTestCore"])
        self.assertEqual("4.16.1", catalog["versions"]["robolectric"])
        self.assertEqual("21.0.0", catalog["versions"]["googleFido"])
        self.assertEqual(
            {"module": "com.google.android.gms:play-services-fido", "version": {"ref": "googleFido"}},
            catalog["libraries"]["google-fido"],
        )
        app = self.read("android/app/build.gradle.kts")
        self.assertIn('"playImplementation"(libs.google.fido)', app)
        self.assertNotIn("implementation(libs.google.fido)", app)
        self.assertEqual(
            {"id": "com.android.application", "version": {"ref": "agp"}},
            catalog["plugins"]["android-application"],
        )
        self.assertEqual(
            {"id": "com.android.library", "version": {"ref": "agp"}},
            catalog["plugins"]["android-library"],
        )
        self.assertEqual(
            {"id": "org.jetbrains.kotlin.plugin.compose", "version": {"ref": "kotlin"}},
            catalog["plugins"]["compose-compiler"],
        )

    def test_build_uses_built_in_kotlin_and_unique_module_namespaces(self) -> None:
        root_build = self.read("android/build.gradle.kts")
        all_builds = {
            path.parent.name: path.read_text(encoding="utf-8")
            for path in (ROOT / "android").glob("*/build.gradle.kts")
        }
        self.assertIn('classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")', root_build)
        self.assertNotIn("org.jetbrains.kotlin.android", root_build + "\n" + "\n".join(all_builds.values()))
        self.assertIn("alias(libs.plugins.android.application)", all_builds["app"])
        for module in (
            "foundation",
            "platform-contracts",
            "vpn",
            "presentation",
            "design-system",
            "test-support",
        ):
            with self.subTest(module=module):
                self.assertIn("alias(libs.plugins.android.library)", all_builds[module])
                self.assertNotIn("alias(libs.plugins.android.application)", all_builds[module])

        expected_namespaces = {
            "app": "com.lavasec.app",
            "foundation": "com.lavasec.app.foundation",
            "platform-contracts": "com.lavasec.app.platform",
            "vpn": "com.lavasec.app.vpn",
            "presentation": "com.lavasec.app.presentation",
            "design-system": "com.lavasec.app.designsystem",
            "test-support": "com.lavasec.app.testsupport",
        }
        for module, namespace in expected_namespaces.items():
            with self.subTest(module=module):
                self.assertIn(f'namespace = "{namespace}"', all_builds[module])
                self.assertIn("JavaVersion.VERSION_17", all_builds[module])

        for module in ("app", "presentation", "design-system"):
            with self.subTest(compose_module=module):
                self.assertIn("alias(libs.plugins.compose.compiler)", all_builds[module])

    def test_app_has_fixed_sdk_ids_versions_and_no_release_signing(self) -> None:
        app = self.read("android/app/build.gradle.kts")
        self.assertIn("compileSdk = 36", app)
        self.assertIn('applicationId = "com.lavasec.app"', app)
        self.assertIn("minSdk = 28", app)
        self.assertIn("targetSdk = 36", app)
        self.assertIn('applicationIdSuffix = ".debug"', app)
        self.assertIn('providers.gradleProperty("lavasecVersionName").orElse(releaseVersion.getProperty("versionName"))', app)
        self.assertIn('providers.gradleProperty("lavasecVersionCode").map(String::toInt).orElse(releaseVersion.getProperty("versionCode").toInt())', app)
        verifier = runpy.run_path(str(ROOT / "scripts/verify-android-foundation"))
        without_public_debug = verifier["signing_source_without_public_debug"](app, "app")
        self.assertNotIn("signingConfig", without_public_debug)
        self.assertNotIn("storeFile", without_public_debug)
        self.assertIn("platform(libs.androidx.compose.bom)", app)

    def test_wrapper_is_gradle_9_5_all_with_verified_artifacts(self) -> None:
        properties = self.read("android/gradle/wrapper/gradle-wrapper.properties")
        self.assertIn("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.0-all.zip", properties)
        self.assertIn(
            "distributionSha256Sum=a3c4ba4aca8f0075688b9c5b18939fd28e8cb4357c227da5c1d9f38343791439",
            properties,
        )
        wrapper_jar = ROOT / "android" / "gradle" / "wrapper" / "gradle-wrapper.jar"
        self.assertTrue(wrapper_jar.is_file(), f"missing {wrapper_jar}")
        self.assertEqual(
            "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
            hashlib.sha256(wrapper_jar.read_bytes()).hexdigest(),
        )

    def test_repository_foundation_verifier_passes(self) -> None:
        verifier = ROOT / "scripts" / "verify-android-foundation"
        result = subprocess.run(
            [str(verifier)],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_greenfield_sources_replace_the_prototype_package(self) -> None:
        manifest = self.read("android/app/src/main/AndroidManifest.xml")
        application = self.read(
            "android/app/src/main/kotlin/com/lavasec/app/LavasecApplication.kt"
        )
        activity = self.read("android/app/src/main/kotlin/com/lavasec/app/MainActivity.kt")
        foundation_spike = self.read(
            "android/foundation/src/main/kotlin/com/lavasec/app/foundation/FoundationMarker.kt"
        )
        compose_spike = self.read(
            "android/design-system/src/main/kotlin/com/lavasec/app/designsystem/FoundationSurface.kt"
        )
        self.assertIn(".LavasecApplication", manifest)
        self.assertIn(".MainActivity", manifest)
        self.assertIn("package com.lavasec.app", application)
        self.assertIn("package com.lavasec.app", activity)
        self.assertIn("package com.lavasec.app.foundation", foundation_spike)
        self.assertIn("package com.lavasec.app.designsystem", compose_spike)
        self.assertIn("@Composable", compose_spike)


if __name__ == "__main__":
    unittest.main()
