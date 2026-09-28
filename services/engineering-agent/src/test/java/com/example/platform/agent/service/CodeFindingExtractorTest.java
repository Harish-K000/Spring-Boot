package com.example.platform.agent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeFindingExtractorTest {
    @Test void rejectsTrailingContentAndIncompleteJson() {
        for (String raw : new String[] {
                "{\"summary\":\"OK\",\"findings\":[]} trailing prose",
                "{\"summary\":\"OK\",\"findings\":[]} {\"another\":true}",
                "{\"summary\":\"OK\",\"findings\":[", "null", "```json\n\n```"}) {
            assertThat(CodeFindingExtractor.parse(raw, "engineering-agent", "", null, "").status())
                    .as(raw).isEqualTo("MALFORMED");
        }
    }

    @Test void missingFindingFieldsAreRejectedWithoutCrashingTheReview() {
        var result = CodeFindingExtractor.parse(
                "{\"summary\":\"Potential issue\",\"findings\":[{}, {\"severity\":\"HIGH\"}]}",
                "engineering-agent", "", null, "");
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.findings()).isEmpty();
        assertThat(result.rejectedCount()).isEqualTo(2);
    }
}
