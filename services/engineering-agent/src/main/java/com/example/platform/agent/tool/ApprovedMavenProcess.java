package com.example.platform.agent.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.*;

/** Shared, fixed compile/test process boundary. The model never supplies arguments or a goal. */
@Component
@Profile({"mcp-server", "test"})
public class ApprovedMavenProcess {
    static final int OUTPUT_LIMIT = 64_000;
    private final RepositoryGit repository;
    private final String configuredJavaHome;
    private final Semaphore slot = new Semaphore(1);

    public ApprovedMavenProcess(RepositoryGit repository,
                                @Value("${agent.build.java-home:}") String configuredJavaHome) {
        this.repository = repository;
        this.configuredJavaHome = configuredJavaHome;
    }

    enum Goal { COMPILE("compile"), TEST("test");
        final String argument;
        Goal(String argument) { this.argument = argument; }
    }

    record Run(String service, String status, Integer exitCode, long durationMs, String output,
               boolean outputTruncated, String operation, String message, Path root, Instant startedAt) {}

    Run execute(String requestedService, Goal goal, int timeoutSeconds) {
        long start = System.nanoTime();
        final String service;
        try {
            service = ToolExecutionPolicy.serviceName(requestedService);
        } catch (IllegalArgumentException ex) {
            return result(null, "DENIED", null, start, "", false, null,
                    "Only registered services are approved.", null, null);
        }
        String operation = "./mvnw -B -ntp -pl services/" + service + " -am " + goal.argument;
        if (!slot.tryAcquire()) {
            return result(service, "BUSY", null, start, "", false, operation,
                    "Another Maven operation is already running; retry in a new request.", null, null);
        }
        try {
            return run(service, goal, timeoutSeconds, operation, start);
        } finally {
            slot.release();
        }
    }

    private Run run(String service, Goal goal, int timeoutSeconds, String operation, long start) {
        try {
            Path root = repository.repositoryRoot();
            Path services = root.resolve("services");
            Path module = services.resolve(service);
            Path wrapper = root.resolve("mvnw");
            if (!Files.isRegularFile(root.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(services, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(module, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(module.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(wrapper, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isExecutable(wrapper)) {
                return result(service, "ERROR", null, start, "", false, operation,
                        "The approved Maven wrapper or registered module is missing or invalid.", root, null);
            }
            Path javaHome = Path.of(configuredJavaHome.isBlank()
                    ? System.getProperty("java.home") : configuredJavaHome).toAbsolutePath().normalize();
            if (!Files.isExecutable(javaHome.resolve("bin/java"))
                    || !Files.isExecutable(javaHome.resolve("bin/javac"))) {
                return result(service, "ERROR", null, start, "", false, operation,
                        "A JDK with java and javac is required.", root, null);
            }
            var builder = new ProcessBuilder(wrapper.toString(), "-B", "-ntp", "-pl",
                    "services/" + service, "-am", goal.argument)
                    .directory(root.toFile()).redirectErrorStream(true);
            String path = builder.environment().getOrDefault("PATH", "/usr/bin:/bin");
            String userHome = builder.environment().getOrDefault("HOME", System.getProperty("user.home"));
            builder.environment().clear();
            builder.environment().put("PATH", path);
            builder.environment().put("HOME", userHome);
            builder.environment().put("JAVA_HOME", javaHome.toString());
            builder.environment().put("LANG", "C");
            return runProcess(builder, service, operation, timeoutSeconds, start, root);
        } catch (IOException | SecurityException ex) {
            return result(service, "ERROR", null, start, "", false, operation,
                    "The approved Maven operation could not start. Check the repository and JDK.", null, null);
        }
    }

    private Run runProcess(ProcessBuilder builder, String service, String operation,
                           int timeoutSeconds, long start, Path root) throws IOException {
        Instant startedAt = Instant.now();
        Process process = builder.start();
        ExecutorService drain = Executors.newSingleThreadExecutor();
        try {
            Future<BoundedTail> output = drain.submit(() -> {
                var tail = new BoundedTail();
                try (var stream = process.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = stream.read(chunk)) != -1) tail.append(chunk, count);
                }
                return tail;
            });
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                stop(process);
                return result(service, "TIMEOUT", null, start, "", true, operation,
                        "Maven exceeded the " + timeoutSeconds + "-second limit; completion is unknown.", root, startedAt);
            }
            BoundedTail tail = output.get(5, TimeUnit.SECONDS);
            return result(service, "COMPLETE", process.exitValue(), start, tail.text(), tail.truncated(),
                    operation, "Maven process completed; inspect its exit code and operation-specific evidence.",
                    root, startedAt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return result(service, "ERROR", null, start, "", false, operation,
                    "Maven was interrupted; completion is unknown.", root, startedAt);
        } catch (ExecutionException | TimeoutException ex) {
            return result(service, "ERROR", null, start, "", false, operation,
                    "Maven output could not be collected safely; completion is unknown.", root, startedAt);
        } finally {
            stop(process);
            drain.shutdownNow();
        }
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        try { process.getInputStream().close(); } catch (IOException ignored) { }
    }

    private static Run result(String service, String status, Integer exit, long start, String output,
                              boolean truncated, String operation, String message, Path root, Instant startedAt) {
        return new Run(service, status, exit, (System.nanoTime() - start) / 1_000_000,
                output, truncated, operation, message, root, startedAt);
    }

    /** Keeps the end of output, where Maven usually writes its final failure summary. */
    private static final class BoundedTail {
        private final byte[] data = new byte[OUTPUT_LIMIT];
        private int size;
        private boolean truncated;

        void append(byte[] chunk, int count) {
            if (count >= OUTPUT_LIMIT) {
                System.arraycopy(chunk, count - OUTPUT_LIMIT, data, 0, OUTPUT_LIMIT);
                size = OUTPUT_LIMIT;
                truncated = true;
            } else if (size + count > OUTPUT_LIMIT) {
                int discard = size + count - OUTPUT_LIMIT;
                System.arraycopy(data, discard, data, 0, size - discard);
                System.arraycopy(chunk, 0, data, size - discard, count);
                size = OUTPUT_LIMIT;
                truncated = true;
            } else {
                System.arraycopy(chunk, 0, data, size, count);
                size += count;
            }
        }

        String text() { return new String(data, 0, size, StandardCharsets.UTF_8); }
        boolean truncated() { return truncated; }
    }
}
