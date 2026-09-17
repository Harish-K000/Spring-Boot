package com.example.platform.agent.controller;

import com.example.platform.agent.tool.AgentSafetyPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.context.annotation.Profile;

/** A second request never starts tools while an agent request is active. */
@RestControllerAdvice
@Profile("!mcp-server")
public class AgentSafetyControllerAdvice {
    @ExceptionHandler(AgentSafetyPolicy.RequestBusyException.class)
    public ProblemDetail busy() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Another agent request is active. Retry after it finishes.");
    }

    @ExceptionHandler(AgentSafetyPolicy.DeniedCapabilityException.class)
    public ProblemDetail denied() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "The requested agent capability is not available in V1.");
    }
}
