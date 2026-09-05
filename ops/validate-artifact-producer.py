#!/usr/bin/env python3
"""校验 immutable-once 的 JAR producer 描述及 GitHub 原始 API 元数据。"""
import datetime
import json
import re
import sys
from pathlib import Path


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def timestamp(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def validate(descriptor, source_sha, metadata=None, repository=None):
    value = json.loads(Path(descriptor).read_text(encoding="utf-8"))
    require(isinstance(value, dict) and set(value) == {
        "schemaVersion", "artifactId", "artifactDigest", "runId", "runAttempt", "sourceSha", "jarSha256"
    } and type(value["schemaVersion"]) is int and value["schemaVersion"] == 1, "JAR producer schema mismatch")
    for field in ("artifactId", "runId", "runAttempt"):
        require(isinstance(value[field], str) and re.fullmatch(r"[1-9][0-9]*", value[field]),
                f"JAR producer {field} is invalid")
    for field in ("artifactDigest", "jarSha256"):
        require(isinstance(value[field], str) and re.fullmatch(r"[0-9a-f]{64}", value[field]),
                f"JAR producer {field} is invalid")
    require(value["sourceSha"] == source_sha and re.fullmatch(r"[0-9a-f]{40}", source_sha),
            "JAR producer source SHA mismatch")
    if metadata is None:
        return value
    root = Path(metadata)
    artifact = json.loads((root / "artifact.json").read_text())
    run = json.loads((root / "attempt.json").read_text())
    pages = json.loads((root / "jobs.json").read_text())
    require(artifact.get("id") == int(value["artifactId"]) and artifact.get("expired") is False,
            "Exact producer artifact is missing or expired; rebuilding is forbidden")
    require(artifact.get("digest") == "sha256:" + value["artifactDigest"], "Artifact archive digest mismatch")
    require(artifact.get("name") == f'tested-backend-jar-{source_sha}-{value["runId"]}-{value["runAttempt"]}',
            "Artifact producer attempt name mismatch")
    origin = artifact.get("workflow_run", {})
    require(origin.get("id") == int(value["runId"]) and origin.get("head_sha") == source_sha,
            "Artifact producer run/source mismatch")
    require(run.get("id") == int(value["runId"]) and run.get("run_attempt") == int(value["runAttempt"])
            and run.get("head_sha") == source_sha and run.get("event") == "push"
            and run.get("path", "").split("@", 1)[0] == ".github/workflows/publish-images.yml"
            and run.get("repository", {}).get("full_name", "").lower() == repository.lower(),
            "Artifact producer workflow attempt mismatch")
    require(origin.get("repository_id") == run.get("repository", {}).get("id")
            and origin.get("head_repository_id") == run.get("repository", {}).get("id"),
            "Artifact originates from another repository")
    require(isinstance(pages, list), "Invalid producer jobs response")
    # 此列表来自 /attempts/{attempt}/jobs；Job 响应没有保证提供 run_attempt。
    jobs = [job for page in pages for job in page.get("jobs", [])
            if job.get("name") == "Attest exact tested backend JAR"]
    require(len(jobs) == 1 and jobs[0].get("conclusion") == "success", "Producer attestation job did not succeed")
    require(jobs[0].get("run_id") == int(value["runId"]) and jobs[0].get("head_sha") == source_sha,
            "Producer job run/source mismatch")
    require(timestamp(jobs[0]["started_at"]) <= timestamp(artifact["created_at"]) <= timestamp(jobs[0]["completed_at"]),
            "Artifact was not created during its producer job")
    return value


if __name__ == "__main__":
    args = sys.argv[1:]
    result = validate(*args)
    if len(args) == 2:
        print("\t".join(result[key] for key in ("artifactId", "runId", "runAttempt")))
