# Engineering Agent

Phase 3 was built one step at a time. Steps 3.1–3.17 provide a local Spring AI
chat endpoint, approved engineering tools over MCP, a review flow, line-linked AI
findings, fixed security scanners, a local approval workflow, durable audit and
explicit V1 safety rules. The five tools exposed to chat are
`getGitDiff()`, `getChangedFiles()`, `readSourceFile(path)`, `runBuild(service)` and
`runTests(service)`. The fixed review flow also uses `securityScan(service)` and
`getHeadCommit()` for audit metadata.

## Step 3.1 — Spring Boot foundation

### One-command review

After Ollama, the MCP tool server and the Engineering Agent are running, invoke
the repository wrapper from the repository root:

```sh
./agent edge-gateway
```

The optional argument selects a registered service. Running `./agent` without an
argument asks the review flow to infer the service from the changed-file list.
The wrapper calls the fixed review endpoint, which collects Git evidence, builds,
tests, scans and requests local model analysis through the MCP tool boundary.

### Files to understand

- `pom.xml` inherits Java **25** and Spring Boot 3.5.10 from the repository parent.
  The Web starter provides the HTTP server; Actuator provides the health endpoint.
- `EngineeringAgentApplication.java` starts Spring Boot.
- `application.yaml` names the service and binds it to `127.0.0.1:8090`.
- The root POM registers this service as a Maven module.

### Build and run

Use JDK 25. On this Mac, it is installed at `/opt/homebrew/opt/openjdk@25`.
If your shell still defaults to Java 17, set the JDK for the command explicitly.
From the repository root:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -pl services/engineering-agent -am clean package
# Terminal 1: approved tool server on loopback port 8091.
/opt/homebrew/opt/openjdk@25/bin/java -jar services/engineering-agent/target/engineering-agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=mcp-server
# Terminal 2, from the same repository root: agent on loopback port 8090.
/opt/homebrew/opt/openjdk@25/bin/java -jar services/engineering-agent/target/engineering-agent-0.0.1-SNAPSHOT.jar
```

On another machine, use its JDK 25 installation path. `-pl` selects the module;
`-am` includes reactor modules it needs. The Boot plugin packages an executable JAR.

In another terminal:

```sh
curl http://127.0.0.1:8090/actuator/health
```

Expected: `{"status":"UP"}`. This checks application health, not model availability.
Stop the application with `Ctrl+C`.

## Step 3.2 — Spring AI and local Ollama

```text
Java → Spring AI ChatModel → Ollama on port 11434 → Local model
```

- **Ollama** is a separate process that loads and runs the model on your machine.
- **Qwen 2.5 1.5B** is our small starter model, approximately 1 GB to download.
  Its review quality needs evaluation before relying on it.
- **Spring AI** supplies the provider-independent `ChatModel` interface. The Ollama
  starter creates its implementation and a `ChatClient.Builder` automatically.
- **The Spring AI BOM** keeps AI dependency versions aligned. We use 1.1.8 with
  the repository's Spring Boot 3.5 baseline.

Later review code will depend on Spring AI interfaces. Switching providers requires
changing the starter and configuration, plus compatibility testing, without putting
provider-specific calls into `CodeReviewAgent`.

### Local setup on macOS

```sh
brew install ollama
OLLAMA_NO_CLOUD=1 ollama serve
```

Leave the server running. In another terminal:

```sh
ollama pull qwen2.5:1.5b
ollama list
```

Downloading requires internet access. Inference runs locally without an API key
or per-request API charges. Application startup does not automatically download models.

### Configuration

| Setting | Meaning |
| --- | --- |
| `spring.ai.model.chat: ollama` | Selects the Ollama chat provider. |
| `spring.ai.model.embedding: none` | Disables embeddings. |
| `spring.ai.ollama.base-url` | Connects to `http://127.0.0.1:11434`. |
| `spring.ai.ollama.init.pull-model-strategy: never` | Downloads are handled explicitly. |
| `spring.ai.ollama.chat.options.model` | Uses `AGENT_MODEL`, defaulting to `qwen2.5:1.5b`. |
| `temperature: 0.1` | Reduces randomness, without guaranteeing identical responses. |
| `num-ctx: 4096` | Sets the initial context window. |
| `spring.ai.retry.max-attempts: 1` | Surfaces failures without a long retry cycle. |
| `spring.http.client.*-timeout` | Bounds connection and read waits to 5 and 120 seconds. |

## Step 3.3 — ChatClient smoke test

```text
POST /api/agent/chat → AgentChatController → AgentChatService → ChatClient → Ollama
```

The controller validates JSON. The service builds a `ChatClient` with standing
instructions in `defaultSystem(...)`, then supplies the user's message separately:

```java
chatClient.prompt()
        .user(message)
        .call()
        .content();
```

`prompt()` creates a request; `user(...)` sets its user message;
`call().content()` performs synchronous inference and extracts response text.
Each request is independent; no conversation history is retained.

With Ollama and the application running:

```sh
curl --max-time 130 http://127.0.0.1:8090/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Explain transactional outbox in three sentences."}'
```

Response shape: `{"response":"..."}`. Wording varies and can be inaccurate.

- Blank/missing messages, malformed JSON and messages over 4,000 characters return `400`.
- Failed inference or an empty model response returns `503` with a problem detail.
- Connection refused usually means Ollama is not running. Model not found means
  the configured model needs to be downloaded.

## Step 3.4 — First approved tool: getGitDiff()

### What changes now

The same chat endpoint can inspect code changes. `GitDiffTool` declares a method
with `@Tool`; the service makes a new tool instance available for each request:

```java
chatClient.prompt()
        .user(message)
        .tools(new GitDiffTool(gitDiffReader))
        .call()
        .content();
```

```text
User asks about changes
  → model requests getGitDiff()
  → Spring AI invokes the Java tool
  → GitDiffReader delegates to the shared RepositoryGit operation
  → structured tool result goes back to the model
  → model summarizes the evidence
```

The model requests a Java operation. It cannot supply a shell command, repository
path or Git arguments. No shell is launched. The `@Tool` annotation is the registration
mechanism; the Java implementation enforces the actual operation boundaries.

### Scope and limits

The diff is equivalent to `git diff HEAD -- <approved paths>`, with additional
options disabling external diff helpers, text conversion and submodule inspection.

- Includes staged and unstaged **tracked Java, SQL and Maven POM files**.
- Excludes untracked files, other formats, hidden paths, generated directories,
  and paths containing `secret` or `credential`. Newly created agent files remain
  untracked until the developer chooses to stage them; the tool never stages files.
- An empty result means no changes **within this scope**, not a clean repository.
- Git configuration inspection and diff each have a five-second timeout.
- Output capture is bounded to 12,000 bytes and marks `truncated=true` when incomplete.
- Known credential patterns and sensitive lines are redacted before reaching the
  model. This is a conservative heuristic, not the later security scanner.
- External diff, textconv, fsmonitor and configured clean/process filters are disabled
  for this invocation. The repository's Git config and index are not modified.
- Git receives a restricted environment, excluding inherited credentials and Git overrides.
- Each tool can be invoked once per request. Repeated calls stop the request
  with HTTP `502`; ordinary Git errors are returned to the model as structured results.
- Tool logs contain status, duration and truncation/redaction indicators, not diff contents.

`SUCCESS` means Git inspection succeeded. It says nothing about build/test results
or production readiness. The model's summary still needs human scrutiny.

The configured repository is trusted local input. These controls are not an OS-level
sandbox for arbitrary hostile repositories or concurrent changes to Git configuration.

### Repository location

By default, the reader finds the nearest Git root above the process's working
directory. To start from elsewhere, set `AGENT_REPOSITORY` to the absolute Git root.
The model cannot change this value. A missing repository or missing `HEAD` commit
produces an `ERROR` result rather than an empty successful diff.

For a pull-request checkout, set `AGENT_REVIEW_BASE_COMMIT` to the full 40- or
64-character base commit hash. Both Git readers then inspect committed changes from
that base to `HEAD` using three-dot comparison semantics. The value is startup
configuration rather than a model or HTTP argument. Malformed values stop application
startup, and a well-formed hash that is absent from the checkout returns structured
`ERROR` evidence. Leave it empty for the normal local working-tree behavior.

### Try the tool

```sh
curl --max-time 260 http://127.0.0.1:8090/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Call getGitDiff now. Briefly summarize the actual changes returned by the tool."}'
```

Look for `Tool getGitDiff: status=SUCCESS` in the application log to confirm execution.
This request can require two model calls: one to choose the tool and another to
interpret its result. If the small model produces a poor summary, execution evidence
and summary quality must be assessed separately.

### Verification

Ordinary builds run the endpoint tests, an HTTP stub test exercising the real Spring
AI adapter and tool dispatch, and isolated Git fixture tests. They require Git but
no model download. Fixture commits occur only in temporary test repositories.

Run the optional real-model tests with Ollama running:

```sh
AGENT_LIVE_OLLAMA_TEST=true JAVA_HOME=/opt/homebrew/opt/openjdk@25 \
  ./mvnw -pl services/engineering-agent test -Dtest=OllamaLiveTest \
  -Dagent.test.excluded-groups=
```

The live tests check a real model response and verify that the model actually
invoked each Git reader. They do not grade the summary's factual accuracy.

## Step 3.5 — Changed-file inventory

`getChangedFiles()` answers which approved files changed. It returns paths and
Git status without returning file contents. `getGitDiff()` supplies the tracked
content changes when a review needs them.

```text
getChangedFiles() → paths + statuses + directly changed services
getGitDiff()      → tracked code changes
```

Both use `RepositoryGit`, which centralizes the fixed commands, path filters,
restricted environment, disabled Git helpers and execution limits. Local mode uses:

```sh
git status --porcelain=v1 -z --untracked-files=all --no-renames --ignore-submodules=all -- <approved paths>
```

PR mode uses the configured full commit hash and a fixed `HEAD` target:

```sh
git diff --name-status -z --no-renames --ignore-submodules=all <base>...HEAD -- <approved paths>
git diff --no-ext-diff --no-textconv --no-renames --no-color \
  --ignore-submodules=all --unified=3 <base>...HEAD -- <approved paths>
```

`--porcelain=v1` provides a stable format for Java to parse. `-z` separates records
with a null byte, preserving spaces, tabs and newlines in filenames. Disabling
rename detection represents a move as a deleted path and an added path.

### Structured result

An illustrative file entry:

```json
{
  "path": "services/auth-service/src/main/java/example/AuthService.java",
  "indexStatus": "UNCHANGED",
  "workTreeStatus": "MODIFIED",
  "conflicted": false,
  "service": "auth-service"
}
```

- **indexStatus** describes the staged change relative to `HEAD`.
- **workTreeStatus** describes the working-tree change relative to the index.
- Both statuses are `UNTRACKED` for a new file not yet staged.
- In PR mode, `indexStatus` contains the committed base-to-HEAD status and
  `workTreeStatus` is `UNCHANGED` because no index/work-tree state is being described.
- `conflicted` flags unresolved merge conflicts.
- `service` comes from a known `services/<name>/...` path. Other paths have `null`.
- `changedServices` contains the sorted, unique services present in returned entries.
- `sharedChanges` flags returned paths outside known service directories, such as
  the root POM or a shared library. It does not calculate all dependent services.

### Scope and limits

This tool keeps the same Java/SQL/POM allowlist and sensitive-path exclusions as
`getGitDiff()`. It additionally lists individual non-ignored untracked files.
A new source file may therefore appear in the inventory before it appears in the diff.
Configuration files such as YAML and `.env` remain outside this step's scope.

The result contains at most 100 entries and captures at most 12,000 bytes from Git.
`truncated=true` means both the list and service mapping may be incomplete; a partial
filename at the byte boundary is discarded. A Git error returns `ERROR`, not a
successful empty list. Git status also works before a repository's first commit.

In local mode these two calls inspect the current working tree at their execution
times. In PR mode they inspect the committed base-to-HEAD comparison, provided the
workflow checks out the expected head commit. A listed file has not necessarily had
its contents inspected by the model.

### Try it

Restart the rebuilt service, then request the inventory:

```sh
curl --max-time 260 http://127.0.0.1:8090/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Call getChangedFiles now. List the changed services and tell me whether any listed files are untracked."}'
```

For a combined inspection, ask: `Call getChangedFiles and getGitDiff once each,
then summarize the changes and the limits of your inspection.`

The application log records `Tool getChangedFiles: status=SUCCESS` with the file
count. The existing endpoint still returns `{"response":"..."}`; the structured
inventory is the tool result consumed by the model, not a separate HTTP endpoint.

Fixture tests cover staged and unstaged edits, untracked files, renames, real merge
conflicts, unusual filenames, exclusions, truncation, shared paths and invocation
limits. The adapter test verifies both tool results reach the model in one request.

## Step 3.6: Safe source-file reader

`readSourceFile(path)` reads one current working-tree source file, including an
untracked file. This fills the gap between listing a new file and understanding
its contents. It uses Java file APIs; it does not execute a shell command.

### Files and responsibilities

- `ToolExecutionPolicy` validates the model-supplied relative path and file type.
- `SourceFileReader` walks the repository through secure directory handles,
  rejects symlinks, reads bounded UTF-8 content, and applies the existing diff
  redaction rules before returning evidence.
- `SourceFileTool` exposes the reader through Spring AI and enforces one call per
  chat request, including denied attempts. Logs contain status, duration and the
  redaction flag, without source contents or requested paths.
- `AgentChatService` registers all three tools for each request and tells the model
  to treat source contents as untrusted data, never as instructions.

### Policy and evidence

Only repository-relative `.java`, `.sql` and `pom.xml` paths are accepted. Absolute
paths, `.`/`..`, hidden paths, names containing `secret` or `credential`, `target`,
`node_modules`, backslashes, percent-encoded paths and control characters are
rejected. The policy deliberately rejects some otherwise valid filenames.

Every path component is opened relative to its parent directory handle with
`NOFOLLOW_LINKS`; both file and directory symlinks are rejected, including links
whose destinations are inside the repository. Unsupported filesystems fail
closed. The configured repository root is trusted and resolved to its real path.

A file must be regular UTF-8 text without NUL bytes and at most **12,000 bytes**.
Oversized files are denied entirely, with no partial content returned. Known
secret patterns are redacted; this remains a heuristic, not a complete secret
scanner. Redaction can remove useful code and change line numbering.

The result contains `status`, `path`, `durationMs`, `content`, `redacted`, `scope`
and `message`. `SUCCESS` means the file was read; `DENIED` means a policy check
failed; `ERROR` means it could not be read safely (for example, a missing file).
Denied and failed reads return empty content. An empty existing file can still
return `SUCCESS`. Builds and tests are not implied by any read result.

This is an application-level boundary for a developer-controlled repository, not
an OS sandbox against another local process changing files during a read. It does
not detect hard-link aliases. File contents can change between tool calls, and
model-generated explanations still require review.

### Try it

After rebuilding and restarting the service:

```sh
curl --max-time 260 http://127.0.0.1:8090/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Call readSourceFile with path services/engineering-agent/src/main/java/com/example/platform/agent/dto/ChatResponse.java. Explain what this file contains."}'
```

Look for `Tool readSourceFile: status=SUCCESS` in the application log. The endpoint
continues to return the model's text in `response`; structured tool evidence is
sent to the model internally. A new chat request gets a fresh one-read budget.

Fixture tests exercise allowed file types, traversal and sensitive-path rejection,
file/directory symlinks, UTF-8 and size limits, redaction, errors and call limits.
The deterministic Ollama adapter test checks the required `path` argument and
source evidence reaching the model alongside the other tools. The optional live
test verifies that the local model actually invokes the reader.

## Step 3.7: Approved Maven compile

`runBuild(service)` turns a model request for one registered service into this
fixed operation from the repository root:

```sh
./mvnw -B -ntp -pl services/auth-service -am compile
```

Here `-pl` selects the requested service and `-am` includes any reactor modules
it depends on, such as `libs/common-observability`. The model supplies only a
service name; it cannot supply Maven flags or a command string. The allowlist is
the seven registered services, including `engineering-agent`.

`ToolExecutionPolicy` validates the exact service name. `ApprovedMavenProcess`
checks the repository, module POM, executable wrapper and JDK, rejects symlinked
module directories, and starts Maven with an argument list. `BuildRunner`
interprets the process exit code as compile evidence. `BuildTool` makes this a
Spring AI tool and enforces one attempt per chat request, including denied calls.
The shared semaphore permits one Maven build or test operation at a time;
another request receives `BUSY`.

The result contains `service`, `status`, `exitCode`, `durationMs`, `diagnostics`,
`outputTruncated`, `operation` and `message`:

- `PASS` means Maven exited with code 0 after `compile`.
- `FAIL` means Maven exited with a nonzero code; the exit code is preserved.
- `DENIED`, `BUSY`, `TIMEOUT` and `ERROR` do not prove compilation completed.

Maven output is drained to prevent a blocked process, with only its last 64,000
bytes retained. At most five `[ERROR]` lines, each capped at 300 characters and
passed through the existing heuristic redactor, reach the model. The tool does
not return full Maven logs. `outputTruncated=true` means diagnostics may be
incomplete. A build runs for at most 180 seconds; its process tree is stopped on
timeout. Tool logs include status, approved service, duration and exit code,
without compiler output.

The service sets INFO logging and defaults Spring's debug property to `false`
at startup, since a parent shell may export `DEBUG` for an unrelated tool.
This keeps framework request traces from printing prompts, tool results or
compiler diagnostics. An explicit JVM `-Ddebug=true` can still enable diagnostic
logging when intentionally requested. A small local model can still misstate a
duration or omit an exit code in its prose; `BuildRunner.BuildResult` is the
authoritative evidence for a compile decision. Structured review output that
exposes this evidence directly is a later step.

The default JDK is the one that launched this service. If you want to run the
agent with one JDK and builds with another, set `AGENT_BUILD_JAVA_HOME` to a JDK
home containing both `bin/java` and `bin/javac`. This workspace currently uses
Java 25. Maven's `compile` phase writes generated output under `target`; it does
not run the module's tests. Maven plugins and the wrapper belong to the trusted
developer workspace and may execute build logic, so this tool is an approved
build boundary rather than an OS sandbox for hostile repositories.

### Try it

After rebuilding and restarting the service:

```sh
curl --max-time 260 http://127.0.0.1:8090/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Call runBuild with service auth-service now. Report its exact status and exit code; say whether tests were run."}'
```

The application log records `Tool runBuild: status=PASS` if Maven finishes
successfully. The HTTP response remains the model's text, so verify important
claims against the structured tool result or build log. Fixture tests cover the
fixed argument list, allowlist, failure evidence and redaction, output bounds,
timeouts, missing prerequisites, concurrency and per-request call limit. The
deterministic Ollama adapter test checks the tool definition and evidence reaching
the model.

## Step 3.8: Approved Maven tests

`runTests(service)` uses the same exact-service allowlist and shared Maven process
boundary as `runBuild`, with the fixed goal `test`:

```sh
./mvnw -B -ntp -pl services/auth-service -am test
```

`-am` also runs tests in required reactor modules. Currently the registered
services use `libs/common-observability` as the only shared reactor dependency;
`engineering-agent` has no shared module dependency. The model supplies only the
registered service name. A build and test cannot write to the same `target`
directories concurrently through this agent.

`ApprovedMavenProcess` waits at most **240 seconds** for the test goal and drains
bounded process output. `TestRunner` then reads Surefire `TEST-*.xml` reports from
the selected service and, where applicable, `common-observability`.
`SurefireReportReader` accepts only regular XML files modified at or after this
invocation started. It ignores older reports, disables XML external entities and
DTDs, and limits itself to 100 reports of at most 1 MB each. It returns at most
five failure entries, with bounded, redacted test names and messages. Full test
logs, system output and XML properties are not sent to the model or tool log.

The structured result reports `service`, `status`, `exitCode`, `durationMs`,
`total`, `passed`, `failed`, `errors`, `skipped`, `serviceTests`, up to five
`failures`, `incomplete`, `outputTruncated`, `operation` and `message`. `total`
includes current selected-service and shared-module tests; `serviceTests` shows
how many of those belong to the requested service. `passed` excludes skipped
tests. A skipped test was not executed.

- `PASS`: Maven exited 0, a fresh selected-service report exists, all returned
  reports are valid, and no failures or errors were reported.
- `FAIL`: Maven exited nonzero. Counts may be partial when it failed before
  Surefire produced reports; then `incomplete=true`.
- `NO_TESTS`: Maven exited 0 but no fresh selected-service tests ran. Passing
  shared-module tests alone cannot prove the selected service's tests passed.
- `INCOMPLETE`: Maven exited 0 but XML reports are missing, malformed, oversized,
  symlinked or inconsistent.
- `DENIED`, `BUSY`, `TIMEOUT`, `ERROR`: no complete test result is claimed.

Fresh timestamps help avoid stale pass claims, but another local Maven process
writing the same reports concurrently is outside the agent's semaphore. The
report scope reflects this repository's current dependency layout; if new
reactor-module dependencies are added, the reader must be extended so totals
still cover every selected module. Testcontainers tests may fail when Docker is
unavailable, and that failure is reported as evidence rather than hidden.

### Try it

After rebuilding and restarting the service:

```sh
curl --max-time 310 http://127.0.0.1:8090/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Call runTests with service engineering-agent now. Report status, exitCode, total, passed, failed, errors and skipped exactly."}'
```

The service log records `Tool runTests: status=PASS` with counts; it does not
record failure messages. The HTTP response is still the model's prose, which
may omit, misstate or contradict a field. In a live run, the tool returned
`skipped=4` while the model's prose also said no tests were skipped. Read the
structured counts as the evidence. Fixture tests cover fresh and stale reports,
failures, skipped tests, malformed XML, early Maven failure, timeouts,
cross-tool concurrency and invocation limits. The deterministic Ollama adapter
test verifies that fresh counts reach the model.

## Step 3.9: First review flow

`POST /api/agent/review` coordinates the existing operations in one request:

```text
Changed-file inventory → tracked diff → choose one service
→ one untracked source read, when available → compile
→ tests, only after compile PASS → local model observations
→ JSON response with tool evidence and observations in separate fields
```

`CodeReviewAgent` owns that sequence. It uses a separate Spring AI `ChatClient`
without tool definitions for the final model call. The model can describe
tentative code concerns, while Java decides which fixed operations run and
returns their results directly. `AgentReviewController`, `ReviewRequest` and
`ReviewResponse` provide the HTTP boundary.

The request can name one registered service:

```json
{"service":"engineering-agent"}
```

`{}` asks the flow to choose a service automatically. It does so only when the
complete changed-file inventory identifies exactly one directly changed service.
If several services are changed, if the inventory is truncated or failed, or if
only shared paths changed, the response has `status=NEEDS_SERVICE` and lists the
known `changedServices`; no build, test or model call is launched. An invalid
service name returns HTTP 400 before repository inspection.

An explicit service can be reviewed even if no direct change appears in the
approved Git scope; the response notes that limit. The flow reads at most one
listed untracked file from that service through `SourceFileReader`, preferring
Java, then SQL, then `pom.xml`. Tracked
changes are supplied by the redacted Git diff. The model sees only diff sections
for the selected service, with at most 4,000 characters of tracked diff and
2,000 characters of untracked source. The response retains the fuller bounded
tool evidence. Source and diff text remain untrusted data.

The response uses `status=EVIDENCE_COLLECTED` when one service was selected.
Its `changedFiles`, `gitDiff`, `sourceFile`, `build` and `tests` fields contain
the real structured results. `tests` is `null` when compilation did not pass.
`analysis` contains only the model's short summary; `analysisStatus` is
`AVAILABLE`, `MALFORMED`, `UNAVAILABLE` or `SKIPPED`. If Ollama is unavailable,
the response still returns
the tool evidence and a note. The `notes` array also flags truncated inspection,
shared changes and preview limits. Step 3.10 adds a preliminary structured
report to this response; it is not an immutable snapshot. Model observations can
still be wrong; build and test status fields are the evidence to check.

### Try it

Restart the rebuilt agent, keep local Ollama running, then send:

```sh
curl --max-time 600 http://127.0.0.1:8090/api/agent/review \
  -H 'Content-Type: application/json' \
  -d '{"service":"engineering-agent"}'
```

For automatic service selection, send `-d '{}'`. In this workspace several
service POMs currently have changes, so an unscoped request may return
`NEEDS_SERVICE`. This flow compiles and tests the selected service, so a review
can take longer than a chat request. Endpoint tests verify execution order,
ambiguous scope, compile failure, model failure, evidence fields and selected
diff filtering. The deterministic Ollama adapter test verifies the final model
call has no tool definitions and receives the bounded source evidence.

## Step 3.10: Structured ReviewReport

Every `/api/agent/review` response now includes a `report` built from tool
results. `ReviewReport` defines `decision`, `risk`, `summary`, `findings`, `tests`,
`recommendations`, `checks`, `evidenceGaps` and `verificationStatus`. The `tests`
entry copies the exact tool counts, including skipped tests; each check points
back to its evidence field in the same response. Step 3.11 adds potential,
line-linked findings. The model summary stays in the separate `analysis` field
and cannot change a report decision or test count.

`verificationStatus` describes only engineering checks. It is `FAIL` if
compilation or tests returned `FAIL`, `PASS` only if selected-service Git and
code evidence are complete, compilation and tests passed, and no tests were
skipped, or `WARN` when checks are incomplete. Step 3.12 also blocks review on
high or critical scanner matches. The report does not emit a production `PASS`;
Step 3.13 records a separate operator choice. Before security scanning was added, `securityScan` was `NOT_RUN` and
`risk` was `UNASSESSED`. Shared-path impact, missing code content and skipped
tests appear as gaps when applicable.

For example, a successful build with four skipped tests and no high scanner
matches yields a `report` with
`decision=WARN`, `risk=UNASSESSED`, `verificationStatus=WARN`, an empty `findings`
array and a test-evidence entry with `total=110`, `passed=106`, `skipped=4`.
The rest of the response retains the bounded tool results for inspection.
Endpoint tests cover unselected scope, passing engineering checks, skipped
tests, build/test failures and model statements that contradict tool evidence.

## Step 3.11: Line-linked AI code review

The local model now receives a bounded selected-service tracked diff and one
approved untracked source preview, with line numbers on the source. It looks
for potential authentication, authorization, query, null, exception,
concurrency, transaction, validation, sensitive logging, API-contract,
idempotency, error-handling and performance concerns. Its required output is a
small JSON object with a summary and at most three proposed findings.

`CodeFindingExtractor` accepts a finding only when its registered category,
severity, exact path, line number and quoted code text match an added line in
the diff preview or a visible line in the source preview. It rejects findings
about unrelated files, unseen lines, redacted text, absent quotes and malformed
JSON. Accepted
findings carry `evidenceField=gitDiff` or `sourceFile` and
`evidenceStatus=POTENTIAL`: the quote proves where the hypothesis came from,
not that the alleged bug exists. Rejected findings are counted in `notes`;
malformed model output leaves `findings=[]` and `analysisStatus=MALFORMED`.

The report decision and risk still come from tool evidence. A model claim
cannot convert a failed test into a pass or declare a vulnerability verified.
This step is intentionally limited to the diff preview and one untracked file;
the report flags other changed files and incomplete evidence. Endpoint tests
cover valid source and diff citations, unrelated paths, wrong line numbers and
malformed replies. The local Ollama adapter test exercises the JSON contract.

## Step 3.12: Fixed security scanners

`SecurityRunner` runs three fixed commands for one registered service. It never
accepts command arguments from the model or the review request. It rejects a
service containing symlinks before starting external scanners, uses a private
temporary report directory, limits process time and report size, and deletes
raw reports afterward. Scanner stdout and stderr do not enter the API.

| Scanner | What it checks | Limit |
| --- | --- | --- |
| Semgrep | `src/main` with three local Java rules for process execution, concatenated SQL and native deserialization. | This is a small starter rule set, not comprehensive SAST. Metrics and version checks are disabled. |
| Gitleaks | The selected service's current working-tree directory for secret patterns, excluding generated Maven `target/` output. | Git history is not scanned. Secret values are withheld from JSON and model prompts. |
| OSV-Scanner | The selected Maven POM and direct/transitive dependencies resolved from Maven Central against known advisories. | Public registry and vulnerability queries need network access; Maven test dependencies may be omitted. |

The response's `security` field contains aggregate and per-scanner statuses,
exact match counts, and up to 20 sanitized examples. A scanner is `PASS` only
when its report is valid, covers its stated scope and has no matches. A missing,
timed-out, malformed or overlarge report is `INCOMPLETE`, `UNAVAILABLE`,
`TIMEOUT` or `ERROR`, never a clean pass. `complete` means all three returned
valid results; a `PASS` applies only to the scopes above.

The structured report copies `securityTotalFindings` and
`securityFindings`. Its `risk` is the highest severity among displayed scanner
matches, or `UNASSESSED` when none were detected. A high or critical match
sets `decision=FAIL` as a conservative review blocker pending human triage;
`evidenceStatus=SCANNER_MATCH` records a tool match, not confirmed
exploitability. Other matches and incomplete scans are surfaced as gaps. The
model sees only sanitized rule IDs, severities, package names and scanner counts
so it can summarize them, while Java owns the decision.

If Gitleaks matches a possible secret anywhere in the selected service, the
review response sets `gitDiff` and `sourceFile` to `WITHHELD` with empty code
content and drops build diagnostics and test failure text before the model call
or HTTP response. Statuses and test counts remain available. The model may still summarize
sanitized scanner metadata. This avoids returning a secret that the earlier
pattern-based source redactor might miss; code review then has an explicit gap.
The `gitleaks.toml` configuration extends Gitleaks' default rules and excludes
only generated `target/` paths. Maven test reports can contain synthetic
credentials and must not hide unrelated source changes.

On this Mac, install the free tools with:

```sh
brew install semgrep gitleaks osv-scanner
```

The default executable paths are `/opt/homebrew/bin/semgrep`,
`/opt/homebrew/bin/gitleaks` and `/opt/homebrew/bin/osv-scanner`. On another
machine set `agent.security.semgrep-executable`,
`agent.security.gitleaks-executable` and `agent.security.osv-executable` to
absolute paths. OSV scanning normally makes public vulnerability network
requests; the local Ollama model remains local.

Tests check path scope, symlink refusal, secret-value removal, code-preview
withholding, incomplete scanners and report decisions. A live run checks the
installed scanner CLIs.

## Step 3.13: Local approval workflow

A completed `/api/agent/review` response now includes `approval` with a
`REV-...` ID, `status=WAITING_FOR_APPROVAL`, a recommended action and an
`actionToken`. `NEEDS_SERVICE` responses have no approval case because no
service was reviewed. `ApprovalWorkflow` stores only a compact case: the agent
decision and risk, build/test statuses and skipped count, security status and
match count, and later the operator action and reason. Code, diffs, scanner raw
output and the action token are not stored in that case.

Use the ID to inspect the case:

```sh
curl 'http://127.0.0.1:8090/api/agent/reviews/REV-REPLACE_WITH_UUID'
```

The lookup response never contains the token. Copy the `actionToken` from the
initial review response and make one explicit decision:

```sh
curl -X POST 'http://127.0.0.1:8090/api/agent/reviews/REV-REPLACE_WITH_UUID/decision' \
  -H 'Content-Type: application/json' \
  -H 'X-Review-Action-Token: REPLACE_WITH_ACTION_TOKEN' \
  -d '{"action":"REQUEST_CHANGES","reason":"Investigate the scanner matches."}'
```

Actions are `APPROVE`, `REJECT` and `REQUEST_CHANGES`. They result in
`APPROVED`, `REJECTED` and `CHANGES_REQUESTED`. The token must match the
initial review; a wrong token returns 403. A FAIL agent report cannot be
approved and returns 409; a case can receive only one decision, so a second
action also returns 409. A short reason is required. The model receives no
approval tool or token, and an operator action never changes the agent's
evidence decision or launches a build, test, scan, deployment or code edit.

The case records `reviewerIdentityStatus=NOT_AUTHENTICATED`: possession of the
token authorizes this local action but does not prove a person's identity.
Step 3.14 stores cases in a local database, so a restart preserves the review
status, action-token hash and one operator choice. There is no deployment
integration in V1.

## Step 3.14: Durable audit

The agent creates a review row before running fixed checks. It writes one compact
tool row after each check, then completes the review row with the agent's
decision and risk. `NEEDS_SERVICE` requests receive a `reviewId` and Git-check
rows even though they have no approval case. A stopped review remains marked
`STARTED` with the rows completed before the interruption.

The default database is the hidden H2 file `~/.engineering-agent-audit.mv.db`,
outside this Git repository. Set `AGENT_AUDIT_DB_PATH` to the absolute path of
another local database file before starting the agent if needed. Keep the same
path across restarts. Schema creation runs at startup. The process must be able
to write this file; a database failure stops the review instead of silently
continuing without audit.

`agent_reviews` stores the review ID, HEAD commit hash if available, local model
name, start/completion times, flow status, decision/risk, build/test/security
counts, approval status, and a SHA-256 action-token hash. `agent_tool_executions`
stores fixed tool names, an approved service or scope label, status, duration
and timestamp. `agent_review_actions` records each successful operator choice
once. The case also retains the operator's short reason; obvious credential
strings in reasons are rejected before persistence, and operators should keep
other sensitive values out of free-text reasons. The database never stores code, diffs,
prompts, raw model or scanner output, or the plaintext action token. A HEAD
hash identifies the commit but does not capture uncommitted working-tree changes.

Inspect an audit trail with the `reviewId` from `/api/agent/review`:

```sh
curl 'http://127.0.0.1:8090/api/agent/reviews/REV-REPLACE_WITH_UUID/audit'
```

This endpoint returns metadata, ordered tool rows and operator choices. It is
bound to loopback with the rest of the service; it does not authenticate a
viewer. The approval lookup and decision endpoints from Step 3.13 now read and
write the same database. Tests use an in-memory database; a separate file-backed
test verifies that review and choice rows survive fresh store instances.

## Step 3.15: Agent safety policies

`AgentSafetyPolicy` gives each agent request a capability budget before any
tools run. Chat can call each of its five fixed tools at most once, for a total
of five. The review flow can run changed-file inspection, Git diff, one source
read, compile, tests, security scan and model analysis at most once each.
It skips tests unless compilation passes and skips model analysis when no
approved review evidence is available. The model cannot request extra review
operations: `CodeReviewAgent` owns that fixed sequence.

| Capability | V1 policy |
| --- | --- |
| Git diff and changed paths | Allowed through fixed read-only Git operations. |
| Source read | Allowed for bounded UTF-8 Java, SQL or pom.xml paths inside the repository; traversal, hidden and sensitive paths and symlinks are denied. |
| Compile and tests | Allowed for registered services with fixed Maven goals and timeouts. |
| Security scan and code review | Allowed in the fixed review flow, using three configured scanner commands and the local model. |
| Edit code, arbitrary shell, commit, push, merge, deploy, Azure access | Denied; no V1 tool exposes these operations. |

One agent request can be active at a time across `/api/agent/chat` and
`/api/agent/review`. A second request receives HTTP 429 before starting the
model or an engineering tool. The slot is released when the active request
finishes or fails. Approval lookups and operator choices remain separate from
the agent request slot. Tool results are bounded, review-model hypotheses are
parsed within a 12,000 character limit, and chat responses over 8,000
characters are refused. The local model is also limited to 512 generated
tokens per call. Fixed Git, Maven and scanner operations retain their
existing timeouts and scoped paths.

These rules are enforced inside the application; they are not an operating
system sandbox. Maven compile writes `target/` files, and Maven plugins, tests
and scanner binaries execute code with the privileges of the agent process.
Run this V1 agent on a trusted developer checkout and with an account that has
only the local access it needs. The agent itself has no Azure or deployment
integration; dependency scanning can make public OSV requests.

Tests verify capability denials, one-call limits, slot release, and HTTP 429
for busy chat and review requests. Known credential strings in operator reasons
return HTTP 400 without changing the approval case.

## Step 3.16: Evaluation suite

Four small Java files under `src/test/resources/evaluation/` are isolated text
fixtures, not compiled application code. `cases.json` labels three possible
concerns and one clean example:

| Case | Expected category | Labeled lines |
| --- | --- | --- |
| Order lookup without an ownership check | `AUTHORIZATION` | 7–8 |
| SQL query executed with account-ID concatenation | `SQL_QUERY` | 6 |
| Nullable account/email dereference | `NULL_HANDLING` | 4 |
| Account/email checked before use | No finding | — |

The offline `EvaluationSuiteTest` validates fixture labels, the existing
visible-line evidence filter, the score calculation, and fixed report decisions
for passing checks, failed tests, and a synthetic critical scanner match. This
test runs in the normal package build without Ollama. It does **not** measure
model accuracy or scanner detection.

The optional `AgentEvaluationLiveTest` uses the same `CodeReviewAgent` prompt,
local model, finding parser, report and audit path used by normal reviews.
It substitutes the fixture source and known clean build/test/scanner statuses,
so it does not execute Maven or scanner binaries on the fixture files. Run it
from the repository root with Ollama and the configured model running:

```sh
AGENT_LIVE_EVAL=true JAVA_HOME=/opt/homebrew/opt/openjdk@25 \
  ./mvnw -B -ntp -pl services/engineering-agent -am test \
  -Dtest=AgentEvaluationLiveTest -Dagent.test.excluded-groups= \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The run writes `services/engineering-agent/target/evaluation-report.json`.
Only accepted category/line labels, statuses, rejected-finding counts and
scores are saved there; fixture source, prompts and raw model output are not.
A true positive requires an accepted finding with the labeled category and
one labeled line. Other accepted findings are false positives; missed labeled
concerns are false negatives. Precision is undefined when the model returns
no accepted findings. This is a four-case diagnostic, not a production-quality
accuracy estimate. Re-run after changing the model or prompt and expand the
fixtures before trusting detection claims.

For one deterministic scanner check, run the existing local Semgrep SQL rule on
the unsafe SQL fixture and the clean email fixture:

```sh
AGENT_LIVE_SCANNER_EVAL=true JAVA_HOME=/opt/homebrew/opt/openjdk@25 \
  ./mvnw -B -ntp -pl services/engineering-agent -am test \
  -Dtest=SemgrepEvaluationLiveTest -Dagent.test.excluded-groups= \
  -Dsurefire.failIfNoSpecifiedTests=false
```

This optional test uses the production rule file but calls Semgrep directly on
two isolated fixture files. It does not measure the full `SecurityRunner`,
Gitleaks or OSV-Scanner. The local run matched the labeled SQL call on line 6
and found no match in the clean fixture. Its compact result is written to
`services/engineering-agent/target/semgrep-evaluation-report.json`.

On the current local `qwen2.5:1.5b` run, all four responses were structured, but
the model proposed no findings. It detected **0 of 3** labeled concerns,
with 0 false positives on the clean case and 0 findings rejected by the
evidence filter. Recall was 0; precision was undefined. The report exposes
this miss instead of treating a valid JSON response as a successful review.

## Step 3.17: MCP server/client boundary

The same executable JAR runs as two processes. Start the `mcp-server` profile
first. It owns the fixed Git readers, safe source reader, Maven build/test
runners and scanners. Its Spring AI Streamable HTTP endpoint is
`http://127.0.0.1:8091/mcp`. Start the default agent profile second. It
connects to that endpoint and exposes its HTTP API on `127.0.0.1:8090`.
Set `AGENT_MCP_URL` only if the loopback server uses a different port.

### Files and responsibilities

- `EngineeringMcpTools.java` registers exactly seven server tools. The methods
  delegate to the existing approved implementations; there is no arbitrary
  command, repository write, commit or deploy tool.
- `EngineeringToolGateway.java` is the fixed operation contract used by the
  review flow and audit. `McpEngineeringToolGateway.java` calls server callbacks
  and decodes their structured MCP results. Missing approved tools stop startup;
  invalid or unavailable results return a safe 503 from the review API.
- `McpChatToolFactory.java` gives chat only five of those callbacks and claims
  the per-request safety budget before each remote call. The model cannot call
  the scanner or audit metadata tool through chat.
- `LocalEngineeringToolGateway.java` and `LocalChatToolFactory.java` keep
  in-process test behavior, selected by `application-test.yaml`. Production
  uses MCP. The raw repository and process components load only in the server
  or test profile.
- `application-mcp-server.yaml` binds the server to loopback. `application.yaml`
  configures the MCP client. `CodeReviewAgent`, `AgentChatService`, `AuditStore`
  and the agent API controllers load only outside the server profile.

The MCP endpoint has no application authentication. Keep it on loopback and
do not expose it to a remote network. Both processes need access to the same
repository root; set `AGENT_REPOSITORY` for each when launching elsewhere.
Run the server before the agent. The agent refuses to start if the required
MCP tools are missing, and it does not fall back to local tool execution.

The regular suite still uses the `test` profile. A packaged two-process check
returned `EVIDENCE_COLLECTED` for a selected-service review: MCP Git HEAD,
changed files, diff and source read succeeded; build and tests passed; scanners
returned findings; model analysis was available. A chat request also returned
200 and used the remote changed-file result. Model prose still needs human
verification against the structured tool fields.
The regular suite excludes the six `live`-tagged Ollama and scanner checks, so
normal test evidence contains no intentional skips. Select those checks explicitly
with the documented environment flag and `-Dagent.test.excluded-groups=`. A
server-down request returned 503 with no local tool fallback.

## Phase 5: GitHub pull-request gate

The `Engineering Agent Review` workflow runs this two-process agent on an ephemeral
GitHub-hosted runner for each non-draft pull request to `main`. It checks the exact
base-to-head commit range, reviews each directly changed registered service and
conservatively reviews every registered service when an approved shared path changes.
It returns a required PASS/BLOCKED status. The workflow has read-only repository
permission and no secrets.

`scripts/ci_review_gate.py` is the headless client. It accepts no tool or shell
command from the pull request, validates the review audit against the PR head and
blocks on incomplete Git evidence, compile/test problems, skipped tests, any
scanner match, unavailable model review or accepted model finding. Its artifact
contains only statuses and counts; action tokens, code, diffs, prompts, model prose
and raw diagnostics are omitted. Policy tests live in
`scripts/test_ci_review_gate.py` and also run in the ordinary backend workflow.

See `docs/phase-5-github-agent-integration.md` for the complete step-by-step flow,
trust boundary, file responsibilities and local commands.

## Phase 3 acceptance check

An isolated repository copy contained a harmless tracked change in
`auth-service`. The agent selected only that service, read its tracked diff,
compiled it and ran its tests. The first run returned 48 passed, 0 failed and
1 skipped out of 49 tests. Semgrep and Gitleaks passed after generated Maven
output was excluded. OSV resolved the Maven graph and returned 64 advisory
matches, including critical-severity matches that need human triage. The
review's conservative decision was `FAIL`, not the sample `WARN`.

The copy then gained one deliberately failing JUnit test. The next review
returned 48 passed, 1 failed and 1 skipped out of 50 tests. Its verification
status and decision were both `FAIL`, and three unsupported model findings were
discarded. Both reviews created approval cases and tool audit history.

The literal definition of done remains open. The Docker-dependent test was
skipped, the dependency matches need triage, and the current local
`qwen2.5:1.5b` model produced no supported code findings in this check. Do not
present the example MEDIUM/WARN report as an observed result. The structured
tool and report fields record the actual evidence.

## References

- [Spring AI 1.1.8 Ollama integration](https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/ollama-chat.adoc)
- [Spring AI 1.1.8 ChatClient](https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chatclient.adoc)
- [Spring AI 1.1.8 tool calling](https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/tools.adoc)
- [Spring AI 1.1 Streamable HTTP MCP server](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)
- [Spring AI 1.1 MCP client](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-client-boot-starter-docs.html)
- [Git diff options](https://git-scm.com/docs/git-diff)
- [Ollama local setup](https://docs.ollama.com/quickstart)

- [Git status format](https://git-scm.com/docs/git-status)

- [Java 25 SecureDirectoryStream](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/SecureDirectoryStream.html)
- [Semgrep local scans](https://semgrep.dev/docs/cli-reference)
- [Gitleaks directory scans](https://github.com/gitleaks/gitleaks/blob/master/README.md)
- [OSV-Scanner Maven and transitive dependency scope](https://google.github.io/osv-scanner/supported-languages-and-lockfiles/)
