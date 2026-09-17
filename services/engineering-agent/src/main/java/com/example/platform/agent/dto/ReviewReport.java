package com.example.platform.agent.dto;

import java.util.List;

/** A bounded, evidence-based review summary. Production risk remains unassessed in this step. */
public record ReviewReport(ReviewDecision decision, RiskLevel risk, String summary,
                           List<Finding> findings, List<TestEvidence> tests,
                           int securityTotalFindings, List<SecurityResult.SecurityFinding> securityFindings,
                           List<String> recommendations, List<Check> checks,
                           List<String> evidenceGaps, VerificationStatus verificationStatus) {
    public enum ReviewDecision { PASS, WARN, FAIL }
    public enum RiskLevel { UNASSESSED, LOW, MEDIUM, HIGH, CRITICAL }
    public enum VerificationStatus { PASS, WARN, FAIL }

    /** A model hypothesis tied to a line actually present in inspected evidence. */
    public record Finding(String severity, String category, String file, Integer line,
                          String description, String recommendation, String evidenceQuote,
                          String evidenceField, String evidenceStatus) {}

    public record TestEvidence(String service, String status, int total, int passed,
                               int failed, int errors, int skipped, boolean incomplete,
                               String evidenceField) {}

    public record Check(String name, String status, Integer exitCode, String evidenceField) {}
}
