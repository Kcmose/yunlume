#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
workflow_source="$(<"${SCRIPT_DIR}/../.github/workflows/publish-images.yml")"

validation_source="${workflow_source}"$'\n'"$(<"${SCRIPT_DIR}/release-preflight.sh")"$'\n'"$(<"${SCRIPT_DIR}/lib/publish-workflow.sh")"

require_workflow_text() {
  local description="$1"
  local expected="$2"
  if [[ "${validation_source}" != *"${expected}"* ]]; then
    printf 'Release workflow is missing %s: %s\n' "${description}" "${expected}" >&2
    exit 1
  fi
}

require_workflow_text 'branch-only sha tag generation' \
  "type=sha,format=long,enable=\${{ github.ref_type != 'tag' }}"
require_workflow_text 'default-branch latest tag generation' \
  'type=raw,value=latest,enable={{is_default_branch}}'
require_workflow_text 'commit-stable tag release candidate generation' \
  "type=raw,value=release-candidate-\${{ github.sha }},enable=\${{ github.ref_type == 'tag' }}"
require_workflow_text 'commit-stable candidate lookup' \
  'CANDIDATE_TAG: release-candidate-${{ github.sha }}'
require_workflow_text 'existing candidate digest reuse' \
  'printf '\''exists=true\ndigest=%s\narchive_ready=false\n'\'' "$candidate_digest" >> "$GITHUB_OUTPUT"'
require_workflow_text 'existing candidate build bypass' \
  "steps.candidate.outputs.archive_ready != 'true'"
require_workflow_text 'candidate OCI is constructed without registry publication' \
  'outputs: type=oci,dest=/tmp/release-candidate-${{ matrix.image }}.oci.tar'
require_workflow_text 'candidate local build does not push' \
  'push: false'
require_workflow_text 'candidate root digest validation' \
  'root_digest="$(oci_archive_root_digest "$OCI_ARCHIVE")"'
require_workflow_text 'candidate archive file hash is separately named' \
  'archive_file_sha256="$(oci_archive_file_sha256 "$OCI_ARCHIVE")"'
require_workflow_text 'candidate provenance exists before publication' \
  'name: Attest immutable candidate before registry publication'
require_workflow_text 'transactional known-address candidate publication' \
  'publish_candidate_transaction "$IMAGE" "$CANDIDATE_TAG" "$OCI_ARCHIVE" "$DIGEST"'
require_workflow_text 'published candidate recovery verifies exact committed digest attestation' \
  'candidate_verify_attestation "$IMAGE" "$candidate_digest"'
if [[ "${workflow_source}" == *'release-candidate-${{ github.run_id }}-${{ github.run_attempt }}'* ]]; then
  printf 'Release workflow still uses an attempt-scoped candidate tag.\n' >&2
  exit 1
fi

require_workflow_text 'remote tag object lookup' \
  'gh api "repos/$repository/git/ref/tags/$tag"'
require_workflow_text 'annotated tag dereference' \
  'gh api "repos/$repository/git/tags/$sha"'
require_workflow_text 'shared tag guard at publication boundaries' \
  'assert_release_tag_commit "$REPOSITORY" "$RELEASE_TAG" "$GITHUB_SHA" || exit 1'
require_workflow_text 'approved default branch lookup' \
  'default_branch="$(gh api "repos/$REPOSITORY"'
require_workflow_text 'tagged commit ancestry comparison' \
  'repos/$REPOSITORY/compare/$GITHUB_SHA...$encoded_default_branch'
require_workflow_text 'tagged commit must belong to default branch history' \
  '[[ "$default_branch_status" == ahead || "$default_branch_status" == identical ]]'

require_workflow_text 'immutable destination preflight' \
  'assert_immutable_destination "$component" "$candidate_digest"'
require_workflow_text 'different-digest refusal' \
  'refusing to overwrite immutable release tag'
require_workflow_text 'same-digest retry convergence' \
  'already has candidate digest; continuing recovery'
require_workflow_text 'cross-service atomicity boundary documentation' \
  'GHCR and GitHub Releases cannot be updated atomically'
require_workflow_text 'recoverable draft release creation' \
  'draft=true'
require_workflow_text 'idempotent exact-ID draft asset upload' \
  'upload_release_asset_by_id "$REPOSITORY" "$release_id" "$asset"'
require_workflow_text 'publish only after uploads converge' \
  'publish_release_by_id "$REPOSITORY" "$release_id"'
require_workflow_text 'single-purpose Administration-read policy helper' \
  'IMMUTABLE_RELEASES_READ_TOKEN="${{ secrets.IMMUTABLE_RELEASES_READ_TOKEN }}" ops/check-immutable-releases-policy.sh "$REPOSITORY"'
if [[ "${workflow_source}" == *'IMMUTABLE_RELEASES_READ_TOKEN:'* ]]; then
  printf 'Policy token is still exported at step scope.\n' >&2
  exit 1
fi
require_workflow_text 'executable policy isolation test in CI' \
  'ops/immutable-release-policy-test.sh'
require_workflow_text 'pinned GitHub CLI installer' \
  'ops/install-pinned-gh.sh'
require_workflow_text 'pinned GitHub CLI capability gate' \
  'assert_pinned_gh_capabilities'
require_workflow_text 'published release immutability read-back' \
  '"$published_immutable" == true'
require_workflow_text 'GitHub immutable Release attestation verification' \
  'gh release verify "$RELEASE_TAG" --repo "$REPOSITORY"'
require_workflow_text 'GitHub immutable Release asset verification' \
  'gh release verify-asset "$RELEASE_TAG" "$verify_dir/$asset_name"'
require_workflow_text 'final tag identity guard immediately before publication' \
  '# Final remote tag identity guard: keep immediately before draft=false.'

final_guard="${workflow_source##*# Final remote tag identity guard: keep immediately before draft=false.}"
if [[ "${final_guard}" != *'assert_release_tag_commit "$REPOSITORY" "$RELEASE_TAG" "$GITHUB_SHA" || exit 1'* ||
      "${final_guard}" != *'gh api "repos/$REPOSITORY/compare/$GITHUB_SHA...$default_branch_ref"'* ||
      "${final_guard}" != *'[[ "$default_branch_status" == "ahead" || "$default_branch_status" == "identical" ]]'* ||
      "${final_guard}" != *'publish_release_by_id "$REPOSITORY" "$release_id"'* ]]; then
  printf 'Release workflow final publication guard does not recursively validate the remote tag.\n' >&2
  exit 1
fi
final_guard_prefix="${final_guard%%publish_release_by_id*}"
if [[ "${final_guard_prefix}" == *'gh release upload '* ||
      "${final_guard_prefix}" == *'gh release download '* ||
      "${final_guard_prefix}" == *'cmp -- '* ]]; then
  printf 'Release workflow performs asset work between the final tag guard and publication.\n' >&2
  exit 1
fi

publish_marker='publish_release_by_id "$REPOSITORY" "$release_id"'
rolling_marker='--tag "${image}:${minor_version}"'
publish_offset="${workflow_source%%"${publish_marker}"*}"
rolling_offset="${workflow_source%%"${rolling_marker}"*}"
if [[ "${workflow_source}" != *"${rolling_marker}"* ||
      ${#rolling_offset} -le ${#publish_offset} ]]; then
  printf 'Rolling major.minor image tags must be promoted only after the Release is published.\n' >&2
  exit 1
fi
require_workflow_text 'published release read-back verification' \
  'Release publication read-back did not match the intended immutable release'
readback_offset="${workflow_source%%Release publication read-back did not match the intended immutable release*}"
if (( ${#readback_offset} >= ${#rolling_offset} )); then
  printf 'Rolling aliases are promoted before published Release read-back verification.\n' >&2
  exit 1
fi
require_workflow_text 'post-publication remote tag verification' \
  'assert_release_tag_commit "$REPOSITORY" "$RELEASE_TAG" "$GITHUB_SHA" || exit 1'

require_workflow_text 'revision-bound PostgreSQL migration runtime test' \
  'test "$(git rev-parse HEAD)" = "$GITHUB_SHA"'
require_workflow_text 'PostgreSQL migration runtime test hashes the exact built JAR' \
  'migration_jar_sha="$(sha256sum nav-backend/target/nav-backend-0.1.0.jar | awk '\''{print $1}'\'')"'
require_workflow_text 'PostgreSQL migration runtime test validates its JAR hash input' \
  '[[ "$migration_jar_sha" =~ ^[0-9a-f]{64}$ ]]'
require_workflow_text 'PostgreSQL migration runtime test receives JAR and exact hash' \
  'ops/postgresql-migration-test.sh nav-backend/target/nav-backend-0.1.0.jar "$migration_jar_sha"'
backend_package_marker='mvn --batch-mode --no-transfer-progress clean verify'
migration_runtime_marker='ops/postgresql-migration-test.sh'
backend_package_prefix="${workflow_source%%"${backend_package_marker}"*}"
migration_runtime_prefix="${workflow_source%%"${migration_runtime_marker}"*}"
if [[ "${workflow_source}" != *"${backend_package_marker}"* ||
      "${workflow_source}" != *"${migration_runtime_marker}"* ||
      ${#migration_runtime_prefix} -le ${#backend_package_prefix} ]]; then
  printf 'PostgreSQL migration runtime test must run after this checkout builds the backend JAR.\n' >&2
  exit 1
fi

require_workflow_text 'backend JAR artifact is downloaded by publish job' \
  'name: Download exact tested backend JAR'
backend_test_job="${workflow_source#*$'\n''  backend-test:'}"
backend_test_job="${backend_test_job%%$'\n''  backend-attest:'*}"
if [[ "${backend_test_job}" == *'id-token: write'* ||
      "${backend_test_job}" == *'attestations: write'* ||
      "${backend_test_job}" == *'actions/attest-build-provenance'* ]]; then
  printf 'Backend tests still execute repository code with attestation or OIDC write authority.\n' >&2
  exit 1
fi
backend_attest_job="${workflow_source#*$'\n''  backend-attest:'}"
backend_attest_job="${backend_attest_job%%$'\n''  publish:'*}"
if [[ "${backend_attest_job}" != *'attestations: write'* ||
      "${backend_attest_job}" != *'id-token: write'* ||
      "${backend_attest_job}" == *'actions/checkout'* ||
      "${backend_attest_job}" == *'ops/'* ]]; then
  printf 'Backend JAR attestation is not isolated from repository-controlled executables.\n' >&2
  exit 1
fi
publish_job="${workflow_source#*$'\n''  publish:'}"
publish_job="${publish_job%%$'\n''  release:'*}"
if [[ "${publish_job}" == *'subject-path: tested-backend-jar/app.jar'* ||
      "${publish_job}" == *'id: backend-jar-attestation'* ]]; then
  printf 'Backend JAR attestation authority is still combined with repository build execution.\n' >&2
  exit 1
fi
require_workflow_text 'isolated backend JAR attestation job' \
  'backend-attest:'
require_workflow_text 'isolated attestation job uploads tested backend JAR transaction' \
  'name: Upload attested backend JAR transaction'
require_workflow_text 'backend image consumes exact tested JAR build context' \
  'context: ${{ matrix.image == '\''backend'\'' && '\''./backend-image-context'\'' || matrix.context }}'
require_workflow_text 'release job downloads rather than rebuilds backend JAR' \
  'name: Download exact tested backend JAR for release'
require_workflow_text 'published backend image JAR byte verification' \
  'verify_container_jar_sha "${image}@${published_digest}" "$BACKEND_JAR_SHA"'
require_workflow_text 'Host archive backend JAR byte verification' \
  'verify_host_archive_jar_sha "release/$archive_name" "$BACKEND_JAR_SHA"'
if [[ "${workflow_source}" == *'name: Build release backend'* ]]; then
  printf 'Release workflow still independently rebuilds the backend JAR.\n' >&2
  exit 1
fi

require_workflow_text 'backend OCI digest manifest input' \
  'BACKEND_DIGEST: ${{ steps.image-digests.outputs.backend }}'
require_workflow_text 'frontend OCI digest manifest input' \
  'FRONTEND_DIGEST: ${{ steps.image-digests.outputs.frontend }}'

require_workflow_text 'published self-release preflight convergence' \
  'published_release=true'
require_workflow_text 'release target commit binding' \
  'existing_release_target'
require_workflow_text 'release target is resolved through GitHub commits API' \
  'resolve_target_commitish'
require_workflow_text 'release resolved target must equal workflow commit' \
  '[[ "$resolved_release_target" == "$GITHUB_SHA" ]]'
if [[ "${workflow_source}" == *'"$existing_release_target" == "$RELEASE_TAG"'* ||
      "${workflow_source}" == *'"$existing_release_target" == "$default_branch"'* ]]; then
  printf 'Release workflow still accepts a mutable target_commitish name without resolving it.\n' >&2
  exit 1
fi
require_workflow_text 'exact unowned reservation validation' \
  '"$release_id" == "$RESERVED_RELEASE_ID" && "$release_body" == "$RELEASE_MARKER"'
require_workflow_text 'self tag excluded from monotonic validation' \
  'select(.draft == false and .tag_name != $tag)'
require_workflow_text 'published release read-only path' \
  'if [[ "$release_draft" == false ]]; then'
for release_build_step in \
  'Build release frontend' \
  'Assemble release assets' \
  'Attest canonical invocation and complete core subject set' \
  'Revalidate tag and converge immutable image tags'; do
  require_workflow_text "published retry bypass for ${release_build_step}" \
    "name: ${release_build_step}"
done
require_workflow_text 'complete asset byte metadata validation' \
  'remote_asset_bytes'
require_workflow_text 'independent release provenance baseline asset' \
  'release-provenance.json'
require_workflow_text 'all release outputs are attested before upload' \
  'name: Attest canonical invocation and complete core subject set'
release_attestation_marker='name: Attest canonical invocation and complete core subject set'
release_upload_marker='upload_release_asset_by_id "$REPOSITORY" "$release_id" "$asset"'
release_attestation_prefix="${workflow_source%%"${release_attestation_marker}"*}"
release_upload_prefix="${workflow_source%%"${release_upload_marker}"*}"
if (( ${#release_attestation_prefix} >= ${#release_upload_prefix} )); then
  printf 'Release asset attestations must be persisted before draft asset upload.\n' >&2
  exit 1
fi
require_workflow_text 'published assets require workflow-bound GitHub attestations' \
  'gh attestation verify "$verify_dir/$asset_name"'
require_workflow_text 'single canonical attestation bundle is persisted' \
  'release/release-assets.sigstore.json'
require_workflow_text 'custom signed canonical invocation predicate' \
  'https://yunlume.example/attestations/release-invocation/v1'
require_workflow_text 'canonical signed run identity' \
  '"githubRunId": run_id'
require_workflow_text 'canonical signed workflow ref' \
  '"workflowRef": workflow_ref'
require_workflow_text 'canonical candidate digest commitment' \
  'name="candidate-commitment-${COMPONENT}.json"'
require_workflow_text 'canonical bundle digest is persisted' \
  '"attestationBundleSha256": bundle_sha'
require_workflow_text 'verification uses exact canonical bundle' \
  'attestation_args=(--bundle "$verify_dir/release-assets.sigstore.json")'
require_workflow_text 'attestation source commit binding' \
  '--source-digest "$GITHUB_SHA"'
require_workflow_text 'attestation source tag binding' \
  '--source-ref "refs/tags/$RELEASE_TAG"'
require_workflow_text 'downloaded digests checked against independent baseline' \
  'release provenance digest mismatch'
require_workflow_text 'downloaded sizes checked against independent baseline' \
  'release provenance size mismatch'
require_workflow_text 'full manifest compatibility epoch contract' \
  'manifest compatibilityEpoch mismatch'
require_workflow_text 'full manifest asset name contract' \
  'manifest asset name mismatch'
require_workflow_text 'full manifest compose hash contract' \
  'manifest composeSha256 mismatch'
require_workflow_text 'full manifest archive hash contract' \
  'manifest archiveSha256 mismatch'
require_workflow_text 'archive sidecar exact contract' \
  'archive sidecar mismatch'
require_workflow_text 'published SHA256SUMS verification' \
  'sha256sum --check --quiet --strict SHA256SUMS'
require_workflow_text 'published manifest backend digest verification' \
  'published_backend_digest'
require_workflow_text 'published manifest frontend digest verification' \
  'published_frontend_digest'
require_workflow_text 'published backend immutable tag check' \
  'verify_immutable_image backend "$published_backend_digest"'
require_workflow_text 'published frontend immutable tag check' \
  'verify_immutable_image frontend "$published_frontend_digest"'

published_marker='if [[ "$release_draft" == false ]]; then'
published_branch="${workflow_source#*"${published_marker}"}"
published_branch="${published_branch%%$'\n''          else'*}"
if [[ "${published_branch}" == *'gh release upload '* ||
      "${published_branch}" == *'-F draft=false'* ||
      "${published_branch}" == *'--method PATCH'* ||
      "${published_branch}" == *'--method DELETE'* ||
      "${published_branch}" == *'docker buildx imagetools create'* ||
      "${published_branch}" == *'npm run build'* ||
      "${published_branch}" == *'mvn '* ||
      "${published_branch}" == *'package-host-release'* ]]; then
  printf 'Published release path contains external mutation or rebuild work.\n' >&2
  exit 1
fi
require_workflow_text 'published rerun exits before mutation branch' \
  'Published release verified without external mutation.'

require_workflow_text 'immediate complete immutable verification before rolling aliases' \
  '# Full immutable Release verification: keep immediately before rolling aliases.'
pre_alias="${workflow_source##*# Full immutable Release verification: keep immediately before rolling aliases.}"
first_alias_marker='            docker buildx imagetools create'
pre_alias="${pre_alias%%"${first_alias_marker}"*}"
if [[ "${pre_alias}" != *'verify_published_release'* ]]; then
  printf 'Pre-alias verification does not invoke the complete verifier.\n' >&2
  exit 1
fi
if [[ "${pre_alias}" == *'gh release upload '* ||
      "${pre_alias}" == *'--method PATCH'* ||
      "${pre_alias}" == *'-F draft=false'* ||
      "${pre_alias}" == *'docker buildx imagetools create'* ]]; then
  printf 'Mutation occurs between complete verification and the first rolling alias.\n' >&2
  exit 1
fi

require_workflow_text 'backend rolling alias immediate digest read-back' \
  'Rolling alias backend digest mismatch'
require_workflow_text 'frontend rolling alias immediate digest read-back' \
  'Rolling alias frontend digest mismatch'

require_workflow_text 'unowned reservation marker' \
  '<!-- yunlume-release-reservation-v3:${GITHUB_SHA} -->'
require_workflow_text 'durable backend OCI archive' 'candidate-oci-backend.tar'
require_workflow_text 'durable frontend OCI archive' 'candidate-oci-frontend.tar'
require_workflow_text 'archive upload precedes candidate commitment' \
  'name: Persist exact candidate OCI archive before commitment'
require_workflow_text 'late canonical owner marker' 'canonical-owner.json'
require_workflow_text 'canonical bytes verified before ownership transfer' \
  '# Verify every canonical byte before final ownership.'
ownership_window="${workflow_source##*# Verify every canonical byte before final ownership.}"
ownership_window="${ownership_window%%ensure_release_asset_once_by_id \"\$REPOSITORY\" \"\$release_id\" release/canonical-owner.json*}"
if [[ "${ownership_window}" != *'verify_canonical_provenance release'* ||
      "${ownership_window}" != *'gh attestation verify "$asset"'* ]]; then
  printf 'Canonical signed inputs are not verified before ownership transfer.\n' >&2
  exit 1
fi
require_workflow_text 'published verification-only job' 'published-release-verify:'
require_workflow_text 'published verifier routing condition' \
  "needs.release-preflight.outputs.published_release == 'true'"

# Simulate the published route from the parsed workflow graph. Every job with a
# known external mutation must be skipped; only preflight and verifier run.
python3 - "${SCRIPT_DIR}/../.github/workflows/publish-images.yml" <<'PY'
import re, sys
text=open(sys.argv[1], encoding='utf-8').read()
job_starts=list(re.finditer(r'^  ([a-zA-Z0-9_-]+):\n', text, re.M))
jobs={}
for i,m in enumerate(job_starts):
    jobs[m.group(1)] = text[m.start():(job_starts[i+1].start() if i+1<len(job_starts) else len(text))]
mutating_tokens=('actions/upload-artifact@','actions/attest@','actions/attest-build-provenance@',
                 'docker/build-push-action@','--method POST','--method PATCH','--method DELETE',
                 'docker buildx imagetools create','upload_release_asset_by_id','publish_release_by_id')
allowed={'release-preflight','published-release-verify','release-reserve'}
for name,body in jobs.items():
    if any(token in body for token in mutating_tokens) and name not in allowed:
        condition=re.search(r'^    if:\s*(?:>-\n((?:      .*\n)+)|([^\n]+))', body, re.M)
        assert condition, f'mutating job lacks job-level published gate: {name}'
        value=' '.join(condition.groups(default=''))
        assert "published_release != 'true'" in value, (name,value)
# Preflight is read-only despite exercising complete published verification.
for token in mutating_tokens:
    assert token not in jobs['release-preflight'], ('preflight mutation', token)
assert "published_release != 'true'" in jobs['release-reserve']
assert "published_release == 'true'" in jobs['published-release-verify']
# A release-only rerun retains old needs outputs. The release job must use its fresh local guard.
release=jobs['release']
assert 'id: release-state' in release and 'run: bash ops/release-preflight.sh' in release
assert 'steps.release-state.outputs.published_release' in release
assert 'needs.release-reserve.outputs.canonical_current' not in release
assert 'needs.release-reserve.outputs.canonical_run_attempt' not in release
assert 'resolve_canonical_owner "$REPOSITORY" "$RELEASE_ID" "$GITHUB_SHA"' in release
# Exact producer IDs, never a consumer-attempt artifact name, feed downloads.
for name in ('backend-attest','publish','release'):
    blocks=re.findall(r'uses: actions/download-artifact@[^\n]+\n(.*?)(?=\n      - name:|\Z)', jobs[name], re.S)
    assert blocks, name
    for block in blocks:
        assert 'artifact-ids:' in block and 'run-id:' in block and 'github-token:' in block, name
        assert 'github.run_attempt' not in block, name
publish=jobs['publish']
for permission in ('attestations: write','id-token: write','contents: write','packages: write'):
    assert permission in publish, permission
# Preflight helper must also stay read-only after extraction.
from pathlib import Path
preflight=Path(sys.argv[1]).resolve().parents[2].joinpath('ops/release-preflight.sh').read_text()
for token in mutating_tokens:
    assert token not in preflight, ('preflight helper mutation', token)
assert 'verify_rolling_aliases' in preflight
print('Published route graph gates are present; external writes are exercised by the behavior suite')
PY

printf 'Release workflow isolates mutable tags, validates tag identity, and supports safe recovery.\n'
