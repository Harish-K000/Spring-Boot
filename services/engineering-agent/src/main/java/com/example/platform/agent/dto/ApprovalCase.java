package com.example.platform.agent.dto;

import java.time.Instant;

/** Compact durable approval state; it does not contain code, secrets or an action token. */
public record ApprovalCase(String reviewId, String status, String service,
                           ReviewReport.ReviewDecision agentDecision, ReviewReport.RiskLevel risk,
                           String buildStatus, String testsStatus, int skippedTests,
                           String securityStatus, int securityMatches, String recommendedAction,
                           ApprovalAction action, String reason, Instant createdAt, Instant decidedAt,
                           String reviewerIdentityStatus) {}
