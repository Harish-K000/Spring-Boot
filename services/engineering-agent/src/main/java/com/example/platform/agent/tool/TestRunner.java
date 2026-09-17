package com.example.platform.agent.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.List;

/** Converts an approved Maven test run and fresh Surefire reports into structured evidence. */
@Component
@Profile({"mcp-server", "test"})
public class TestRunner {
    static final int DEFAULT_TIMEOUT_SECONDS = 240;
    private final ApprovedMavenProcess maven;
    private final int timeoutSeconds;

    @Autowired
    public TestRunner(ApprovedMavenProcess maven) { this(maven, DEFAULT_TIMEOUT_SECONDS); }

    TestRunner(ApprovedMavenProcess maven, int timeoutSeconds) {
        this.maven = maven;
        this.timeoutSeconds = timeoutSeconds;
    }

    public record TestResult(String service, String status, Integer exitCode, long durationMs,
                             int total, int passed, int failed, int errors, int skipped, int serviceTests,
                             List<SurefireReportReader.Failure> failures, boolean incomplete,
                             boolean outputTruncated, String operation, String message) {}

    public TestResult run(String requestedService) {
        var run = maven.execute(requestedService, ApprovedMavenProcess.Goal.TEST, timeoutSeconds);
        if (!run.status().equals("COMPLETE")) {
            return new TestResult(run.service(), run.status(), run.exitCode(), run.durationMs(),
                    0, 0, 0, 0, 0, 0, List.of(), false, run.outputTruncated(),
                    run.operation(), run.message());
        }
        var reports = SurefireReportReader.read(run.root(), run.service(), run.startedAt());
        boolean incomplete = reports.incomplete() || (run.exitCode() != 0 && reports.serviceTests() == 0)
                || (run.exitCode() == 0 && reports.failed() + reports.errors() > 0);
        String status = run.exitCode() != 0 ? "FAIL"
                : incomplete ? "INCOMPLETE"
                : reports.serviceTests() == 0 ? "NO_TESTS" : "PASS";
        String message = switch (status) {
            case "PASS" -> "Maven test phase passed with fresh reports for the selected service. Skipped tests did not run.";
            case "FAIL" -> incomplete
                    ? "Maven test phase failed before complete service test evidence was available. Counts may be partial."
                    : "Maven test phase failed. Fresh Surefire reports provide the returned counts and failures.";
            case "NO_TESTS" -> "Maven exited successfully, but no fresh tests ran for the selected service.";
            default -> "Maven exited successfully, but test reports are incomplete or inconsistent; do not claim a test pass.";
        };
        return new TestResult(run.service(), status, run.exitCode(), run.durationMs(),
                reports.total(), reports.passed(), reports.failed(), reports.errors(), reports.skipped(),
                reports.serviceTests(), reports.failures(), incomplete, run.outputTruncated(),
                run.operation(), message);
    }
}
