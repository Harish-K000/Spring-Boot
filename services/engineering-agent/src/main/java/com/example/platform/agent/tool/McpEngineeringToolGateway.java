package com.example.platform.agent.tool;

import com.example.platform.agent.dto.SecurityResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Production agent calls the allowlisted server tools over MCP only. */
@Component
@ConditionalOnProperty(name = "agent.execution.mode", havingValue = "mcp")
public class McpEngineeringToolGateway implements EngineeringToolGateway {
    private static final Set<String> REQUIRED = Set.of("getChangedFiles", "getGitDiff", "readSourceFile",
            "runBuild", "runTests", "securityScan", "getHeadCommit");
    private final Map<String, ToolCallback> callbacks;
    private final ObjectMapper json;

    public McpEngineeringToolGateway(SyncMcpToolCallbackProvider provider, ObjectMapper json) {
        this.callbacks = java.util.Arrays.stream(provider.getToolCallbacks())
                .collect(Collectors.toUnmodifiableMap(c -> c.getToolDefinition().name(), Function.identity()));
        if (!callbacks.keySet().equals(REQUIRED)) {
            throw new IllegalStateException("The engineering MCP server tool set differs from the approved set");
        }
        this.json = json;
    }

    public ToolCallback callback(String name) { return callbacks.get(name); }

    public ChangedFilesReader.ChangedFilesResult changedFiles() {
        return call("getChangedFiles", "{}", ChangedFilesReader.ChangedFilesResult.class);
    }
    public GitDiffReader.GitDiffResult gitDiff() {
        return call("getGitDiff", "{}", GitDiffReader.GitDiffResult.class);
    }
    public SourceFileReader.SourceFileResult sourceFile(String path) {
        return call("readSourceFile", args("path", path), SourceFileReader.SourceFileResult.class);
    }
    public BuildRunner.BuildResult build(String service) {
        return call("runBuild", args("service", ToolExecutionPolicy.serviceName(service)), BuildRunner.BuildResult.class);
    }
    public TestRunner.TestResult tests(String service) {
        return call("runTests", args("service", ToolExecutionPolicy.serviceName(service)), TestRunner.TestResult.class);
    }
    public SecurityResult security(String service) {
        return call("securityScan", args("service", ToolExecutionPolicy.serviceName(service)), SecurityResult.class);
    }
    public String headCommit() {
        return call("getHeadCommit", "{}", EngineeringMcpTools.HeadCommitResult.class).commitHash();
    }

    private String args(String key, String value) {
        try { return json.writeValueAsString(Map.of(key, value)); }
        catch (IOException ex) { throw new IllegalStateException("Could not encode approved MCP arguments", ex); }
    }

    private <T> T call(String name, String arguments, Class<T> type) {
        try {
            JsonNode content = json.readTree(callbacks.get(name).call(arguments));
            if (!content.isArray() || content.size() != 1 || !content.get(0).path("text").isTextual()) {
                throw new RemoteToolException();
            }
            String body = content.get(0).path("text").asText();
            return json.readValue(body, type);
        } catch (IOException | ToolExecutionException ex) {
            throw new RemoteToolException(ex);
        }
    }

    public static class RemoteToolException extends RuntimeException {
        public RemoteToolException() { super("The approved MCP tool did not return usable evidence"); }
        public RemoteToolException(Throwable cause) { super("The approved MCP tool did not return usable evidence", cause); }
    }
}
