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

## 5.1 Execution and trust boundary

The repository is public, so PR code must not execute on the developer's Mac. GitHub warns that a
self-hosted runner attached to a public repository can be persistently compromised by code from a
pull request. The automated review therefore runs on a fresh GitHub-hosted `ubuntu-latest` runner.

The review job will:

1. Check out the exact pull-request revision.
2. Pass the trusted base commit SHA as startup configuration and check out the exact head SHA. The
   bounded Git tools compare that base to `HEAD` directly; the model and HTTP request cannot choose
   revisions.
3. Set up Java 25 and the Maven dependency cache.
4. Install pinned versions of Ollama, Semgrep, Gitleaks, and OSV-Scanner.
5. Pull the configured small Ollama model, then disable cloud inference.
6. Build and start the engineering MCP server on loopback.
7. Build and start the Spring AI engineering agent on loopback.
8. Review each directly changed registered service with the fixed review endpoint. If an approved
   shared path changed, conservatively review all seven registered services.
9. Publish a sanitized GitHub job summary and JSON evidence artifact.
10. Return success or failure through one uniquely named `Engineering Agent Review` check.

The workflow uses a read-only `GITHUB_TOKEN`, receives no repository secrets, and exposes neither
local services nor review action tokens outside the temporary runner.

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

During development, only the existing check remains required:

```text
Maven verify (Java 25)
```

After the agent workflow has successfully run on a pull request, branch protection will require:

```text
Maven verify (Java 25)
Engineering Agent Review
```

GitHub requires checks to pass on the latest pull-request commit, so pushing another commit runs
both gates again.

## Implemented files

| File | Responsibility |
| --- | --- |
| `.github/workflows/engineering-agent-review.yml` | Runs the isolated PR review job with read-only repository permission and pinned actions/tools. |
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
2. The runner restores or downloads the checksum-verified Ollama runtime and model, then verifies
   their expected versions/ID.
3. It installs exact Semgrep, Gitleaks and OSV-Scanner versions and verifies each executable.
4. The deterministic gate unit tests run before any review decision is trusted.
5. Maven packages the agent, then GitHub starts Ollama, the MCP server and the agent on loopback.
6. MCP compares the configured base SHA with `HEAD` and identifies directly changed services plus
   any shared-path change.
7. Each directly changed service compiles, runs its normal tests, runs all three scanners and
   receives a bounded structured model review. A shared-path change expands deterministic build,
   test and scan coverage to every registered service; indirectly affected services can have model
   status `SKIPPED` because no direct service code was supplied to the model.
8. The gate fetches compact audit metadata and requires its commit hash to equal the PR head.
9. The job writes a GitHub summary and uploads `engineering-agent-review.json`. The artifact contains
   statuses and counts; it omits action tokens, source, diffs, prompts, model prose and diagnostics.
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
5.3  Headless review command and exit policy
              ↓
5.4  GitHub-hosted agent workflow
              ↓
5.5  PR summary and evidence artifact
              ↓
5.6  Failure and tampering tests
              ↓
5.7  Require Engineering Agent Review on main
              ↓
5.8  Optional auto-merge request
```

Steps 5.1 through 5.7 are delivered together. Step 5.8 remains deliberately optional: automatic
merge is disabled, so the repository owner still chooses when to merge after both required checks
pass.

## References

- [GitHub secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)
- [GitHub status checks](https://docs.github.com/en/pull-requests/reference/status-checks)
- [GitHub protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
