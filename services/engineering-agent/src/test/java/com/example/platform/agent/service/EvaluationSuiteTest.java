package com.example.platform.agent.service;

import com.example.platform.agent.dto.ReviewReport;
import com.example.platform.agent.dto.SecurityResult;
import com.example.platform.agent.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline evaluation contracts: fixture labels, evidence filter, scoring and deterministic decision cases. */
class EvaluationSuiteTest {
    private static final String SERVICE = "engineering-agent";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void labeledFixturesAreVisibleAndAcceptedOnlyWithMatchingSourceEvidence() throws Exception {
        var fixtures = EvaluationCases.load();
        assertThat(fixtures).hasSize(4);
        assertThat(fixtures.stream().filter(EvaluationCases.Case::positive)).hasSize(3);
        var observations = new ArrayList<EvaluationScorer.Observation>();
        for (var fixture : fixtures) {
            String source = EvaluationCases.source(fixture);
            var result = new SourceFileReader.SourceFileResult("SUCCESS", fixture.path(),
                    1, source, false, "fixture", "fixture");
            if (!fixture.positive()) {
                observations.add(new EvaluationScorer.Observation(fixture, "AVAILABLE", 0, List.of()));
                continue;
            }
            int line = fixture.lines().getFirst();
            String quote = source.split("\\n", -1)[line - 1].trim();
            String raw = JSON.writeValueAsString(Map.of("summary", "Potential issue to review.",
                    "findings", List.of(Map.of("severity", "MEDIUM", "category", fixture.category(),
                            "file", fixture.path(), "line", line, "evidence", quote,
                            "description", "Potential issue in this isolated snippet.",
                            "recommendation", "Inspect this line and surrounding checks."))));
            var accepted = CodeFindingExtractor.parse(raw, SERVICE, "", result, source);
            assertThat(accepted.status()).isEqualTo("AVAILABLE");
            assertThat(accepted.findings()).hasSize(1);
            observations.add(new EvaluationScorer.Observation(fixture,
                    accepted.status(), accepted.rejectedCount(), accepted.findings()));
        }
        var ideal = EvaluationScorer.score("scripted-offline", observations);
        assertThat(ideal.metrics().truePositives()).isEqualTo(3);
        assertThat(ideal.metrics().falsePositives()).isZero();
        assertThat(ideal.metrics().falseNegatives()).isZero();

        // A clean-case extra finding lowers precision; a missed positive lowers recall.
        var clean = fixtures.getLast();
        observations.set(0, new EvaluationScorer.Observation(fixtures.getFirst(), "MALFORMED", 0, List.of()));
        observations.set(3, new EvaluationScorer.Observation(clean, "AVAILABLE", 0, List.of(
                new ReviewReport.Finding("LOW", "PERFORMANCE", clean.path(), 5,
                        "Possible false alarm.", "Inspect manually.", "return account.email()",
                        "sourceFile", "POTENTIAL"))));
        var measured = EvaluationScorer.score("scripted-offline", observations);
        assertThat(measured.metrics().truePositives()).isEqualTo(2);
        assertThat(measured.metrics().falsePositives()).isEqualTo(1);
        assertThat(measured.metrics().falseNegatives()).isEqualTo(1);
        assertThat(measured.metrics().precision()).isCloseTo(2.0 / 3.0,
                org.assertj.core.data.Offset.offset(0.0001));
        assertThat(measured.metrics().recall()).isCloseTo(2.0 / 3.0,
                org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test void fixedReportDecisionMatchesLabeledBuildTestAndScannerOutcomes() {
        var files = new ChangedFilesReader.ChangedFilesResult("SUCCESS", 0, 1,
                List.of(new ChangedFilesReader.ChangedFile("services/engineering-agent/src/main/java/Eval.java",
                        ChangedFilesReader.ChangeStatus.UNTRACKED,
                        ChangedFilesReader.ChangeStatus.UNTRACKED, false, SERVICE)),
                List.of(SERVICE), false, false, "fixture", "fixture");
        var diff = new GitDiffReader.GitDiffResult("SUCCESS", 0, 1, "", false,
                false, "fixture", "fixture");
        var source = new SourceFileReader.SourceFileResult("SUCCESS",
                "services/engineering-agent/src/main/java/Eval.java", 1,
                "class Eval {}", false, "fixture", "fixture");
        var build = new BuildRunner.BuildResult(SERVICE, "PASS", 0, 1,
                List.of(), false, "simulated compile", "fixture");
        var passingTests = testResult("PASS", 0, 0);
        var clean = new SecurityResult(SERVICE, "PASS", true, 0, false,
                List.of(), List.of(), 1, "fixture");
        var baseline = ReviewReportFactory.create(SERVICE, files, diff, source, build,
                passingTests, clean, List.of(), "AVAILABLE");
        assertThat(baseline.decision()).isEqualTo(ReviewReport.ReviewDecision.WARN);
        assertThat(baseline.risk()).isEqualTo(ReviewReport.RiskLevel.UNASSESSED);
        assertThat(baseline.verificationStatus()).isEqualTo(ReviewReport.VerificationStatus.PASS);

        var failingTests = ReviewReportFactory.create(SERVICE, files, diff, source, build,
                testResult("FAIL", 1, 0), clean, List.of(), "AVAILABLE");
        assertThat(failingTests.decision()).isEqualTo(ReviewReport.ReviewDecision.FAIL);
        assertThat(failingTests.verificationStatus()).isEqualTo(ReviewReport.VerificationStatus.FAIL);

        var advisory = new SecurityResult.SecurityFinding("dependencies", "EVAL-ADVISORY",
                "CRITICAL", "services/engineering-agent/pom.xml", null,
                "Synthetic advisory match.", "fixture:library", "1", "SCANNER_MATCH");
        var matched = new SecurityResult(SERVICE, "FINDINGS", true, 1, false,
                List.of(), List.of(advisory), 1, "fixture");
        var scannerCase = ReviewReportFactory.create(SERVICE, files, diff, source, build,
                passingTests, matched, List.of(), "AVAILABLE");
        assertThat(scannerCase.decision()).isEqualTo(ReviewReport.ReviewDecision.FAIL);
        assertThat(scannerCase.risk()).isEqualTo(ReviewReport.RiskLevel.CRITICAL);
    }

    private static TestRunner.TestResult testResult(String status, int failed, int skipped) {
        return new TestRunner.TestResult(SERVICE, status, status.equals("PASS") ? 0 : 1,
                1, 1, 1 - failed - skipped, failed, 0, skipped, 1, List.of(),
                false, false, "simulated tests", "fixture");
    }
}
