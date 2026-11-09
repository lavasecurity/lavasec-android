#!/usr/bin/env python3
"""Run one explicitly selected emulator DoQ exchange against its owned host test peer.

This records controlled JNI/socket evidence, never a shipping provider/Plus/TUN or F25 verdict.
The peer bundle must carry source-sha.txt beside its binary. No retries or app resets occur.
"""
import argparse
from dataclasses import dataclass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import selectors
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

if __package__:
    from .android_emulator_evidence import transcript_xml
else:
    from android_emulator_evidence import transcript_xml

ROOT = Path(__file__).resolve().parents[1]
CLASS = "com.lavasec.app.vpn.NativeDoqSocketJourneyInstrumentationTest"
METHOD = "verifiedAndroidQuicPeerRestoresCallerIdAndReleasesExactEpochResources"
RUNNER = "com.lavasec.app.vpn.test/androidx.test.runner.AndroidJUnitRunner"


@dataclass(frozen=True)
class Limits:
    guard: float = 10
    ready: float = 10
    instrument: float = 60
    finish: float = 10
    cleanup: float = 2


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source_sha():
    result = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"], check=True,
                            capture_output=True, text=True, timeout=10)
    value = result.stdout.strip()
    if len(value) != 40 or any(char not in "0123456789abcdef" for char in value):
        raise ValueError("repository source SHA is invalid")
    return value


def identity(args):
    source = source_sha()
    if (args.peer.parent / "source-sha.txt").read_text().strip() != source:
        raise ValueError("DoQ peer bundle source differs from repository HEAD")
    result = {"source_sha": source, "peer_sha256": sha256(args.peer),
              "certificate_sha256": sha256(args.cert), "key_sha256": sha256(args.key)}
    fixtures = ROOT / "ThirdParty/doq-core/tests/fixtures"
    if result["certificate_sha256"] != sha256(fixtures / "resolver.crt") or result["key_sha256"] != sha256(fixtures / "resolver.key"):
        raise ValueError("DoQ peer must use the exact source-controlled public synthetic TLS fixtures")
    if result["certificate_sha256"] != sha256(ROOT / "android/vpn/src/androidTest/assets/doq/resolver.crt"):
        raise ValueError("DoQ Android trust asset differs from the source-controlled peer certificate")
    return result


def stop_owned(process, timeout):
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=timeout)


def command(argv, output, timeout, cleanup):
    with output.open("xb") as raw:
        process = subprocess.Popen(argv, stdout=raw, stderr=subprocess.STDOUT)
        original = None
        try:
            try:
                return process.wait(timeout=timeout)
            except subprocess.TimeoutExpired as error:
                raise TimeoutError(f"owned command exceeded its {timeout}s bound") from error
        except BaseException as error:
            original = error
            raise
        finally:
            try:
                stop_owned(process, cleanup)
            except Exception as error:
                if original is None:
                    raise
                original.add_note(f"owned command cleanup failed: {error}")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate peer JSON field")
        result[key] = value
    return result


class Peer:
    def __init__(self, argv, output, diagnostics, cleanup):
        self.raw = output.open("xb")
        self.selector = selectors.DefaultSelector()
        try:
            # Only stdout is the strict JSON receipt channel. Keep native/quiche
            # diagnostics separately so a genuine peer error is not replaced by
            # a misleading JSON parse error, or mistaken for a valid receipt.
            self.diagnostics = diagnostics.open("xb")
            self.process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=self.diagnostics)
            self.selector.register(self.process.stdout, selectors.EVENT_READ)
        except BaseException as failure:
            process = getattr(self, "process", None)
            if process is not None:
                try:
                    stop_owned(process, cleanup)
                except Exception as error:
                    failure.add_note(f"owned peer initialization cleanup failed: {error}")
                process.stdout.close()
            self.selector.close()
            self.raw.close()
            if hasattr(self, "diagnostics"):
                self.diagnostics.close()
            raise
        self.buffer = bytearray()
        self.eof = False
        self.size = 0

    def line(self, deadline):
        while b"\n" not in self.buffer:
            if self.eof:
                if self.buffer:
                    raise ValueError("peer ended with an unterminated JSON receipt")
                return None
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("owned DoQ peer output deadline expired")
            if not self.selector.select(remaining):
                raise TimeoutError("owned DoQ peer output deadline expired")
            chunk = os.read(self.process.stdout.fileno(), 4096)
            if not chunk:
                self.eof = True
                continue
            self.raw.write(chunk)
            self.raw.flush()
            self.size += len(chunk)
            if self.size > 1024 * 1024:
                raise ValueError("DoQ peer output exceeded its bound")
            self.buffer.extend(chunk)
        line, _, remaining = self.buffer.partition(b"\n")
        self.buffer = bytearray(remaining)
        value = json.loads(line.decode("utf-8"), object_pairs_hook=unique_object)
        if not isinstance(value, dict):
            raise ValueError("peer receipt must be a JSON object")
        return value

    def close(self, timeout):
        try:
            stop_owned(self.process, timeout)
        finally:
            # Preserve pending raw stdout bytes, including malformed receipts, on failure.
            try:
                os.set_blocking(self.process.stdout.fileno(), False)
                deadline = time.monotonic() + timeout
                while time.monotonic() < deadline:
                    try:
                        chunk = os.read(self.process.stdout.fileno(), 4096)
                    except BlockingIOError:
                        break
                    if not chunk:
                        break
                    self.raw.write(chunk)
            finally:
                self.selector.close()
                self.process.stdout.close()
                self.raw.close()
                self.diagnostics.close()


def peer_diagnostic(path):
    if not path.exists():
        return ""
    with path.open("rb") as stream:
        stream.seek(0, os.SEEK_END)
        stream.seek(max(0, stream.tell() - 4096))
        return stream.read().decode("utf-8", errors="replace").strip()


def ready(value):
    if value is None or set(value) != {"event", "address", "port", "max_duration_ms"} or (
        value.get("event") != "ready" or value.get("address") != "127.0.0.1"):
        raise ValueError("first DoQ peer receipt must be loopback readiness")
    port = value.get("port")
    duration = value.get("max_duration_ms")
    if type(port) is not int or not 1024 <= port <= 65535 or type(duration) is not int or not 1000 <= duration <= 120000:
        raise ValueError("DoQ readiness has an invalid ephemeral port or duration")
    return port


def native_pass(text):
    suite = transcript_xml(text)
    cases = list(suite.iter("testcase"))
    if len(cases) != 1 or cases[0].get("classname") != CLASS or cases[0].get("name") != METHOD or list(cases[0]):
        raise ValueError("DoQ instrumentation must contain exactly its one executed terminal pass")
    return suite


def host_pass(records):
    if len(records) != 2 or records[0].get("event") != "query" or records[1].get("event") != "complete":
        raise ValueError("DoQ host must emit exactly one query and complete receipt")
    query = bytes.fromhex("000001000001000000000000") + b"\x0bandroid-doq\x07invalid\x00\x00\x01\x00\x01"
    frame = len(query).to_bytes(2, "big") + query
    reply = bytearray(frame)
    reply[4:6] = b"\x81\x83"
    event, complete = records
    if event.get("query_wire_hex") != frame.hex() or event.get("reply_wire_hex") != reply.hex() or (
        event.get("alpn") != "doq" or event.get("sni") != "resolver.test" or type(event.get("stream")) is not int or
        event["stream"] != 0 or event.get("query_fin") is not True or event.get("source_scope") != "NAT-translated-host-loopback"):
        raise ValueError("DoQ host query/framing/TLS receipt differs from the exact synthetic exchange")
    address, separator, port = str(event.get("host_observed_peer", "")).rpartition(":")
    if not separator or not ipaddress.ip_address(address).is_loopback or not port.isdigit() or not 1 <= int(port) <= 65535:
        raise ValueError("DoQ host-observed NAT tuple is invalid")
    if type(complete.get("accepted_queries")) is not int or complete["accepted_queries"] != 1 or (
        complete.get("response_queued_with_fin") is not True or complete.get("scope") != "host-loopback-NAT-peer"):
        raise ValueError("DoQ host completion differs from the one controlled transaction")


def run_fixture(args, limits=Limits()):
    args.evidence.mkdir(parents=True, exist_ok=True)
    paths = {name: args.evidence / name for name in ("doq.txt", "doq-peer.txt", "doq-peer-stderr.txt", "doq-emulator.txt", "doq.xml", "doq-peer-evidence.json")}
    if any(path.exists() for path in paths.values()):
        raise ValueError("DoQ evidence already exists; refuse reused or overwritten evidence")
    metadata = {"schema": 1, "serial": args.serial, "class": CLASS, "method": METHOD,
                "scope": "controlled JNI/UDP/Network fixture; not shipping provider/Plus/TUN/F25"}
    peer = None
    failure = None
    try:
        metadata.update(identity(args))
        adb = [str(args.adb), "-s", args.serial]
        guard_exit = command(adb + ["shell", "getprop", "ro.kernel.qemu"], paths["doq-emulator.txt"], limits.guard, limits.cleanup)
        if guard_exit != 0 or paths["doq-emulator.txt"].read_text().strip() != "1":
            raise ValueError("Refuse DoQ fixture: explicitly selected target is not a confirmed emulator")
        peer = Peer([str(args.peer.absolute()), "--cert", str(args.cert), "--key", str(args.key), "--max-duration-ms", "120000"],
                    paths["doq-peer.txt"], paths["doq-peer-stderr.txt"], limits.cleanup)
        readiness = peer.line(time.monotonic() + limits.ready)
        port = ready(readiness)
        metadata["peer_ready"] = readiness
        if peer.process.poll() is not None:
            raise ValueError("DoQ peer exited before instrumentation admission")
        argv = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", f"{CLASS}#{METHOD}",
                      "-e", "doqPeerHost", "10.0.2.2", "-e", "doqPeerPort", str(port), RUNNER]
        metadata["adb_exit"] = command(argv, paths["doq.txt"], limits.instrument, limits.cleanup)
        native_pass(paths["doq.txt"].read_text())
        if metadata["adb_exit"] != 0:
            raise ValueError("DoQ adb exited nonzero despite its instrumentation transcript")
        deadline = time.monotonic() + limits.finish
        records = []
        while True:
            value = peer.line(deadline)
            if value is None:
                break
            records.append(value)
            if len(records) > 2:
                raise ValueError("DoQ peer emitted extra terminal receipts")
        metadata["peer_exit"] = peer.process.wait(timeout=max(0.001, deadline - time.monotonic()))
        if metadata["peer_exit"] != 0:
            diagnostic = peer_diagnostic(paths["doq-peer-stderr.txt"])
            raise ValueError(f"DoQ host peer exited {metadata['peer_exit']}: {diagnostic or 'no stderr diagnostic'}")
        host_pass(records)
        metadata["peer_receipts"] = records
    except BaseException as error:
        failure = error
    finally:
        if peer is not None:
            try:
                peer.close(limits.cleanup)
            except Exception as cleanup:
                if failure is None:
                    failure = cleanup
                else:
                    metadata["cleanup_error"] = str(cleanup)
            metadata["peer_exit"] = peer.process.poll()
            try:
                diagnostic = peer_diagnostic(paths["doq-peer-stderr.txt"])
                if diagnostic:
                    metadata["peer_diagnostic"] = diagnostic
            except Exception as recording:
                if failure is None:
                    failure = recording
                else:
                    metadata["diagnostic_error"] = str(recording)
        try:
            text = paths["doq.txt"].read_text(encoding="utf-8", errors="replace") if paths["doq.txt"].exists() else ""
            suite = transcript_xml(text)
            if failure is not None:
                case = ET.SubElement(suite, "testcase", classname="runner", name="doq-peer-execution")
                ET.SubElement(case, "error", message=str(failure))
            ET.ElementTree(suite).write(paths["doq.xml"], encoding="utf-8", xml_declaration=True)
            metadata["status"] = "fail" if failure else "pass"
            if failure is not None:
                metadata["error"] = str(failure)
            paths["doq-peer-evidence.json"].write_text(json.dumps(metadata, indent=2) + "\n")
        except Exception as recording:
            if failure is None:
                failure = recording
            else:
                failure.add_note(f"DoQ evidence recording failed: {recording}")
    if failure is not None:
        raise failure
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("peer", "cert", "key", "adb", "evidence"):
        parser.add_argument(f"--{name}", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not args.serial.strip():
        parser.error("--serial must explicitly select the owned emulator")
    try:
        run_fixture(args)
    except Exception as error:
        print(f"ANDROID_DOQ_PEER: FAIL: {error}", file=sys.stderr)
        return 1
    print("ANDROID_DOQ_PEER: PASS (controlled JNI/socket fixture)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
