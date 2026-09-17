package com.example.platform.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** A fake Maven wrapper verifies command selection, limits and result handling. */
class BuildRunnerTest {
    @TempDir Path repository;
    private BuildRunner runner;

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
                printf '%s\\n' "$JAVA_HOME" > invoked-jdk
                case "$(cat mode)" in
                  fail) echo '[ERROR] Compilation failed on Example.java'; echo '[ERROR] password = private-value'; exit 7 ;;
                  large) dd if=/dev/zero bs=70000 count=1 2>/dev/null | tr '\\000' x; echo; echo '[ERROR] final compiler message'; exit 1 ;;
                  slow) touch started; sleep 3 ;;
                  *) echo '[INFO] BUILD SUCCESS' ;;
                esac
                """);
        assertThat(wrapper.toFile().setExecutable(true)).isTrue();
        mode("pass");
        runner = new BuildRunner(new ApprovedMavenProcess(new RepositoryGit(repository.toString()), ""), 5);
    }

    @Test void runsOnlyFixedCompileArgsWithTheRunningJdk() throws Exception {
        var result = runner.run("auth-service");
        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.exitCode()).isZero();
        assertThat(result.message()).contains("tests were not run");
        assertThat(Files.readAllLines(repository.resolve("invoked-args"))).containsExactly(
                "-B", "-ntp", "-pl", "services/auth-service", "-am", "compile");
        assertThat(Files.readString(repository.resolve("invoked-jdk")).trim()).isEqualTo(System.getProperty("java.home"));
    }

    @Test void deniesUnregisteredAndInjectedServiceNamesWithoutStartingMaven() {
        for (String service : new String[]{"../auth-service", "auth-service -DskipTests", "common-observability", "AUTH-SERVICE", ""}) {
            assertThat(runner.run(service).status()).isEqualTo("DENIED");
        }
        assertThat(runner.run(null).status()).isEqualTo("DENIED");
        assertThat(repository.resolve("invoked-args")).doesNotExist();
    }

    @Test void reportsNonzeroExitAndOnlyRedactedBoundedDiagnostics() throws Exception {
        mode("fail");
        var result = runner.run("auth-service");
        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.diagnostics()).hasSize(2).anySatisfy(line -> assertThat(line).contains("Compilation failed"));
        assertThat(result.toString()).doesNotContain("private-value");
    }

    @Test void keepsTheFinalDiagnosticWhenMavenOutputExceedsCaptureLimit() throws Exception {
        mode("large");
        var result = runner.run("auth-service");
        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.diagnostics()).contains("[ERROR] final compiler message");
        assertThat(result.toString().length()).isLessThan(3000);
    }

    @Test void timesOutAndDoesNotReportASuccess() throws Exception {
        mode("slow");
        var limited = new BuildRunner(new ApprovedMavenProcess(new RepositoryGit(repository.toString()), ""), 1);
        var result = limited.run("auth-service");
        assertThat(result.status()).isEqualTo("TIMEOUT");
        assertThat(result.exitCode()).isNull();
        assertThat(result.message()).contains("incomplete");
    }

    @Test void refusesConcurrentBuilds() throws Exception {
        mode("slow");
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> runner.run("auth-service"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!Files.exists(repository.resolve("started")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(repository.resolve("started")).exists();
            assertThat(runner.run("auth-service").status()).isEqualTo("BUSY");
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo("PASS");
            mode("pass");
            assertThat(runner.run("auth-service").status()).isEqualTo("PASS");
        }
    }

    @Test void reportsInvalidWrapperModuleAndJdkWithoutStartingMaven() throws Exception {
        Files.delete(repository.resolve("services/auth-service/pom.xml"));
        assertThat(runner.run("auth-service").status()).isEqualTo("ERROR");
        Files.writeString(repository.resolve("services/auth-service/pom.xml"), "<project/>");
        Files.delete(repository.resolve("mvnw"));
        Files.createSymbolicLink(repository.resolve("mvnw"), repository.resolve("outside-wrapper"));
        assertThat(runner.run("auth-service").status()).isEqualTo("ERROR");
        Files.delete(repository.resolve("mvnw"));
        Files.writeString(repository.resolve("mvnw"), "#!/bin/sh\n");
        repository.resolve("mvnw").toFile().setExecutable(true);
        var wrongJdk = new BuildRunner(new ApprovedMavenProcess(new RepositoryGit(repository.toString()),
                repository.resolve("missing-jdk").toString()));
        assertThat(wrongJdk.run("auth-service").status()).isEqualTo("ERROR");
        assertThat(repository.resolve("invoked-args")).doesNotExist();
    }

    @Test void rejectsASymlinkedModuleDirectory() throws Exception {
        Path module = repository.resolve("services/auth-service");
        Files.delete(module.resolve("pom.xml"));
        Files.delete(module);
        Path other = repository.resolve("outside-module");
        Files.createDirectory(other);
        Files.writeString(other.resolve("pom.xml"), "<project/>");
        Files.createSymbolicLink(module, other);
        assertThat(runner.run("auth-service").status()).isEqualTo("ERROR");
        assertThat(repository.resolve("invoked-args")).doesNotExist();
    }

    @Test void givesEachRequestWrapperOneAttemptIncludingDenials() {
        var tool = new BuildTool(runner);
        assertThat(tool.runBuild("unknown").status()).isEqualTo("DENIED");
        assertThatThrownBy(() -> tool.runBuild("auth-service")).isInstanceOf(IllegalStateException.class);
        assertThat(new BuildTool(runner).runBuild("auth-service").status()).isEqualTo("PASS");
    }

    private void mode(String name) throws Exception {
        Files.writeString(repository.resolve("mode"), name);
    }
}
