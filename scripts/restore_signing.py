#!/usr/bin/env python3
"""Restore an owner-approved signing bundle for an isolated CI release job."""

import base64
import io
import os
from pathlib import Path
import sys
from zipfile import ZipFile


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 scripts/restore_signing.py DESTINATION")
    encoded = os.environ.get("ANDROID_SIGNING_BUNDLE_BASE64", "")
    if not encoded:
        raise SystemExit("The owner must configure the ANDROID_SIGNING_BUNDLE_BASE64 Actions secret.")
    destination = Path(sys.argv[1]).resolve()
    destination.mkdir(parents=True, exist_ok=False, mode=0o700)
    required = {"legacy.jks", "release.jks", "legacy-password.txt", "release-password.txt", "lineage.bin"}
    with ZipFile(io.BytesIO(base64.b64decode(encoded, validate=True))) as archive:
        available = set(archive.namelist())
        if not required.issubset(available):
            raise SystemExit("Signing bundle is incomplete.")
        for name in required:
            content = archive.read(name)
            if len(content) > 100_000:
                raise SystemExit("Unexpected signing file size.")
            path = destination / name
            path.touch(mode=0o600, exist_ok=False)
            path.write_bytes(content)
    print("Signing inputs restored to the isolated job directory.")


if __name__ == "__main__":
    main()
