#!/usr/bin/env python3
"""Sign an owner release and verify its Android 8 and Android 9+ certificates."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: python3 scripts/sign_release.py INPUT.apk OUTPUT.apk")
    project = Path(__file__).resolve().parents[1]
    signing = os.environ.get("PULSE_SIGNING_DIR")
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not signing or not sdk:
        raise SystemExit("Set PULSE_SIGNING_DIR and ANDROID_SDK_ROOT before signing.")
    signing = Path(signing).resolve()
    inputs = {
        name: signing / name for name in (
            "legacy.jks", "release.jks", "legacy-password.txt", "release-password.txt", "lineage.bin"
        )
    }
    for path in inputs.values():
        if not path.is_file():
            raise SystemExit("Missing signing input: " + path.name)
    expected = json.loads((project / "certificates/signers.json").read_text())
    proof = hashlib.sha256(inputs["lineage.bin"].read_bytes()).hexdigest()
    if proof != expected["lineageSha256"]:
        raise SystemExit("Signing lineage does not match the public certificate record.")
    tools = Path(sdk) / "build-tools/35.0.0"
    source = Path(sys.argv[1]).resolve()
    destination = Path(sys.argv[2]).resolve()
    if not source.is_file() or source == destination:
        raise SystemExit("Use an existing input APK and a separate output path.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="pulse-sign-", dir=destination.parent) as temporary:
        aligned = Path(temporary) / "aligned.apk"
        signed = Path(temporary) / "signed.apk"
        subprocess.run([str(tools / "zipalign"), "-f", "-P", "16", "4", str(source), str(aligned)], check=True)
        command = [str(tools / "apksigner"), "sign", "--out", str(signed),
                   "--lineage", str(inputs["lineage.bin"]), "--rotation-min-sdk-version", "28",
                   "--v4-signing-enabled", "false"]
        for name, alias in (("legacy", "pulse"), ("release", "pulse-release")):
            if name == "release":
                command.append("--next-signer")
            password = "file:" + str(inputs[name + "-password.txt"])
            command += ["--ks", str(inputs[name + ".jks"]), "--ks-key-alias", alias,
                        "--ks-pass", password]
        subprocess.run(command + [str(aligned)], check=True)
        for minimum, maximum, field in ((26, 27, "legacySha256"), (28, 32, "releaseSha256"), (33, 36, "releaseSha256")):
            result = subprocess.run(
                [str(tools / "apksigner"), "verify", "--verbose", "--print-certs",
                 "--min-sdk-version", str(minimum), "--max-sdk-version", str(maximum), str(signed)],
                check=True, text=True, capture_output=True
            )
            match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", result.stdout)
            if not match or match.group(1) != expected[field]:
                raise SystemExit("Unexpected signing certificate for Android API " + str(minimum))
        subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(signed)], check=True)
        os.replace(signed, destination)
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    destination.with_suffix(".apk.sha256").write_text(digest + "  " + destination.name + "\n")
    print("Verified release APK and checksum: " + destination.name)


if __name__ == "__main__":
    main()
