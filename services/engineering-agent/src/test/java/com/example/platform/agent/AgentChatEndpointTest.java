package com.example.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.platform.agent.tool.AgentSafetyPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controller, validation, service and ChatClient; substitutes only the model. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AgentChatEndpointTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AgentSafetyPolicy safetyPolicy;
    @MockitoBean private ChatModel chatModel;

    @BeforeEach
    void configureModel() {
        when(chatModel.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
    }

    @Test
    void returnsModelTextAndKeepsSystemAndUserMessagesSeparate() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("An outbox stores events in the same transaction."));
        String message = "Explain transactional outbox with JSON {\"event\":\"created\"}.";
        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("message", message))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("An outbox stores events in the same transaction."));
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions()).hasSize(2);
        assertThat(prompt.getValue().getUserMessage().getText()).isEqualTo(message);
        assertThat(prompt.getValue().getSystemMessage().getText()).contains("engineering learning assistant");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"message\":null}", "{\"message\":\"\"}", "{\"message\":\"   \"}", "{"})
    void rejectsInvalidRequestsBeforeCallingModel(String body) throws Exception {
        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void rejectsMessagesOverTheLimit() throws Exception {
        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("message", "x".repeat(4001)))))
                .andExpect(status().isBadRequest());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void returns503WithoutExposingProviderErrorDetails() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenThrow(new ResourceAccessException("Internal provider detail"));
        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("The local model could not respond. Check that Ollama is running and the configured model is installed."));
    }

    @Test
    void rejectsEmptyModelResponses() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(reply(""));
        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Hello\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void rejectsOversizedModelResponses() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("x".repeat(8_001)));
        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void doesNotCarryConversationHistoryBetweenRequests() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(reply("Hello"));
        for (String message : List.of("First question", "Second question")) {
            mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("message", message))))
                    .andExpect(status().isOk());
        }
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getInstructions()).hasSize(2);
        assertThat(prompts.getAllValues().get(1).getUserMessage().getText()).isEqualTo("Second question");
    }

    @Test
    void busyReviewPreventsChatModelOrToolsFromStarting() throws Exception {
        try (var permit = safetyPolicy.enter(AgentSafetyPolicy.Mode.REVIEW)) {
            mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"Review changes\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.detail").value(
                            "Another agent request is active. Retry after it finishes."));
        }
        verify(chatModel, never()).call(any(Prompt.class));
    }

    private static ChatResponse reply(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
