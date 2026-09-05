# Release trust and transaction model

## Trust anchors

1. The annotated/lightweight Git tag must resolve to `GITHUB_SHA` immediately before each publication boundary.
2. An invocation reserves the exact numeric draft Release ID before candidate publication. Its
   exact body marker binds only tag/source identity; the reservation is intentionally unowned.
   Any later attempt may complete it until `canonical-owner.json` is written as the final draft
   asset. After that immutable-once marker exists, retries reuse its exact signed asset set.
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
6. GitHub CLI is downloaded as exact v2.93.0 archives with published SHA-256 checksums and its
   `release verify`, `release verify-asset`, and `attestation verify` capabilities are checked before
   release work. The runner's moving preinstalled `gh` is not trusted.
7. The backend JAR built and migration-tested in `backend-test` is attested once and moved to
   downstream jobs with an attempt-scoped Actions artifact. The release backend image copies that
   JAR rather than running Maven, and the Host archive packages the same file. Both delivery formats
   are opened and their embedded JAR SHA-256 is checked before Release publication.

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

## Verified GitHub and OCI semantics

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
