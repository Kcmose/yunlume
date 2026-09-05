#!/usr/bin/env python3
"""运行真实 Bash/helper/工作流片段；仅替换 GitHub 和 Docker 外部进程，无网络或守护进程。"""
import base64
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import tempfile

REPO = Path(__file__).resolve().parent.parent
SHA = "a" * 40
WORKFLOW = "example/nav/.github/workflows/publish-images.yml"
TAG = "v1.2.3"
ARCHIVE = f"yunlume-host-{TAG}.tar.gz"
JAR = b"exact tested JAR bytes, attempt one\n"
JAR_SHA = hashlib.sha256(JAR).hexdigest()
checks = 0


def dump(path, value):
    path.write_text(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def archive(path, files):
    with tarfile.open(path, "w:gz" if path.name.endswith(".gz") else "w") as tar:
        for name, content in files.items():
            member = tarfile.TarInfo(name)
            member.size = len(content)
            tar.addfile(member, io.BytesIO(content))


def step_script(name):
    text = (REPO / ".github/workflows/publish-images.yml").read_text()
    start = text.index("      - name: " + name + "\n")
    end = text.find("\n      - name:", start + 1)
    body = text[start:end if end != -1 else len(text)]
    body = body.split("        run: |\n", 1)[1]
    return "\n".join(line[10:] if line.startswith("          ") else line for line in body.splitlines()) + "\n"


MOCK = r'''#!/usr/bin/env python3
import json, os, pathlib, subprocess, sys
p=pathlib.Path(os.environ['FIXTURE']); state=json.loads((p/'state.json').read_text()); args=sys.argv[1:]
tool=pathlib.Path(sys.argv[0]).name
with (p/'calls').open('a') as log: log.write(tool+' '+repr(args)+'\n')
def fail(): sys.exit(42)
def emit(value): print(json.dumps(value))
def query(value):
    if '--jq' not in args: emit(value); return
    expression=args[args.index('--jq')+1]
    r=subprocess.run(['jq','-r',expression],input=json.dumps(value),text=True,capture_output=True)
    sys.stdout.write(r.stdout); sys.stderr.write(r.stderr)
    sys.exit(42 if state.get('fail_after_output') and state['fail_after_output'] in endpoint else r.returncode)
if tool=='docker':
    if args[:3]==['buildx','imagetools','inspect']:
        ref=args[-1]; component='backend' if 'backend' in ref else 'frontend'
        if ref.endswith(':1.2'):
            mode=state.get('alias','good')
            if mode in ('missing','network') and component=='backend': fail()
            if mode=='stale' or (mode=='mixed' and component=='frontend'): print('sha256:'+'9'*64); sys.exit()
        print(state['digests'][component]); sys.exit()
    if args[:4]==['run','--rm','--entrypoint','sha256sum']:
        print(state['jar_sha']+'  /app/app.jar'); sys.exit()
    (p/'writes').write_text('docker mutation '+repr(args)); fail()
if args==['version']: print('gh version 2.93.0 (fixture)'); sys.exit()
if args[:2] in (['release','verify'],['release','verify-asset'],['attestation','verify']):
    if '--help' not in args and state.get('signature_failure'): fail()
    sys.exit()
if args and args[0]=='api':
    if '--method' in args and args[args.index('--method')+1]!='GET':
        (p/'writes').write_text('gh mutation '+repr(args)); fail()
    endpoint=next((v for v in args if v.startswith('repos/')), '')
    if state.get('api_failure') and state['api_failure'] in endpoint: fail()
    if '/git/ref/tags/' in endpoint:
        depth=state.get('tag_depth',0)
        print(('tag\t'+format(depth,'040x')) if depth else 'commit\t'+state.get('tag_sha',os.environ['GITHUB_SHA'])); sys.exit()
    if '/git/tags/' in endpoint:
        depth=int(endpoint.rsplit('/',1)[1],16)-1
        print(('tag\t'+format(depth,'040x')) if depth else 'commit\t'+state.get('tag_sha',os.environ['GITHUB_SHA'])); sys.exit()
    if '/actions/artifacts/' in endpoint: emit(state['artifact']); sys.exit()
    if '/actions/runs/' in endpoint:
        emit(state['jobs'] if endpoint.endswith('/jobs') else state['attempt']); sys.exit()
    if '/releases/assets/' in endpoint:
        identity=endpoint.rsplit('/',1)[1]
        name=state['asset_ids'][identity]
        sys.stdout.buffer.write((p/'assets'/name).read_bytes()); sys.exit()
    if endpoint.endswith('/assets'):
        rows=[{'id':int(key),'name':name,'size':(p/'assets'/name).stat().st_size} for key,name in state['asset_ids'].items()]
        query([rows] if '--slurp' in args else rows); sys.exit()
    if endpoint.endswith('/releases'): query([state['release']]); sys.exit()
    if '/releases/' in endpoint: query(state['release']); sys.exit()
    if '/compare/' in endpoint: query({'status':'identical'}); sys.exit()
    if '/commits/' in endpoint: query({'sha':os.environ['GITHUB_SHA']}); sys.exit()
    if endpoint=='repos/example/nav': query({'default_branch':'main'}); sys.exit()
print('Unexpected external command: '+repr(args),file=sys.stderr); fail()
'''


class Fixture:
    def __init__(self, path):
        self.path = path
        self.assets = path / "assets"
        self.assets.mkdir()
        self.bin = path / "bin"
        self.bin.mkdir()
        for tool in ("gh", "docker"):
            file = self.bin / tool
            file.write_text(MOCK, encoding="utf-8")
            file.chmod(0o755)
        self.descriptor = {
            "schemaVersion": 1, "artifactId": "77", "artifactDigest": "b" * 64,
            "runId": "100", "runAttempt": "1", "sourceSha": SHA, "jarSha256": JAR_SHA,
        }
        self.state = {
            "digests": {}, "jar_sha": JAR_SHA, "asset_ids": {},
            "artifact": {"id": 77, "name": f"tested-backend-jar-{SHA}-100-1", "expired": False,
                         "digest": "sha256:" + "b" * 64, "created_at": "2026-09-05T01:01:00Z",
                         "workflow_run": {"id": 100, "head_sha": SHA, "repository_id": 5, "head_repository_id": 5}},
            "attempt": {"id": 100, "run_attempt": 1, "head_sha": SHA, "event": "push",
                        "path": ".github/workflows/publish-images.yml", "repository": {"id": 5, "full_name": "example/nav"}},
            "jobs": [{"jobs": [{"name": "Attest exact tested backend JAR", "run_id": 100, "head_sha": SHA,
                                "conclusion": "success", "started_at": "2026-09-05T01:00:00Z",
                                "completed_at": "2026-09-05T01:02:00Z"}]}],
            "release": {"id": 9, "tag_name": TAG, "draft": False, "target_commitish": SHA,
                        "prerelease": False, "immutable": True, "body": f"<!-- yunlume-release-reservation-v3:{SHA} -->"},
        }
        self.environment = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"], FIXTURE=str(path),
                                GITHUB_SHA=SHA, GITHUB_REPOSITORY="example/nav", GITHUB_REF_TYPE="tag",
                                GITHUB_REF="refs/tags/" + TAG, GITHUB_RUN_ID="100", GITHUB_RUN_ATTEMPT="2",
                                REPOSITORY="example/nav", RELEASE_TAG=TAG, RELEASE_ID="9", GH_TOKEN="fixture")

    def run(self, label, script, success=True, contains=None, environment=None, cwd=None):
        global checks
        dump(self.path / "state.json", self.state)
        output = self.path / "output"
        output.write_text("")
        env = dict(self.environment, GITHUB_OUTPUT=str(output))
        env.update(environment or {})
        result = subprocess.run(["bash", "-c", script], cwd=cwd or REPO, env=env, text=True, capture_output=True)
        assert (result.returncode == 0) == success, f"{label}: exit={result.returncode}\n{result.stdout}\n{result.stderr}"
        if contains is not None:
            assert contains in result.stdout + output.read_text(), f"{label}: missing {contains!r}\n{result.stdout}\n{output.read_text()}"
        assert not (self.path / "writes").exists(), f"{label}: unexpected external mutation"
        checks += 1
        return result, output.read_text()

    def shell(self, label, body, **kwargs):
        prefix = "set -Eeuo pipefail\nsource ops/lib/release-transaction.sh\nsource ops/lib/publish-workflow.sh\n"
        return self.run(label, prefix + body, **kwargs)

    def complete_release(self):
        (self.assets / "install.sh").write_text("#!/bin/bash\nexit 0\n")
        (self.assets / "yunlume-compose.yml").write_text("services: {}\n")
        archive(self.assets / ARCHIVE, {"backend/yunlume-backend.jar": JAR})
        (self.assets / (ARCHIVE + ".sha256")).write_text(f"{digest(self.assets / ARCHIVE)}  {ARCHIVE}\n")
        for component in ("backend", "frontend"):
            blob = json.dumps({"schemaVersion": 2, "component": component}).encode()
            sha = hashlib.sha256(blob).hexdigest()
            image_digest = "sha256:" + sha
            index = json.dumps({"schemaVersion": 2, "manifests": [{"digest": image_digest, "size": len(blob)}]}).encode()
            path = self.assets / f"candidate-oci-{component}.tar"
            archive(path, {"index.json": index, "blobs/sha256/" + sha: blob})
            dump(self.assets / f"candidate-commitment-{component}.json", {
                "schemaVersion": 2, "component": component, "sourceSha": SHA, "digest": image_digest,
                "archiveSha256": digest(path), "archiveSize": path.stat().st_size})
            self.state["digests"][component] = image_digest
        manifest = {"version": "1.2.3", "compatibilityEpoch": 1,
                    "docker": {"compose": "yunlume-compose.yml", "composeSha256": digest(self.assets / "yunlume-compose.yml"),
                               **{c + "Image": f"ghcr.io/example/yunlume-{c}@{d}" for c, d in self.state["digests"].items()}},
                    "host": {"archive": ARCHIVE, "archiveSha256": digest(self.assets / ARCHIVE)}}
        dump(self.assets / "release-manifest.json", manifest)
        checksum_names = ["install.sh", "release-manifest.json", "yunlume-compose.yml", ARCHIVE, ARCHIVE + ".sha256"]
        (self.assets / "SHA256SUMS").write_text("".join(f"{digest(self.assets / name)}  {name}\n" for name in checksum_names))
        dump(self.assets / "canonical-owner.json", {"schemaVersion": 1, "runId": "100", "runAttempt": "1", "sourceSha": SHA})
        dump(self.assets / "backend-jar-producer.json", self.descriptor)
        names = sorted(file.name for file in self.assets.iterdir())
        subjects = {name: digest(self.assets / name) for name in names}
        predicate = {"schemaVersion": 1, "githubRunId": "100", "githubRunAttempt": "1", "sourceSha": SHA,
                     "sourceRef": "refs/tags/" + TAG, "workflowIdentity": WORKFLOW,
                     "workflowRef": WORKFLOW + "@refs/tags/" + TAG,
                     "candidateDigests": self.state["digests"], "subjects": subjects}
        statement = {"predicateType": "https://yunlume.example/attestations/release-invocation/v1", "predicate": predicate,
                     "subject": [{"name": n, "digest": {"sha256": d}} for n, d in subjects.items()]}
        # Fixture envelope: real byte/predicate/subject validation stays active; the gh trust service is the stub above.
        dump(self.assets / "release-assets.sigstore.json", {
            "dsseEnvelope": {"payload": base64.b64encode(json.dumps(statement).encode()).decode(), "signatures": [{"sig": "fixture"}]},
            "verificationMaterial": {"fixture": "external trust authority stub"}})
        predicate_sha = hashlib.sha256(json.dumps(predicate, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        bundle_sha = digest(self.assets / "release-assets.sigstore.json")
        dump(self.assets / "release-provenance.json", {
            "schemaVersion": 3, "canonicalIdentity": f"100:1:{predicate_sha}:{bundle_sha}",
            "attestationBundleSha256": bundle_sha, "predicateSha256": predicate_sha, "backendJarSha256": JAR_SHA,
            "assets": {n: {"sha256": subjects[n], "size": (self.assets / n).stat().st_size} for n in names}, "manifest": manifest})
        self.state["asset_ids"] = {str(i): f.name for i, f in enumerate(sorted(self.assets.iterdir()), 10)}


def tests(f):
    q = shlex.quote
    f.complete_release()
    for depth in (0, 1, 3, 16):
        f.state["tag_depth"] = depth
        f.shell(f"tag nesting {depth}", 'assert_release_tag_commit "$REPOSITORY" "$RELEASE_TAG" "$GITHUB_SHA"')
    f.state["tag_depth"] = 17
    f.shell("tag depth refusal", 'if assert_release_tag_commit "$REPOSITORY" "$RELEASE_TAG" "$GITHUB_SHA"; then exit 9; fi')
    f.state["tag_depth"] = 0
    f.state["tag_sha"] = "c" * 40
    f.shell("moved tag refusal", 'assert_release_tag_commit "$REPOSITORY" "$RELEASE_TAG" "$GITHUB_SHA"', success=False)
    del f.state["tag_sha"]
    f.state["api_failure"] = "/git/ref/"
    f.shell("tag lookup failure in conditional", 'if value="$(resolve_release_tag_commit "$REPOSITORY" "$RELEASE_TAG")"; then exit 9; fi; [[ -z "$value" ]]')
    del f.state["api_failure"]

    checksum = f.assets / "SHA256SUMS"
    original = checksum.read_text()
    command = f"verify_release_checksums {q(str(f.assets))} {q(ARCHIVE)}"
    f.shell("writer checksum order", command)
    checksum.write_text("\n".join(reversed(original.splitlines())) + "\n")
    f.shell("reordered checksum exact set", command)
    for label, value in (("missing", "\n".join(original.splitlines()[:-1]) + "\n"),
                         ("duplicate", original + original.splitlines()[0] + "\n"),
                         ("unexpected", original + "0" * 64 + "  unexpected\n"),
                         ("wrong digest", "0" * 64 + original[64:])):
        checksum.write_text(value)
        f.shell("checksum " + label, command, success=False)
    checksum.write_text(original)

    # Complete live published path, including source/canonical assets/JAR/image/alias checks.
    for mode in ("good", "missing", "stale", "mixed", "network"):
        f.state["alias"] = mode
        result, outputs = f.run("published full preflight aliases " + mode, "bash ops/release-preflight.sh", success=mode == "good")
        assert ("published_release=true" in outputs) == (mode == "good")
    f.state["alias"] = "good"
    f.state["fail_after_output"] = "/releases/9/assets"
    f.run("published rejects complete asset response with failed CLI status", "bash ops/release-preflight.sh", success=False)
    del f.state["fail_after_output"]
    f.state["signature_failure"] = True
    f.run("published rejects failed trust service", "bash ops/release-preflight.sh", success=False)
    del f.state["signature_failure"]

    descriptor = f.assets / "backend-jar-producer.json"
    verify = f'verify_backend_jar_producer {q(str(descriptor))} "$REPOSITORY" "$GITHUB_SHA"'
    f.shell("producer attempt one consumed by attempt two", verify)
    base = copy.deepcopy(f.state)
    corruptions = [
        ("expired", lambda s: s["artifact"].update(expired=True)),
        ("artifact id", lambda s: s["artifact"].update(id=78)),
        ("artifact digest", lambda s: s["artifact"].update(digest="sha256:" + "c" * 64)),
        ("artifact attempt", lambda s: s["artifact"].update(name=f"tested-backend-jar-{SHA}-100-2")),
        ("source", lambda s: s["artifact"]["workflow_run"].update(head_sha="c" * 40)),
        ("fork", lambda s: s["artifact"]["workflow_run"].update(head_repository_id=6)),
        ("workflow attempt", lambda s: s["attempt"].update(run_attempt=2)),
        ("workflow path", lambda s: s["attempt"].update(path=".github/workflows/untrusted.yml")),
        ("failed producer", lambda s: s["jobs"][0]["jobs"][0].update(conclusion="failure")),
        ("wrong job source", lambda s: s["jobs"][0]["jobs"][0].update(head_sha="c" * 40)),
        ("outside producer time", lambda s: s["artifact"].update(created_at="2026-09-05T00:59:00Z")),
    ]
    for label, mutate in corruptions:
        f.state = copy.deepcopy(base)
        mutate(f.state)
        f.shell("producer rejects " + label, verify, success=False)
    f.state = copy.deepcopy(base)
    f.state["api_failure"] = "/actions/artifacts/"
    f.shell("producer API failure conditional", f"if {verify}; then exit 9; fi")
    f.state = copy.deepcopy(base)

    # Isolated signer runs its actual inline code without checking out helper code.
    f.state["artifact"]["name"] = f"tested-backend-jar-raw-{SHA}-100-1"
    f.state["jobs"][0]["jobs"][0]["name"] = "Backend tests and package"
    raw_env = {"ARTIFACT_ID": "77", "ARTIFACT_DIGEST": "b" * 64, "PRODUCER_RUN_ID": "100",
               "PRODUCER_RUN_ATTEMPT": "1", "SOURCE_SHA": SHA}
    raw = step_script("Verify raw JAR artifact producer before signing")
    f.run("isolated signer reuses raw producer attempt one", raw, environment=raw_env, cwd=f.path)
    f.state["attempt"]["run_attempt"] = 2
    f.run("isolated signer rejects wrong producer attempt", raw, success=False, environment=raw_env, cwd=f.path)
    f.state = copy.deepcopy(base)

    transaction = f.path / "transaction"
    transaction.mkdir()
    (transaction / "app.jar").write_bytes(JAR)
    identity = f"sha256={JAR_SHA}\nsource_sha={SHA}\nrun_id=100\nrun_attempt=1\nattestation_id=fixture\n"
    (transaction / "identity.txt").write_text(identity)
    (transaction / "attestation.sigstore.json").write_text("{}")
    command = f'verify_attested_backend_jar {q(str(descriptor))} {q(str(transaction))} "$REPOSITORY" "$GITHUB_REF" "$GITHUB_SHA"'
    f.shell("JAR bytes and producer identity reused", command)
    (transaction / "identity.txt").write_text(identity.replace("run_attempt=1", "run_attempt=2"))
    f.shell("JAR identity refuses consumer attempt", f"if {command}; then exit 9; fi")
    (transaction / "identity.txt").write_text(identity)
    (transaction / "app.jar").write_bytes(b"rebuilt same source")
    f.shell("JAR refuses rebuilt different bytes", command, success=False)
    (transaction / "app.jar").write_bytes(JAR)
    f.state["signature_failure"] = True
    f.shell("JAR trust failure conditional", f"if {command}; then exit 9; fi")
    f.state = copy.deepcopy(base)

    owner_command = 'resolve_canonical_owner "$REPOSITORY" 9 "$GITHUB_SHA" "$GITHUB_RUN_ID" "$GITHUB_RUN_ATTEMPT"'
    owner_ids = f.state["asset_ids"].copy()
    f.state["asset_ids"] = {key: name for key, name in owner_ids.items() if name != "canonical-owner.json"}
    f.shell("unfinalized canonical uses actual release attempt two", owner_command, contains="canonical_run_attempt=2")
    f.state["asset_ids"] = owner_ids.copy()
    f.shell("finalized canonical preserves attempt one", owner_command, contains="canonical_current=false\ncanonical_run_id=100\ncanonical_run_attempt=1")
    f.state["asset_ids"]["999"] = "canonical-owner.json"
    f.shell("duplicate canonical owner refuses", owner_command, success=False)
    f.state["asset_ids"] = owner_ids.copy()
    owner = f.assets / "canonical-owner.json"
    owner_bytes = owner.read_bytes()
    dump(owner, {"schemaVersion": 1, "runId": "100", "runAttempt": "1", "sourceSha": "c" * 40})
    f.shell("canonical owner wrong source refuses", owner_command, success=False)
    owner.write_bytes(owner_bytes)

    # Execute workflow producer selection with stale needs outputs from a fresh rerun-all.
    selection = step_script("Select immutable backend JAR producer")
    producer_env = {"PRODUCER_ARTIFACT_ID": "88", "PRODUCER_ARTIFACT_DIGEST": "c" * 64,
                    "PRODUCER_RUN_ID": "100", "PRODUCER_RUN_ATTEMPT": "2", "PRODUCER_JAR_SHA": "d" * 64}
    # Run repository-relative helper commands from an isolated checkout facade, never modify checkout files.
    sandbox = f.path / "checkout"
    sandbox.mkdir()
    (sandbox / "ops").symlink_to(REPO / "ops", target_is_directory=True)
    f.run("rerun all selects durable artifact one instead of newly built two", selection, environment=producer_env,
          cwd=sandbox, contains="artifact_id=77")
    f.state["artifact"]["expired"] = True
    f.run("expired durable JAR fails without rebuild fallback", selection, environment=producer_env, cwd=sandbox, success=False)
    f.state = copy.deepcopy(base)
    f.state["asset_ids"] = {key: name for key, name in owner_ids.items() if name != "backend-jar-producer.json"}
    f.run("legacy candidate without producer refuses substitution", selection, environment=producer_env, cwd=sandbox, success=False)
    f.state = copy.deepcopy(base)

    # resolve_digest is tested as the actual workflow function, including the old command-substitution boundary.
    resolution = step_script("Resolve candidate digests from durable GitHub attestations")
    function = resolution[resolution.index("resolve_digest() {"):resolution.index('backend_digest="$(resolve_digest backend)"')]
    prefix = "set -Eeuo pipefail\nsource ops/lib/release-transaction.sh\nsource ops/lib/publish-workflow.sh\nowner=example\nmkdir -p candidate-commitments\n"
    env = {"CANDIDATE_TAG": "release-candidate-" + SHA}
    f.run("candidate digest extraction succeeds", prefix + function + 'value="$(resolve_digest backend)"; printf "%s" "$value"',
          environment=env, cwd=sandbox, contains=f.state["digests"]["backend"])
    for label, configure in (("download", lambda s: s.update(api_failure="/releases/assets/")),
                             ("attestation", lambda s: s.update(signature_failure=True))):
        f.state = copy.deepcopy(base)
        configure(f.state)
        f.run("candidate " + label + " failure under conditional substitution", prefix + function +
              'if value="$(resolve_digest backend)"; then exit 9; fi; [[ -z "$value" ]]', environment=env, cwd=sandbox)
    f.state = copy.deepcopy(base)
    f.state["signature_failure"] = True
    _, outputs = f.run("candidate trust failure emits no digest outputs", resolution,
                       environment=dict(env, OWNER="example"), cwd=sandbox, success=False)
    assert not outputs

    # Also execute the post-publication verifier where aliases have not yet been promoted.
    converge = step_script("Revalidate tag and converge GitHub Release assets")
    start = converge.index("verify_published_release() {")
    verifier = converge[start:converge.index('\nif [[ "$release_draft" == false ]]; then', start)]
    target_start = converge.index("resolve_target_commitish() {")
    target = converge[target_start:converge.index("\n}", target_start) + 2]
    setup = "set -Eeuo pipefail\nsource ops/lib/release-transaction.sh\nsource ops/lib/publish-workflow.sh\n"
    setup += "release_id=9\nOWNER=example\nVERSION=1.2.3\n"
    setup += f"archive_name={q(ARCHIVE)}\nrecovery_marker={q(base['release']['body'])}\n"
    setup += "expected_assets=(" + " ".join(q("release/" + n) for n in base["asset_ids"].values()) + ")\n"
    setup += f"BACKEND_DIGEST={q(base['digests']['backend'])}\nFRONTEND_DIGEST={q(base['digests']['frontend'])}\n"
    setup += target + "\n" + verifier + "\n"
    f.state = copy.deepcopy(base)
    f.state["alias"] = "missing"
    f.run("postpublish verifies writer checksum order before alias promotion", setup + "verify_published_release")
    for label, configure in (("complete failed response", lambda s: s.update(fail_after_output="/releases/9/assets")),
                             ("trust service", lambda s: s.update(signature_failure=True))):
        f.state = copy.deepcopy(base)
        configure(f.state)
        f.run("postpublish " + label + " refuses under conditional", setup + "if verify_published_release; then exit 9; fi")


with tempfile.TemporaryDirectory(prefix="yunlume-publish-behavior-") as directory:
    tests(Fixture(Path(directory)))
print(f"Publish workflow behavior: {checks} real shell scenarios passed; external writes: 0")
