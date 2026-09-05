#!/usr/bin/env python3
from __future__ import annotations

import re
import sys

SEMVER = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")


def parse(value: str) -> tuple[int, int, int] | None:
    match = SEMVER.fullmatch(value)
    if match is None:
        return None
    major, minor, patch = match.groups()
    return int(major), int(minor), int(patch)


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: validate-release-version.py CURRENT_TAG [PUBLISHED_TAG ...]")

    current_tag = sys.argv[1]
    current = parse(current_tag)
    if current is None:
        raise SystemExit(f"release tag must use vX.Y.Z without leading zeroes: {current_tag}")

    published = [(version, tag) for tag in sys.argv[2:] if (version := parse(tag)) is not None]
    if not published:
        return

    latest_version, latest_tag = max(published)
    if current <= latest_version:
        raise SystemExit(
            f"release tag {current_tag} must be greater than latest published version {latest_tag}"
        )


if __name__ == "__main__":
    main()
