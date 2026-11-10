"""Produce and verify unsigned public distribution artifacts, without store credentials."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

from ci.release_policy import release_defaults, validate_tag, version_code
from ci.verify_android_bundle import verify_bundle
from ci.verify_foss_boundary import ANDROID, audit, dump_packaged_manifest, verify_manifest

REPOSITORY = "lavasecurity/lavasec-android"
SHA = re.compile(r"[0-9a-f]{40}\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")
MAX_ARTIFACT = 1024 * 1024 * 1024
MANIFEST = "release-manifest.json"
SUMS = "SHA256SUMS"
REPORTS = ("foss-boundary.json", "foss-apk-boundary.json")
LOCKS = ("android/gradle/libs.versions.toml", "android/gradle/wrapper/gradle-wrapper.properties",
         "android/react-native/package-lock.json", "ThirdParty/wireguard-core/Cargo.lock",
         "ThirdParty/doq-core/Cargo.lock", "ThirdParty/wireguard-core/rust-toolchain.toml",
         "release/android-version.properties")


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def canonical(value) -> bytes:
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=root, text=True).strip()


def context(root: Path, env, *, publishing=False) -> dict:
    if env.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise ValueError("release artifacts run only in the canonical public repository")
    source = env.get("GITHUB_SHA", "")
    if not SHA.fullmatch(source) or git(root, "rev-parse", "HEAD") != source:
        raise ValueError("checkout must equal the exact workflow source SHA")
    if git(root, "status", "--porcelain"):
        raise ValueError("release source checkout must be clean")
    event, ref = env.get("GITHUB_EVENT_NAME"), env.get("GITHUB_REF", "")
    run, attempt = env.get("GITHUB_RUN_ID", ""), env.get("GITHUB_RUN_ATTEMPT", "")
    if not re.fullmatch(r"[1-9][0-9]*", run) or not re.fullmatch(r"[1-9][0-9]*", attempt):
        raise ValueError("invalid workflow run identity")
    name, code = release_defaults(root)
    tag = ""
    if event == "pull_request" and re.fullmatch(r"refs/pull/[1-9][0-9]*/merge", ref) and not publishing:
        mode = "validation"
    elif event == "workflow_dispatch" and ref == "refs/heads/main" and not publishing:
        mode = "manual"
        if source != git(root, "rev-parse", "origin/main^{commit}"):
            raise ValueError("manual builds require current public main")
    elif event == "push" and ref.startswith("refs/tags/"):
        tag, mode = ref.removeprefix("refs/tags/"), "tag"
        if not validate_tag("external", tag) or tag != "v" + name or version_code("external", tag) != code:
            raise ValueError("stable tag must match canonical version and code")
        if git(root, "rev-parse", "--verify", ref + "^{commit}") != source:
            raise ValueError("tag must retain its exact source commit")
        # Main may advance while a tag waits/builds. Only reviewed main history is
        # accepted, and retries never authorize replacing an existing release.
        if subprocess.run(["git", "merge-base", "--is-ancestor", source, "origin/main"], cwd=root).returncode:
            raise ValueError("tag source must be reviewed public main history")
    else:
        raise ValueError("unsupported release event or ref")
    return {"repository": REPOSITORY, "sourceSha": source, "tag": tag, "mode": mode,
            "versionName": name, "versionCode": code, "runId": run, "runAttempt": attempt,
            "sourceDateEpoch": int(git(root, "show", "-s", "--format=%ct", source))}


def names(ctx: dict) -> dict[str, str]:
    suffix = f"{ctx['versionName']}-{ctx['versionCode']}-unsigned"
    return {"foss": f"lavasec-foss-{suffix}.apk", "play": f"lavasec-play-{suffix}.aab"}


def unsigned(archive: Path) -> None:
    with zipfile.ZipFile(archive) as bundle:
        entries = bundle.namelist()
        if len(entries) != len(set(entries)) or not entries:
            raise ValueError("ambiguous or empty Android archive")
        if any(re.fullmatch(r"META-INF/[^/]+\.(?:SF|RSA|DSA|EC)", p, re.I) for p in entries):
            raise ValueError("distribution input must be unsigned")
        # APK v2/v3 signatures live before the ZIP central directory, outside ZIP entries.
        if archive.suffix == ".apk":
            with archive.open("rb") as stream:
                stream.seek(max(0, archive.stat().st_size - 65557))
                tail = stream.read()
            eocd = tail.rfind(b"PK\x05\x06")
            if (eocd < 0 or len(tail) - eocd < 22 or
                eocd + 22 + struct.unpack_from("<H", tail, eocd + 20)[0] != len(tail)):
                raise ValueError("invalid APK ZIP directory")
            central = struct.unpack_from("<I", tail, eocd + 16)[0]
            with archive.open("rb") as stream:
                stream.seek(max(0, central - 16))
                if stream.read(16) == b"APK Sig Block 42":
                    raise ValueError("APK already has a signing block")


def identity(xml: bytes, package: str, ctx: dict) -> None:
    root = ET.fromstring(xml)
    app, sdk = root.find("application"), root.find("uses-sdk")
    if (root.tag != "manifest" or root.get("package") != package or
        root.get(ANDROID + "versionName") != ctx["versionName"] or
        root.get(ANDROID + "versionCode") != str(ctx["versionCode"]) or
        root.get(ANDROID + "versionCodeMajor", "0") != "0" or app is None or sdk is None or
        app.get(ANDROID + "debuggable", "false") != "false" or
        app.get(ANDROID + "testOnly", "false") != "false" or
        sdk.get(ANDROID + "minSdkVersion") != "28" or sdk.get(ANDROID + "targetSdkVersion") != "36"):
        raise ValueError("packaged release identity, SDK or debug flags differ from the release context")


def apk_payload(apk: Path, bundle: Path) -> dict:
    with zipfile.ZipFile(apk) as a, zipfile.ZipFile(bundle) as b:
        projected = []
        for p in b.namelist():
            if p.endswith("/"):
                continue
            if p.startswith("base/dex/"):
                projected.append((p.removeprefix("base/dex/"), p))
            elif p.startswith(("base/assets/", "base/lib/")):
                projected.append((p.removeprefix("base/"), p))
            elif p.startswith("base/root/"):
                projected.append((p.removeprefix("base/root/"), p))
        # AGP stores these in bundle metadata but installs the same bytes in APK paths.
        metadata = {"assets/dexopt/" + n: "BUNDLE-METADATA/com.android.tools.build.profiles/" + n
                    for n in ("baseline.prof", "baseline.profm")}
        metadata["META-INF/com/android/build/gradle/app-metadata.properties"] = "BUNDLE-METADATA/com.android.tools.build.gradle/app-metadata.properties"
        for target, origin in metadata.items():
            if origin not in b.namelist():
                continue
            existing = dict(projected).get(target)
            if existing is not None:
                if target != "META-INF/com/android/build/gradle/app-metadata.properties" or b.read(existing) != b.read(origin):
                    raise ValueError("ambiguous FOSS bundle metadata projection")
            else:
                projected.append((target, origin))
        expected = dict(projected)
        if len(expected) != len(projected):
            raise ValueError("ambiguous FOSS bundle to APK payload projection")
        # Binary Android resources/manifest use different encodings. The SDK decodes
        # and checks the APK manifest independently; every other APK file must match.
        actual = {p for p in a.namelist() if not p.endswith("/") and
                  not p.startswith("res/") and p not in ("AndroidManifest.xml", "resources.arsc")}
        if not expected or set(expected) != actual:
            raise ValueError("FOSS APK payload does not match the audited FOSS bundle")
        result = {}
        for target, origin in expected.items():
            if a.read(target) != b.read(origin):
                raise ValueError("FOSS APK payload differs from audited bundle: " + target)
            result[target] = hashlib.sha256(a.read(target)).hexdigest()
        return result


def decode_apk_manifest(apk: Path, analyzer: Path) -> bytes:
    data = subprocess.run([str(analyzer), "manifest", "print", str(apk)], capture_output=True, check=True, timeout=60).stdout
    if not data or len(data) > 1024 * 1024:
        raise ValueError("invalid decoded FOSS APK manifest")
    return data


def verify_zip_alignment(apk: Path, zipalign: Path) -> None:
    subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(apk)], check=True, capture_output=True, timeout=60)


def package(root: Path, ctx: dict, output: Path, bundletool: Path, analyzer: Path, zipalign: Path, graph: Path) -> None:
    if output.exists():
        raise ValueError("artifact output must be a new directory")
    if git(root, "rev-parse", "HEAD") != ctx["sourceSha"] or git(root, "status", "--porcelain"):
        raise ValueError("build changed the release source")
    app = root / "android/app/build"
    bundles = {e: app / f"outputs/bundle/{e}Release/app-{e}-release.aab" for e in ("play", "foss")}
    apk = app / "outputs/apk/foss/release/app-foss-release-unsigned.apk"
    for path in (*bundles.values(), apk):
        if path.is_symlink() or not path.is_file():
            raise ValueError("missing or linked release build input")
        unsigned(path)
    for edition, bundle in bundles.items():
        verify_bundle(bundle, app / f"intermediates/stripped_native_libs/{edition}Release/strip{edition.title()}ReleaseDebugSymbols/out/lib",
                      root / "android/app/src/main/assets/index.android.bundle", edition=edition)
        xml = dump_packaged_manifest(bundle, bundletool)
        identity(xml, "com.lavasec.app" + (".foss" if edition == "foss" else ""), ctx)
    foss = audit(bundles["foss"], graph, bundletool)
    decoded = decode_apk_manifest(apk, analyzer)
    identity(decoded, "com.lavasec.app.foss", ctx)
    verify_manifest(decoded)
    verify_zip_alignment(apk, zipalign)
    payload = apk_payload(apk, bundles["foss"])
    output.mkdir(parents=True)
    for edition, filename in names(ctx).items():
        shutil.copyfile(apk if edition == "foss" else bundles["play"], output / filename)
    (output / REPORTS[0]).write_bytes(canonical(foss))
    (output / REPORTS[1]).write_bytes(canonical({"schemaVersion": 1, "packageName": "com.lavasec.app.foss",
        "apkSha256": digest(apk), "bundleSha256": digest(bundles["foss"]),
        "packagedManifestSha256": hashlib.sha256(decoded).hexdigest(), "payload": payload}))
    artifacts = [{"file": filename, "edition": edition, "packageName": "com.lavasec.app" + (".foss" if edition == "foss" else ""),
                  "signing": "unsigned", "sha256": digest(output / filename), "size": (output / filename).stat().st_size}
                 for edition, filename in names(ctx).items()]
    manifest = {"schemaVersion": 1, "context": ctx, "artifacts": artifacts,
                "reports": {p: digest(output / p) for p in REPORTS},
                "sourceInputs": {p: digest(root / p) for p in LOCKS},
                "runner": {"os": os.environ.get("ImageOS", ""), "imageVersion": os.environ.get("ImageVersion", "")}}
    (output / MANIFEST).write_bytes(canonical(manifest))
    files = sorted([*names(ctx).values(), *REPORTS, MANIFEST])
    (output / SUMS).write_text("".join(f"{digest(output / p)}  {p}\n" for p in files), encoding="utf-8")
    verify(output, ctx, root)


def verify(directory: Path, ctx: dict, root: Path, *, extra_files=()) -> dict:
    allowed = {*names(ctx).values(), *REPORTS, MANIFEST, SUMS}
    if directory.is_symlink() or {p.name for p in directory.iterdir()} != allowed | set(extra_files):
        raise ValueError("unexpected release artifact inventory")
    for p in directory.iterdir():
        if p.is_symlink() or not p.is_file() or not 0 < p.stat().st_size <= MAX_ARTIFACT:
            raise ValueError("invalid release artifact file")
    manifest = json.loads((directory / MANIFEST).read_bytes())
    if (not isinstance(manifest, dict) or
        set(manifest) != {"schemaVersion", "context", "artifacts", "reports", "sourceInputs", "runner"} or
        manifest["schemaVersion"] != 1 or manifest["context"] != ctx or
        (directory / MANIFEST).read_bytes() != canonical(manifest)):
        raise ValueError("release artifact context mismatch")
    expected = [{"file": filename, "edition": edition, "packageName": "com.lavasec.app" + (".foss" if edition == "foss" else ""),
                 "signing": "unsigned", "sha256": digest(directory / filename), "size": (directory / filename).stat().st_size}
                for edition, filename in names(ctx).items()]
    if manifest["artifacts"] != expected or manifest["reports"] != {p: digest(directory / p) for p in REPORTS}:
        raise ValueError("release artifact digest or identity mismatch")
    if manifest["sourceInputs"] != {p: digest(root / p) for p in LOCKS}:
        raise ValueError("invalid source input digest inventory")
    expected_sums = "".join(f"{digest(directory / p)}  {p}\n" for p in sorted(allowed - {SUMS}))
    if (directory / SUMS).read_text(encoding="utf-8") != expected_sums:
        raise ValueError("release checksum list mismatch")
    for filename in names(ctx).values():
        unsigned(directory / filename)
    return manifest


def unpack(archive: Path, expected_digest: str, output: Path, ctx: dict, root: Path, *, extra_files=()) -> None:
    if not HASH.fullmatch(expected_digest) or digest(archive) != expected_digest or output.exists():
        raise ValueError("release archive digest or destination mismatch")
    allowed = {*names(ctx).values(), *REPORTS, MANIFEST, SUMS, *extra_files}
    with zipfile.ZipFile(archive) as z:
        entries = z.infolist()
        if len(entries) != len(allowed) or {p.filename for p in entries} != allowed:
            raise ValueError("unexpected archive file inventory")
        for p in entries:
            mode = stat.S_IFMT(p.external_attr >> 16)
            if mode not in (0, stat.S_IFREG) or p.is_dir() or not 0 < p.file_size <= MAX_ARTIFACT:
                raise ValueError("unsafe release archive entry")
        output.mkdir(parents=True)
        for p in entries:
            with z.open(p) as source, (output / p.filename).open("xb") as target:
                shutil.copyfileobj(source, target)
    verify(output, ctx, root, extra_files=extra_files)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "package", "unpack"))
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--context", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--bundletool", type=Path)
    parser.add_argument("--apkanalyzer", type=Path)
    parser.add_argument("--zipalign", type=Path)
    parser.add_argument("--dependencies", type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--digest")
    args = parser.parse_args()
    if args.command == "package" and any(x is None for x in (args.context, args.bundletool, args.apkanalyzer, args.zipalign, args.dependencies)):
        parser.error("package requires --context, --bundletool, --apkanalyzer, --zipalign and --dependencies")
    if args.command == "unpack" and (args.archive is None or args.digest is None):
        parser.error("unpack requires --archive and --digest")
    root = args.root.resolve()
    try:
        if args.command == "prepare":
            ctx = context(root, os.environ)
            args.output.write_bytes(canonical(ctx))
            with Path(os.environ["GITHUB_OUTPUT"]).open("a") as stream:
                for key in ("sourceSha", "tag", "mode", "versionName", "versionCode", "sourceDateEpoch"):
                    stream.write(f"{key}={ctx[key]}\n")
        elif args.command == "package":
            ctx = context(root, os.environ)
            if json.loads(args.context.read_bytes()) != ctx:
                raise ValueError("release context changed during build")
            package(root, ctx, args.output, args.bundletool, args.apkanalyzer, args.zipalign, args.dependencies)
        else:
            ctx = context(root, os.environ, publishing=True)
            unpack(args.archive, args.digest, args.output, ctx, root)
        print("Release source coordinates verified." if args.command == "prepare" else "Unsigned Android release artifacts verified.")
    except (ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile, ET.ParseError) as error:
        parser.exit(1, f"Android release artifacts: {error}\n")


if __name__ == "__main__":
    main()
