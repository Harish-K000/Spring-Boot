package com.example.platform.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

/** Each chat request gets its own invocation limit. */
public class ChangedFilesTool {
    private static final Logger log = LoggerFactory.getLogger(ChangedFilesTool.class);
    private final ChangedFilesReader reader;
    private final AgentSafetyPolicy.Budget budget;

    public ChangedFilesTool(ChangedFilesReader reader) {
        this(reader, AgentSafetyPolicy.newBudget(AgentSafetyPolicy.Mode.CHAT));
    }

    public ChangedFilesTool(ChangedFilesReader reader, AgentSafetyPolicy.Budget budget) {
        this.reader = reader;
        this.budget = budget;
    }

    @Tool(description = "List changed Java, SQL and Maven POM paths, Git statuses and directly changed services. "
            + "Uses the configured PR base-to-HEAD comparison when present; otherwise includes staged, unstaged, deleted and non-ignored untracked files. No file contents. "
            + "No arguments; call at most once per request. Respect exclusions and truncation. "
            + "Use getGitDiff separately when you need tracked code changes.")
    public ChangedFilesReader.ChangedFilesResult getChangedFiles() {
        budget.claim(AgentSafetyPolicy.Capability.CHANGED_FILES);
        var result = reader.read();
        log.info("Tool getChangedFiles: status={}, durationMs={}, files={}, truncated={}",
                result.status(), result.durationMs(), result.files().size(), result.truncated());
        return result;
    }
}
