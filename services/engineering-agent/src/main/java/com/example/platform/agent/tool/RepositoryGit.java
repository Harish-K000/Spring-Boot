package com.example.platform.agent.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Fixed repository operations shared by the readers. No arbitrary command API is exposed. */
@Component
@Profile({"mcp-server", "test"})
public class RepositoryGit {
    private static final Pattern FILTER_KEY = Pattern.compile("filter\\.[a-zA-Z0-9._/-]+\\.(clean|smudge|process|required)");
    private static final List<String> APPROVED_PATHS = List.of(
            ":(glob)**/*.java", ":(glob)**/*.sql", ":(glob)**/pom.xml",
            ":(exclude,glob)**/.*", ":(exclude,glob)**/.*/**",
            ":(exclude,glob)**/target/**", ":(exclude,glob)**/node_modules/**",
            ":(exclude,icase,glob)**/*secret*", ":(exclude,icase,glob)**/*secret*/**",
            ":(exclude,icase,glob)**/*credential*", ":(exclude,icase,glob)**/*credential*/**");
    private final Path repository;

    public RepositoryGit(@Value("${agent.repository:}") String configuredRepository) {
        Path candidate = Path.of(configuredRepository.isBlank() ? "." : configuredRepository).toAbsolutePath().normalize();
        if (configuredRepository.isBlank()) {
            while (candidate != null && !Files.exists(candidate.resolve(".git"))) candidate = candidate.getParent();
        }
        // A wrong path becomes a structured tool error; ordinary chat can still start.
        this.repository = candidate;
    }

    record Output(int exitCode, boolean timedOut, boolean truncated, String text) {}

    Path repositoryRoot() throws IOException {
        if (repository == null || !Files.exists(repository.resolve(".git"))) {
            throw new IOException("Configured directory is not a Git repository root");
        }
        return repository.toRealPath();
    }

    /** Fixed, read-only HEAD lookup. Null means no trustworthy commit identifier was available. */
    public String headCommit() {
        if (repository == null || !Files.exists(repository.resolve(".git"))) return null;
        try {
            Output result = execute(List.of("git", "--no-pager", "-C", repository.toString(),
                    "-c", "core.hooksPath=/dev/null", "rev-parse", "--verify", "HEAD"), 80);
            String hash = result.text().trim();
            return result.exitCode() == 0 && !result.timedOut() && !result.truncated()
                    && hash.matches("[0-9a-fA-F]{40,64}") ? hash : null;
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    Output diff(int limit) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        return run(List.of("diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--no-color",
                "--ignore-submodules=all", "--unified=3", "HEAD", "--"), limit);
    }

    Output changedFiles(int limit) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        return run(List.of("status", "--porcelain=v1", "-z", "--untracked-files=all",
                "--no-renames", "--ignore-submodules=all", "--"), limit);
    }

    private Output run(List<String> operation, int limit)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        if (repository == null || !Files.exists(repository.resolve(".git"))) {
            throw new IOException("Configured directory is not a Git repository root");
        }
        List<String> base = new ArrayList<>(List.of("git", "--no-pager", "-C", repository.toString(),
                "-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null", "-c", "core.attributesFile=/dev/null"));
        // Git diff can invoke configured clean filters, even with --no-textconv.
        // Read only their key names and disable them for this invocation, without changing .git/config.
        List<String> config = new ArrayList<>(base);
        config.addAll(List.of("config", "--null", "--name-only", "--get-regexp", "^filter\\..*\\.(clean|smudge|process|required)$"));
        Output filters = execute(config, 16_384);
        if (filters.timedOut()) return new Output(-1, true, false, "");
        if (filters.truncated() || (filters.exitCode() != 0 && filters.exitCode() != 1)) {
            throw new IOException("Git configuration could not be checked safely");
        }
        if (filters.exitCode() == 0) {
            for (String key : filters.text().split("\u0000")) {
                if (!FILTER_KEY.matcher(key).matches()) {
                    throw new IOException("Unsupported Git filter configuration");
                }
                base.addAll(List.of("-c", key + (key.endsWith(".required") ? "=false" : "=")));
            }
        }
        base.addAll(operation);
        base.addAll(APPROVED_PATHS);
        return execute(base, limit);
    }

    private Output execute(List<String> arguments, int limit)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        String path = builder.environment().getOrDefault("PATH", "/usr/bin:/bin");
        builder.environment().clear();
        builder.environment().put("PATH", path);
        builder.environment().put("LANG", "C");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_ATTR_NOSYSTEM", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        Process process = builder.start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> captured = reader.submit(() -> {
                try (InputStream stream = process.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        int room = limit + 1 - bytes.size();
                        if (room > 0) bytes.write(buffer, 0, Math.min(room, count));
                    }
                    return bytes.toByteArray();
                }
            });
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                return new Output(-1, true, false, "");
            }
            byte[] bytes = captured.get(1, TimeUnit.SECONDS);
            return new Output(process.exitValue(), false, bytes.length > limit,
                    new String(bytes, 0, Math.min(bytes.length, limit), StandardCharsets.UTF_8));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
            reader.shutdownNow();
        }
    }
}
