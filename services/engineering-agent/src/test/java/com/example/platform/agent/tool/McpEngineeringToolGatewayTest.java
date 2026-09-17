package com.example.platform.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpEngineeringToolGatewayTest {
    private static final String[] APPROVED = {"getChangedFiles", "getGitDiff", "readSourceFile",
            "runBuild", "runTests", "securityScan", "getHeadCommit"};

    @Test
    void decodesTheMcpTextEnvelopeForAuditMetadata() {
        ToolCallback[] callbacks = Arrays.stream(APPROVED).map(McpEngineeringToolGatewayTest::named).toArray(ToolCallback[]::new);
        when(callbacks[6].call("{}")).thenReturn("[{\"text\":\"{\\\"commitHash\\\":\\\"abc123\\\"}\"}]");
        McpEngineeringToolGateway gateway = gateway(callbacks);
        assertThat(gateway.headCommit()).isEqualTo("abc123");
    }

    @Test
    void refusesAnUnexpectedServerTool() {
        ToolCallback[] callbacks = Arrays.stream(APPROVED).map(McpEngineeringToolGatewayTest::named).toArray(ToolCallback[]::new);
        ToolCallback[] expanded = Arrays.copyOf(callbacks, callbacks.length + 1);
        expanded[callbacks.length] = named("writeFile");
        assertThatThrownBy(() -> gateway(expanded))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved set");
    }

    @Test
    void rejectsAnIncompleteMcpResult() {
        ToolCallback[] callbacks = Arrays.stream(APPROVED).map(McpEngineeringToolGatewayTest::named).toArray(ToolCallback[]::new);
        when(callbacks[6].call("{}")).thenReturn("[]");
        assertThatThrownBy(() -> gateway(callbacks).headCommit())
                .isInstanceOf(McpEngineeringToolGateway.RemoteToolException.class);
    }

    private static McpEngineeringToolGateway gateway(ToolCallback[] callbacks) {
        SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(callbacks);
        return new McpEngineeringToolGateway(provider, new ObjectMapper());
    }

    private static ToolCallback named(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
