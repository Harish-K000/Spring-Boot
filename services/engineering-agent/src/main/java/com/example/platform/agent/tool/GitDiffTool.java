package com.example.platform.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

/** Create one instance per chat request so the model can inspect the diff once. */
public class GitDiffTool {
    private static final Logger log = LoggerFactory.getLogger(GitDiffTool.class);
    private final GitDiffReader reader;
    private final AgentSafetyPolicy.Budget budget;

    public GitDiffTool(GitDiffReader reader) {
        this(reader, AgentSafetyPolicy.newBudget(AgentSafetyPolicy.Mode.CHAT));
    }

    public GitDiffTool(GitDiffReader reader, AgentSafetyPolicy.Budget budget) {
        this.reader = reader;
        this.budget = budget;
    }

    @Tool(description = "Inspect current tracked Java, SQL and Maven POM changes against HEAD, including staged and unstaged edits. "
            + "No arguments. Call once per request. Returns status, diff, truncation and redaction indicators. "
            + "Untracked files, configuration and sensitive paths are excluded. This does not build or test code.")
    public GitDiffReader.GitDiffResult getGitDiff() {
        budget.claim(AgentSafetyPolicy.Capability.GIT_DIFF);
        var result = reader.read();
        log.info("Tool getGitDiff: status={}, durationMs={}, truncated={}, redacted={}",
                result.status(), result.durationMs(), result.truncated(), result.redacted());
        return result;
    }
}
