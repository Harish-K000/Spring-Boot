package com.example.platform.agent.controller;

import org.springframework.context.annotation.Profile;

import com.example.platform.agent.dto.ChatRequest;
import com.example.platform.agent.dto.ChatResponse;
import com.example.platform.agent.service.AgentChatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!mcp-server")
@RequestMapping("/api/agent")
public class AgentChatController {
    private final AgentChatService chatService;

    public AgentChatController(AgentChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return new ChatResponse(chatService.chat(request.message()));
    }

    @ExceptionHandler(AgentChatService.ModelUnavailableException.class)
    public ProblemDetail modelUnavailable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The local model could not respond. Check that Ollama is running and the configured model is installed.");
    }

    @ExceptionHandler(AgentChatService.ToolCallFailedException.class)
    public ProblemDetail toolCallFailed() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The model made an invalid or repeated tool call. Start a new request.");
    }
}
