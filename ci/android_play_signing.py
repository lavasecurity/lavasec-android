"""Sign only the verified public Play AAB; preserve the unsigned FOSS build and evidence."""
from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

from ci import android_release_artifacts as build

POLICY = "release/android-play-signing.json"
RECORD = "play-signing-record.json"
SUMS = "PLAY-SIGNED-SHA256SUMS"
SIGNATURE_FILES = {"META-INF/MANIFEST.MF", "META-INF/LAVASEC.SF", "META-INF/LAVASEC.RSA"}
SECRET_NAMES = ("ANDROID_UPLOAD_KEYSTORE_BASE64", "ANDROID_UPLOAD_KEYSTORE_PASSWORD", "ANDROID_UPLOAD_KEY_PASSWORD")


def policy(root: Path) -> dict:
    result = json.loads((root / POLICY).read_bytes())
    if (not isinstance(result, dict) or set(result) != {"schemaVersion", "keyAlias", "storeSha256", "certificateSha256"} or
        type(result["schemaVersion"]) is not int or result["schemaVersion"] != 1 or
        not isinstance(result["keyAlias"], str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}", result["keyAlias"]) or
        not all(isinstance(result[k], str) and build.HASH.fullmatch(result[k]) for k in ("storeSha256", "certificateSha256"))):
        raise ValueError("invalid reviewed Play signing policy")
    return result


def signing_context(root: Path, env) -> dict:
    ctx = build.context(root, env)
    if ctx["mode"] not in ("manual", "tag"):
        raise ValueError("PR builds cannot access Play signing")
    return ctx


def signed_name(ctx: dict) -> str:
    return build.names(ctx)["play"].replace("-unsigned.aab", "-upload-signed.aab")


def extra_files(ctx: dict) -> set[str]:
    return {signed_name(ctx), RECORD, SUMS}


def tool_env(env, *secrets: str) -> dict:
    result = {k: v for k, v in env.items() if k not in SECRET_NAMES}
    for name in secrets:
        result[name] = env[name]
    return result


def run_tool(command: list[str], env) -> bytes:
    # Never echo subprocess arguments/output: keytool errors can include sensitive input.
    result = subprocess.run(command, env=env, capture_output=True, timeout=120)
    if result.returncode:
        raise ValueError(Path(command[0]).name + " rejected Play signing or verification")
    return result.stdout


def payload(unsigned: Path, signed: Path) -> dict:
    with zipfile.ZipFile(unsigned) as a, zipfile.ZipFile(signed) as b:
        left = {p.filename for p in a.infolist() if not p.is_dir()}
        right = {p.filename for p in b.infolist() if not p.is_dir()}
        if (len(a.namelist()) != len(set(a.namelist())) or len(b.namelist()) != len(set(b.namelist())) or
            not left or left & SIGNATURE_FILES or right != left | SIGNATURE_FILES):
            raise ValueError("ambiguous Play AAB signature or payload inventory")
        digests = {}
        for name in sorted(left):
            original = a.read(name)
            if original != b.read(name):
                raise ValueError("signing changed the Play AAB payload")
            digests[name] = hashlib.sha256(original).hexdigest()
    return digests


def verify_signature(root: Path, signed: Path, cert_sha: str, env) -> None:
    run_tool(["java", str(root / "ci/VerifyPlaySignature.java"), str(signed), cert_sha], tool_env(env))


def record(directory: Path, ctx: dict, root: Path, expected: dict, digests: dict) -> dict:
    original = directory / build.names(ctx)["play"]
    signed = directory / signed_name(ctx)
    return {"schemaVersion": 1, "context": ctx,
            "buildManifestSha256": build.digest(directory / build.MANIFEST),
            "signingPolicySha256": build.digest(root / POLICY),
            "uploadCertificateSha256": expected["certificateSha256"],
            "uploadKeystoreSha256": expected["storeSha256"],
            "input": {"file": original.name, "sha256": build.digest(original), "size": original.stat().st_size},
            "output": {"file": signed.name, "sha256": build.digest(signed), "size": signed.stat().st_size},
            "payloadEntries": len(digests), "payloadSha256": hashlib.sha256(build.canonical(digests)).hexdigest()}


def checksums(directory: Path) -> str:
    return "".join(f"{build.digest(p)}  {p.name}\n" for p in sorted(directory.iterdir()) if p.name != SUMS)


def verify(directory: Path, ctx: dict, root: Path, env) -> None:
    build.verify(directory, ctx, root, extra_files=extra_files(ctx))
    expected = policy(root)
    digests = payload(directory / build.names(ctx)["play"], directory / signed_name(ctx))
    verify_signature(root, directory / signed_name(ctx), expected["certificateSha256"], env)
    if (directory / RECORD).read_bytes() != build.canonical(record(directory, ctx, root, expected, digests)):
        raise ValueError("Play signing record differs from verified inputs")
    if (directory / SUMS).read_text(encoding="utf-8") != checksums(directory):
        raise ValueError("signed distribution checksum list mismatch")


def sign(directory: Path, output: Path, ctx: dict, root: Path, env) -> None:
    if ctx["mode"] not in ("manual", "tag") or output.exists():
        raise ValueError("signing requires an authorized release context and a new output directory")
    # Verify the entire build inventory before reading or materializing any signing secret.
    build.verify(directory, ctx, root)
    expected = policy(root)
    if any(not env.get(k) for k in SECRET_NAMES):
        raise ValueError("protected Play signing secrets are not configured")
    try:
        key_bytes = base64.b64decode("".join(env[SECRET_NAMES[0]].split()), validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("invalid protected upload keystore encoding") from None
    if hashlib.sha256(key_bytes).hexdigest() != expected["storeSha256"]:
        raise ValueError("upload keystore differs from reviewed existing key")
    with tempfile.TemporaryDirectory(prefix="lavasec-play-signing-", dir=output.parent) as temporary:
        work = Path(temporary)
        keystore = work / "upload.jks"
        with keystore.open("xb") as stream:
            keystore.chmod(0o600)
            stream.write(key_bytes)
        certificate = run_tool(["keytool", "-exportcert", "-keystore", str(keystore), "-alias", expected["keyAlias"],
                                "-storepass:env", SECRET_NAMES[1]], tool_env(env, SECRET_NAMES[1]))
        if hashlib.sha256(certificate).hexdigest() != expected["certificateSha256"]:
            raise ValueError("upload certificate differs from reviewed existing key")
        candidate = work / "release-assets"
        candidate.mkdir(mode=0o700)
        for source in directory.iterdir():
            shutil.copyfile(source, candidate / source.name)
        run_tool(["jarsigner", "-keystore", str(keystore), "-storepass:env", SECRET_NAMES[1],
                  "-keypass:env", SECRET_NAMES[2], "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256",
                  "-sigfile", "LAVASEC", "-signedjar", str(candidate / signed_name(ctx)),
                  str(directory / build.names(ctx)["play"]), expected["keyAlias"]],
                 tool_env(env, SECRET_NAMES[1], SECRET_NAMES[2]))
        digests = payload(candidate / build.names(ctx)["play"], candidate / signed_name(ctx))
        (candidate / RECORD).write_bytes(build.canonical(record(candidate, ctx, root, expected, digests)))
        (candidate / SUMS).write_text(checksums(candidate), encoding="utf-8")
        verify(candidate, ctx, root, env)
        candidate.rename(output)
    print("Verified Play AAB signed with the existing upload key; FOSS inputs unchanged and unsigned.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("unpack-build", "sign", "unpack-signed"))
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--digest")
    args = parser.parse_args()
    if args.command == "sign" and args.directory is None:
        parser.error("sign requires --directory")
    if args.command != "sign" and (args.archive is None or args.digest is None):
        parser.error("unpack requires --archive and --digest")
    try:
        root = args.root.resolve()
        ctx = signing_context(root, os.environ)
        if args.command == "sign":
            sign(args.directory, args.output, ctx, root, os.environ)
        elif args.command == "unpack-build":
            build.unpack(args.archive, args.digest, args.output, ctx, root)
            print("Verified unsigned build for protected Play signing.")
        else:
            # Only stable tags authorize a release-writing handoff.
            ctx = build.context(root, os.environ, publishing=True)
            build.unpack(args.archive, args.digest, args.output, ctx, root, extra_files=extra_files(ctx))
            verify(args.output, ctx, root, os.environ)
            print("Verified signed Play distribution and unchanged unsigned FOSS build.")
    except (ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Protected Play signing: {error}\n")


if __name__ == "__main__":
    main()
