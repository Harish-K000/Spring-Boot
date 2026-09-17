package com.example.platform.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Fake wrapper, real files and XML parser: verifies that evidence belongs to this run. */
class TestRunnerTest {
    @TempDir Path repository;
    private ApprovedMavenProcess maven;
    private TestRunner runner;

    @BeforeEach void setup() throws Exception {
        Files.createDirectory(repository.resolve(".git"));
        Files.writeString(repository.resolve("pom.xml"), "<project/>");
        Path module = repository.resolve("services/auth-service");
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path wrapper = repository.resolve("mvnw");
        Files.writeString(wrapper, """
                #!/bin/sh
                printf '%s\\n' "$@" > invoked-args
                case "$(cat mode)" in
                  slow) touch started; sleep 3; exit 0 ;;
                  none) echo '[INFO] No tests to run.'; exit 0 ;;
                  earlyfail) echo '[ERROR] Compilation stopped'; exit 7 ;;
                esac
                mkdir -p services/auth-service/target/surefire-reports
                case "$(cat mode)" in
                  fail) cat > services/auth-service/target/surefire-reports/TEST-Selected.xml <<'REPORT'
                <testsuite tests="3" failures="1" errors="1" skipped="0">
                  <testcase classname="Example" name="rejectsBadToken"><failure message="password=private-value"/></testcase>
                  <testcase classname="Example" name="databaseUnavailable"><error message="database unavailable"/></testcase>
                  <testcase classname="Example" name="works"/>
                </testsuite>
                REPORT
                  exit 1 ;;
                  malformed) echo '<!DOCTYPE test [<!ENTITY x SYSTEM "file:///etc/passwd">]><testsuite tests="1" failures="0" errors="0" skipped="0" />' > services/auth-service/target/surefire-reports/TEST-Selected.xml; exit 0 ;;
                  invalidfailure) echo '<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase classname="Example" name="bad"><failure message="password=private-value"/></testcase><broken' > services/auth-service/target/surefire-reports/TEST-Selected.xml; exit 0 ;;
                  inconsistent) echo '<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase classname="Example" name="bad"><failure message="bad result"/></testcase></testsuite>' > services/auth-service/target/surefire-reports/TEST-Selected.xml; exit 0 ;;
                esac
                cat > services/auth-service/target/surefire-reports/TEST-Selected.xml <<'REPORT'
                <testsuite tests="3" failures="0" errors="0" skipped="1">
                  <testcase classname="Example" name="works"/>
                  <testcase classname="Example" name="alsoWorks"/>
                  <testcase classname="Example" name="notRun"><skipped/></testcase>
                </testsuite>
                REPORT
                mkdir -p libs/common-observability/target/surefire-reports
                echo '<testsuite tests="2" failures="0" errors="0" skipped="0" />' > libs/common-observability/target/surefire-reports/TEST-Shared.xml
                echo '[INFO] BUILD SUCCESS'
                """);
        assertThat(wrapper.toFile().setExecutable(true)).isTrue();
        mode("pass");
        maven = new ApprovedMavenProcess(new RepositoryGit(repository.toString()), "");
        runner = new TestRunner(maven, 5);
    }

    @Test void reportsFreshServiceAndDependencyCountsWithSkippedTests() throws Exception {
        var result = runner.run("auth-service");
        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.exitCode()).isZero();
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.passed()).isEqualTo(4);
        assertThat(result.failed()).isZero();
        assertThat(result.errors()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.serviceTests()).isEqualTo(3);
        assertThat(result.failures()).isEmpty();
        assertThat(Files.readAllLines(repository.resolve("invoked-args"))).containsExactly(
                "-B", "-ntp", "-pl", "services/auth-service", "-am", "test");
    }

    @Test void reportsFailuresAndRedactsFailureMessages() throws Exception {
        mode("fail");
        var result = runner.run("auth-service");
        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.passed()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.failures()).hasSize(2);
        assertThat(result.toString()).contains("rejectsBadToken", "databaseUnavailable")
                .doesNotContain("private-value", "file:///etc/passwd");
    }

    @Test void ignoresOldReportsWhenNoTestsRun() throws Exception {
        Path directory = repository.resolve("services/auth-service/target/surefire-reports");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("TEST-Old.xml"),
                "<testsuite tests=\"99\" failures=\"0\" errors=\"0\" skipped=\"0\" />");
        mode("none");
        var result = runner.run("auth-service");
        assertThat(result.status()).isEqualTo("NO_TESTS");
        assertThat(result.total()).isZero();
        assertThat(result.serviceTests()).isZero();
    }

    @Test void findsFreshReportsAfterManyOldReports() throws Exception {
        Path directory = repository.resolve("services/auth-service/target/surefire-reports");
        Files.createDirectories(directory);
        for (int index = 0; index < 105; index++) {
            Files.writeString(directory.resolve("TEST-Old" + String.format("%03d", index) + ".xml"),
                    "<testsuite tests=\"99\" failures=\"0\" errors=\"0\" skipped=\"0\" />");
        }
        var result = runner.run("auth-service");
        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.serviceTests()).isEqualTo(3);
    }

    @Test void marksMalformedOrHostileXmlIncompleteInsteadOfClaimingPass() throws Exception {
        mode("malformed");
        var result = runner.run("auth-service");
        assertThat(result.status()).isEqualTo("INCOMPLETE");
        assertThat(result.incomplete()).isTrue();
        assertThat(result.total()).isZero();
    }

    @Test void discardsFailureDetailsFromAnInvalidXmlReport() throws Exception {
        mode("invalidfailure");
        var result = runner.run("auth-service");
        assertThat(result.status()).isEqualTo("INCOMPLETE");
        assertThat(result.failures()).isEmpty();
        assertThat(result.toString()).doesNotContain("private-value");
    }

    @Test void treatsSuccessExitWithFailedTestsAsInconsistentEvidence() throws Exception {
        mode("inconsistent");
        var result = runner.run("auth-service");
        assertThat(result.status()).isEqualTo("INCOMPLETE");
        assertThat(result.incomplete()).isTrue();
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test void distinguishesFailureBeforeTestsFromFailedTests() throws Exception {
        mode("earlyfail");
        var result = runner.run("auth-service");
        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.total()).isZero();
        assertThat(result.incomplete()).isTrue();
    }

    @Test void timesOutWithoutClaimingTestResults() throws Exception {
        mode("slow");
        var result = new TestRunner(maven, 1).run("auth-service");
        assertThat(result.status()).isEqualTo("TIMEOUT");
        assertThat(result.exitCode()).isNull();
        assertThat(result.total()).isZero();
    }

    @Test void sharesOneExecutionLimitWithBuilds() throws Exception {
        mode("slow");
        var build = new BuildRunner(maven, 5);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> build.run("auth-service"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!Files.exists(repository.resolve("started")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(repository.resolve("started")).exists();
            assertThat(runner.run("auth-service").status()).isEqualTo("BUSY");
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo("PASS");
        }
    }

    @Test void deniesUnregisteredServicesAndRepeatedCalls() {
        var tool = new TestTool(runner);
        assertThat(tool.runTests("../auth-service").status()).isEqualTo("DENIED");
        assertThatThrownBy(() -> tool.runTests("auth-service")).isInstanceOf(IllegalStateException.class);
        assertThat(repository.resolve("invoked-args")).doesNotExist();
    }

    private void mode(String value) throws Exception { Files.writeString(repository.resolve("mode"), value); }
}
