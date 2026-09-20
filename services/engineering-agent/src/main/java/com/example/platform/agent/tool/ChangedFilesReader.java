package com.example.platform.agent.tool;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Component
@Profile({"mcp-server", "test"})
public class ChangedFilesReader {
    static final int MAX_FILES = 500;
    private static final int MAX_BYTES = 128_000;
    private static final Set<String> SERVICES = ToolExecutionPolicy.approvedServices();
    private static final String SCOPE = "Paths and Git status only for approved Java, SQL and pom.xml files. "
            + "Includes staged, unstaged and non-ignored untracked files. Sensitive, hidden and generated paths are excluded. "
            + "Renames are shown as deletion plus addition. No file contents are returned by this tool. "
            + "Services are mapped from returned paths, not a complete dependency-impact analysis.";
    private static final String COMPARISON_SCOPE = "Committed paths changed between the configured pull-request base commit and HEAD "
            + "for approved Java, SQL and pom.xml files. Sensitive, hidden and generated paths are excluded. "
            + "Renames are shown as deletion plus addition. No file contents are returned by this tool. "
            + "Services are mapped from returned paths, not a complete dependency-impact analysis.";
    private final RepositoryGit git;

    public ChangedFilesReader(RepositoryGit git) {
        this.git = git;
    }

    public enum ChangeStatus { UNCHANGED, MODIFIED, ADDED, DELETED, TYPE_CHANGED, UNMERGED, UNTRACKED }
    public record ChangedFile(String path, ChangeStatus indexStatus, ChangeStatus workTreeStatus,
                              boolean conflicted, String service) {}
    public record ChangedFilesResult(String status, Integer exitCode, long durationMs, List<ChangedFile> files,
                                     List<String> changedServices, boolean sharedChanges, boolean truncated,
                                     String scope, String message) {}

    public ChangedFilesResult read() {
        long start = System.nanoTime();
        try {
            RepositoryGit.Output output = git.changedFiles(MAX_BYTES);
            if (output.timedOut()) return failure("TIMEOUT", null, start, "Git file listing timed out.");
            if (output.exitCode() != 0) return failure("ERROR", output.exitCode(), start, "Git file listing failed.");
            return git.comparesCommittedChanges()
                    ? parseComparison(output.text(), output.truncated(), start)
                    : parse(output.text(), output.truncated(), start);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failure("ERROR", null, start, "Git file listing was interrupted.");
        } catch (IOException | ExecutionException | TimeoutException ex) {
            return failure("ERROR", null, start, "Git file listing could not complete. Check Git and the configured repository root.");
        }
    }

    // -z keeps spaces, tabs, newlines and non-ASCII paths unambiguous. Never split on lines.
    static ChangedFilesResult parse(String output, boolean truncated, long start) throws IOException {
        var files = new ArrayList<ChangedFile>();
        var services = new TreeSet<String>();
        boolean shared = false;
        int offset = 0;
        while (offset < output.length()) {
            int end = output.indexOf('\0', offset);
            if (end == -1) {
                if (!truncated) throw new IOException("Incomplete Git status record");
                break; // Do not fabricate a path from a partial record at the byte limit.
            }
            if (files.size() == MAX_FILES) {
                truncated = true;
                break;
            }
            String record = output.substring(offset, end);
            offset = end + 1;
            if (record.length() < 4 || record.charAt(2) != ' ') throw new IOException("Invalid Git status record");
            String path = record.substring(3);
            String[] parts = path.split("/", 3);
            String service = parts.length == 3 && parts[0].equals("services") && SERVICES.contains(parts[1])
                    ? parts[1] : null;
            if (service != null) services.add(service);
            else shared = true;
            String xy = record.substring(0, 2);
            boolean conflicted = xy.indexOf('U') >= 0 || xy.equals("AA") || xy.equals("DD");
            files.add(new ChangedFile(path, status(record.charAt(0)), status(record.charAt(1)), conflicted, service));
        }
        return success(files, services, shared, truncated, start, SCOPE);
    }

    static ChangedFilesResult parseComparison(String output, boolean truncated, long start) throws IOException {
        var files = new ArrayList<ChangedFile>();
        var services = new TreeSet<String>();
        boolean shared = false;
        int offset = 0;
        while (offset < output.length()) {
            int statusEnd = output.indexOf('\0', offset);
            if (statusEnd == -1) {
                if (!truncated) throw new IOException("Incomplete Git comparison status");
                break;
            }
            String statusText = output.substring(offset, statusEnd);
            offset = statusEnd + 1;
            int pathEnd = output.indexOf('\0', offset);
            if (pathEnd == -1) {
                if (!truncated) throw new IOException("Incomplete Git comparison path");
                break;
            }
            if (files.size() == MAX_FILES) {
                truncated = true;
                break;
            }
            if (statusText.length() != 1) throw new IOException("Unexpected Git comparison status");
            String path = output.substring(offset, pathEnd);
            offset = pathEnd + 1;
            String[] parts = path.split("/", 3);
            String service = parts.length == 3 && parts[0].equals("services") && SERVICES.contains(parts[1])
                    ? parts[1] : null;
            if (service != null) services.add(service);
            else shared = true;
            ChangeStatus status = status(statusText.charAt(0));
            files.add(new ChangedFile(path, status, ChangeStatus.UNCHANGED,
                    status == ChangeStatus.UNMERGED, service));
        }
        return success(files, services, shared, truncated, start, COMPARISON_SCOPE);
    }

    private static ChangedFilesResult success(List<ChangedFile> files, Set<String> services,
                                              boolean shared, boolean truncated, long start, String scope) {
        return new ChangedFilesResult("SUCCESS", 0, (System.nanoTime() - start) / 1_000_000,
                List.copyOf(files), List.copyOf(services), shared, truncated, scope,
                truncated ? "File list and service mapping are incomplete."
                        : files.isEmpty() ? "No changed files within the approved scope."
                        : "Changed paths collected; contents were not provided and builds/tests were not run.");
    }

    private static ChangeStatus status(char code) throws IOException {
        return switch (code) {
            case ' ' -> ChangeStatus.UNCHANGED;
            case 'M' -> ChangeStatus.MODIFIED;
            case 'A' -> ChangeStatus.ADDED;
            case 'D' -> ChangeStatus.DELETED;
            case 'T' -> ChangeStatus.TYPE_CHANGED;
            case 'U' -> ChangeStatus.UNMERGED;
            case '?' -> ChangeStatus.UNTRACKED;
            default -> throw new IOException("Unexpected Git status code");
        };
    }

    private ChangedFilesResult failure(String status, Integer exitCode, long start, String message) {
        return new ChangedFilesResult(status, exitCode, (System.nanoTime() - start) / 1_000_000,
                List.of(), List.of(), false, false,
                git.comparesCommittedChanges() ? COMPARISON_SCOPE : SCOPE, message);
    }
}
