#!/usr/bin/env bash
set -Eeuo pipefail

readonly GH_VERSION=2.93.0
case "$(uname -m)" in
  x86_64)
    archive="gh_${GH_VERSION}_linux_amd64.tar.gz"
    expected_sha256="02d1290eba130e0b896f3709ffff22e1c75a51475ddb70476a85abc6b5807af0"
    ;;
  aarch64|arm64)
    archive="gh_${GH_VERSION}_linux_arm64.tar.gz"
    expected_sha256="c55feb33684abba57e9909737340d5b39282257c0363e1edde6785ac4a413be7"
    ;;
  *)
    printf 'Unsupported architecture for pinned GitHub CLI: %s\n' "$(uname -m)" >&2
    exit 1
    ;;
esac

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
url="https://github.com/cli/cli/releases/download/v${GH_VERSION}/${archive}"
curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --output "$work/$archive" "$url"
printf '%s  %s\n' "$expected_sha256" "$work/$archive" | sha256sum --check --strict
mkdir -p "$HOME/.local/bin"
tar -xzf "$work/$archive" -C "$work"
install -m 0755 "$work/gh_${GH_VERSION}_linux_"*/bin/gh "$HOME/.local/bin/gh"
if [[ -n "${GITHUB_PATH:-}" ]]; then
  printf '%s\n' "$HOME/.local/bin" >> "$GITHUB_PATH"
fi
PATH="$HOME/.local/bin:$PATH"
export PATH
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/lib/release-transaction.sh"
assert_pinned_gh_capabilities
