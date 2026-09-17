package com.example.platform.agent.controller;

import org.springframework.context.annotation.Profile;

import com.example.platform.agent.dto.ReviewRequest;
import com.example.platform.agent.dto.ReviewResponse;
import com.example.platform.agent.service.CodeReviewAgent;
import com.example.platform.agent.tool.McpEngineeringToolGateway;
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
public class AgentReviewController {
    private final CodeReviewAgent reviewAgent;

    public AgentReviewController(CodeReviewAgent reviewAgent) { this.reviewAgent = reviewAgent; }

    @PostMapping("/review")
    public ReviewResponse review(@Valid @RequestBody ReviewRequest request) {
        return reviewAgent.review(request.service());
    }

    @ExceptionHandler(CodeReviewAgent.InvalidServiceException.class)
    public ProblemDetail invalidService() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Choose one registered service name, or omit service for automatic selection.");
    }

    @ExceptionHandler(McpEngineeringToolGateway.RemoteToolException.class)
    public ProblemDetail remoteToolUnavailable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The approved engineering tool server did not return usable evidence. Check the local MCP server.");
    }
}
