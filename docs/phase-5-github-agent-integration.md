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
8. Review each directly changed registered service with the fixed review endpoint.
9. Publish a sanitized GitHub job summary and JSON evidence artifact.
10. Return success or failure through one uniquely named `Engineering Agent Review` check.

The workflow uses a read-only `GITHUB_TOKEN`, receives no repository secrets, and exposes neither
local services nor review action tokens outside the temporary runner.

## Gate policy

The check reports **BLOCKED** when any required evidence is absent or fails:

- changed-file or Git-diff inspection fails or is truncated;
- compilation fails;
- tests fail, error, are incomplete, or are skipped;
- any security scanner is unavailable, incomplete, times out, errors, or reports a blocking
  high/critical match;
- the agent or MCP server cannot start;
- the response is malformed or does not match the checked-out commit.

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

## Implementation order

```text
5.1  Execution and trust boundary
              ↓
5.2  PR-aware Git evidence (implemented)
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

## References

- [GitHub secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)
- [GitHub status checks](https://docs.github.com/en/pull-requests/reference/status-checks)
- [GitHub protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
