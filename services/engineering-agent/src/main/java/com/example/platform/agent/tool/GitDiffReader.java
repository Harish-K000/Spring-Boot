package com.example.platform.agent.tool;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Collects and redacts the diff produced by the fixed Git operation. */
@Component
@Profile({"mcp-server", "test"})
public class GitDiffReader {
    // Large enough for normal service PRs while still bounding process output and API evidence.
    static final int MAX_BYTES = 256_000;
    private static final String SCOPE = "Tracked Java, SQL and pom.xml changes from the configured Git comparison. "
            + "Untracked files, configuration and sensitive paths are excluded. "
            + "Known secret patterns are redacted; this is not a complete secret scanner.";
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?(?:-----END [^-]*PRIVATE KEY-----|$)");
    private static final Pattern SECRET_LINE = Pattern.compile("(?im)^.*(?:password|passwd|secret|api[_-]?key|authorization|credential|access[_-]?token|refresh[_-]?token|private[_-]?key).*$");
    private static final Pattern KNOWN_TOKEN = Pattern.compile("(?:AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)");
    private final RepositoryGit git;

    public GitDiffReader(RepositoryGit git) {
        this.git = git;
    }

    public record GitDiffResult(String status, Integer exitCode, long durationMs,
                                String diff, boolean truncated, boolean redacted, String scope, String message) {}

    public GitDiffResult read() {
        long started = System.nanoTime();
        try {
            RepositoryGit.Output output = git.diff(MAX_BYTES);
            if (output.timedOut()) return result("TIMEOUT", null, started, "", false, false, "Git diff exceeded its five-second timeout.");
            if (output.exitCode() != 0) return result("ERROR", output.exitCode(), started, "", false, false,
                    "Git diff failed. Check that Git is installed and the repository has a HEAD commit.");
            String safe = redact(output.text());
            return result("SUCCESS", 0, started, safe, output.truncated(), !safe.equals(output.text()),
                    output.truncated() ? "Diff is truncated; inspection is incomplete."
                            : safe.isBlank() ? "No changes within the tool's tracked-file scope."
                            : "Diff collected for " + git.comparisonDescription() + "; no build or tests were run.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return result("ERROR", null, started, "", false, false, "Git inspection was interrupted.");
        } catch (IOException | ExecutionException | TimeoutException ex) {
            return result("ERROR", null, started, "", false, false, "Git inspection could not complete. Check Git and the configured repository root.");
        }
    }

    static String redact(String diff) {
        return KNOWN_TOKEN.matcher(SECRET_LINE.matcher(PRIVATE_KEY.matcher(diff)
                .replaceAll("[REDACTED PRIVATE KEY]")).replaceAll("[REDACTED SENSITIVE LINE]"))
                .replaceAll("[REDACTED TOKEN]");
    }

    private static GitDiffResult result(String status, Integer exitCode, long start, String diff,
                                        boolean truncated, boolean redacted, String message) {
        return new GitDiffResult(status, exitCode, (System.nanoTime() - start) / 1_000_000,
                diff, truncated, redacted, SCOPE, message);
    }

}
