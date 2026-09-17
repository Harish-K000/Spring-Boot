package com.example.platform.agent.service;

import com.example.platform.agent.dto.ReviewReport;
import com.example.platform.agent.dto.SecurityResult;
import com.example.platform.agent.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Opt-in local-model benchmark: exact review prompt/parser, fixture evidence, no Maven/scanner execution. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AGENT_LIVE_EVAL", matches = "true")
class AgentEvaluationLiveTest {
    private static final String SERVICE = "engineering-agent";
    @Autowired private CodeReviewAgent agent;
    @Autowired private ObjectMapper json;
    @Value("${spring.ai.ollama.chat.options.model}") private String model;
    @MockitoBean private ChangedFilesReader changedFilesReader;
    @MockitoBean private GitDiffReader gitDiffReader;
    @MockitoBean private SourceFileReader sourceFileReader;
    @MockitoBean private BuildRunner buildRunner;
    @MockitoBean private TestRunner testRunner;
    @MockitoBean private SecurityRunner securityRunner;

    @Test
    @Timeout(600)
    void measureAcceptedFindingsAgainstSafeFixtures() throws Exception {
        var observations = new ArrayList<EvaluationScorer.Observation>();
        for (var fixture : EvaluationCases.load()) {
            String source = EvaluationCases.source(fixture);
            var changed = new ChangedFilesReader.ChangedFile(fixture.path(),
                    ChangedFilesReader.ChangeStatus.UNTRACKED,
                    ChangedFilesReader.ChangeStatus.UNTRACKED, false, SERVICE);
            when(changedFilesReader.read()).thenReturn(new ChangedFilesReader.ChangedFilesResult(
                    "SUCCESS", 0, 1, List.of(changed), List.of(SERVICE), false, false,
                    "Isolated evaluation fixture", "One fixture path."));
            when(gitDiffReader.read()).thenReturn(new GitDiffReader.GitDiffResult(
                    "SUCCESS", 0, 1, "", false, false, "No tracked diff", "No tracked changes."));
            when(sourceFileReader.read(fixture.path())).thenReturn(new SourceFileReader.SourceFileResult(
                    "SUCCESS", fixture.path(), 1, source, false, "Isolated evaluation fixture", "Fixture source."));
            when(buildRunner.run(SERVICE)).thenReturn(new BuildRunner.BuildResult(
                    SERVICE, "PASS", 0, 1, List.of(), false, "simulated compile",
                    "Simulated pass for model evaluation; Maven did not run."));
            when(testRunner.run(SERVICE)).thenReturn(new TestRunner.TestResult(
                    SERVICE, "PASS", 0, 1, 1, 1, 0, 0, 0, 1, List.of(),
                    false, false, "simulated test",
                    "Simulated pass for model evaluation; JUnit did not run."));
            when(securityRunner.run(SERVICE)).thenReturn(cleanSecurity());

            var review = agent.review(SERVICE);
            assertThat(review.status()).isEqualTo("EVIDENCE_COLLECTED");
            assertThat(review.sourceFile().path()).isEqualTo(fixture.path());
            assertThat(review.report().decision()).isEqualTo(ReviewReport.ReviewDecision.WARN);
            observations.add(new EvaluationScorer.Observation(fixture,
                    review.analysisStatus(), review.rejectedModelFindings(), review.report().findings()));
        }
        var report = EvaluationScorer.score(model, observations);
        assertThat(report.metrics().cases()).isEqualTo(EvaluationCases.load().size());
        Path output = Path.of("target/evaluation-report.json").toAbsolutePath();
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.printf("Evaluation model=%s TP=%d FP=%d FN=%d rejected=%d precision=%s recall=%s report=%s%n",
                model, report.metrics().truePositives(), report.metrics().falsePositives(),
                report.metrics().falseNegatives(), report.metrics().rejectedModelFindings(),
                metric(report.metrics().precision()), metric(report.metrics().recall()), output);
    }

    private static SecurityResult cleanSecurity() {
        var scanners = List.of("sast", "secrets", "dependencies").stream()
                .map(name -> new SecurityResult.ScannerResult(name, "PASS", 0, 0, 1, 1,
                        "Simulated evaluation scope", "No fixture scanner run."))
                .toList();
        return new SecurityResult(SERVICE, "PASS", true, 0, false, scanners,
                List.of(), 1, "Simulated clean result for model evaluation.");
    }

    private static String metric(Double value) {
        return value == null ? "undefined" : String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
