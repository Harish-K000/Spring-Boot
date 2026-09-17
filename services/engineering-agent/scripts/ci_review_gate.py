#!/usr/bin/env python3
"""Run the engineering-agent review and turn its structured evidence into a CI exit code."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

SHA = re.compile(r"^[0-9a-fA-F]{40}$")
EXPECTED_SCANNERS = {"sast", "secrets", "dependencies"}
REGISTERED_SERVICES = (
    "auth-service", "orders-service", "inventory-service", "payments-service",
    "edge-gateway", "worker", "engineering-agent",
)


class GateError(RuntimeError):
    pass


def _object(value: Any, name: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise GateError(f"{name} is missing or malformed")
    return value


def _list(value: Any, name: str) -> list[Any]:
    if not isinstance(value, list):
        raise GateError(f"{name} is missing or malformed")
    return value


def _post_json(url: str, body: dict[str, Any], timeout: int) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status != 200:
                raise GateError(f"review endpoint returned HTTP {response.status}")
            return _object(json.load(response), "review response")
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise GateError(f"review endpoint failed: {type(error).__name__}") from error


def _get_json(url: str, timeout: int) -> dict[str, Any]:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            if response.status != 200:
                raise GateError(f"audit endpoint returned HTTP {response.status}")
            return _object(json.load(response), "audit response")
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise GateError(f"audit endpoint failed: {type(error).__name__}") from error


def evaluate_review(review: dict[str, Any], audit: dict[str, Any], expected_head: str,
                    shared_coverage: bool = False) -> list[str]:
    """Return deterministic blocking reasons. Empty means the review passes."""
    reasons: list[str] = []
    try:
        if review.get("status") != "EVIDENCE_COLLECTED":
            reasons.append("agent did not collect service evidence")
        service = review.get("service")
        if not isinstance(service, str) or not service:
            reasons.append("reviewed service is missing")

        changed = _object(review.get("changedFiles"), "changedFiles")
        if changed.get("status") != "SUCCESS" or changed.get("truncated") is not False:
            reasons.append("changed-file evidence is incomplete")
        if changed.get("sharedChanges") is not False and not shared_coverage:
            reasons.append("shared-path impact is unresolved")
        changed_services = _list(changed.get("changedServices"), "changedFiles.changedServices")
        direct_change = service in changed_services
        if service and not direct_change and not (shared_coverage and changed.get("sharedChanges") is True):
            reasons.append("selected service is absent from the changed-service evidence")

        diff = _object(review.get("gitDiff"), "gitDiff")
        if diff.get("status") != "SUCCESS" or diff.get("truncated") is not False:
            reasons.append("Git diff evidence is incomplete")

        build = _object(review.get("build"), "build")
        if build.get("status") != "PASS" or build.get("exitCode") != 0:
            reasons.append("compilation did not pass")

        tests = _object(review.get("tests"), "tests")
        test_counts = ("total", "passed", "failed", "errors", "skipped", "serviceTests")
        if any(not isinstance(tests.get(field), int) for field in test_counts):
            reasons.append("test counts are malformed")
        elif (
            tests.get("status") != "PASS"
            or tests.get("exitCode") != 0
            or tests.get("incomplete") is not False
            or tests["failed"] != 0
            or tests["errors"] != 0
            or tests["skipped"] != 0
            or tests["serviceTests"] <= 0
            or tests["passed"] != tests["total"]
        ):
            reasons.append("tests are failed, skipped, missing, or incomplete")

        security = _object(review.get("security"), "security")
        scanners = _list(security.get("scanners"), "security.scanners")
        scanner_names = {item.get("name") for item in scanners if isinstance(item, dict)}
        scanners_clean = (
            scanner_names == EXPECTED_SCANNERS
            and all(
                isinstance(item, dict)
                and item.get("status") == "PASS"
                and item.get("exitCode") == 0
                and item.get("findings") == 0
                for item in scanners
            )
        )
        if (
            security.get("status") != "PASS"
            or security.get("complete") is not True
            or security.get("totalFindings") != 0
            or security.get("findingsTruncated") is not False
            or not scanners_clean
        ):
            reasons.append("security scanner evidence is incomplete or has findings")

        allowed_analysis = {"AVAILABLE"} if direct_change else {"AVAILABLE", "SKIPPED"}
        if review.get("analysisStatus") not in allowed_analysis:
            reasons.append("structured model review is unavailable")
        report = _object(review.get("report"), "report")
        findings = _list(report.get("findings"), "report.findings")
        if findings:
            reasons.append("model findings require human triage")
        allowed_verification = {"PASS", "WARN"} if shared_coverage or not direct_change else {"PASS"}
        if report.get("verificationStatus") not in allowed_verification or report.get("decision") == "FAIL":
            reasons.append("agent report did not verify the engineering checks")

        audit_review = _object(audit.get("review"), "audit.review")
        if audit_review.get("reviewId") != review.get("reviewId"):
            reasons.append("audit review ID does not match the response")
        if audit_review.get("commitHash") != expected_head:
            reasons.append("audit commit does not match the pull-request head")
        if audit_review.get("service") != service:
            reasons.append("audit service does not match the response")
    except GateError as error:
        reasons.append(str(error))
    return list(dict.fromkeys(reasons))


def sanitized_review(review: dict[str, Any], audit: dict[str, Any], reasons: list[str]) -> dict[str, Any]:
    """Return bounded metadata only; omit tokens, code, model prose, and raw diagnostics."""
    changed = review.get("changedFiles") if isinstance(review.get("changedFiles"), dict) else {}
    build = review.get("build") if isinstance(review.get("build"), dict) else {}
    tests = review.get("tests") if isinstance(review.get("tests"), dict) else {}
    security = review.get("security") if isinstance(review.get("security"), dict) else {}
    report = review.get("report") if isinstance(review.get("report"), dict) else {}
    audit_review = audit.get("review") if isinstance(audit.get("review"), dict) else {}
    scanner_items = security.get("scanners") if isinstance(security.get("scanners"), list) else []
    failure_items = tests.get("failures") if isinstance(tests.get("failures"), list) else []
    return {
        "reviewId": review.get("reviewId"),
        "service": review.get("service"),
        "commitHash": audit_review.get("commitHash"),
        "result": "PASS" if not reasons else "BLOCKED",
        "reasons": reasons,
        "changedFileCount": len(changed.get("files", [])) if isinstance(changed.get("files"), list) else None,
        "build": {"status": build.get("status"), "exitCode": build.get("exitCode")},
        "tests": {
            **{key: tests.get(key) for key in
               ("status", "exitCode", "total", "passed", "failed", "errors", "skipped", "serviceTests", "incomplete")},
            # Class/method identity is enough to diagnose CI-only failures. Messages and stack traces stay private.
            "failures": [
                {key: item.get(key) for key in ("module", "test", "type")}
                for item in failure_items[:20] if isinstance(item, dict)
            ],
        },
        "security": {
            "status": security.get("status"),
            "complete": security.get("complete"),
            "totalFindings": security.get("totalFindings"),
            "scanners": [
                {key: item.get(key) for key in ("name", "status", "exitCode", "findings", "inspectedPackages")}
                for item in scanner_items if isinstance(item, dict)
            ],
        },
        "model": {
            "status": review.get("analysisStatus"),
            "acceptedFindingCount": len(report.get("findings", [])) if isinstance(report.get("findings"), list) else None,
            "rejectedFindingCount": review.get("rejectedModelFindings"),
        },
        "verificationStatus": report.get("verificationStatus"),
        "decision": report.get("decision"),
    }


def write_summary(path: Path | None, expected_head: str, result: str,
                  services: list[dict[str, Any]], reasons: list[str]) -> None:
    lines = [f"## Engineering Agent Review: {result}", "", f"Commit: `{expected_head}`", ""]
    if services:
        lines.extend(["| Service | Build | Tests | Security | Model | Result |",
                      "| --- | --- | --- | --- | --- | --- |"])
        for item in services:
            lines.append("| {service} | {build} | {tests} | {security} | {model} | {result} |".format(
                service=item.get("service") or "unknown",
                build=item.get("build", {}).get("status") or "missing",
                tests=item.get("tests", {}).get("status") or "missing",
                security=item.get("security", {}).get("status") or "missing",
                model=item.get("model", {}).get("status") or "missing",
                result=item.get("result") or "BLOCKED"))
        lines.append("")
    if reasons:
        lines.append("### Blocking reasons")
        lines.extend(f"- {reason}" for reason in reasons)
        lines.append("")
    lines.append("The uploaded JSON contains statuses, counts, and bounded failing-test identifiers. Review tokens, code, diffs, prompts, model prose, failure messages, and stack traces are omitted.")
    rendered = "\n".join(lines) + "\n"
    if path:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as output:
            output.write(rendered)
    print(rendered, end="")


def run(agent_url: str, expected_head: str, output: Path, summary: Path | None, timeout: int) -> int:
    if not SHA.fullmatch(expected_head):
        raise GateError("expected head must be a full 40-character Git SHA")
    base = agent_url.rstrip("/")
    first = _post_json(f"{base}/api/agent/review", {}, timeout)
    changed = _object(first.get("changedFiles"), "changedFiles")
    if changed.get("status") != "SUCCESS" or changed.get("truncated") is not False:
        raise GateError("initial changed-file discovery is incomplete")
    discovered_services = _list(first.get("changedServices"), "changedServices")
    if not all(isinstance(service, str) and service in REGISTERED_SERVICES for service in discovered_services):
        raise GateError("changedServices is malformed")
    shared_coverage = changed.get("sharedChanges") is True
    target_services = list(REGISTERED_SERVICES) if shared_coverage else discovered_services

    reviews: list[dict[str, Any]] = []
    all_reasons: list[str] = []
    if target_services:
        responses = []
        completed = first.get("service") if first.get("status") == "EVIDENCE_COLLECTED" else None
        if completed in target_services:
            responses.append(first)
        responses.extend(_post_json(f"{base}/api/agent/review", {"service": service}, timeout)
                         for service in target_services if service != completed)
        for review in responses:
            review_id = review.get("reviewId")
            if not isinstance(review_id, str) or not review_id:
                raise GateError("review ID is missing")
            audit = _get_json(f"{base}/api/agent/reviews/{review_id}/audit", timeout)
            reasons = evaluate_review(review, audit, expected_head, shared_coverage)
            safe = sanitized_review(review, audit, reasons)
            reviews.append(safe)
            all_reasons.extend(f"{safe.get('service') or 'unknown'}: {reason}" for reason in reasons)

    result = "PASS" if not all_reasons else "BLOCKED"
    artifact = {"schemaVersion": 1, "headCommit": expected_head, "result": result,
                "reasons": all_reasons, "services": reviews}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(artifact, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    write_summary(summary, expected_head, result, reviews, all_reasons)
    return 0 if result == "PASS" else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-url", default="http://127.0.0.1:8090")
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--summary", type=Path, default=Path(os.environ["GITHUB_STEP_SUMMARY"])
                        if os.environ.get("GITHUB_STEP_SUMMARY") else None)
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    try:
        return run(args.agent_url, args.expected_head, args.output, args.summary, args.timeout)
    except GateError as error:
        artifact = {"schemaVersion": 1, "headCommit": args.expected_head,
                    "result": "BLOCKED", "reasons": [str(error)], "services": []}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(artifact, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        write_summary(args.summary, args.expected_head, "BLOCKED", [], [str(error)])
        return 1


if __name__ == "__main__":
    sys.exit(main())
