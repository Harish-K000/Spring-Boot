package com.example.platform.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Safe, isolated source snippets and category/line labels; these files are never compiled. */
final class EvaluationCases {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "services/engineering-agent/src/test/resources/evaluation/";

    record Case(String id, String resource, String category, List<Integer> lines) {
        String path() { return PREFIX + resource; }
        boolean positive() { return category != null; }
    }

    static List<Case> load() throws IOException {
        try (var input = new ClassPathResource("evaluation/cases.json").getInputStream()) {
            List<Case> cases = JSON.readValue(input, new TypeReference<>() {});
            if (cases.isEmpty()) throw new IOException("Evaluation cases are empty");
            Set<String> ids = new HashSet<>();
            for (Case fixture : cases) {
                if (fixture.id() == null || !ids.add(fixture.id())
                        || fixture.resource() == null || !fixture.resource().matches("Eval[A-Za-z]+\\.java")
                        || fixture.lines() == null || fixture.positive() != !fixture.lines().isEmpty()) {
                    throw new IOException("Evaluation case metadata is invalid");
                }
                String[] sourceLines = source(fixture).split("\\n", -1);
                for (int line : fixture.lines()) {
                    if (line < 1 || line > sourceLines.length || sourceLines[line - 1].trim().length() < 8) {
                        throw new IOException("Evaluation label has no visible source line");
                    }
                }
            }
            return List.copyOf(cases);
        }
    }

    static String source(Case fixture) throws IOException {
        try (var input = new ClassPathResource("evaluation/" + fixture.resource()).getInputStream()) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (text.length() > 2_000 || text.indexOf('\0') >= 0) {
                throw new IOException("Evaluation source exceeds the model preview limit");
            }
            return text;
        }
    }

    private EvaluationCases() {}
}
