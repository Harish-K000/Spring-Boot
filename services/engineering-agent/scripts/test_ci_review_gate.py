import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE = Path(__file__).with_name("ci_review_gate.py")
SPEC = importlib.util.spec_from_file_location("ci_review_gate", MODULE)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


def passing_review():
    return {
        "reviewId": "REV-1", "status": "EVIDENCE_COLLECTED", "service": "engineering-agent",
        "changedFiles": {"status": "SUCCESS", "truncated": False, "sharedChanges": False,
                         "changedServices": ["engineering-agent"], "files": [{"path": "secret-name.java"}]},
        "gitDiff": {"status": "SUCCESS", "truncated": False, "diff": "SECRET_DIFF"},
        "build": {"status": "PASS", "exitCode": 0, "diagnostics": ["SECRET_DIAGNOSTIC"]},
        "tests": {"status": "PASS", "exitCode": 0, "total": 12, "passed": 12, "failed": 0,
                  "errors": 0, "skipped": 0, "serviceTests": 12, "incomplete": False},
        "security": {"status": "PASS", "complete": True, "totalFindings": 0,
                     "findingsTruncated": False, "scanners": [
                         {"name": name, "status": "PASS", "exitCode": 0, "findings": 0,
                          "inspectedPackages": 1} for name in ("sast", "secrets", "dependencies")]},
        "analysis": "SECRET_MODEL_TEXT", "analysisStatus": "AVAILABLE", "rejectedModelFindings": 0,
        "report": {"verificationStatus": "PASS", "decision": "WARN", "findings": []},
        "approval": {"actionToken": "SECRET_ACTION_TOKEN"},
    }


def audit(head="a" * 40):
    return {"review": {"reviewId": "REV-1", "commitHash": head, "service": "engineering-agent"}}


class GatePolicyTest(unittest.TestCase):
    def test_complete_clean_evidence_passes(self):
        self.assertEqual([], gate.evaluate_review(passing_review(), audit(), "a" * 40))

    def test_failed_or_skipped_tests_block(self):
        review = passing_review()
        review["tests"]["skipped"] = 1
        review["tests"]["passed"] = 11
        self.assertIn("tests are failed, skipped, missing, or incomplete",
                      gate.evaluate_review(review, audit(), "a" * 40))

    def test_scanner_findings_block(self):
        review = passing_review()
        review["security"]["status"] = "FINDINGS"
        review["security"]["totalFindings"] = 1
        review["security"]["scanners"][0].update(status="FINDINGS", exitCode=1, findings=1)
        self.assertIn("security scanner evidence is incomplete or has findings",
                      gate.evaluate_review(review, audit(), "a" * 40))

    def test_mismatched_commit_blocks_tampered_evidence(self):
        self.assertIn("audit commit does not match the pull-request head",
                      gate.evaluate_review(passing_review(), audit("b" * 40), "a" * 40))

    def test_malformed_response_blocks_closed(self):
        review = passing_review()
        review.pop("tests")
        self.assertIn("tests is missing or malformed",
                      gate.evaluate_review(review, audit(), "a" * 40))

    def test_indirect_service_passes_only_when_all_shared_impacts_are_covered(self):
        review = passing_review()
        review["service"] = "auth-service"
        review["changedFiles"].update(sharedChanges=True, changedServices=["engineering-agent"])
        review["analysisStatus"] = "SKIPPED"
        review["report"]["verificationStatus"] = "WARN"
        shared_audit = audit()
        shared_audit["review"]["service"] = "auth-service"
        self.assertEqual([], gate.evaluate_review(review, shared_audit, "a" * 40, shared_coverage=True))
        self.assertIn("shared-path impact is unresolved",
                      gate.evaluate_review(review, shared_audit, "a" * 40, shared_coverage=False))

    def test_sanitized_artifact_omits_secrets_code_and_diagnostics(self):
        safe = gate.sanitized_review(passing_review(), audit(), [])
        encoded = json.dumps(safe)
        for forbidden in ("SECRET_ACTION_TOKEN", "SECRET_DIFF", "SECRET_MODEL_TEXT",
                          "SECRET_DIAGNOSTIC", "secret-name.java"):
            self.assertNotIn(forbidden, encoded)

    def test_summary_and_json_are_written_without_sensitive_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "review.json"
            summary = Path(directory) / "summary.md"
            safe = gate.sanitized_review(passing_review(), audit(), [])
            output.write_text(json.dumps({"services": [safe]}), encoding="utf-8")
            gate.write_summary(summary, "a" * 40, "PASS", [safe], [])
            combined = output.read_text() + summary.read_text()
            self.assertIn("Engineering Agent Review: PASS", combined)
            self.assertNotIn("SECRET_", combined)


if __name__ == "__main__":
    unittest.main()
