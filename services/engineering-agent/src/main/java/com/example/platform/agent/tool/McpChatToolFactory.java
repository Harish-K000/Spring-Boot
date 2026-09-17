package com.example.platform.agent.tool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The model sees five MCP tools; each call claims the local request budget first. */
@Component
@ConditionalOnProperty(name = "agent.execution.mode", havingValue = "mcp")
public class McpChatToolFactory implements ChatToolFactory {
    private final McpEngineeringToolGateway gateway;

    public McpChatToolFactory(McpEngineeringToolGateway gateway) { this.gateway = gateway; }

    public ChatClient.ChatClientRequestSpec attach(ChatClient.ChatClientRequestSpec prompt,
                                                   AgentSafetyPolicy.Budget budget) {
        return prompt.toolCallbacks(
                budgeted("getGitDiff", AgentSafetyPolicy.Capability.GIT_DIFF, budget),
                budgeted("getChangedFiles", AgentSafetyPolicy.Capability.CHANGED_FILES, budget),
                budgeted("readSourceFile", AgentSafetyPolicy.Capability.SOURCE_READ, budget),
                budgeted("runBuild", AgentSafetyPolicy.Capability.COMPILE, budget),
                budgeted("runTests", AgentSafetyPolicy.Capability.TEST, budget));
    }

    private ToolCallback budgeted(String name, AgentSafetyPolicy.Capability capability,
                                  AgentSafetyPolicy.Budget budget) {
        ToolCallback remote = gateway.callback(name);
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return remote.getToolDefinition(); }
            public ToolMetadata getToolMetadata() { return remote.getToolMetadata(); }
            public String call(String input) {
                budget.claim(capability);
                return remote.call(input);
            }
        };
    }
}
