package com.example.platform.agent.controller;

import org.springframework.context.annotation.Profile;

import com.example.platform.agent.dto.ApprovalActionRequest;
import com.example.platform.agent.dto.ApprovalCase;
import com.example.platform.agent.service.ApprovalWorkflow;
import com.example.platform.agent.service.AuditStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

/** Separate operator endpoints; the model has no approval tool or access to an action token. */
@RestController
@Profile("!mcp-server")
@RequestMapping("/api/agent/reviews")
public class ApprovalController {
    private final ApprovalWorkflow workflow;
    private final AuditStore audit;

    public ApprovalController(ApprovalWorkflow workflow, AuditStore audit) {
        this.workflow = workflow;
        this.audit = audit;
    }

    @GetMapping("/{reviewId}")
    public ApprovalCase get(@PathVariable String reviewId) {
        return workflow.get(reviewId);
    }

    @GetMapping("/{reviewId}/audit")
    public AuditStore.AuditTrail audit(@PathVariable String reviewId) {
        return audit.trail(reviewId);
    }

    @PostMapping("/{reviewId}/decision")
    public ApprovalCase decide(@PathVariable String reviewId,
                               @RequestHeader("X-Review-Action-Token") String token,
                               @Valid @RequestBody ApprovalActionRequest request) {
        return workflow.decide(reviewId, token, request);
    }

    @ExceptionHandler(ApprovalWorkflow.ReviewNotFoundException.class)
    public ProblemDetail missingReview() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Review ID was not found in the audit store.");
    }

    @ExceptionHandler(ApprovalWorkflow.InvalidTokenException.class)
    public ProblemDetail invalidToken() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Review action token is invalid.");
    }

    @ExceptionHandler(ApprovalWorkflow.SensitiveReasonException.class)
    public ProblemDetail sensitiveReason() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Write a short reason without credentials or token values.");
    }

    @ExceptionHandler(ApprovalWorkflow.DecisionConflictException.class)
    public ProblemDetail alreadyDecided() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This review already has a human decision.");
    }

    @ExceptionHandler(ApprovalWorkflow.FailedReviewCannotBeApprovedException.class)
    public ProblemDetail blockedApproval() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "A FAIL report cannot be approved; reject it or request changes.");
    }
}
