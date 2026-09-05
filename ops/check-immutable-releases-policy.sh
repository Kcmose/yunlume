#!/usr/bin/env bash
set -Eeuo pipefail

# This executable is the complete lifetime boundary for the Administration-read
# credential. The shell process and the one intended HTTPS client necessarily
# see it; no gh, jq, Python, mutation command, or later workflow step does.
repository="${1-}"
policy_token="${IMMUTABLE_RELEASES_READ_TOKEN-}"
unset IMMUTABLE_RELEASES_READ_TOKEN GH_TOKEN GITHUB_TOKEN GH_HOST GH_ENTERPRISE_TOKEN
auth_token='' GITHUB_ENTERPRISE_TOKEN='' GITHUB_API_URL='' GITHUB_SERVER_URL=''
unset auth_token GITHUB_ENTERPRISE_TOKEN GITHUB_API_URL GITHUB_SERVER_URL
unset GH_CONFIG_DIR XDG_CONFIG_HOME

[[ "$policy_token" =~ ^[A-Za-z0-9_]+$ ]] || {
  printf 'IMMUTABLE_RELEASES_READ_TOKEN is required and must use GitHub token grammar.\n' >&2
  exit 1
}
# GitHub owner names are 1-39 characters and repository names are 1-100. This
# strict subset deliberately rejects URL syntax, alternate hosts, and dot repos.
if [[ ! "$repository" =~ ^([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9-]{0,37}[A-Za-z0-9])/([A-Za-z0-9_]|[A-Za-z0-9_][A-Za-z0-9._-]{0,98}[A-Za-z0-9_-])$ ]]; then
  printf 'Repository must be one validated GitHub owner/repository pair.\n' >&2
  exit 1
fi

if ! response="$(curl --disable --silent --show-error --fail-with-body --request GET \
  --proto '=https' --tlsv1.2 --connect-timeout 15 --max-time 30 --config - \
  "https://api.github.com/repos/${repository}/immutable-releases" <<EOF
header = "Authorization: Bearer ${policy_token}"
header = "Accept: application/vnd.github+json"
header = "X-GitHub-Api-Version: 2026-03-10"
EOF
)"; then
  policy_token=''; unset policy_token
  printf 'Administration-read credential could not read immutable-release policy for %s (missing/403/network failure).\n' "$repository" >&2
  exit 1
fi
policy_token=''; unset policy_token

python3 -c '
import json, sys
try:
    value=json.load(sys.stdin)
except Exception as exc:
    raise SystemExit(f"Malformed immutable-release policy JSON: {exc}")
if not isinstance(value, dict) or set(value) != {"enabled", "enforced_by_owner"}:
    raise SystemExit("Immutable-release policy JSON must contain exactly enabled and enforced_by_owner")
if value["enabled"] is not True or value["enforced_by_owner"] is not True:
    raise SystemExit("Repository immutable releases must be enabled and enforced by the owner")
' <<<"$response"
