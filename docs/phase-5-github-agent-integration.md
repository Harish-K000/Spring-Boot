# Phase 5 — GitHub PR and Engineering Agent Integration

## Objective

Turn every pull request into the same evidence-based review that a developer can run locally:

```text
Feature branch
     ↓
Local verification (optional but recommended)
     ↓
git push
     ↓
Pull request
     ↓
GitHub automatically runs verification
     ↓
Build + tests + security + engineering-agent review
     ↓
GitHub PR: PASS or BLOCKED
```

GitHub remains the merge authority. The engineering agent produces evidence and a review result;
it does not receive permission to bypass branch protection or push to `main`.

## 5.3 Automatic review on the self-hosted Mac

The `Engineering Agent Review` workflow runs on `[self-hosted, macOS, ARM64]` for
same-repository PRs on `opened`, `synchronize`, `reopened` and `ready_for_review`,
including drafts. It checks out the exact event head SHA with full history and passes
`AGENT_REVIEW_BASE_COMMIT` from the event to the MCP server at startup. Java validates
the base and constructs its fixed `base...HEAD` comparison; Qwen never chooses Git
arguments. The existing `ci_review_gate.py` calls `POST /api/agent/review`, checks the
audit commit against the event head, and maps complete evidence to exit 0 or 1.

Runner prerequisites are Python 3, Ollama, the `qwen2.5:1.5b` model (ID
`65ec06548149`), Semgrep 1.176.0, Gitleaks 8.30.1 and OSV-Scanner 2.6.0. Java 25 is
provisioned by the pinned setup action. Scanner versions and the installed model ID
are checked before the review starts. Missing tools/models fail the check.

The job starts its own local-only Ollama daemon on 11435, MCP on 18091 and agent on
18090, after checking those ports are free. Startup uses bounded health checks and
process liveness checks. Run-specific logs and audit files live under `RUNNER_TEMP`.
The always-run cleanup stops only the recorded job processes and removes private
logs/audit databases; sanitized JSON remains available as the review artifact.
GitHub's runner process cleanup also applies on cancellation.
The job holds a bounded `caffeinate` idle-sleep assertion while running. Keep the
Mac awake with its lid open and the runner online; the assertion cannot override
lid closure or make a sleeping/offline runner accept a new job.

The repository is public. Fork PRs are excluded from this Mac job, and the token is
read-only with checkout credentials disabled. These are defense-in-depth controls,
not isolation: PR workflow code itself can change. A persistent personal Mac must
only run trusted code; untrusted contributions need isolated disposable runners.
The SHA checks constrain the agent's Git interface, not arbitrary Maven/PR code.

## Gate policy

The check reports **BLOCKED** when any required evidence is absent or fails:

- changed-file or Git-diff inspection fails or is truncated;
- compilation fails;
- tests fail, error, are incomplete, or are skipped;
- any security scanner is unavailable, incomplete, times out, errors, or reports a match;
- the agent or MCP server cannot start;
- the response is malformed or does not match the checked-out commit.
- the structured model review is unavailable or returns an accepted potential finding that needs
  human triage.

The check reports **PASS** when all deterministic evidence is complete and successful. Model
findings remain explicitly `POTENTIAL`; they appear in the summary for human review and cannot
override failed tool evidence or manufacture a passing result.

## GitHub permissions

Initial workflow permissions:

```yaml
permissions:
  contents: read
```

The job does not comment, approve, merge, or push. A later publishing step may receive narrowly
scoped `pull-requests: write` permission through a separate job after its output and fork behavior
are tested. Merge automation, if enabled later, will request GitHub auto-merge and let required
checks and branch protection make the final decision.

## Required checks

The current `main` branch protection already requires:

```text
Maven verify (Java 25)
Engineering Agent Review
```

GitHub requires checks to pass on the latest pull-request commit, so pushing another commit runs
both gates again.

## Implemented files

| File | Responsibility |
| --- | --- |
| `.github/workflows/engineering-agent-review.yml` | Runs the trusted same-repository PR review on the Mac with read-only repository permission and pinned actions. |
| `services/engineering-agent/scripts/ci_review_gate.py` | Calls the fixed review API, verifies the audit commit, applies the PASS/BLOCKED policy and emits sanitized evidence. |
| `services/engineering-agent/scripts/test_ci_review_gate.py` | Proves pass, test-failure, scanner-failure, malformed-response, commit-tampering and secret-omission behavior. |
| `services/engineering-agent/osv-scanner.toml` | Records the dated MCP advisory exception whose own advisory says Spring AI is unaffected. |
| `services/engineering-agent/pom.xml` | Excludes explicitly tagged live checks from ordinary test evidence, while keeping them available on demand. |
| `pom.xml` | Pins patched Tomcat, PostgreSQL JDBC, Commons Compress and Commons Lang versions to remove the current advisories found by the new gate. |

The Git readers accept up to 500 approved files, 128 KB of path/status evidence and 256 KB of
redacted diff evidence. The model still receives only its existing 4,000-character selected-service
preview. The larger tool bound prevents ordinary PRs from becoming incomplete without increasing
the model prompt limit.

## What happens on a pull request

1. GitHub checks out the exact PR head with full history and validates both event SHAs.
2. The runner starts its installed Ollama runtime on a dedicated loopback port and verifies the model ID.
3. It verifies the installed Semgrep, Gitleaks and OSV-Scanner versions.
4. The deterministic gate unit tests run before any review decision is trusted.
5. Maven packages and tests the agent, then GitHub starts the MCP server and the agent on loopback.
6. MCP compares the configured base SHA with `HEAD` and identifies directly changed services plus
   any shared-path change.
7. Each directly changed service compiles, runs its normal tests, runs all three scanners and
   receives a bounded structured model review. A shared-path change expands deterministic build,
   test and scan coverage to every registered service; indirectly affected services can have model
   status `SKIPPED` because no direct service code was supplied to the model.
8. The gate fetches compact audit metadata and requires its commit hash to equal the PR head.
9. The job writes a GitHub summary and uploads sanitized `review.json`. The artifact contains
   statuses, counts and bounded failing-test identifiers; it omits action tokens, source, diffs,
   prompts, model prose, failure messages and stack traces.
10. Exit code `0` produces **PASS**. Any missing, failing, mismatched or triage-required evidence exits
    `1` and produces **BLOCKED**.

The normal engineering-agent suite excludes tests tagged `live`; this avoids representing tests that
were never selected as skipped evidence. They remain runnable with their documented environment flag
and `-Dagent.test.excluded-groups=`.

## Local gate policy test

From the repository root:

```sh
python3 -m unittest services/engineering-agent/scripts/test_ci_review_gate.py
```

For an already running agent/MCP/Ollama stack, run the same headless gate used by GitHub:

```sh
python3 services/engineering-agent/scripts/ci_review_gate.py \
  --expected-head "$(git rev-parse HEAD)" \
  --output /tmp/engineering-agent-review.json
```

## Implementation order

```text
5.1  Execution and trust boundary
              ↓
5.2  PR-aware Git evidence
              ↓
5.3  Automatic Engineering Agent Review on the self-hosted Mac
              ↓
5.4  Repository rules / required Engineering Agent Review check
              ↓
Later: optional auto-merge request
```

The repository already requires both named checks. Phase 5.3 does not change branch protection
or enable automatic merge. The owner still chooses when to merge after required checks pass.

## References

- [GitHub secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)
- [GitHub status checks](https://docs.github.com/en/pull-requests/reference/status-checks)
- [GitHub protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
