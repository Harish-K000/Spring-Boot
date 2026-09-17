package com.example.platform.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real Semgrep rule check on safe fixture text; no application scanner or OSV call. */
@EnabledIfEnvironmentVariable(named = "AGENT_LIVE_SCANNER_EVAL", matches = "true")
class SemgrepEvaluationLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SQL_RULE = "java-sql-string-concatenation";

    @Test
    @Timeout(90)
    void localSqlRuleMatchesUnsafeCallAndLeavesCleanFixtureUnmatched() throws Exception {
        Path module = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path config = module.resolve("src/main/resources/security/semgrep-rules.yml");
        Path fixtureDir = module.resolve("src/test/resources/evaluation");
        Path binary = Path.of("/opt/homebrew/bin/semgrep");
        assertThat(Files.isRegularFile(config)).isTrue();
        assertThat(Files.isRegularFile(fixtureDir.resolve("EvalSqlQuery.java"))).isTrue();
        assertThat(Files.isRegularFile(fixtureDir.resolve("EvalCheckedEmail.java"))).isTrue();
        assertThat(Files.isExecutable(binary)).isTrue();

        Path temporary = Files.createTempDirectory("agent-semgrep-evaluation-");
        try {
            Path rawReport = temporary.resolve("report.json");
            var builder = new ProcessBuilder(List.of(binary.toString(), "scan",
                    "--config=" + config, "--json-output=" + rawReport,
                    "--metrics=off", "--disable-version-check", "--error",
                    fixtureDir.resolve("EvalSqlQuery.java").toString(),
                    fixtureDir.resolve("EvalCheckedEmail.java").toString()))
                    .directory(module.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            String executablePath = builder.environment().getOrDefault("PATH", "/usr/bin:/bin");
            String home = builder.environment().getOrDefault("HOME", System.getProperty("user.home"));
            builder.environment().clear();
            builder.environment().put("PATH", executablePath);
            builder.environment().put("HOME", home);
            builder.environment().put("LANG", "C");
            var process = builder.start();
            try {
                assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
            assertThat(process.exitValue()).isEqualTo(1);
            assertThat(Files.size(rawReport)).isLessThan(4_000_000L);
            JsonNode result = JSON.readTree(rawReport.toFile());
            assertThat(result.path("errors").isEmpty()).isTrue();
            boolean sqlMatch = false;
            int unexpectedClean = 0;
            int sqlRuleMatches = 0;
            for (JsonNode item : result.path("results")) {
                String rule = item.path("check_id").asText();
                String path = item.path("path").asText();
                int line = item.path("start").path("line").asInt();
                if (rule.endsWith(SQL_RULE)) {
                    sqlRuleMatches++;
                    if (path.endsWith("EvalSqlQuery.java") && line == 6) sqlMatch = true;
                    if (path.endsWith("EvalCheckedEmail.java")) unexpectedClean++;
                }
            }
            assertThat(sqlMatch).isTrue();
            assertThat(unexpectedClean).isZero();
            Path output = module.resolve("target/semgrep-evaluation-report.json");
            Files.createDirectories(output.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                    "scanner", "semgrep", "rule", SQL_RULE, "sqlFixtureLine6Matched", sqlMatch,
                    "sqlRuleMatches", sqlRuleMatches, "unexpectedCleanMatches", unexpectedClean));
            System.out.printf("Semgrep evaluation SQL line 6 matched=%s clean matches=%d report=%s%n",
                    sqlMatch, unexpectedClean, output);
        } finally {
            Files.deleteIfExists(temporary.resolve("report.json"));
            Files.deleteIfExists(temporary);
        }
    }
}
