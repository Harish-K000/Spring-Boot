package com.example.platform.agent;

import com.example.platform.agent.service.AgentChatService;
import com.example.platform.agent.tool.GitDiffReader;
import com.example.platform.agent.tool.SourceFileReader;
import com.example.platform.agent.tool.ChangedFilesReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/** Opt-in verification against a running local Ollama instance and a real model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AGENT_LIVE_OLLAMA_TEST", matches = "true")
class OllamaLiveTest {
    @Autowired private ChatModel chatModel;
    @Autowired private AgentChatService chatService;
    @MockitoSpyBean private GitDiffReader gitDiffReader;
    @MockitoSpyBean private ChangedFilesReader changedFilesReader;
    @MockitoSpyBean private SourceFileReader sourceFileReader;

    @Test
    @Timeout(120)
    void localModelActuallyReadsSourceFile() {
        String path = "services/engineering-agent/src/main/java/com/example/platform/agent/dto/ChatResponse.java";
        String reply = chatService.chat("Call readSourceFile with path " + path + ". Explain what this file contains.");
        verify(sourceFileReader).read(path);
        assertThat(reply).isNotBlank();
    }

    @Test
    @Timeout(120)
    void localModelReturnsText() {
        assertThat(chatModel.call("Reply with a short greeting.")).isNotBlank();
    }

    @Test
    @Timeout(120)
    void localModelActuallyInvokesGitDiff() {
        String reply = chatService.chat("Call getGitDiff now. Briefly summarize the actual changes returned by the tool.");
        verify(gitDiffReader).read();
        assertThat(reply).isNotBlank();
    }

    @Test
    @Timeout(120)
    void localModelActuallyInvokesChangedFiles() {
        String reply = chatService.chat("Call getChangedFiles now. Briefly list the changed services and whether any listed files are untracked.");
        verify(changedFilesReader).read();
        assertThat(reply).isNotBlank();
    }
}
