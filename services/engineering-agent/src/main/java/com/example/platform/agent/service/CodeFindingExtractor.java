package com.example.platform.agent.service;

import com.example.platform.agent.dto.ReviewReport;
import com.example.platform.agent.tool.SourceFileReader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Accepts only bounded model hypotheses that cite a visible line of selected-service code. */
final class CodeFindingExtractor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> CATEGORIES = Set.of("AUTHENTICATION", "AUTHORIZATION", "SQL_QUERY",
            "NULL_HANDLING", "EXCEPTION_HANDLING", "CONCURRENCY", "TRANSACTION", "VALIDATION",
            "SENSITIVE_LOGGING", "API_CONTRACT", "IDEMPOTENCY", "ERROR_HANDLING", "PERFORMANCE");
    private static final int MAX_OUTPUT = 12_000;
    private static final int MAX_FINDINGS = 3;

    private CodeFindingExtractor() {}

    record Result(String status, String summary, List<ReviewReport.Finding> findings, int rejectedCount) {}
    private record EvidenceLine(String file, int line, String text, String field) {}

    static Result parse(String raw, String service, String diffPreview,
                        SourceFileReader.SourceFileResult source, String sourcePreview) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_OUTPUT) return malformed();
        final JsonNode root;
        try {
            String trimmed = raw.trim();
            if (trimmed.startsWith("```")) {
                int newline = trimmed.indexOf('\n');
                if (newline < 0 || !trimmed.endsWith("```")) return malformed();
                trimmed = trimmed.substring(newline + 1, trimmed.length() - 3).trim();
            }
            root = JSON.readTree(trimmed);
        } catch (JsonProcessingException ex) {
            return malformed();
        }
        if (!root.isObject() || !root.path("findings").isArray()) return malformed();
        String summary = boundedText(root.path("summary"), 600);
        if (summary == null) return malformed();

        var visibleLines = new ArrayList<EvidenceLine>();
        addDiffLines(visibleLines, service, diffPreview);
        if (source != null && source.status().equals("SUCCESS") && source.path() != null) {
            String[] lines = sourcePreview.split("\\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].isBlank() && !lines[i].contains("[REDACTED")) visibleLines.add(new EvidenceLine(source.path(), i + 1,
                        lines[i].replace("\\r", ""), "sourceFile"));
            }
        }

        var accepted = new ArrayList<ReviewReport.Finding>();
        int rejected = 0;
        for (JsonNode node : root.path("findings")) {
            if (accepted.size() >= MAX_FINDINGS) {
                rejected++;
                continue;
            }
            var finding = validate(node, visibleLines);
            if (finding == null) rejected++;
            else accepted.add(finding);
        }
        return new Result("AVAILABLE", summary, List.copyOf(accepted), rejected);
    }

    private static ReviewReport.Finding validate(JsonNode node, List<EvidenceLine> visibleLines) {
        if (!node.isObject()) return null;
        String severity = boundedText(node.path("severity"), 20);
        String category = boundedText(node.path("category"), 40);
        String file = boundedText(node.path("file"), 300);
        String evidence = boundedText(node.path("evidence"), 180);
        String description = boundedText(node.path("description"), 500);
        String recommendation = boundedText(node.path("recommendation"), 500);
        JsonNode lineNode = node.path("line");
        if (!SEVERITIES.contains(severity) || !CATEGORIES.contains(category)
                || file == null || evidence == null || evidence.contains("[REDACTED")
                || evidence.trim().length() < 8
                || description == null || recommendation == null
                || !lineNode.isIntegralNumber() || !lineNode.canConvertToInt()
                || lineNode.intValue() < 1) return null;
        for (EvidenceLine visible : visibleLines) {
            if (visible.file().equals(file) && visible.line() == lineNode.intValue()
                    && visible.text().contains(evidence)) {
                return new ReviewReport.Finding(severity, category, file, lineNode.intValue(),
                        description, recommendation, evidence, visible.field(), "POTENTIAL");
            }
        }
        return null;
    }

    private static void addDiffLines(List<EvidenceLine> lines, String service, String diff) {
        String selectedPrefix = "services/" + service + "/";
        String file = null;
        int newLine = -1;
        for (String text : diff.split("\\n", -1)) {
            if (text.startsWith("diff --git a/")) {
                int separator = text.indexOf(" b/");
                String path = separator < 0 ? "" : text.substring("diff --git a/".length(), separator);
                file = path.startsWith(selectedPrefix) ? path : null;
                newLine = -1;
                continue;
            }
            var hunk = HUNK.matcher(text);
            if (hunk.matches()) {
                newLine = file == null ? -1 : Integer.parseInt(hunk.group(1));
                continue;
            }
            if (newLine < 0 || file == null) continue;
            if (text.startsWith("+") && !text.startsWith("+++")) {
                String code = text.substring(1).replace("\\r", "");
                if (!code.isBlank()) lines.add(new EvidenceLine(file, newLine, code, "gitDiff"));
                newLine++;
            } else if (text.startsWith(" ")) {
                newLine++;
            }
        }
    }

    private static String boundedText(JsonNode node, int max) {
        if (!node.isTextual()) return null;
        String value = node.textValue().trim();
        return value.isBlank() || value.length() > max || value.chars().anyMatch(c -> Character.isISOControl(c))
                ? null : value;
    }

    private static Result malformed() {
        return new Result("MALFORMED", null, List.of(), 0);
    }
}
