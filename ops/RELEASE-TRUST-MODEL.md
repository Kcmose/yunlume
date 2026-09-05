# Release trust and transaction model

## Trust anchors

1. The annotated/lightweight Git tag must resolve to `GITHUB_SHA` immediately before each publication boundary.
2. An invocation reserves the exact numeric draft Release ID before candidate publication. Its
   exact body marker binds only tag/source identity; the reservation is intentionally unowned.
   Any later attempt may complete it until `canonical-owner.json` is written as the final draft
   asset. After that immutable-once marker exists, retries reuse its exact signed asset set.
   The release job reads this owner at execution time. Without a final owner it signs using its
   actual run/attempt, even when a failed-jobs rerun retained an older reservation job's outputs.
3. Each exact OCI archive is first retained as `candidate-oci-{backend,frontend}.tar` on that exact
   draft ID. Only after exact-ID readback is its byte SHA-256, byte size, OCI root digest, component,
   and source committed in immutable-once `candidate-commitment-{backend,frontend}.json`. The
   commit/component GHCR tag is published last and is only a locator. Recovery requires the
   locator to equal the committed digest and rejects a moved tag even if the new digest has another
   otherwise valid same-source attestation. The OCI archive hash is never accepted as a substitute.
4. `release-assets.sigstore.json` is a generic `actions/attest` custom attestation. Its signed
   predicate contains the exact canonical run and attempt, source SHA/ref, workflow identity/ref,
   both committed candidate root digests, and the complete core release subject name/digest set.
   `release-provenance.json` records the predicate and bundle SHA-256 values and defines canonical
   identity as `run:attempt:predicate-sha256:bundle-sha256`; the API attestation ID is deliberately
   not claimed to be signed. Provenance and bundle are additionally covered by GitHub's immutable
   Release attestation, avoiding an impossible self-referential subject digest.
5. Repository immutable releases are mandatory. `IMMUTABLE_RELEASES_READ_TOKEN` is passed only as
   a process-scoped assignment to `ops/check-immutable-releases-policy.sh`. That helper immediately
   captures and unsets it, clears GitHub host/token/config overrides, and gives it only to one curl
   request (through curl configuration on standard input, never argv): explicit `GET` to hardcoded
   `https://api.github.com/repos/{validated-owner}/{validated-repo}/immutable-releases`. The helper
   process and intended HTTPS client necessarily handle the credential; no `gh`, jq, Python,
   mutation command, or later step inherits it. All Release mutations continue to use
   `GITHUB_TOKEN`. The secret must be a fine-grained PAT or GitHub App installation token scoped to
   this repository with **Administration: read** and no Administration write permission. Missing,
   unauthorized, disabled, or non-owner-enforced policy fails closed before mutation. Never print
   or pass this secret to mutation commands.
6. In candidate/Release verification and publication jobs, GitHub CLI is downloaded as exact
   v2.93.0 archives with published SHA-256 checksums and its
   `release verify`, `release verify-asset`, and `attestation verify` capabilities are checked before
   release work. The isolated JAR signer uses the runner CLI only for read-only REST metadata
   requests; signing itself uses the pinned attestation action and never executes repository code.
7. `backend-test` publishes its artifact ID, archive digest, source SHA, and producer run/attempt.
   The isolated `backend-attest` job downloads that exact ID and signs without checking out or
   executing repository helpers. Its own upload supplies the attested artifact ID/run/attempt to
   consumers; a failed-jobs rerun never derives an artifact name from the consumer's attempt.
   Before download, GitHub's artifact, workflow-attempt and attempt-jobs APIs must agree on the
   artifact name/digest, repository, source, workflow, successful producer job and creation time.
   The attempt-specific jobs endpoint scopes provenance; the Job object does not guarantee a
   `run_attempt` field. Downloaded identity metadata, JAR bytes and the exact signature bundle
   are then verified together.
8. Before staging a backend OCI candidate, the exact draft retains immutable-once
   `backend-jar-producer.json` (artifact ID/digest, producer run/attempt, source SHA and JAR SHA-256).
   This descriptor is included in the canonical signed subject set and immutable Release asset
   set. A rerun-all can build a new JAR, but candidate/release recovery uses the original descriptor
   and attested artifact. Missing/expired original artifacts fail closed without rebuilding or
   substituting bytes. Uploads request 90 days of retention, subject to repository retention limits.
   Once canonical assets are finalized, a release-job-only retry uses those retained assets and
   no longer needs the Actions artifact. Both delivery formats' embedded JAR SHA-256 must match
   the signed baseline.
9. The candidate publish job explicitly requests `attestations: write` and `id-token: write`,
   together with the required Release/registry writes. Test jobs retain read-only permissions.

## Candidate transaction

Buildx writes an OCI archive without publishing. The archive-file SHA-256 identifies only tar
bytes; the validated `index.json.manifests[0].digest` is the OCI root commitment. Exact archive
bytes are uploaded and read back before the commitment, and the commitment is read back before
`skopeo --preserve-digests` creates the candidate locator. Archive-without-commitment and
commitment-with-archive-without-locator states are completed by later runs. Commitment plus a
matching locator needs no archive. Commitment without either locator or retained archive fails
closed; normal ordering makes it unreachable. A moved locator is always fatal.

The large candidate OCI archives are deliberately retained through immutable publication. This
costs Release storage and download bandwidth, but makes every pre-locator interruption recoverable
after Actions artifacts and runner files disappear and lets published verification re-check the
exact committed bytes.

## Release mutation boundary

All mutable operations use immutable numeric IDs:

- exact Release state is reread by release ID immediately before each asset DELETE, asset upload,
  and `PATCH draft=false`;
- the state must still have the exact ID, draft/tag/prerelease/target/recovery marker, and the
  repository immutability feature must still be enabled;
- uploads use the release-specific uploads host and numeric release ID; clobbers delete only the
  exact existing asset ID;
- verification downloads assets by numeric asset ID, not by resolving a tag.

The exact reread closes ordinary stale-state windows. Repository immutable releases provide the
server-side race barrier if another actor publishes between the reread and mutation: published
assets are locked. The workflow then requires `immutable: true` on the publication readback and
fails closed otherwise.

The shared `ops/release-preflight.sh` runs both for the initial graph and at the beginning of the
release job, before any Release/registry mutation. A release-only retry therefore cannot treat
cached upstream `published=false` as write authorization. Already-published recovery verifies the
complete canonical asset set, exact five-member `SHA256SUMS` set (order independent), immutable
image tags and both `major.minor` aliases. Missing, stale, mixed or unreadable aliases fail with
zero external writes. Alias repair requires a separate explicit operation; this workflow never
repairs a published release during its verification-only retry. Monotonic version checks remain.

## Verified GitHub and OCI semantics

- GitHub reruns preserve the source SHA/ref; `run_id` remains stable and `run_attempt` increases.
  Failed-jobs reruns can retain successful producer jobs from an earlier attempt, so the producer
  identity is propagated explicitly instead of inferred from the downloading job.
  <https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs>
  <https://docs.github.com/en/actions/reference/workflows-and-actions/variables>
- Artifact metadata exposes an ID, digest, expiration state and source workflow run. The exact
  workflow-attempt and attempt-jobs endpoints provide the additional producer checks described above.
  Pinned `download-artifact` supports `artifact-ids`, `run-id` and `github-token`; pinned upload
  exposes `artifact-id` and `artifact-digest`. Consumer downloads never use a rerun-derived name.
  <https://docs.github.com/en/rest/actions/artifacts>
  <https://docs.github.com/en/rest/actions/workflow-jobs#list-jobs-for-a-workflow-run-attempt>
  <https://github.com/actions/download-artifact/blob/d3f86a106a0bac45b974a628896c90dbdf5c8093/action.yml>
  <https://github.com/actions/upload-artifact/blob/ea165f8d65b6e75b540449e92b4886f43607fa02/action.yml>
- Explicit job permissions set unspecified permissions to none; signing needs both OIDC and
  attestation write permissions even when registry/Release writes are already granted.
  <https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions>
- GitHub REST, repository immutable releases: current docs define
  `GET /repos/{owner}/{repo}/immutable-releases`, require fine-grained repository
  **Administration: read**, and return `enabled` and `enforced_by_owner`. `GITHUB_TOKEN` has no
  Administration permission key, so the workflow uses the separate read-only secret described
  above. Enabling is a separate admin `PUT`, which this workflow deliberately does not perform.
  <https://docs.github.com/en/rest/repos/repos#check-if-immutable-releases-are-enabled-for-a-repository>
- GitHub REST Release responses expose `immutable`; immutable-release documentation says tags and
  assets are locked after publication (release notes remain editable).
  <https://docs.github.com/en/rest/releases/releases>
  <https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/immutable-releases>
- GitHub REST requires the release-specific `upload_url` returned by the Release resource and
  provides numeric-ID endpoints to list/delete/download assets.
  <https://docs.github.com/en/rest/releases/assets#upload-a-release-asset>
- Repository attestation lookup requires an already-known `subject_digest`; the list response
  exposes bundle URLs but is not a run/tag index. This is why candidate discovery cannot rely on
  an orphaned attestation.
  <https://docs.github.com/en/rest/repos/attestations#list-attestations>
- GitHub CLI verifies GitHub's signed immutable Release attestation with `gh release verify`,
  and `gh release verify-asset <tag> <file>` binds a downloaded file digest to that specific
  Release attestation.
  <https://cli.github.com/manual/gh_release_verify>
  <https://cli.github.com/manual/gh_release_verify-asset>
- `gh attestation verify` supports an exact local `--bundle`, source ref/digest, and signer
  workflow policy. Its JSON documentation distinguishes certificate/timestamp trust from
  workflow-controllable predicate data.
  <https://cli.github.com/manual/gh_attestation_verify>
- Pinned `actions/attest-build-provenance` commit
  `977bb373ede98d70efdf65b84cb5f73e068dcc2a` (v3.0.0) declares `bundle-path`,
  `attestation-id`, and `attestation-url`; `subject-path` supports up to 1024 subjects. Its pinned
  `actions/attest` implementation creates one statement from the subject array and names path
  subjects by basename.
  <https://github.com/actions/attest-build-provenance/blob/977bb373ede98d70efdf65b84cb5f73e068dcc2a/action.yml>
  <https://github.com/actions/attest/blob/daf44fb950173508f38bd2406030372c1d1162b1/src/subject.ts>
- OCI Distribution specifies manifest publication as `PUT /v2/<name>/manifests/<tag-or-digest>`
  and success as `201 Created`; blobs use separate upload endpoints. Thus uploaded blobs do not
  create the candidate tag, while successful final manifest publication does.
  <https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-manifests>
