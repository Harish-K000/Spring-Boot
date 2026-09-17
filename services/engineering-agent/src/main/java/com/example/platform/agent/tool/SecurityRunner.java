package com.example.platform.agent.tool;

import com.example.platform.agent.dto.SecurityResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;

/** Executes three fixed scanner commands. Raw output and secret values stay out of the API. */
@Component
@Profile({"mcp-server", "test"})
public class SecurityRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int REPORT_BYTES = 4_000_000;
    private static final int FINDING_LIMIT = 20;
    private final RepositoryGit repository;
    private final Path semgrep;
    private final Path gitleaks;
    private final Path osv;
    private final Semaphore slot = new Semaphore(1);

    public SecurityRunner(RepositoryGit repository,
                          @Value("${agent.security.semgrep-executable:/opt/homebrew/bin/semgrep}") String semgrepExecutable,
                          @Value("${agent.security.gitleaks-executable:/opt/homebrew/bin/gitleaks}") String gitleaksExecutable,
                          @Value("${agent.security.osv-executable:/opt/homebrew/bin/osv-scanner}") String osvExecutable) {
        this.repository = repository;
        this.semgrep = Path.of(semgrepExecutable);
        this.gitleaks = Path.of(gitleaksExecutable);
        this.osv = Path.of(osvExecutable);
    }

    public SecurityResult run(String requestedService) {
        long start = System.nanoTime();
        final String service;
        try {
            service = ToolExecutionPolicy.serviceName(requestedService);
        } catch (IllegalArgumentException ex) {
            return empty(null, "DENIED", start, "Only registered services may be scanned.");
        }
        if (!slot.tryAcquire()) return empty(service, "BUSY", start, "Another security scan is running.");
        try {
            return scan(service, start);
        } finally {
            slot.release();
        }
    }

    private SecurityResult scan(String service, long start) {
        final Path root;
        final Path module;
        try {
            root = repository.repositoryRoot();
            module = root.resolve("services").resolve(service);
            if (!Files.isDirectory(module, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(module.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(module.resolve("src/main"), LinkOption.NOFOLLOW_LINKS)) {
                return empty(service, "ERROR", start, "The registered service's source or pom.xml is missing.");
            }
            // External scanners must not traverse a service-owned link outside the approved module.
            Files.walkFileTree(module, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    if (attrs.isSymbolicLink()) throw new IOException("Service contains a symbolic link.");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException ex) {
            return empty(service, "ERROR", start, "Service scope could not be checked safely.");
        }

        var scans = List.of(
                runSemgrep(root, module),
                runGitleaks(root, module),
                runOsv(root, module));
        var scannerResults = scans.stream().map(Scan::result).toList();
        int total = scannerResults.stream().mapToInt(SecurityResult.ScannerResult::findings).sum();
        boolean complete = scannerResults.stream().allMatch(item -> item.status().equals("PASS")
                || item.status().equals("FINDINGS"));
        String status = total > 0 ? "FINDINGS" : complete ? "PASS" : "INCOMPLETE";
        var findings = scans.stream().flatMap(item -> item.findings().stream())
                .sorted(Comparator.comparingInt((SecurityResult.SecurityFinding item) -> severityRank(item.severity()))
                        .reversed().thenComparing(SecurityResult.SecurityFinding::scanner)
                        .thenComparing(SecurityResult.SecurityFinding::ruleId))
                .limit(FINDING_LIMIT).toList();
        return new SecurityResult(service, status, complete, total, total > FINDING_LIMIT,
                scannerResults, findings, elapsed(start), complete
                ? "Scanner results collected; matches require human triage."
                : "One or more scanners did not provide complete evidence; inspect scanner statuses.");
    }

    private Scan runSemgrep(Path root, Path module) {
        String scope = "Production source in the selected service; three local Java rules only.";
        Path config = root.resolve("services/engineering-agent/src/main/resources/security/semgrep-rules.yml");
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS))
            return unavailable("sast", scope, "Local Semgrep rule file is missing.");
        if (!executable(semgrep)) return unavailable("sast", scope, "Semgrep is not installed at the configured path.");
        return invoke("sast", scope, semgrep, 60, report -> List.of("scan", "--config=" + config,
                "--json-output=" + report, "--metrics=off", "--disable-version-check",
                "--error", module.resolve("src/main").toString()), this::parseSemgrep, root, module);
    }

    private Scan runGitleaks(Path root, Path module) {
        String scope = "Selected service working-tree directory, excluding generated Maven target/ output; current files, not Git history.";
        Path config = root.resolve("services/engineering-agent/src/main/resources/security/gitleaks.toml");
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS))
            return unavailable("secrets", scope, "Local Gitleaks configuration is missing.");
        if (!executable(gitleaks)) return unavailable("secrets", scope, "Gitleaks is not installed at the configured path.");
        return invoke("secrets", scope, gitleaks, 60, report -> List.of("dir", module.toString(),
                "--config=" + config, "--redact=100", "--report-format=json", "--report-path=" + report,
                "--no-banner", "--log-level=error"), this::parseGitleaks, root, module);
    }

    private Scan runOsv(Path root, Path module) {
        String scope = "Selected Maven pom.xml, direct and transitive dependencies resolved from Maven Central; public OSV queries. Test dependencies may be omitted.";
        if (!executable(osv)) return unavailable("dependencies", scope, "OSV-Scanner is not installed at the configured path.");
        return invoke("dependencies", scope, osv, 150, report -> List.of("scan", "source",
                "--format=json", "--all-packages", "--data-source=native", "--lockfile=" + module.resolve("pom.xml"),
                "--output-file=" + report, "--verbosity=error"), this::parseOsv, root, module);
    }

    private Scan invoke(String name, String scope, Path binary, int timeoutSeconds,
                        java.util.function.Function<Path, List<String>> arguments,
                        ReportParser parser, Path root, Path module) {
        long start = System.nanoTime();
        Path directory = null;
        try {
            directory = Files.createTempDirectory("agent-security-");
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            Path report = directory.resolve("report.json");
            var command = new ArrayList<String>();
            command.add(binary.toString());
            command.addAll(arguments.apply(report));
            var builder = new ProcessBuilder(command).directory(root.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            String path = builder.environment().getOrDefault("PATH", "/usr/bin:/bin");
            String userHome = builder.environment().getOrDefault("HOME", System.getProperty("user.home"));
            builder.environment().clear();
            builder.environment().put("PATH", path);
            builder.environment().put("HOME", userHome);
            builder.environment().put("LANG", "C");
            Process process = builder.start();
            boolean finished;
            try {
                finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            }
            if (!finished) return failure(name, scope, "TIMEOUT", null, start, "Scanner exceeded its fixed time limit.");
            int exit = process.exitValue();
            if (exit != 0 && exit != 1) return failure(name, scope, "ERROR", exit, start, "Scanner process failed.");
            if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(report) > REPORT_BYTES) {
                return failure(name, scope, "INCOMPLETE", exit, start, "Scanner report is missing or too large.");
            }
            Parsed parsed = parser.parse(JSON.readTree(report.toFile()), root, module);
            if (parsed.incomplete() || (exit == 0 && parsed.findingsCount() > 0)
                    || (exit == 1 && parsed.findingsCount() == 0)) {
                return new Scan(new SecurityResult.ScannerResult(name, "INCOMPLETE", exit,
                        parsed.findingsCount(), parsed.packages(), elapsed(start), scope,
                        "Scanner report and process status require inspection."), parsed.findings());
            }
            String status = parsed.findingsCount() > 0 ? "FINDINGS" : "PASS";
            return new Scan(new SecurityResult.ScannerResult(name, status, exit,
                    parsed.findingsCount(), parsed.packages(), elapsed(start), scope,
                    status.equals("PASS") ? "No matches in this scanner's stated scope."
                            : "Scanner matches require triage."), parsed.findings());
        } catch (IOException | InterruptedException | SecurityException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return failure(name, scope, "ERROR", null, start, "Scanner evidence could not be collected safely.");
        } finally {
            if (directory != null) {
                try { Files.deleteIfExists(directory.resolve("report.json")); } catch (IOException ignored) { }
                try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
            }
        }
    }

    private Parsed parseSemgrep(JsonNode report, Path root, Path module) {
        if (!report.isObject() || !report.path("results").isArray()) return invalid();
        var findings = new ArrayList<SecurityResult.SecurityFinding>();
        for (JsonNode item : report.path("results")) {
            String file = scopedFile(root, module, item.path("path").asText(), false);
            if (file == null) return invalid();
            String severity = item.path("extra").path("severity").asText().equals("ERROR") ? "HIGH" : "MEDIUM";
            findings.add(new SecurityResult.SecurityFinding("sast", safe(item.path("check_id").asText(), 100),
                    severity, file, positiveLine(item.path("start").path("line")),
                    safe(item.path("extra").path("message").asText(), 250), null, null, "SCANNER_MATCH"));
        }
        boolean incomplete = report.path("errors").size() > 0 || report.path("paths").path("scanned").size() == 0;
        return new Parsed(findings.size(), 0, findings, incomplete);
    }

    private Parsed parseGitleaks(JsonNode report, Path root, Path module) {
        if (!report.isArray()) return invalid();
        var findings = new ArrayList<SecurityResult.SecurityFinding>();
        for (JsonNode item : report) {
            String file = scopedFile(root, module, item.path("File").asText(), false);
            if (file == null) return invalid();
            findings.add(new SecurityResult.SecurityFinding("secrets", safe(item.path("RuleID").asText(), 100),
                    "HIGH", file, positiveLine(item.path("StartLine")),
                    "Potential secret matched Gitleaks rule; value withheld.", null, null, "SCANNER_MATCH"));
        }
        return new Parsed(findings.size(), 0, findings, false);
    }

    private Parsed parseOsv(JsonNode report, Path root, Path module) {
        if (!report.isObject() || !report.path("results").isArray()) return invalid();
        var findings = new ArrayList<SecurityResult.SecurityFinding>();
        int packages = 0;
        for (JsonNode result : report.path("results")) {
            JsonNode listed = result.path("packages");
            if (!listed.isArray()) return invalid();
            packages += listed.size();
            String file = scopedFile(root, module, result.path("source").path("path").asText(), true);
            if (file == null) return invalid();
            for (JsonNode item : listed) {
                String packageName = safe(item.path("package").path("name").asText(), 150);
                String version = safe(item.path("package").path("version").asText(), 80);
                for (JsonNode vuln : item.path("vulnerabilities")) {
                    String advisory = safe(vuln.path("id").asText(), 100);
                    String severity = switch (vuln.path("database_specific").path("severity").asText()) {
                        case "CRITICAL" -> "CRITICAL";
                        case "HIGH" -> "HIGH";
                        case "MODERATE", "MEDIUM" -> "MEDIUM";
                        case "LOW" -> "LOW";
                        default -> "UNKNOWN";
                    };
                    findings.add(new SecurityResult.SecurityFinding("dependencies", advisory, severity,
                            file, null, "Known advisory matched a resolved dependency version.",
                            packageName, version, "SCANNER_MATCH"));
                }
            }
        }
        return new Parsed(findings.size(), packages, findings, packages == 0);
    }

    private static String scopedFile(Path root, Path module, String raw, boolean allowRootPom) {
        if (raw == null || raw.isBlank()) return allowRootPom ? root.relativize(module.resolve("pom.xml")).toString() : null;
        try {
            Path path = Path.of(raw);
            Path relative = path.isAbsolute() ? root.relativize(path.normalize()) : path.normalize();
            if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().length() > 300)
                return null;
            Path selected = root.relativize(module);
            if (!relative.startsWith(selected)
                    && !(allowRootPom && relative.equals(Path.of("pom.xml")))) return null;
            return relative.toString();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ");
        return clean.substring(0, Math.min(clean.length(), max));
    }

    private static Integer positiveLine(JsonNode node) {
        return node.isIntegralNumber() && node.intValue() > 0 ? node.intValue() : null;
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 4; case "HIGH" -> 3; case "MEDIUM" -> 2; case "LOW" -> 1;
            default -> 0;
        };
    }

    private static boolean executable(Path path) {
        // Homebrew places trusted executables behind symlinks in /opt/homebrew/bin.
        return path.isAbsolute() && Files.isRegularFile(path)
                && Files.isExecutable(path);
    }

    private static Scan unavailable(String name, String scope, String message) {
        return new Scan(new SecurityResult.ScannerResult(name, "UNAVAILABLE", null, 0, 0, 0,
                scope, message), List.of());
    }

    private static Scan failure(String name, String scope, String status, Integer exit,
                                long start, String message) {
        return new Scan(new SecurityResult.ScannerResult(name, status, exit, 0, 0,
                elapsed(start), scope, message), List.of());
    }

    private static SecurityResult empty(String service, String status, long start, String message) {
        return new SecurityResult(service, status, false, 0, false, List.of(), List.of(),
                elapsed(start), message);
    }

    private static Parsed invalid() { return new Parsed(0, 0, List.of(), true); }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private record Scan(SecurityResult.ScannerResult result, List<SecurityResult.SecurityFinding> findings) {}
    private record Parsed(int findingsCount, int packages, List<SecurityResult.SecurityFinding> findings,
                          boolean incomplete) {}
    private interface ReportParser { Parsed parse(JsonNode report, Path root, Path module); }
}
