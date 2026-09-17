package com.example.platform.agent.dto;

import java.util.List;

/** Sanitized scanner evidence. Secret values and raw scanner output are never returned. */
public record SecurityResult(String service, String status, boolean complete, int totalFindings,
                             boolean findingsTruncated, List<ScannerResult> scanners,
                             List<SecurityFinding> findings, long durationMs, String message) {
    public record ScannerResult(String name, String status, Integer exitCode, int findings,
                                int inspectedPackages, long durationMs, String scope, String message) {}

    public record SecurityFinding(String scanner, String ruleId, String severity, String file,
                                  Integer line, String description, String packageName,
                                  String packageVersion, String evidenceStatus) {}
}
