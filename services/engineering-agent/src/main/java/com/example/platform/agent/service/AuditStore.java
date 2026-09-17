package com.example.platform.agent.service;

import com.example.platform.agent.dto.*;
import com.example.platform.agent.tool.BuildRunner;
import com.example.platform.agent.tool.EngineeringToolGateway;
import com.example.platform.agent.tool.TestRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable, compact audit. Callers pass only allowlisted metadata, never tool output or code. */
@Service
@Profile("!mcp-server")
public class AuditStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EngineeringToolGateway tools;
    private final String model;

    public AuditStore(JdbcTemplate jdbc, PlatformTransactionManager manager, EngineeringToolGateway tools,
                      @Value("${spring.ai.ollama.chat.options.model}") String model) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.tools = tools;
        this.model = model;
    }

    public String startReview(String service) {
        String id = "REV-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_reviews(review_id, commit_hash, model, started_at, service, flow_status)
                VALUES (?, ?, ?, ?, ?, 'STARTED')
                """, id, tools.headCommit(), model, Timestamp.from(Instant.now()), service);
        return id;
    }

    public void tool(String reviewId, String tool, String input, String status, long durationMs) {
        // The caller supplies fixed tool names and a registered service or scope label only.
        jdbc.update("""
                INSERT INTO agent_tool_executions(review_id, tool, input, status, duration_ms, executed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, reviewId, tool, input, status, Math.max(0, durationMs), Timestamp.from(Instant.now()));
    }

    public void complete(String reviewId, String flowStatus, String service, ReviewReport report,
                         BuildRunner.BuildResult build, TestRunner.TestResult tests, SecurityResult security,
                         String recommendedAction, byte[] tokenHash) {
        int changed = jdbc.update("""
                UPDATE agent_reviews SET completed_at=?, service=?, flow_status=?, decision=?, risk_level=?,
                    build_status=?, tests_status=?, skipped_tests=?, security_status=?, security_matches=?,
                    approval_status=?, recommended_action=?, action_token_hash=?
                WHERE review_id=? AND flow_status='STARTED'
                """, Timestamp.from(Instant.now()), service, flowStatus,
                report.decision().name(), report.risk().name(),
                build == null ? "NOT_RUN" : build.status(), tests == null ? "NOT_RUN" : tests.status(),
                tests == null ? 0 : tests.skipped(), security == null ? "NOT_RUN" : security.status(),
                security == null ? 0 : security.totalFindings(),
                recommendedAction == null ? null : "WAITING_FOR_APPROVAL", recommendedAction,
                tokenHash, reviewId);
        if (changed != 1) throw new IllegalStateException("Audit review could not be completed");
    }

    public ApprovalCase approvalCase(String reviewId) {
        List<ApprovalCase> rows = jdbc.query("""
                SELECT * FROM agent_reviews WHERE review_id=? AND approval_status IS NOT NULL
                """, (rs, n) -> approval(rs), reviewId);
        if (rows.isEmpty()) throw new ApprovalWorkflow.ReviewNotFoundException();
        return rows.getFirst();
    }

    public ApprovalCase decide(String reviewId, byte[] presentedHash, ApprovalAction action, String reason) {
        return transactions.execute(tx -> {
            List<LockedCase> rows = jdbc.query("""
                    SELECT * FROM agent_reviews WHERE review_id=? AND approval_status IS NOT NULL FOR UPDATE
                    """, (rs, n) -> new LockedCase(approval(rs), rs.getBytes("action_token_hash")), reviewId);
            if (rows.isEmpty()) throw new ApprovalWorkflow.ReviewNotFoundException();
            LockedCase locked = rows.getFirst();
            if (!MessageDigest.isEqual(locked.tokenHash(), presentedHash)) {
                throw new ApprovalWorkflow.InvalidTokenException();
            }
            ApprovalCase before = locked.review();
            if (!"WAITING_FOR_APPROVAL".equals(before.status())) {
                throw new ApprovalWorkflow.DecisionConflictException();
            }
            if (action == ApprovalAction.APPROVE
                    && before.agentDecision() == ReviewReport.ReviewDecision.FAIL) {
                throw new ApprovalWorkflow.FailedReviewCannotBeApprovedException();
            }
            String state = switch (action) {
                case APPROVE -> "APPROVED";
                case REJECT -> "REJECTED";
                case REQUEST_CHANGES -> "CHANGES_REQUESTED";
            };
            Instant at = Instant.now();
            int changed = jdbc.update("""
                    UPDATE agent_reviews SET approval_status=?, action=?, reason=?, decided_at=?
                    WHERE review_id=? AND approval_status='WAITING_FOR_APPROVAL'
                    """, state, action.name(), reason, Timestamp.from(at), reviewId);
            if (changed != 1) throw new ApprovalWorkflow.DecisionConflictException();
            jdbc.update("""
                    INSERT INTO agent_review_actions(review_id, action, decided_at, reviewer_identity_status)
                    VALUES (?, ?, ?, 'NOT_AUTHENTICATED')
                    """, reviewId, action.name(), Timestamp.from(at));
            return approvalCase(reviewId);
        });
    }

    public ReviewAudit audit(String reviewId) {
        List<ReviewAudit> rows = jdbc.query("""
                SELECT review_id, commit_hash, model, started_at, completed_at, service, flow_status,
                       decision, risk_level, approval_status, action, decided_at
                FROM agent_reviews WHERE review_id=?
                """, (rs, n) -> new ReviewAudit(rs.getString("review_id"), rs.getString("commit_hash"),
                rs.getString("model"), instant(rs, "started_at"), instant(rs, "completed_at"),
                rs.getString("service"), rs.getString("flow_status"), rs.getString("decision"),
                rs.getString("risk_level"), rs.getString("approval_status"), rs.getString("action"),
                instant(rs, "decided_at")), reviewId);
        if (rows.isEmpty()) throw new ApprovalWorkflow.ReviewNotFoundException();
        return rows.getFirst();
    }

    public AuditTrail trail(String reviewId) {
        ReviewAudit review = audit(reviewId);
        List<ToolAudit> tools = jdbc.query("""
                SELECT tool, input, status, duration_ms, executed_at FROM agent_tool_executions
                WHERE review_id=? ORDER BY execution_id
                """, (rs, n) -> new ToolAudit(rs.getString("tool"), rs.getString("input"),
                rs.getString("status"), rs.getLong("duration_ms"), instant(rs, "executed_at")), reviewId);
        List<ActionAudit> actions = jdbc.query("""
                SELECT action, decided_at, reviewer_identity_status FROM agent_review_actions
                WHERE review_id=? ORDER BY action_id
                """, (rs, n) -> new ActionAudit(rs.getString("action"), instant(rs, "decided_at"),
                rs.getString("reviewer_identity_status")), reviewId);
        return new AuditTrail(review, tools, actions);
    }

    private static ApprovalCase approval(ResultSet rs) throws SQLException {
        return new ApprovalCase(rs.getString("review_id"), rs.getString("approval_status"),
                rs.getString("service"), ReviewReport.ReviewDecision.valueOf(rs.getString("decision")),
                ReviewReport.RiskLevel.valueOf(rs.getString("risk_level")), rs.getString("build_status"),
                rs.getString("tests_status"), rs.getInt("skipped_tests"), rs.getString("security_status"),
                rs.getInt("security_matches"), rs.getString("recommended_action"),
                rs.getString("action") == null ? null : ApprovalAction.valueOf(rs.getString("action")),
                rs.getString("reason"), instant(rs, "completed_at"), instant(rs, "decided_at"),
                rs.getString("reviewer_identity_status"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }

    private record LockedCase(ApprovalCase review, byte[] tokenHash) {}
    public record ReviewAudit(String reviewId, String commitHash, String model, Instant startedAt,
                              Instant completedAt, String service, String flowStatus, String decision,
                              String riskLevel, String approvalStatus, String action, Instant decidedAt) {}
    public record ToolAudit(String tool, String input, String status, long durationMs, Instant timestamp) {}
    public record ActionAudit(String action, Instant decidedAt, String reviewerIdentityStatus) {}
    public record AuditTrail(ReviewAudit review, List<ToolAudit> toolExecutions, List<ActionAudit> actions) {}
}
