package com.example.platform.agent.tool;

import com.example.platform.agent.dto.SecurityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityRunnerTest {
    @TempDir Path root;

    @Test void returnsScannerMatchesWithoutLeakingASecretValue() throws Exception {
        fixture();
        Path sast = script("sast", """
                {"results":[{"check_id":"java-runtime-command-execution","path":"services/auth-service/src/main/A.java","start":{"line":1},"extra":{"severity":"WARNING","message":"Review process execution."}}],"errors":[],"paths":{"scanned":["services/auth-service/src/main/A.java"]}}
                """);
        Path secrets = script("secrets", """
                [{"RuleID":"github-pat","File":"services/auth-service/src/main/A.java","StartLine":1,"Secret":"TEST_ONLY_SECRET_VALUE"}]
                """);
        Path dependencies = script("dependencies", """
                {"results":[{"source":{"path":"services/auth-service/pom.xml"},"packages":[{"package":{"name":"example:library","version":"1.0"},"vulnerabilities":[{"id":"GHSA-example-1234","database_specific":{"severity":"HIGH"}}]}]}]}
                """);
        var result = new SecurityRunner(new RepositoryGit(root.toString()),
                sast.toString(), secrets.toString(), dependencies.toString()).run("auth-service");
        assertThat(result.status()).isEqualTo("FINDINGS");
        assertThat(result.complete()).isTrue();
        assertThat(result.totalFindings()).isEqualTo(3);
        assertThat(result.scanners()).extracting(SecurityResult.ScannerResult::status)
                .containsExactly("FINDINGS", "FINDINGS", "FINDINGS");
        assertThat(result.findings()).extracting(SecurityResult.SecurityFinding::scanner)
                .containsExactlyInAnyOrder("sast", "secrets", "dependencies");
        assertThat(result.findings()).allMatch(item -> item.evidenceStatus().equals("SCANNER_MATCH"));
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("TEST_ONLY_SECRET_VALUE");
    }

    @Test void refusesAScannerReportThatNamesAnotherService() throws Exception {
        fixture();
        Path sast = script("sast", """
                {"results":[{"check_id":"rule","path":"services/orders-service/src/main/Outside.java","start":{"line":1},"extra":{"severity":"WARNING","message":"Unscoped."}}],"errors":[],"paths":{"scanned":["services/auth-service/src/main/A.java"]}}
                """);
        var result = new SecurityRunner(new RepositoryGit(root.toString()),
                sast.toString(), root.resolve("missing-gitleaks").toString(),
                root.resolve("missing-osv").toString()).run("auth-service");
        assertThat(result.status()).isEqualTo("INCOMPLETE");
        assertThat(result.complete()).isFalse();
        assertThat(result.totalFindings()).isZero();
        assertThat(result.scanners().getFirst().status()).isEqualTo("INCOMPLETE");
        assertThat(result.scanners().get(1).status()).isEqualTo("UNAVAILABLE");
    }

    @Test void deniesASymlinkBeforeStartingExternalScanners() throws Exception {
        fixture();
        Files.createSymbolicLink(root.resolve("services/auth-service/src/main/Outside.java"),
                root.resolve("outside.java"));
        Path binary = script("unused", "{}");
        var result = new SecurityRunner(new RepositoryGit(root.toString()),
                binary.toString(), binary.toString(), binary.toString()).run("auth-service");
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.scanners()).isEmpty();
    }

    private void fixture() throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("services/auth-service/src/main"));
        Files.writeString(root.resolve("services/auth-service/pom.xml"), "<project/>\n");
        Files.writeString(root.resolve("services/auth-service/src/main/A.java"), "class A {}\n");
        Path rules = root.resolve("services/engineering-agent/src/main/resources/security/semgrep-rules.yml");
        Files.createDirectories(rules.getParent());
        Files.writeString(rules, "rules: []\n");
        Files.writeString(rules.getParent().resolve("gitleaks.toml"),
                "[extend]\nuseDefault = true\n[[allowlists]]\npaths = ['''(^|/)target/''']\n");
    }

    private Path script(String name, String report) throws Exception {
        Path binary = root.resolve(name + ".sh");
        Files.writeString(binary, """
                #!/bin/sh
                output=''
                for arg in "$@"; do
                  case "$arg" in
                    --json-output=*|--report-path=*|--output-file=*) output="${arg#*=}" ;;
                  esac
                done
                test -n "$output" || exit 2
                cat > "$output" <<'SCAN_REPORT'
                """ + report + "SCAN_REPORT\nexit 1\n");
        assertThat(binary.toFile().setExecutable(true)).isTrue();
        return binary;
    }
}
