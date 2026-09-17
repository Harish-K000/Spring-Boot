package com.example.platform.agent;

import com.example.platform.agent.tool.*;
import com.example.platform.agent.dto.SecurityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP review contract with real orchestration; fixed readers, runners and model are substituted. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentReviewEndpointTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AgentSafetyPolicy safetyPolicy;
    @MockitoBean private ChangedFilesReader changedFilesReader;
    @MockitoBean private GitDiffReader gitDiffReader;
    @MockitoBean private SourceFileReader sourceFileReader;
    @MockitoBean private BuildRunner buildRunner;
    @MockitoBean private TestRunner testRunner;
    @MockitoBean private SecurityRunner securityRunner;
    @MockitoBean private ChatModel chatModel;

    private static final String SOURCE_PATH = "services/engineering-agent/src/main/java/New.java";
    private static final String DIRECT_DIFF = """
            diff --git a/services/engineering-agent/pom.xml b/services/engineering-agent/pom.xml
            --- a/services/engineering-agent/pom.xml
            +++ b/services/engineering-agent/pom.xml
            @@ -1 +1 @@
            -<name>Old</name>
            +<name>New</name>
            """;

    @BeforeEach void setup() {
        when(chatModel.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        when(changedFilesReader.read()).thenReturn(files("engineering-agent", true));
        when(gitDiffReader.read()).thenReturn(diff(DIRECT_DIFF));
        when(sourceFileReader.read(SOURCE_PATH)).thenReturn(new SourceFileReader.SourceFileResult(
                "SUCCESS", SOURCE_PATH, 1, "class New {}\n", false, "scope", "Source read."));
        when(buildRunner.run("engineering-agent")).thenReturn(build("engineering-agent", "PASS"));
        when(testRunner.run("engineering-agent")).thenReturn(tests("engineering-agent", "PASS", 4));
        when(securityRunner.run("engineering-agent")).thenReturn(cleanSecurity());
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("""
                {"summary":"No line-linked concern found in the visible code.","findings":[]}
                """));
    }

    @Test void exposesEvidenceAndRunsTheFixedSequenceBeforeModelObservations() throws Exception {
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EVIDENCE_COLLECTED"))
                .andExpect(jsonPath("$.service").value("engineering-agent"))
                .andExpect(jsonPath("$.changedFiles.files[0].path").value(SOURCE_PATH))
                .andExpect(jsonPath("$.gitDiff.diff").value(DIRECT_DIFF))
                .andExpect(jsonPath("$.sourceFile.content").value("class New {}\n"))
                .andExpect(jsonPath("$.build.status").value("PASS"))
                .andExpect(jsonPath("$.tests.status").value("PASS"))
                .andExpect(jsonPath("$.tests.skipped").value(4))
                .andExpect(jsonPath("$.analysisStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.report.decision").value("WARN"))
                .andExpect(jsonPath("$.report.risk").value("UNASSESSED"))
                .andExpect(jsonPath("$.report.verificationStatus").value("WARN"))
                .andExpect(jsonPath("$.report.tests[0].skipped").value(4))
                .andExpect(jsonPath("$.report.findings.length()").value(0))
                .andExpect(jsonPath("$.report.checks[5].status").value("PASS"))
                .andExpect(jsonPath("$.security.totalFindings").value(0))
                .andExpect(jsonPath("$.approval.reviewId").exists())
                .andExpect(jsonPath("$.approval.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.approval.actionToken").exists());
        var order = inOrder(changedFilesReader, gitDiffReader, sourceFileReader, buildRunner,
                testRunner, securityRunner, chatModel);
        order.verify(changedFilesReader).read();
        order.verify(gitDiffReader).read();
        order.verify(sourceFileReader).read(SOURCE_PATH);
        order.verify(buildRunner).run("engineering-agent");
        order.verify(testRunner).run("engineering-agent");
        order.verify(securityRunner).run("engineering-agent");
        order.verify(chatModel).call(any(Prompt.class));
    }

    @Test void auditShowsFixedChecksAndChoicesWithoutCodeOrActionToken() throws Exception {
        var created = mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(created.getResponse().getContentAsString());
        String id = body.path("reviewId").asText();
        String token = body.path("approval").path("actionToken").asText();
        assertThat(id).isEqualTo(body.path("approval").path("reviewId").asText());
        var before = mvc.perform(get("/api/agent/reviews/{id}/audit", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.reviewId").value(id))
                .andExpect(jsonPath("$.review.model").isNotEmpty())
                .andExpect(jsonPath("$.review.startedAt").exists())
                .andExpect(jsonPath("$.review.completedAt").exists())
                .andExpect(jsonPath("$.toolExecutions[0].tool").value("getChangedFiles"))
                .andExpect(jsonPath("$.toolExecutions[1].tool").value("getGitDiff"))
                .andExpect(jsonPath("$.toolExecutions[2].tool").value("readSourceFile"))
                .andExpect(jsonPath("$.actions.length()").value(0))
                .andReturn();
        assertThat(before.getResponse().getContentAsString())
                .doesNotContain(token, "class New {}", "<name>New</name>");
        mvc.perform(action(id, token, "REQUEST_CHANGES", "Check the skipped tests."))
                .andExpect(status().isOk());
        mvc.perform(get("/api/agent/reviews/{id}/audit", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.approvalStatus").value("CHANGES_REQUESTED"))
                .andExpect(jsonPath("$.actions.length()").value(1))
                .andExpect(jsonPath("$.actions[0].action").value("REQUEST_CHANGES"));
    }

    @Test void busyAgentRequestDoesNotStartAReviewOrEngineeringTools() throws Exception {
        try (var permit = safetyPolicy.enter(AgentSafetyPolicy.Mode.CHAT)) {
            mvc.perform(review(Map.of("service", "engineering-agent")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.detail").value(
                            "Another agent request is active. Retry after it finishes."));
        }
        verifyNoInteractions(changedFilesReader, gitDiffReader, sourceFileReader,
                buildRunner, testRunner, securityRunner);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test void doesNotLetContradictoryModelProseChangeTestCounts() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("""
                {"summary":"No tests were skipped.","findings":[]}
                """));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tests.skipped").value(4))
                .andExpect(jsonPath("$.report.tests[0].skipped").value(4))
                .andExpect(jsonPath("$.report.decision").value("WARN"))
                .andExpect(jsonPath("$.analysis").value("No tests were skipped."));
    }

    @Test void acceptsOnlyPotentialFindingsCitingVisibleSelectedServiceLines() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("""
                {"summary":"Two potential concerns need human review.","findings":[
                  {"severity":"MEDIUM","category":"VALIDATION","file":"services/engineering-agent/src/main/java/New.java","line":1,"evidence":"class New {}","description":"Potential missing validation.","recommendation":"Check input validation."},
                  {"severity":"LOW","category":"API_CONTRACT","file":"services/engineering-agent/pom.xml","line":1,"evidence":"<name>New</name>","description":"Potential artifact naming change.","recommendation":"Check consumers."},
                  {"severity":"CRITICAL","category":"AUTHORIZATION","file":"services/auth-service/Secret.java","line":1,"evidence":"class New {}","description":"Unsupported service claim.","recommendation":"Check ownership."},
                  {"severity":"HIGH","category":"CONCURRENCY","file":"services/engineering-agent/src/main/java/New.java","line":99,"evidence":"class New {}","description":"Unsupported line claim.","recommendation":"Check concurrency."}
                ]}
                """));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.report.findings.length()").value(2))
                .andExpect(jsonPath("$.rejectedModelFindings").value(2))
                .andExpect(jsonPath("$.report.findings[0].evidenceStatus").value("POTENTIAL"))
                .andExpect(jsonPath("$.report.findings[0].evidenceField").value("sourceFile"))
                .andExpect(jsonPath("$.report.findings[1].evidenceField").value("gitDiff"))
                .andExpect(jsonPath("$.report.risk").value("UNASSESSED"))
                .andExpect(jsonPath("$.notes[0]").exists());
    }

    @Test void malformedModelOutputCannotBecomeACodeFinding() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("HIGH: authorization is broken everywhere."));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisStatus").value("MALFORMED"))
                .andExpect(jsonPath("$.report.findings.length()").value(0))
                .andExpect(jsonPath("$.report.checks[6].status").value("MALFORMED"))
                .andExpect(jsonPath("$.report.risk").value("UNASSESSED"))
                .andExpect(jsonPath("$.build.status").value("PASS"));
    }

    @Test void rejectsACitationToSourceCodeBeyondTheModelPreview() throws Exception {
        when(sourceFileReader.read(SOURCE_PATH)).thenReturn(new SourceFileReader.SourceFileResult(
                "SUCCESS", SOURCE_PATH, 1, "x".repeat(2_100) + "\nclass Unseen {}\n",
                false, "scope", "Source read."));
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("""
                {"summary":"Potential concern in an unseen line.","findings":[
                  {"severity":"HIGH","category":"VALIDATION","file":"services/engineering-agent/src/main/java/New.java","line":2,"evidence":"class Unseen {}","description":"Potential missing validation.","recommendation":"Inspect the source."}
                ]}
                """));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.report.findings.length()").value(0));
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).doesNotContain("class Unseen {}");
    }

    @Test void distinguishesPassingEngineeringChecksFromAnUnassessedProductionDecision() throws Exception {
        when(testRunner.run("engineering-agent")).thenReturn(tests("engineering-agent", "PASS", 0));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.verificationStatus").value("PASS"))
                .andExpect(jsonPath("$.report.decision").value("WARN"))
                .andExpect(jsonPath("$.report.risk").value("UNASSESSED"))
                .andExpect(jsonPath("$.report.tests[0].passed").value(10))
                .andExpect(jsonPath("$.report.evidenceGaps[0]").value(
                        "Scanner scope is limited; production exposure still needs human assessment."));
    }

    @Test void blocksOnHighSeverityScannerMatchesWithoutTreatingThemAsVerifiedExploits() throws Exception {
        var match = new SecurityResult.SecurityFinding("dependencies", "GHSA-example-1234", "CRITICAL",
                "services/engineering-agent/pom.xml", null,
                "Known advisory matched a resolved dependency version.", "example:library", "1.0",
                "SCANNER_MATCH");
        when(securityRunner.run("engineering-agent")).thenReturn(new SecurityResult(
                "engineering-agent", "FINDINGS", true, 1, false,
                List.of(new SecurityResult.ScannerResult("dependencies", "FINDINGS", 1, 1,
                        20, 1, "Maven dependencies", "Scanner matches require triage.")),
                List.of(match), 1, "Matches require human triage."));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.security.totalFindings").value(1))
                .andExpect(jsonPath("$.report.securityTotalFindings").value(1))
                .andExpect(jsonPath("$.report.securityFindings[0].evidenceStatus").value("SCANNER_MATCH"))
                .andExpect(jsonPath("$.report.decision").value("FAIL"))
                .andExpect(jsonPath("$.report.risk").value("CRITICAL"))
                .andExpect(jsonPath("$.report.verificationStatus").value("WARN"));
    }

    @Test void failedReviewCanRequestChangesButCannotBeApprovedOrDecidedTwice() throws Exception {
        when(buildRunner.run("engineering-agent")).thenReturn(build("engineering-agent", "FAIL"));
        var created = mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approval.recommendedAction").value("REQUEST_CHANGES"))
                .andReturn();
        var approval = mapper.readTree(created.getResponse().getContentAsString()).path("approval");
        String id = approval.path("reviewId").asText();
        String token = approval.path("actionToken").asText();
        assertThat(id).startsWith("REV-");
        assertThat(token).isNotBlank();

        var lookup = mvc.perform(get("/api/agent/reviews/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.agentDecision").value("FAIL"))
                .andExpect(jsonPath("$.reviewerIdentityStatus").value("NOT_AUTHENTICATED"))
                .andExpect(jsonPath("$.actionToken").doesNotExist())
                .andReturn();
        assertThat(lookup.getResponse().getContentAsString()).doesNotContain(token);
        mvc.perform(action(id, "wrong-token", "REQUEST_CHANGES", "Needs a fix."))
                .andExpect(status().isForbidden());
        mvc.perform(action(id, token, "REQUEST_CHANGES", ""))
                .andExpect(status().isBadRequest());
        mvc.perform(action(id, token, "REQUEST_CHANGES", "api_key=sk-abcdefghijklmnopqrstuvwxyz"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Write a short reason without credentials or token values."));
        mvc.perform(action(id, token, "APPROVE", "Ship it."))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/agent/reviews/{id}", id))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"));
        mvc.perform(action(id, token, "REQUEST_CHANGES", "Investigate failed build."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHANGES_REQUESTED"))
                .andExpect(jsonPath("$.action").value("REQUEST_CHANGES"))
                .andExpect(jsonPath("$.agentDecision").value("FAIL"))
                .andExpect(jsonPath("$.decidedAt").exists());
        mvc.perform(action(id, token, "REJECT", "Second choice."))
                .andExpect(status().isConflict());
        verifyNoInteractions(testRunner);
        verify(buildRunner, times(1)).run("engineering-agent");
    }

    @Test void localApprovalOfAWarnReviewPreservesTheAgentDecision() throws Exception {
        var created = mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.decision").value("WARN"))
                .andReturn();
        var approval = mapper.readTree(created.getResponse().getContentAsString()).path("approval");
        String id = approval.path("reviewId").asText();
        String token = approval.path("actionToken").asText();
        mvc.perform(action(id, token, "APPROVE", "Reviewed the four skipped tests."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.agentDecision").value("WARN"))
                .andExpect(jsonPath("$.skippedTests").value(4))
                .andExpect(jsonPath("$.reason").value("Reviewed the four skipped tests."));
        verify(buildRunner, times(1)).run("engineering-agent");
        verify(testRunner, times(1)).run("engineering-agent");
        verify(securityRunner, times(1)).run("engineering-agent");
    }

    @Test void unknownReviewIdReturnsNotFound() throws Exception {
        mvc.perform(get("/api/agent/reviews/REV-missing"))
                .andExpect(status().isNotFound());
    }

    @Test void anUnavailableScannerDoesNotBecomeACleanSecurityCheck() throws Exception {
        when(securityRunner.run("engineering-agent")).thenReturn(new SecurityResult(
                "engineering-agent", "INCOMPLETE", false, 0, false,
                List.of(new SecurityResult.ScannerResult("dependencies", "UNAVAILABLE", null,
                        0, 0, 1, "Maven dependencies", "Scanner not installed.")),
                List.of(), 1, "Scanner incomplete."));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.security.complete").value(false))
                .andExpect(jsonPath("$.report.checks[5].status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.report.risk").value("UNASSESSED"))
                .andExpect(jsonPath("$.report.decision").value("WARN"));
    }

    @Test void withholdsCodePreviewsWhenTheSecretScannerHasAMatch() throws Exception {
        String value = "TOKEN_VALUE_FOR_TEST";
        when(sourceFileReader.read(SOURCE_PATH)).thenReturn(new SourceFileReader.SourceFileResult(
                "SUCCESS", SOURCE_PATH, 1, "class New { String token = \"" + value + "\"; }\n",
                false, "scope", "Source read."));
        when(gitDiffReader.read()).thenReturn(diff(DIRECT_DIFF + "+" + value + "\n"));
        when(buildRunner.run("engineering-agent")).thenReturn(new BuildRunner.BuildResult(
                "engineering-agent", "PASS", 0, 1, List.of(value), false, "compile", "Build result."));
        when(testRunner.run("engineering-agent")).thenReturn(new TestRunner.TestResult(
                "engineering-agent", "FAIL", 1, 1, 10, 9, 1, 0, 0, 10,
                List.of(new SurefireReportReader.Failure("engineering-agent", "testSecret",
                        "AssertionError", value)), false, false, "test", "Test result."));
        when(securityRunner.run("engineering-agent")).thenReturn(new SecurityResult(
                "engineering-agent", "FINDINGS", true, 1, false,
                List.of(new SecurityResult.ScannerResult("secrets", "FINDINGS", 1, 1, 0,
                        1, "Working-tree service", "Match needs triage.")),
                List.of(new SecurityResult.SecurityFinding("secrets", "test-secret", "HIGH",
                        SOURCE_PATH, 1, "Potential secret matched; value withheld.",
                        null, null, "SCANNER_MATCH")), 1, "Match needs triage."));
        var result = mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gitDiff.status").value("WITHHELD"))
                .andExpect(jsonPath("$.sourceFile.status").value("WITHHELD"))
                .andExpect(jsonPath("$.build.diagnostics.length()").value(0))
                .andExpect(jsonPath("$.tests.failures.length()").value(0))
                .andExpect(jsonPath("$.report.decision").value("FAIL"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(value);
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).doesNotContain(value);
    }

    @Test void derivesAFailedDecisionFromTestEvidenceEvenWhenModelClaimsSuccess() throws Exception {
        when(testRunner.run("engineering-agent")).thenReturn(new TestRunner.TestResult(
                "engineering-agent", "FAIL", 1, 1, 10, 9, 1, 0, 0, 10,
                List.of(), false, false, "test", "Test result."));
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("""
                {"summary":"All checks passed.","findings":[]}
                """));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis").value("All checks passed."))
                .andExpect(jsonPath("$.report.decision").value("FAIL"))
                .andExpect(jsonPath("$.report.verificationStatus").value("FAIL"))
                .andExpect(jsonPath("$.report.tests[0].failed").value(1));
    }

    @Test void returnsServiceChoicesWithoutLaunchingBuildsWhenSeveralChanged() throws Exception {
        when(changedFilesReader.read()).thenReturn(new ChangedFilesReader.ChangedFilesResult(
                "SUCCESS", 0, 1, List.of(), List.of("auth-service", "engineering-agent"),
                false, false, "scope", "Paths collected."));
        mvc.perform(review(Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_SERVICE"))
                .andExpect(jsonPath("$.changedServices[0]").value("auth-service"))
                .andExpect(jsonPath("$.changedServices[1]").value("engineering-agent"))
                .andExpect(jsonPath("$.build").doesNotExist())
                .andExpect(jsonPath("$.tests").doesNotExist())
                .andExpect(jsonPath("$.analysisStatus").value("SKIPPED"))
                .andExpect(jsonPath("$.report.verificationStatus").value("WARN"))
                .andExpect(jsonPath("$.report.risk").value("UNASSESSED"))
                .andExpect(jsonPath("$.report.tests.length()").value(0))
                .andExpect(jsonPath("$.approval").doesNotExist());
        verifyNoInteractions(buildRunner, testRunner, securityRunner);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test void selectsTheOnlyChangedServiceAutomatically() throws Exception {
        mvc.perform(review(Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("engineering-agent"))
                .andExpect(jsonPath("$.build.status").value("PASS"));
        verify(buildRunner).run("engineering-agent");
    }

    @Test void prefersAnUntrackedJavaFileOverAPomForTheOneSourceRead() throws Exception {
        String pom = "services/engineering-agent/pom.xml";
        var entries = List.of(
                new ChangedFilesReader.ChangedFile(pom, ChangedFilesReader.ChangeStatus.UNTRACKED,
                        ChangedFilesReader.ChangeStatus.UNTRACKED, false, "engineering-agent"),
                new ChangedFilesReader.ChangedFile(SOURCE_PATH, ChangedFilesReader.ChangeStatus.UNTRACKED,
                        ChangedFilesReader.ChangeStatus.UNTRACKED, false, "engineering-agent"));
        when(changedFilesReader.read()).thenReturn(new ChangedFilesReader.ChangedFilesResult(
                "SUCCESS", 0, 1, entries, List.of("engineering-agent"), false, false, "scope", "Paths collected."));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceFile.path").value(SOURCE_PATH));
        verify(sourceFileReader).read(SOURCE_PATH);
        verify(sourceFileReader, never()).read(pom);
    }

    @Test void rejectsInvalidServiceBeforeInspectingRepository() throws Exception {
        mvc.perform(review(Map.of("service", "../auth-service")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose one registered service name, or omit service for automatic selection."));
        verifyNoInteractions(changedFilesReader, gitDiffReader, buildRunner, testRunner, securityRunner);
    }

    @Test void skipsTestsAfterFailedCompileButKeepsTheBuildResult() throws Exception {
        when(buildRunner.run("engineering-agent")).thenReturn(build("engineering-agent", "FAIL"));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.status").value("FAIL"))
                .andExpect(jsonPath("$.tests").doesNotExist())
                .andExpect(jsonPath("$.report.decision").value("FAIL"))
                .andExpect(jsonPath("$.report.verificationStatus").value("FAIL"))
                .andExpect(jsonPath("$.report.tests.length()").value(0))
                .andExpect(jsonPath("$.notes[0]").exists());
        verifyNoInteractions(testRunner);
    }

    @Test void returnsToolEvidenceWhenModelIsUnavailable() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenThrow(new ResourceAccessException("provider detail"));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.status").value("PASS"))
                .andExpect(jsonPath("$.tests.skipped").value(4))
                .andExpect(jsonPath("$.analysis").doesNotExist())
                .andExpect(jsonPath("$.analysisStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.report.tests[0].skipped").value(4))
                .andExpect(jsonPath("$.report.risk").value("UNASSESSED"));
    }

    @Test void KeepsUnrelatedDiffOutOfTheModelPrompt() throws Exception {
        when(gitDiffReader.read()).thenReturn(diff("""
                diff --git a/services/auth-service/pom.xml b/services/auth-service/pom.xml
                +<name>AUTH_ONLY_MARKER</name>
                """ + DIRECT_DIFF));
        mvc.perform(review(Map.of("service", "engineering-agent"))).andExpect(status().isOk());
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).contains("New.java", "<name>New</name>")
                .doesNotContain("AUTH_ONLY_MARKER");
        assertThat(prompt.getValue().getSystemMessage().getText()).contains("untrusted data");
    }

    @Test void skipsModelObservationsWhenThereIsNoSelectedSourceContent() throws Exception {
        when(changedFilesReader.read()).thenReturn(files("engineering-agent", false));
        when(gitDiffReader.read()).thenReturn(diff("""
                diff --git a/pom.xml b/pom.xml
                +<name>ROOT_ONLY</name>
                """));
        mvc.perform(review(Map.of("service", "engineering-agent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisStatus").value("SKIPPED"));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder review(Map<String, String> body)
            throws Exception {
        return post("/api/agent/review").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder action(
            String id, String token, String choice, String reason) throws Exception {
        return post("/api/agent/reviews/{id}/decision", id)
                .header("X-Review-Action-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("action", choice, "reason", reason)));
    }

    private static ChangedFilesReader.ChangedFilesResult files(String service, boolean untracked) {
        var entries = untracked ? List.of(new ChangedFilesReader.ChangedFile(SOURCE_PATH,
                ChangedFilesReader.ChangeStatus.UNTRACKED, ChangedFilesReader.ChangeStatus.UNTRACKED,
                false, service)) : List.<ChangedFilesReader.ChangedFile>of();
        return new ChangedFilesReader.ChangedFilesResult("SUCCESS", 0, 1, entries,
                List.of(service), false, false, "scope", "Paths collected.");
    }

    private static GitDiffReader.GitDiffResult diff(String text) {
        return new GitDiffReader.GitDiffResult("SUCCESS", 0, 1, text, false, false, "scope", "Diff collected.");
    }

    private static BuildRunner.BuildResult build(String service, String status) {
        return new BuildRunner.BuildResult(service, status, status.equals("PASS") ? 0 : 1, 1,
                List.of(), false, "compile", "Build result.");
    }

    private static TestRunner.TestResult tests(String service, String status, int skipped) {
        return new TestRunner.TestResult(service, status, 0, 1, 10, 10 - skipped, 0, 0, skipped,
                10, List.of(), false, false, "test", "Test result.");
    }

    private static SecurityResult cleanSecurity() {
        return new SecurityResult("engineering-agent", "PASS", true, 0, false,
                List.of(new SecurityResult.ScannerResult("sast", "PASS", 0, 0, 0, 1,
                        "Three local rules", "No matches."),
                        new SecurityResult.ScannerResult("secrets", "PASS", 0, 0, 0, 1,
                                "Working-tree service", "No matches."),
                        new SecurityResult.ScannerResult("dependencies", "PASS", 0, 0, 20, 1,
                                "Maven dependencies", "No matches.")),
                List.of(), 1, "Scanner results collected.");
    }

    private static ChatResponse reply(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
