package com.example.platform.agent.service;

import java.util.List;
import java.util.Map;

/** Ollama's native output constraint; evidence validation remains in CodeFindingExtractor. */
final class ReviewOutputSchema {
    private ReviewOutputSchema() {}

    static Map<String, Object> schema() {
        var finding = Map.of(
                "type", "object", "additionalProperties", false,
                "required", List.of("severity", "category", "file", "line", "evidence", "description", "recommendation"),
                "properties", Map.of(
                        "severity", Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")),
                        "category", Map.of("type", "string", "enum", List.of("AUTHENTICATION", "AUTHORIZATION", "SQL_QUERY",
                                "NULL_HANDLING", "EXCEPTION_HANDLING", "CONCURRENCY", "TRANSACTION", "VALIDATION",
                                "SENSITIVE_LOGGING", "API_CONTRACT", "IDEMPOTENCY", "ERROR_HANDLING", "PERFORMANCE")),
                        "file", text(300), "line", Map.of("type", "integer", "minimum", 1),
                        "evidence", text(180), "description", text(500), "recommendation", text(500)));
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("summary", "findings"),
                "properties", Map.of("summary", text(600), "findings",
                        Map.of("type", "array", "maxItems", 3, "items", finding)));
    }

    private static Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maximum);
    }
}
