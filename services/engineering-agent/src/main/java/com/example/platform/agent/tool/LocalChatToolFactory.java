package com.example.platform.agent.tool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent.execution.mode", havingValue = "local")
public class LocalChatToolFactory implements ChatToolFactory {
    private final GitDiffReader diff;
    private final ChangedFilesReader files;
    private final SourceFileReader source;
    private final BuildRunner build;
    private final TestRunner tests;

    public LocalChatToolFactory(GitDiffReader diff, ChangedFilesReader files, SourceFileReader source,
                                BuildRunner build, TestRunner tests) {
        this.diff = diff; this.files = files; this.source = source; this.build = build; this.tests = tests;
    }

    public ChatClient.ChatClientRequestSpec attach(ChatClient.ChatClientRequestSpec prompt,
                                                   AgentSafetyPolicy.Budget budget) {
        return prompt.tools(new GitDiffTool(diff, budget), new ChangedFilesTool(files, budget),
                new SourceFileTool(source, budget), new BuildTool(build, budget), new TestTool(tests, budget));
    }
}
