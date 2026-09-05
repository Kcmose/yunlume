#!/usr/bin/env python3

import json
import re
import sys
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(message)


def main() -> None:
    if len(sys.argv) != 10:
        fail(
            "usage: create-release-manifest.py VERSION OWNER BACKEND_DIGEST FRONTEND_DIGEST COMPOSE_SHA "
            "ARCHIVE_NAME ARCHIVE_SHA COMPATIBILITY_EPOCH OUTPUT"
        )

    (
        version,
        owner,
        backend_digest,
        frontend_digest,
        compose_sha,
        archive_name,
        archive_sha,
        epoch_text,
        output,
    ) = sys.argv[1:]
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        fail("version must use X.Y.Z")
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", owner):
        fail("owner must be a lowercase GHCR namespace")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", backend_digest):
        fail("backend image digest is invalid")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", frontend_digest):
        fail("frontend image digest is invalid")
    if not re.fullmatch(r"[0-9a-f]{64}", compose_sha):
        fail("compose SHA-256 is invalid")
    if not re.fullmatch(r"[0-9a-f]{64}", archive_sha):
        fail("archive SHA-256 is invalid")
    if archive_name != f"yunlume-host-v{version}.tar.gz":
        fail("archive name does not match version")
    if not re.fullmatch(r"[1-9][0-9]{0,8}", epoch_text):
        fail("compatibility epoch must be an integer from 1 to 999999999")

    manifest = {
        "version": version,
        "compatibilityEpoch": int(epoch_text),
        "docker": {
            "compose": "yunlume-compose.yml",
            "composeSha256": compose_sha,
            "backendImage": f"ghcr.io/{owner}/yunlume-backend@{backend_digest}",
            "frontendImage": f"ghcr.io/{owner}/yunlume-frontend@{frontend_digest}",
        },
        "host": {
            "archive": archive_name,
            "archiveSha256": archive_sha,
        },
    }
    Path(output).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )


if __name__ == "__main__":
    main()
