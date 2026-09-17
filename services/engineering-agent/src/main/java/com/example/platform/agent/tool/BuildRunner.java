package com.example.platform.agent.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

/** Interprets the shared fixed Maven process as compile evidence. */
@Component
@Profile({"mcp-server", "test"})
public class BuildRunner {
    static final int DEFAULT_TIMEOUT_SECONDS = 180;
    private final ApprovedMavenProcess maven;
    private final int timeoutSeconds;

    @Autowired
    public BuildRunner(ApprovedMavenProcess maven) {
        this(maven, DEFAULT_TIMEOUT_SECONDS);
    }

    BuildRunner(ApprovedMavenProcess maven, int timeoutSeconds) {
        this.maven = maven;
        this.timeoutSeconds = timeoutSeconds;
    }

    public record BuildResult(String service, String status, Integer exitCode, long durationMs,
                              List<String> diagnostics, boolean outputTruncated,
                              String operation, String message) {}

    public BuildResult run(String requestedService) {
        var run = maven.execute(requestedService, ApprovedMavenProcess.Goal.COMPILE, timeoutSeconds);
        String status = run.status().equals("COMPLETE") ? run.exitCode() == 0 ? "PASS" : "FAIL" : run.status();
        String message = run.status().equals("TIMEOUT")
                ? "Maven compile exceeded its timeout; compilation is incomplete."
                : run.status().equals("COMPLETE")
                ? run.exitCode() == 0
                ? "Maven compile finished successfully; tests were not run, so their result and necessity are unknown."
                : "Maven compile failed; tests were not run, so their result and necessity are unknown."
                : run.message();
        return new BuildResult(run.service(), status, run.exitCode(), run.durationMs(),
                diagnostics(run.output()), run.outputTruncated(), run.operation(), message);
    }

    private static List<String> diagnostics(String output) {
        var lines = new ArrayList<String>();
        for (String line : output.split("\\R")) {
            if (line.startsWith("[ERROR] ") && !line.startsWith("[ERROR] [Help")) {
                String safe = GitDiffReader.redact(line);
                lines.add(safe.length() > 300 ? safe.substring(0, 300) + "…" : safe);
                if (lines.size() == 5) break;
            }
        }
        return List.copyOf(lines);
    }
}
