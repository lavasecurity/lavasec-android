#!/usr/bin/env bash
# Run only on an explicitly selected disposable emulator. Never clears a physical device.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_SERIAL:?Select the test emulator explicitly with ANDROID_SERIAL}"
: "${ANDROID_SDK_ROOT:?Android SDK root is required}"
evidence="${ANDROID_JOURNEY_EVIDENCE:-${root}/device-evidence}"
adb_cmd=("${ANDROID_SDK_ROOT}/platform-tools/adb" -s "$ANDROID_SERIAL")
test "$("${adb_cmd[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 || {
  echo "Refusing test-data reset: the selected device is not an emulator." >&2
  exit 1
}
journey_budget_seconds="${ANDROID_JOURNEY_BUDGET_SECONDS:-2400}"
case "$journey_budget_seconds" in 2400|3000|3600|4500) ;; *) echo "Invalid bounded journey budget." >&2; exit 1 ;; esac
if [[ ( "$journey_budget_seconds" == 3600 || "$journey_budget_seconds" == 4500 ) && "$("${adb_cmd[@]}" shell getprop ro.build.version.sdk | tr -d '\r')" != 28 ]]; then
  echo "The extended journey budgets are reserved for API28." >&2
  exit 1
fi
if [[ "$journey_budget_seconds" == 3000 && "$("${adb_cmd[@]}" shell getprop ro.build.version.sdk | tr -d '\r')" != 36 ]]; then
  echo "The fifty-minute journey budget is reserved for API36." >&2
  exit 1
fi
mkdir -p "$evidence"
# Only this run's results may be attributed to its APK/source. Preserve older
# output directories for inspection without letting them satisfy current cases.
results_dir="$(mktemp -d "$evidence/journey-results.XXXXXX")"
app_apk="${root}/android/app/build/outputs/apk/play/debug/app-play-debug.apk"
test_apk="${root}/android/app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"
vpn_test_apk="${root}/android/vpn/build/outputs/apk/androidTest/debug/vpn-debug-androidTest.apk"
if [[ -n "${ANDROID_PREBUILT_APK_DIR:-}" ]]; then
  app_apk="$ANDROID_PREBUILT_APK_DIR/app-debug.apk"
  test_apk="$ANDROID_PREBUILT_APK_DIR/app-debug-androidTest.apk"
  vpn_test_apk="$ANDROID_PREBUILT_APK_DIR/vpn-debug-androidTest.apk"
  for input in "$app_apk" "$test_apk" "$vpn_test_apk" "$ANDROID_PREBUILT_APK_DIR/source-sha.txt"; do
    test -s "$input" || { echo "Missing prebuilt candidate input: $input" >&2; exit 1; }
  done
  test "$(cat "$ANDROID_PREBUILT_APK_DIR/source-sha.txt")" = "$(git -C "$root" rev-parse HEAD)" || {
    echo "Prebuilt candidate source does not match this checkout." >&2
    exit 1
  }
fi
apk_digest() {
  python3 - "$app_apk" <<'PY'
import hashlib, sys
with open(sys.argv[1], "rb") as artifact:
    print(hashlib.file_digest(artifact, "sha256").hexdigest())
PY
}
installed_apk_digest="$(apk_digest)"
if ! "${adb_cmd[@]}" shell pm path com.lavasec.app.debug | grep -q '^package:'; then
  "${adb_cmd[@]}" install -r "$app_apk"
fi
"${adb_cmd[@]}" shell run-as com.lavasec.app.debug mkdir -p files
"${adb_cmd[@]}" shell 'run-as com.lavasec.app.debug sh -c "echo emulator-synthetic-update > files/emulator-update-witness"'
"${adb_cmd[@]}" install -r "$app_apk"
"${adb_cmd[@]}" shell run-as com.lavasec.app.debug cat files/emulator-update-witness \
  | tr -d '\r' > "$results_dir/update-preserved.txt"
grep -Fx emulator-synthetic-update "$results_dir/update-preserved.txt"
"${adb_cmd[@]}" install -r -t "$test_apk"
# Retain the installed candidate even when a later journey fails. Qualification still
# requires the complete result manifest; this keeps the build available for inspection.
cp "$app_apk" "$evidence/app-debug.apk"
# Discard prior-source diagnostics only after emulator, source and update-preservation
# checks have passed. No application data is erased by this log-buffer operation.
"${adb_cmd[@]}" logcat -c
# This package exists only on our managed emulator, with explicitly synthetic test data.
"${adb_cmd[@]}" shell pm clear com.lavasec.app.debug
runner="com.lavasec.app.debug.test/androidx.test.runner.AndroidJUnitRunner"
product="com.lavasec.app.reactnative.LavaReactProductJourneyTest"
shared_setup="com.lavasec.app.reactnative.LavaReactSharedForegroundOnboardingDeviceTest"
exported_entry="com.lavasec.app.reactnative.LavaReactExportedEntryJourneyTest"
death="com.lavasec.app.reactnative.VpnProcessDeathInstrumentationTest"
doq="com.lavasec.app.vpn.NativeDoqSocketJourneyInstrumentationTest"
# Google APIs images do not establish the TalkBack service/tutorial capability.
# Invoke this API36-only journey separately; it is not a default-cohort verdict.
talkback="com.lavasec.app.reactnative.LavaReactTalkBackJourneyTest"
vpn_runner="com.lavasec.app.vpn.test/androidx.test.runner.AndroidJUnitRunner"
run_doq_peer() {
  local peer_dir="${ANDROID_DOQ_PEER_DIR:-${ANDROID_PREBUILT_APK_DIR:-}}"
  if [[ -z "$peer_dir" ]]; then
    echo "Required source-bound DoQ peer directory is missing; set ANDROID_DOQ_PEER_DIR for local connected mode." >&2
    return 1
  fi
  # Artifact download strips executable permissions. Restore only this admitted
  # synthetic peer's mode; a missing/bad peer still fails its phase and does not
  # prevent the independent suites from running.
  if [[ -n "${ANDROID_PREBUILT_APK_DIR:-}" && -f "$peer_dir/android-doq-peer" ]]; then
    chmod 755 "$peer_dir/android-doq-peer" || return 1
  fi
  # The driver independently verifies source SHA, exact public TLS fixture hashes,
  # readiness, raw Android result, host receipts and owned-child exit. Its XML is
  # recorded only once in this run's fresh cohort, including any failure marker.
  python3 "$root/ci/android_doq_peer.py" \
    --peer "$peer_dir/android-doq-peer" --cert "$peer_dir/resolver.crt" --key "$peer_dir/resolver.key" \
    --adb "$ANDROID_SDK_ROOT/platform-tools/adb" --serial "$ANDROID_SERIAL" \
    --evidence "$results_dir/doq"
}
canary_port=""
canary_token=""
canary_pid=""
canary_ready="$results_dir/canary-peer-ready.json"
cleanup_canary_peer() {
  if [[ -z "$canary_pid" ]]; then return; fi
  local status=0
  if [[ -s "$canary_ready" ]]; then
    python3 - "$canary_ready" <<'PY' || true
import json, socket, sys
try:
    ready = json.load(open(sys.argv[1]))
    with socket.create_connection(("127.0.0.1", ready["controlPort"]), timeout=2) as control:
        control.settimeout(2)
        control.sendall((json.dumps({"token": ready["token"], "op": "shutdown"}) + "\n").encode())
        control.recv(4096)
except (OSError, ValueError, KeyError):
    pass
PY
  fi
  local deadline=$((SECONDS + 8))
  while kill -0 "$canary_pid" 2>/dev/null && (( SECONDS < deadline )); do sleep 0.2; done
  if kill -0 "$canary_pid" 2>/dev/null; then
    kill "$canary_pid" 2>/dev/null || true
    deadline=$((SECONDS + 2))
    while kill -0 "$canary_pid" 2>/dev/null && (( SECONDS < deadline )); do sleep 0.2; done
    kill -KILL "$canary_pid" 2>/dev/null || true
    deadline=$((SECONDS + 2))
    while kill -0 "$canary_pid" 2>/dev/null && (( SECONDS < deadline )); do sleep 0.2; done
  fi
  if kill -0 "$canary_pid" 2>/dev/null; then
    echo "Could not confirm controlled canary peer exit within bounded cleanup." >&2
    status=1
  else
    wait "$canary_pid" 2>/dev/null || status=1
  fi
  canary_pid=""
  # The random control token is test-private, not part of retained UDP receipts.
  rm -f "$canary_ready"
  return "$status"
}
trap cleanup_canary_peer EXIT
start_canary_peer() {
  local command=(python3 "$root/scripts/android-canary-udp-peer.py" --ready "$canary_ready"
    --observations "$evidence/canary-udp-peer-observations-${results_dir##*.}.jsonl"
    --owner-uid "$(id -u)" --owner-gid "$(id -g)")
  if [[ "${ANDROID_CANARY_PEER_ALLOW_SUDO:-0}" == 1 ]]; then
    # Only the existing disposable owned-repository Linux CI host may authorize
    # this one privileged loopback bind. The helper drops uid/gid immediately.
    if [[ "$(uname -s)" != Linux || "${GITHUB_ACTIONS:-}" != true ||
          "${GITHUB_REPOSITORY:-}" != lavasecurity/lavasec-android-internal ]]; then
      echo "Refusing privileged canary peer outside the disposable owned Linux CI host." >&2
      return 1
    fi
    command=(sudo -n -- "${command[@]}")
  fi
  "${command[@]}" >"$evidence/canary-udp-peer.log" 2>&1 &
  canary_pid=$!
  local deadline=$((SECONDS + 15)) settings
  while (( SECONDS < deadline )) && kill -0 "$canary_pid" 2>/dev/null; do
    if [[ -s "$canary_ready" ]] && settings="$(python3 - "$canary_ready" <<'PY'
import json, re, sys
value = json.load(open(sys.argv[1]))
assert value.get("protocol") == 1 and value.get("udpAddress") == "127.0.0.1" and value.get("udpPort") == 53
assert type(value.get("controlPort")) is int and 1024 <= value["controlPort"] <= 65535
assert re.fullmatch(r"[0-9a-f]{64}", value.get("token", ""))
print(value["controlPort"], value["token"])
PY
)"; then
      read -r canary_port canary_token <<< "$settings"
      echo "Controlled canary host UDP53 peer is ready; Android and host NAT tuples remain distinct."
      return 0
    fi
    sleep 0.2
  done
  echo "Required host loopback UDP53 peer is unavailable; canary cases will fail and independent suites will continue." >&2
  cat "$evidence/canary-udp-peer.log" >&2
  cleanup_canary_peer
  return 1
}
# Capture only a closed original phase's failed return, before another process
# can evict its log buffer. Files are immutable and separate from the EXIT tail.
phase_capture_status=0
capture_original_failure() {
  local name="$1" stem="$results_dir/failure-$1" remaining
  case "$name" in shared-setup|exported-entry|product|stagePending|recoverPending|stageSaved|verifySaved|vpn|app) ;; *) return 1 ;; esac
  [[ ! -e "$stem-started" && ! -e "$stem-navigation-removal-trace.log" &&
     ! -e "$stem-android-runtime-crashes.log" && ! -e "$stem-native-mount-trace.json" && ! -e "$stem-complete" ]] || return 1
  command -v timeout >/dev/null || return 1
  remaining=$((journey_budget_seconds - SECONDS - 60))
  (( remaining > 60 )) || return 1
  (
    umask 077
    set -o noclobber
    : > "$stem-started" || exit 1
    if ! timeout --kill-after=5s 15s "${adb_cmd[@]}" logcat -d -v raw ReactNativeJS:I '*:S' \
      | python3 "$root/ci/android_navigation_trace.py" --output "$stem-navigation-removal-trace.log"; then
      exit 1
    fi
    if ! timeout --kill-after=5s 15s "${adb_cmd[@]}" logcat -d -v threadtime AndroidRuntime:E '*:S' \
      | LC_ALL=C tail -n 256 > "$stem-android-runtime-crashes.log"; then
      exit 1
    fi
    # Colon-bearing RN tags cannot use logcat tag:priority rules. Stream E/F
    # only into the exact numeric/class parser; raw RN hierarchy is never
    # retained. Require60s for three maximum20s captures plus the final-minute reserve.
    if ! timeout --kill-after=5s 15s "${adb_cmd[@]}" logcat -d -v threadtime \
      '*:E' \
      | python3 "$root/ci/android_native_mount_trace.py" --output "$stem-native-mount-trace.json"; then
      exit 1
    fi
    : > "$stem-complete"
  )
}
instrument() {
  local name="$1" class="$2" phase="${3:-}" target_runner="${4:-$runner}" status=0
  local extra=(-w -r)
  if [[ -n "$class" ]]; then
    extra+=(-e class "$class")
  elif [[ "$target_runner" == "$runner" ]]; then
    extra+=(-e notClass "$product,$shared_setup,$exported_entry,$death,$talkback")
  elif [[ "$target_runner" == "$vpn_runner" ]]; then
    extra+=(-e notClass "$doq")
  fi
  if [[ -n "$phase" ]]; then extra+=(-e parityProcessDeath "$phase"); fi
  if [[ "$target_runner" == "$vpn_runner" && -n "$canary_port" ]]; then
    extra+=(-e canaryPeerControlPort "$canary_port" -e canaryPeerControlToken "$canary_token")
  fi
  "${adb_cmd[@]}" shell am instrument "${extra[@]}" "$target_runner" \
    | tee "$evidence/${name}.txt" || status=1
  python3 "$root/ci/android_emulator_evidence.py" transcript \
    --input "$evidence/${name}.txt" --output "$results_dir/${name}.xml" --phase "$phase" || status=1
  # Shared setup's original System.out measurements must be the immediate next
  # adb call. Its failed-return snapshot is taken just after that capture below.
  if (( status != 0 )) && [[ "$name" != shared-setup ]]; then
    capture_original_failure "$name" || phase_capture_status=1
  fi
  return "$status"
}
# Establish the first-run path before owner tests configure the application.
# Keep a failed shipping journey in the evidence and continue independent suites.
# Otherwise an API-specific UI failure hides every later persistence/security result
# until another complete CI build. No failure is waived by collecting more evidence.
journey_status=0
# Exercise the production shared first-run and isolated mock owners on the same APK.
# Reset only this disposable emulator's synthetic app data before the original fresh-run suite.
shared_setup_failed=0
if ! instrument shared-setup "$shared_setup"; then
  journey_status=1
  shared_setup_failed=1
fi
# A small API28 log buffer can evict first-run measurements during the later
# independent suites. Capture only this phase's exact scalar prefix now, in its
# fresh results directory; the final matrix capture remains a separate snapshot.
if ! python3 - "${adb_cmd[@]}" > "$results_dir/shared-setup-measurements.log" <<'PY_CAPTURE'
import re, subprocess, sys
try:
    result = subprocess.run(sys.argv[1:] + ["logcat", "-d", "-v", "raw", "System.out:I", "*:S"],
                            capture_output=True, text=True, timeout=15)
    output, status = result.stdout, result.returncode
except subprocess.TimeoutExpired as error:
    output = error.stdout or ""
    if isinstance(output, bytes):
        output = output.decode("utf-8", errors="replace")
    status = 1
for line in output.splitlines():
    if re.match(r"^LAVA_ONBOARDING_MEASUREMENT(?:\s|$)", line):
        print(line)
sys.exit(status)
PY_CAPTURE
then
  journey_status=1
fi
if (( shared_setup_failed != 0 )); then
  capture_original_failure shared-setup || { phase_capture_status=1; journey_status=1; }
fi
# The actual exported entry needs an independent fresh install state. Preserve its
# failures, then retire only this emulator fixture before the original product journey.
"${adb_cmd[@]}" shell pm clear com.lavasec.app.debug
instrument exported-entry "$exported_entry" || journey_status=1
"${adb_cmd[@]}" shell pm clear com.lavasec.app.debug
instrument product "$product" || journey_status=1
for phase in stagePending recoverPending stageSaved verifySaved; do
  instrument "$phase" "$death" "$phase" || journey_status=1
  case "$phase" in stagePending|stageSaved) "${adb_cmd[@]}" shell am force-stop com.lavasec.app.debug ;; esac
done
# This fixture receives only the intentional canary datagram. It is neither a
# resolver nor a DNS reply/protection producer, and never needs an Android privileged bind.
start_canary_peer || journey_status=1
# Install the same already-built synthetic VPN runner before its bounded DoQ phase
# in both modes. Replacement applies only to this test package, never the app.
if "${adb_cmd[@]}" shell pm path com.lavasec.app.vpn.test | grep -q '^package:'; then
  "${adb_cmd[@]}" uninstall com.lavasec.app.vpn.test || journey_status=1
fi
vpn_installed=0
if "${adb_cmd[@]}" install --no-streaming -r -t "$vpn_test_apk"; then
  vpn_installed=1
  run_doq_peer || journey_status=1
else
  journey_status=1
fi
# Recovery is supplementary execution, never a retry or a qualification waiver.
# Use this invocation's admitted total budget; reserve its final minute for
# receipts and cap each owned adb child inside the remaining execution budget.
recover_never_started_app_cases() {
  local plan="$results_dir/app-recovery-plan.json" ledger="$results_dir/app-recovery-attempts.json"
  local selectors="$results_dir/app-recovery-selectors.txt" original="$evidence/app.txt"
  local original_xml="$results_dir/app.xml" selector index=0 remaining duration adb_status
  local tool="$root/ci/android_default_app_recovery.py"
  command -v timeout >/dev/null || return 1
  remaining=$((journey_budget_seconds - SECONDS - 60))
  (( remaining > 40 )) || return 1
  # The failed original app return already captured both immutable tails.
  # Any original phase capture refusal prevents recovery, never infers safety.
  (( phase_capture_status == 0 )) || return 1
  [[ -f "$results_dir/failure-app-complete" &&
     -f "$results_dir/failure-app-navigation-removal-trace.log" &&
     -f "$results_dir/failure-app-android-runtime-crashes.log" &&
     -f "$results_dir/failure-app-native-mount-trace.json" ]] || return 1
  python3 "$tool" plan --results "$results_dir" --original "$original" --original-xml "$original_xml" --plan "$plan" || return 1
  python3 "$tool" selectors --results "$results_dir" --original "$original" --original-xml "$original_xml" --plan "$plan" > "$selectors" || return 1
  while IFS= read -r selector; do
    remaining=$((journey_budget_seconds - SECONDS - 60))
    (( remaining > 5 )) || break
    [[ "$(apk_digest)" == "$installed_apk_digest" ]] || return 1
    if python3 "$tool" claim --results "$results_dir" --original "$original" --original-xml "$original_xml" \
      --plan "$plan" --ledger "$ledger" --selector "$selector"; then
      :
    else
      local claim_status=$?
      if (( claim_status == 2 )); then continue; fi
      return 1
    fi
    index=$((index + 1))
    (( index <= 73 )) || return 1
    duration=180
    (( remaining >= duration + 5 )) || duration=$((remaining - 5))
    local name
    printf -v name 'recovery-%03d' "$index"
    # adb shell reads stdin; it must not consume this loop's pending selectors.
    if timeout --kill-after=5s "${duration}s" "${adb_cmd[@]}" shell am instrument -w -r -e class "$selector" "$runner" \
      </dev/null > "$results_dir/$name.txt" 2>&1; then
      adb_status=0
    else
      adb_status=$?
    fi
    python3 "$root/ci/android_emulator_evidence.py" transcript --input "$results_dir/$name.txt" \
      --output "$results_dir/$name.xml" --phase "" || journey_status=1
    python3 "$tool" verify --results "$results_dir" --original "$original" --original-xml "$original_xml" --plan "$plan" || return 1
    [[ "$(apk_digest)" == "$installed_apk_digest" ]] || return 1
    # A killed/disconnected adb does not prove the device-side invocation stopped.
    # Retain it and stop the queue; never force-stop or launch a competing runner.
    if (( adb_status != 0 )); then break; fi
  done < "$selectors"
}

if [[ -n "${ANDROID_PREBUILT_APK_DIR:-}" ]]; then
  # Both CI emulators consume the same build. Running prebuilt instrumentation
  # directly prevents a second Gradle build from replacing the installed APK.
  if (( vpn_installed == 1 )); then
    instrument vpn "" "" "$vpn_runner" || journey_status=1
  fi
  if instrument app ""; then
    :
  else
    journey_status=1
    recover_never_started_app_cases || journey_status=1
  fi
else
  # Connected Gradle XML has no raw advertised/START provenance. Absence from it
  # cannot authorize recovery, so this original invocation remains unchanged.
  cd "$root/android"
  signing=(--no-daemon --max-workers=2 --stacktrace)
  if [[ -n "${ANDROID_PREVIEW_KEYSTORE:-}" ]]; then
    # A local build may keep the committed, public debug signer outside the user's normal home.
    signing+=("-PlavaDebugKeystore=$ANDROID_PREVIEW_KEYSTORE")
  fi
  if [[ -n "$canary_port" ]]; then
    signing+=("-Pandroid.testInstrumentationRunnerArguments.canaryPeerControlPort=$canary_port"
      "-Pandroid.testInstrumentationRunnerArguments.canaryPeerControlToken=$canary_token")
  fi
  for module in app vpn; do
    connected="$root/android/$module/build/outputs/androidTest-results/connected"
    if [[ -d "$connected" ]]; then
      mv "$connected" "$evidence/previous-connected-$module-${results_dir##*.}"
    fi
  done
  if ! ./gradlew "${signing[@]}" --continue :vpn:connectedDebugAndroidTest :app:connectedPlayDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.notClass=${product},${shared_setup},${exported_entry},${death},${talkback},${doq}"; then
    journey_status=1
  fi
  for module in app vpn; do
    mkdir -p "$results_dir/$module"
    if [[ -d "$root/android/$module/build/outputs/androidTest-results/connected" ]]; then
      cp -R "$root/android/$module/build/outputs/androidTest-results/connected/." "$results_dir/$module/"
    else
      journey_status=1
    fi
  done
fi
cleanup_canary_peer || journey_status=1
# Retain only the finite shipping pages admitted by the real public-surface guards.
# A refused or missing capture fails qualification while preserving all other outcomes.
python3 "$root/ci/android_public_ui_evidence.py" \
  --adb "$ANDROID_SDK_ROOT/platform-tools/adb" --serial "$ANDROID_SERIAL" \
  --output "$results_dir/public-ui" || journey_status=1
# Matrix println measurements live in System.out, outside the instrumentation
# transcript. Retain current synthetic LAVA_UI_* records and the five bounded,
# redacted editable-control records and exact numeric onboarding/Back diagnostics,
# even on suite failure. No other journey logs;
# never materialize the complete logcat buffer in an artifact or shell output.
if ! "${adb_cmd[@]}" logcat -d -v raw 'System.out:I' '*:S' \
  | LC_ALL=C grep -E '^(LAVA_UI_[A-Z0-9_]+|LAVA_JOURNEY_EDITABLE_(FIELDS|FIELD|ANCESTOR)|LAVA_JOURNEY_SET_TEXT_(TARGET|RESULT)|LAVA_ONBOARDING_MEASUREMENT|LAVA_LOCALIZED_ACCOUNT_BACK)([[:space:]]|$)' \
    > "$evidence/rendered-matrix-measurements.log"; then
  journey_status=1
fi
if [[ "$(apk_digest)" != "$installed_apk_digest" ]]; then
  echo "The app APK changed after its first journeys; this is not one qualified candidate." >&2
  journey_status=1
fi
cp "$app_apk" "$evidence/app-debug.apk"
if (( journey_status != 0 )); then
  # A tool/build failure can leave no current testcase XML. Record it explicitly
  # so inspecting the collected manifest cannot turn a nonzero run into a pass.
  python3 - "$results_dir/run-failed.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
suite = ET.Element("testsuite", name="Emulator execution")
case = ET.SubElement(suite, "testcase", classname="runner", name="execution")
ET.SubElement(case, "error", message="An emulator journey or connected test command failed")
ET.ElementTree(suite).write(sys.argv[1], encoding="utf-8", xml_declaration=True)
PY
fi
python3 "$root/ci/android_emulator_evidence.py" record \
  --api "$("${adb_cmd[@]}" shell getprop ro.build.version.sdk | tr -d '\r')" \
  --apk "$app_apk" --results "$results_dir" --output "$evidence/emulator-evidence.json" \
  --apksigner "$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner"
exit "$journey_status"
