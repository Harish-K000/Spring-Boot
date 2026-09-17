package com.example.platform.agent.service;

import com.example.platform.agent.dto.*;
import com.example.platform.agent.tool.BuildRunner;
import com.example.platform.agent.tool.TestRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Local human-choice gate, backed by the durable audit store. */
@Service
@Profile("!mcp-server")
public class ApprovalWorkflow {
    private static final Pattern SENSITIVE_REASON = Pattern.compile(
            "(?i)(?:password|passwd|secret|api[_-]?key|authorization|credential|access[_-]?token|refresh[_-]?token|private[_-]?key)\\s*[:=]\\s*\\S+"
                    + "|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}"
                    + "|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|-----BEGIN [^-]*PRIVATE KEY-----");
    private final AuditStore audit;
    private final SecureRandom random = new SecureRandom();

    public ApprovalWorkflow(AuditStore audit) {
        this.audit = audit;
    }

    public ApprovalInvitation open(String reviewId, String service, ReviewReport report,
                                   BuildRunner.BuildResult build, TestRunner.TestResult tests,
                                   SecurityResult security) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String recommended = report.decision() == ReviewReport.ReviewDecision.FAIL
                ? "REQUEST_CHANGES" : "HUMAN_REVIEW";
        audit.complete(reviewId, "EVIDENCE_COLLECTED", service, report, build, tests, security,
                recommended, hash(token));
        return new ApprovalInvitation(reviewId, "WAITING_FOR_APPROVAL", recommended, token);
    }

    public ApprovalCase get(String reviewId) {
        return audit.approvalCase(reviewId);
    }

    public ApprovalCase decide(String reviewId, String token, ApprovalActionRequest request) {
        if (token == null || token.isBlank() || token.length() > 200) throw new InvalidTokenException();
        if (request == null || request.action() == null || request.reason() == null
                || request.reason().isBlank() || request.reason().length() > 500) {
            throw new IllegalArgumentException("Action and a short reason are required.");
        }
        if (SENSITIVE_REASON.matcher(request.reason()).find()) throw new SensitiveReasonException();
        return audit.decide(reviewId, hash(token), request.action(), request.reason().trim());
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    public static class ReviewNotFoundException extends RuntimeException {}
    public static class InvalidTokenException extends RuntimeException {}
    public static class DecisionConflictException extends RuntimeException {}
    public static class FailedReviewCannotBeApprovedException extends RuntimeException {}
    public static class SensitiveReasonException extends RuntimeException {}
}
