package com.example.platform.agent.service;

import com.example.platform.agent.dto.ReviewReport;
import com.example.platform.agent.dto.SecurityResult;
import com.example.platform.agent.tool.BuildRunner;
import com.example.platform.agent.tool.ChangedFilesReader;
import com.example.platform.agent.tool.GitDiffReader;
import com.example.platform.agent.tool.SourceFileReader;
import com.example.platform.agent.tool.TestRunner;

import java.util.ArrayList;
import java.util.List;

/** Maps tool results to a report without treating model prose as verified evidence. */
final class ReviewReportFactory {
    private ReviewReportFactory() {}

    static ReviewReport create(String service, ChangedFilesReader.ChangedFilesResult files,
                               GitDiffReader.GitDiffResult diff,
                               SourceFileReader.SourceFileResult source,
                               BuildRunner.BuildResult build, TestRunner.TestResult tests,
                               SecurityResult security,
                               List<ReviewReport.Finding> findings, String analysisStatus) {
        var checks = new ArrayList<>(List.of(
                new ReviewReport.Check("changedFiles", files.status(), files.exitCode(), "changedFiles"),
                new ReviewReport.Check("gitDiff", diff.status(), diff.exitCode(), "gitDiff"),
                new ReviewReport.Check("sourceFile", source == null ? "NOT_READ" : source.status(),
                        null, "sourceFile"),
                new ReviewReport.Check("build", build == null ? "NOT_RUN" : build.status(),
                        build == null ? null : build.exitCode(), "build"),
                new ReviewReport.Check("tests", tests == null ? "NOT_RUN" : tests.status(),
                        tests == null ? null : tests.exitCode(), "tests"),
                new ReviewReport.Check("securityScan", security == null ? "NOT_RUN" : security.status(),
                        null, "security"),
                new ReviewReport.Check("aiCodeReview", analysisStatus, null, "analysis")));
        if (security != null) {
            for (int i = 0; i < security.scanners().size(); i++) {
                var scanner = security.scanners().get(i);
                checks.add(new ReviewReport.Check(scanner.name(), scanner.status(), scanner.exitCode(),
                        "security.scanners[" + i + "]"));
            }
        }
        var testEvidence = tests == null ? List.<ReviewReport.TestEvidence>of() : List.of(
                new ReviewReport.TestEvidence(tests.service(), tests.status(), tests.total(),
                        tests.passed(), tests.failed(), tests.errors(), tests.skipped(),
                        tests.incomplete(), "tests"));

        var gaps = new ArrayList<String>();
        var recommendations = new ArrayList<String>();
        if (service == null) {
            gaps.add("No service was selected for build and test checks.");
            recommendations.add("Select one registered service to review.");
        }
        if (!files.status().equals("SUCCESS") || files.truncated()
                || !diff.status().equals("SUCCESS") || diff.truncated()) {
            gaps.add("Git inspection did not provide complete evidence.");
            recommendations.add("Repeat or inspect the incomplete Git operations.");
        }
        if (files.sharedChanges()) {
            gaps.add("The impact of shared-path changes on dependent services was not calculated.");
            recommendations.add("Review services affected by shared-path changes.");
        }
        if (service != null) {
            long directFiles = files.files().stream().filter(file -> service.equals(file.service())).count();
            if (directFiles == 0) {
                gaps.add("No direct selected-service change was listed in the approved Git scope.");
            }
            boolean selectedDiff = diff.status().equals("SUCCESS") && diff.diff().contains(
                    "diff --git a/services/" + service + "/");
            boolean sourceRead = source != null && source.status().equals("SUCCESS");
            if (!selectedDiff && !sourceRead) {
                gaps.add("No selected-service code content was inspected.");
                recommendations.add("Inspect changed code for the selected service.");
            }
            if (source != null && !sourceRead) {
                gaps.add("The selected source read did not succeed.");
            }
            if (directFiles > 1 && sourceRead) {
                gaps.add("Only one untracked source file was read; other changed files need review.");
            }
        }
        if (build == null) {
            gaps.add("Compilation was not run.");
        } else if (!build.status().equals("PASS")) {
            recommendations.add("Investigate the compilation result.");
        }
        if (tests == null) {
            gaps.add("Tests were not run.");
        } else {
            if (!tests.status().equals("PASS") || tests.incomplete()) {
                recommendations.add("Investigate the test result and incomplete reports.");
            }
            if (tests.skipped() > 0) {
                gaps.add(tests.skipped() + " tests were skipped.");
                recommendations.add("Determine why the skipped tests did not run.");
            }
        }
        boolean verifiedFailure = build != null && build.status().equals("FAIL")
                || tests != null && tests.status().equals("FAIL");
        boolean engineeringPass = service != null && gaps.isEmpty()
                && build != null && build.status().equals("PASS")
                && tests != null && tests.status().equals("PASS")
                && !tests.incomplete() && tests.skipped() == 0;
        if (security == null) {
            gaps.add("Security scanning did not run.");
        } else {
            if (!security.complete()) {
                gaps.add("Security scanning is incomplete; inspect each scanner status.");
                recommendations.add("Retry unavailable or incomplete security scanners.");
            }
            if (security.totalFindings() > 0) {
                gaps.add(security.totalFindings() + " scanner matches need human triage.");
                recommendations.add("Investigate matched rules and dependency advisories before approval.");
            }
            if (security.findingsTruncated()) gaps.add("Only the highest-severity scanner matches are shown in the report.");
        }
        gaps.add("Scanner scope is limited; production exposure still needs human assessment.");
        recommendations.add("Assess scanner matches and coverage before a production decision.");
        if (!analysisStatus.equals("AVAILABLE")) {
            gaps.add("Structured AI code review was " + analysisStatus.toLowerCase() + ".");
            if (service != null) recommendations.add("Retry or manually inspect the selected-service code.");
        }
        var verification = verifiedFailure ? ReviewReport.VerificationStatus.FAIL
                : engineeringPass ? ReviewReport.VerificationStatus.PASS
                : ReviewReport.VerificationStatus.WARN;
        var detectedRisk = ReviewReport.RiskLevel.UNASSESSED;
        if (security != null) {
            for (var item : security.findings()) {
                var candidate = switch (item.severity()) {
                    case "CRITICAL" -> ReviewReport.RiskLevel.CRITICAL;
                    case "HIGH" -> ReviewReport.RiskLevel.HIGH;
                    case "MEDIUM" -> ReviewReport.RiskLevel.MEDIUM;
                    case "LOW" -> ReviewReport.RiskLevel.LOW;
                    default -> ReviewReport.RiskLevel.UNASSESSED;
                };
                if (candidate.ordinal() > detectedRisk.ordinal()) detectedRisk = candidate;
            }
        }
        // This is a conservative blocker based on scanner matches, not a claim of exploitability.
        boolean highScannerMatch = detectedRisk == ReviewReport.RiskLevel.HIGH
                || detectedRisk == ReviewReport.RiskLevel.CRITICAL;
        var decision = verifiedFailure || highScannerMatch ? ReviewReport.ReviewDecision.FAIL
                : ReviewReport.ReviewDecision.WARN;
        String summary = verifiedFailure ? "A build or test check failed; investigate before proceeding."
                : highScannerMatch ? "High-severity scanner matches block review until triaged."
                : security != null && security.totalFindings() > 0
                ? "Scanner matches need triage before a production decision."
                : engineeringPass ? "Engineering checks passed; scanner coverage still needs human assessment."
                : "Review evidence is incomplete; inspect the listed gaps.";
        return new ReviewReport(decision, detectedRisk, summary,
                List.copyOf(findings), testEvidence,
                security == null ? 0 : security.totalFindings(),
                security == null ? List.of() : security.findings(),
                List.copyOf(recommendations), List.copyOf(checks),
                List.copyOf(gaps), verification);
    }
}
