#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

mkdir -p "$work/bin"
cat > "$work/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\0' "$@" > "$MOCK_CURL_ARGV"
env -0 > "$MOCK_CURL_ENV"
cat > "$MOCK_CURL_CONFIG"
case "${MOCK_POLICY_MODE:-ok}" in
  ok) printf '{"enabled":true,"enforced_by_owner":true}\n' ;;
  disabled) printf '{"enabled":false,"enforced_by_owner":true}\n' ;;
  malformed) printf '{"enabled":true,"enforced_by_owner":"true"}\n' ;;
  extra) printf '{"enabled":true,"enforced_by_owner":true,"surprise":1}\n' ;;
  forbidden) exit 22 ;;
esac
MOCK
chmod 0755 "$work/bin/curl"
export PATH="$work/bin:$PATH"
export MOCK_CURL_ARGV="$work/argv" MOCK_CURL_ENV="$work/env" MOCK_CURL_CONFIG="$work/config"
export GH_TOKEN=wrong GITHUB_TOKEN=wrong GH_HOST=evil.example GH_ENTERPRISE_TOKEN=wrong
export GH_CONFIG_DIR="$work/gh-config" XDG_CONFIG_HOME="$work/xdg" MOCK_POLICY_MODE=ok

IMMUTABLE_RELEASES_READ_TOKEN=policy_secret \
  "$SCRIPT_DIR/check-immutable-releases-policy.sh" Valid-Owner/repo.name

python3 - "$work" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
argv=p.joinpath('argv').read_bytes().split(b'\0')[:-1]
env=dict(x.split(b'=',1) for x in p.joinpath('env').read_bytes().split(b'\0') if b'=' in x)
config=p.joinpath('config').read_text()
assert argv == [b'--disable',b'--silent',b'--show-error',b'--fail-with-body',b'--request',b'GET',b'--proto',b'=https',b'--tlsv1.2',b'--connect-timeout',b'15',b'--max-time',b'30',b'--config',b'-',b'https://api.github.com/repos/Valid-Owner/repo.name/immutable-releases']
for key in (b'IMMUTABLE_RELEASES_READ_TOKEN',b'GH_TOKEN',b'GITHUB_TOKEN',b'GH_HOST',b'GH_ENTERPRISE_TOKEN',b'GH_CONFIG_DIR',b'XDG_CONFIG_HOME'):
    assert key not in env, (key, env.get(key))
assert config.startswith('header = "Authorization: Bearer ')
assert '\nheader = "Accept: application/vnd.github+json"' in config
assert 'policy_secret' not in b'\0'.join(argv).decode()
assert 'evil.example' not in b'\0'.join(argv).decode()
PY

for repository in 'https://evil.example/o/r' 'o/r/extra' '../r' 'owner/-bad' 'owner/repo?x=1' 'owner/.git'; do
  if IMMUTABLE_RELEASES_READ_TOKEN=policy_secret "$SCRIPT_DIR/check-immutable-releases-policy.sh" "$repository" >/dev/null 2>&1; then
    fail "invalid repository was accepted: $repository"
  fi
done
if "$SCRIPT_DIR/check-immutable-releases-policy.sh" owner/repo >/dev/null 2>&1; then
  fail 'missing policy token was accepted'
fi
for mode in forbidden disabled malformed extra; do
  export MOCK_POLICY_MODE="$mode"
  if IMMUTABLE_RELEASES_READ_TOKEN=policy_secret "$SCRIPT_DIR/check-immutable-releases-policy.sh" owner/repo >/dev/null 2>&1; then
    fail "policy response mode was accepted: $mode"
  fi
done
printf 'Immutable release policy isolation tests passed.\n'
