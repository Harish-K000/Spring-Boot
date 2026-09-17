package com.example.platform.agent;

import com.example.platform.agent.service.AgentChatService;
import com.example.platform.agent.service.CodeReviewAgent;
import com.example.platform.agent.dto.SecurityResult;
import com.example.platform.agent.tool.SecurityRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Real Ollama adapter and tool dispatch, with deterministic HTTP responses instead of a model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class OllamaConfigurationTest {
    private static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
    private static volatile boolean repeatedCall;
    private static volatile List<String> toolSequence = List.of("getGitDiff");
    private static volatile String sourcePath = "Example.java";
    private static volatile String buildService = "auth-service";
    private static final Path REPOSITORY = createFixtureRepository();
    private static final HttpServer OLLAMA = startOllamaStub();
    @Autowired private ChatModel chatModel;
    @Autowired private ChatClient.Builder chatClientBuilder;
    @Autowired private AgentChatService chatService;
    @Autowired private CodeReviewAgent reviewAgent;
    @MockitoBean private SecurityRunner securityRunner;

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
        registry.add("spring.ai.ollama.chat.options.model", () -> "test-local-model");
        registry.add("agent.repository", REPOSITORY::toString);
    }

    @BeforeEach void resetRequests() {
        REQUESTS.clear(); repeatedCall = false; toolSequence = List.of("getGitDiff");
        sourcePath = "Example.java"; buildService = "auth-service";
        when(securityRunner.run("auth-service")).thenReturn(new SecurityResult("auth-service", "PASS",
                true, 0, false, List.of(), List.of(), 1, "Fixture scan."));
    }
    @AfterAll
    static void cleanup() throws IOException {
        OLLAMA.stop(0);
        try (var paths = Files.walk(REPOSITORY)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test
    void sendsConfiguredModelAndPromptThroughTheOllamaAdapter() {
        assertThat(chatModel.call("Say hello.")).isEqualTo("Hello from the test model.");
        assertThat(chatClientBuilder).isNotNull();
        assertThat(REQUESTS.getFirst().path("model").asText()).isEqualTo("test-local-model");
        assertThat(REQUESTS.getFirst().path("options").path("num_predict").asInt()).isEqualTo(512);
        assertThat(REQUESTS.getFirst().path("messages").get(0).path("content").asText()).isEqualTo("Say hello.");
        assertThat(REQUESTS.getFirst().path("stream").asBoolean()).isFalse();
    }

    @Test
    void sendsToolDefinitionAndReturnsRealGitEvidenceToTheModel() {
        assertThat(chatService.chat("Inspect current changes.")).isEqualTo("Hello from the test model.");
        assertThat(REQUESTS).hasSize(2);
        assertThat(REQUESTS.getFirst().path("tools").toString()).contains("getGitDiff", "getChangedFiles");
        JsonNode toolMessage = null;
        for (JsonNode message : REQUESTS.get(1).path("messages")) {
            if (message.path("role").asText().equals("tool")) toolMessage = message;
        }
        assertThat(toolMessage).isNotNull();
        assertThat(toolMessage.path("content").asText()).contains("SUCCESS", "Tracked Java", "exitCode");
    }

    @Test
    void stopsWhenModelRepeatsToolCall() {
        repeatedCall = true;
        assertThatThrownBy(() -> chatService.chat("Inspect changes."))
                .isInstanceOf(AgentChatService.ToolCallFailedException.class);
        assertThat(REQUESTS).hasSize(2);
    }

    @Test
    void dispatchesBothToolsAndSuppliesEachResultToTheModel() {
        toolSequence = List.of("getChangedFiles", "getGitDiff");
        assertThat(chatService.chat("Inspect files and their changes.")).isEqualTo("Hello from the test model.");
        assertThat(REQUESTS).hasSize(3);
        String messages = REQUESTS.getLast().path("messages").toString();
        assertThat(messages).contains("changedServices", "indexStatus", "workTreeStatus", "Tracked Java");
    }

    @Test
    void stopsWhenModelRepeatsChangedFilesCall() {
        toolSequence = List.of("getChangedFiles");
        repeatedCall = true;
        assertThatThrownBy(() -> chatService.chat("List changed files."))
                .isInstanceOf(AgentChatService.ToolCallFailedException.class);
        assertThat(REQUESTS).hasSize(2);
    }

    @Test
    void dispatchesSourcePathAndReturnsFileContent() {
        toolSequence = List.of("getChangedFiles", "getGitDiff", "readSourceFile");
        assertThat(chatService.chat("Inspect Example.java.")).isEqualTo("Hello from the test model.");
        assertThat(REQUESTS).hasSize(4);
        assertThat(REQUESTS.getLast().path("messages").toString()).contains("class After {}", "Source file read");
        JsonNode definition = null;
        for (JsonNode tool : REQUESTS.getFirst().path("tools")) {
            if (tool.path("function").path("name").asText().equals("readSourceFile")) definition = tool.path("function");
        }
        assertThat(definition).isNotNull();
        assertThat(definition.path("parameters").path("required").toString()).contains("path");
    }

    @Test
    void suppliesDenialAsEvidenceWithoutReadingOutsideRepository() {
        toolSequence = List.of("readSourceFile");
        sourcePath = "../Outside.java";
        chatService.chat("Read the supplied path.");
        assertThat(REQUESTS).hasSize(2);
        assertThat(REQUESTS.getLast().path("messages").toString()).contains("DENIED", "approved source-file policy");
    }

    @Test
    void stopsRepeatedSourceReads() {
        toolSequence = List.of("readSourceFile");
        repeatedCall = true;
        assertThatThrownBy(() -> chatService.chat("Read Example.java."))
                .isInstanceOf(AgentChatService.ToolCallFailedException.class);
        assertThat(REQUESTS).hasSize(2);
    }

    @Test
    void dispatchesApprovedBuildAndReturnsProcessEvidenceToTheModel() {
        toolSequence = List.of("runBuild");
        assertThat(chatService.chat("Compile auth-service.")).isEqualTo("Hello from the test model.");
        assertThat(REQUESTS).hasSize(2);
        assertThat(REQUESTS.getFirst().path("tools").toString()).contains("runBuild", "service");
        assertThat(REQUESTS.getLast().path("messages").toString())
                .contains("PASS", "exitCode", "auth-service", "tests were not run");
    }

    @Test
    void returnsBuildDenialAsEvidence() {
        toolSequence = List.of("runBuild");
        buildService = "../auth-service";
        chatService.chat("Compile the supplied service.");
        assertThat(REQUESTS.getLast().path("messages").toString()).contains("DENIED");
    }

    @Test
    void stopsRepeatedBuildCalls() {
        toolSequence = List.of("runBuild");
        repeatedCall = true;
        assertThatThrownBy(() -> chatService.chat("Compile auth-service twice."))
                .isInstanceOf(AgentChatService.ToolCallFailedException.class);
        assertThat(REQUESTS).hasSize(2);
    }

    @Test
    void dispatchesApprovedTestsAndSuppliesFreshCountsToTheModel() {
        toolSequence = List.of("runTests");
        assertThat(chatService.chat("Test auth-service.")).isEqualTo("Hello from the test model.");
        assertThat(REQUESTS).hasSize(2);
        assertThat(REQUESTS.getFirst().path("tools").toString()).contains("runTests", "service");
        assertThat(REQUESTS.getLast().path("messages").toString())
                .contains("PASS", "total", "passed", "failed", "auth-service");
    }

    @Test
    void stopsRepeatedTestCalls() {
        toolSequence = List.of("runTests");
        repeatedCall = true;
        assertThatThrownBy(() -> chatService.chat("Run tests twice."))
                .isInstanceOf(AgentChatService.ToolCallFailedException.class);
        assertThat(REQUESTS).hasSize(2);
    }

    @Test
    void reviewFlowRunsFixedEvidenceSequenceAndCallsModelWithoutToolDefinitions() {
        var result = reviewAgent.review("auth-service");
        assertThat(result.status()).isEqualTo("EVIDENCE_COLLECTED");
        assertThat(result.service()).isEqualTo("auth-service");
        assertThat(result.sourceFile()).isNotNull();
        assertThat(result.sourceFile().content()).contains("class ReviewExample");
        assertThat(result.build().status()).isEqualTo("PASS");
        assertThat(result.tests().status()).isEqualTo("PASS");
        assertThat(result.tests().total()).isEqualTo(2);
        assertThat(result.analysisStatus()).isEqualTo("AVAILABLE");
        assertThat(REQUESTS).hasSize(1);
        assertThat(REQUESTS.getFirst().path("tools").isMissingNode()
                || REQUESTS.getFirst().path("tools").isEmpty()).isTrue();
        assertThat(REQUESTS.getFirst().path("messages").toString()).contains("ReviewExample.java", "untrusted data");
    }

    private static Path createFixtureRepository() {
        try {
            Path root = Files.createTempDirectory("agent-tool-dispatch-");
            Files.writeString(root.resolve("Example.java"), "class Before {}\n");
            Files.writeString(root.resolve("pom.xml"), "<project/>\n");
            Files.createDirectories(root.resolve("services/auth-service"));
            Files.writeString(root.resolve("services/auth-service/pom.xml"), "<project/>\n");
            Files.writeString(root.resolve("mvnw"), """
                    #!/bin/sh
                    if [ "$6" = test ]; then
                      mkdir -p services/auth-service/target/surefire-reports
                      echo '<testsuite tests="2" failures="0" errors="0" skipped="0" />' > services/auth-service/target/surefire-reports/TEST-Fixture.xml
                    fi
                    echo '[INFO] BUILD SUCCESS'
                    """);
            if (!root.resolve("mvnw").toFile().setExecutable(true)) throw new IOException("Cannot make fixture wrapper executable");
            for (List<String> operation : List.of(List.of("init", "-q"), List.of("add", "."), List.of("commit", "-qm", "fixture"))) {
                var command = new ArrayList<>(List.of("git", "-C", root.toString(), "-c", "core.hooksPath=/dev/null",
                        "-c", "commit.gpgsign=false", "-c", "user.name=Test", "-c", "user.email=test@example.invalid"));
                command.addAll(operation);
                var builder = new ProcessBuilder(command).redirectErrorStream(true);
                builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
                builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
                var process = builder.start();
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    process.destroyForcibly();
                    throw new IOException("Cannot prepare the temporary Git fixture");
                }
            }
            Files.writeString(root.resolve("Example.java"), "class After {}\n");
            Files.writeString(root.resolve("services/auth-service/ReviewExample.java"), "class ReviewExample {}\n");
            return root;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git fixture creation interrupted", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create Git fixture", ex);
        }
    }

    private static HttpServer startOllamaStub() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/chat", exchange -> {
                try (exchange) {
                    JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody());
                    REQUESTS.add(request);
                    boolean toolCall = request.path("tools").size() > 0 && (REQUESTS.size() <= toolSequence.size() || repeatedCall);
                    String toolName = toolSequence.get(Math.min(REQUESTS.size() - 1, toolSequence.size() - 1));
                    String arguments = toolName.equals("readSourceFile")
                            ? new ObjectMapper().writeValueAsString(java.util.Map.of("path", sourcePath))
                            : toolName.equals("runBuild") || toolName.equals("runTests")
                            ? new ObjectMapper().writeValueAsString(java.util.Map.of("service", buildService)) : "{}";
                    String content = request.path("messages").toString().contains("TRACKED_DIFF_DATA_BEGIN")
                            ? "{\"summary\":\"No line-linked concern found.\",\"findings\":[]}"
                            : "Hello from the test model.";
                    String message = toolCall
                            ? "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"" + toolName + "\",\"arguments\":" + arguments + "}}]}"
                            : new ObjectMapper().writeValueAsString(java.util.Map.of("role", "assistant", "content", content));
                    byte[] response = ("{\"model\":\"test-local-model\",\"created_at\":\"2026-09-15T12:00:00Z\",\"message\":"
                            + message + ",\"done\":true,\"done_reason\":\"stop\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot start Ollama stub", ex);
        }
    }
}
