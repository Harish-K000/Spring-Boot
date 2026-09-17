package com.example.platform.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class GitDiffReaderTest {
    @TempDir Path repository;
    private GitDiffReader reader;

    @BeforeEach
    void createRepository() throws Exception {
        git("init", "-q");
        write("Example.java", "class Example { int original = 1; }\n");
        write("pom.xml", "<project/>\n");
        git("add", ".");
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture");
        reader = new GitDiffReader(new RepositoryGit(repository.toString()));
    }

    @Test
    void includesStagedAndUnstagedChangesWithoutMutatingRepository() throws Exception {
        write("Example.java", "class Example { int staged = 2; }\n");
        git("add", "Example.java");
        write("pom.xml", "<project><name>unstaged</name></project>\n");
        String before = git("status", "--porcelain=v1");
        byte[] index = Files.readAllBytes(repository.resolve(".git/index"));
        var result = reader.read();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.exitCode()).isZero();
        assertThat(result.diff()).contains("+class Example { int staged = 2; }", "<name>unstaged</name>");
        assertThat(result.truncated()).isFalse();
        assertThat(git("status", "--porcelain=v1")).isEqualTo(before);
        assertThat(Files.readAllBytes(repository.resolve(".git/index"))).isEqualTo(index);
    }

    @Test
    void excludesUntrackedConfigurationHiddenAndSensitivePaths() throws Exception {
        for (String name : List.of(".env", "application.yaml", ".private/Hidden.java", "secrets/Example.java", "credentials/pom.xml", "target/Generated.java")) {
            write(name, "before\n");
        }
        git("add", ".");
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "excluded fixture");
        for (String name : List.of(".env", "application.yaml", ".private/Hidden.java", "secrets/Example.java", "credentials/pom.xml", "target/Generated.java")) {
            write(name, "DO_NOT_EXPOSE\n");
        }
        write("Untracked.java", "UNTRACKED_CONTENT\n");
        var result = reader.read();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.diff()).isEmpty();
        assertThat(result.message()).contains("within the tool's tracked-file scope");
    }

    @Test
    void redactsKnownSecretsInAllowedFiles() throws Exception {
        write("Example.java", "class Example { String password = \"fake-fixture-value\"; }\n");
        var result = reader.read();
        assertThat(result.redacted()).isTrue();
        assertThat(result.diff()).contains("[REDACTED SENSITIVE LINE]").doesNotContain("fake-fixture-value");
        assertThat(GitDiffReader.redact("-----BEGIN PRIVATE KEY-----\nfixture-body\n-----END PRIVATE KEY-----"))
                .doesNotContain("fixture-body");
    }

    @Test
    void marksLargeDiffAsIncomplete() throws Exception {
        write("Example.java", "class Changed {}\n".repeat(2000));
        var result = reader.read();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.truncated()).isTrue();
        assertThat(result.diff().length()).isLessThanOrEqualTo(GitDiffReader.MAX_BYTES);
        assertThat(result.message()).contains("incomplete");
    }

    @Test
    void reportsInvalidRepositoryAndUnbornHeadAsErrors() throws Exception {
        assertThat(new GitDiffReader(new RepositoryGit(repository.resolve("missing").toString())).read().status()).isEqualTo("ERROR");
        Path unborn = Files.createDirectory(repository.resolve("unborn"));
        git("-C", unborn.toString(), "init", "-q");
        var result = new GitDiffReader(new RepositoryGit(unborn.toString())).read();
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.diff()).isEmpty();
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void disablesExternalDiffTextConversionFsmonitorAndCleanFilters() throws Exception {
        write(".gitattributes", "*.java diff=custom filter=custom\n");
        write("Example.java", "class Changed {}\n");
        String markerCommand = "touch SHOULD_NOT_RUN";
        git("config", "diff.external", markerCommand);
        git("config", "diff.custom.textconv", markerCommand);
        git("config", "core.fsmonitor", markerCommand);
        git("config", "filter.custom.clean", markerCommand);
        git("config", "filter.custom.process", markerCommand);
        git("config", "filter.custom.required", "true");
        byte[] config = Files.readAllBytes(repository.resolve(".git/config"));
        var result = reader.read();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.diff()).contains("+class Changed {}");
        assertThat(repository.resolve("SHOULD_NOT_RUN")).doesNotExist();
        assertThat(Files.readAllBytes(repository.resolve(".git/config"))).isEqualTo(config);
    }

    @Test
    void doesNotFollowSourceSymlinks() throws Exception {
        write("private.txt", "OUTSIDE_CONTENT\n");
        Files.delete(repository.resolve("Example.java"));
        Files.createSymbolicLink(repository.resolve("Example.java"), Path.of("private.txt"));
        var result = reader.read();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.diff()).doesNotContain("OUTSIDE_CONTENT");
    }

    @Test
    void readsCommittedPullRequestDiffFromConfiguredBaseOnACleanTree() throws Exception {
        String base = git("rev-parse", "HEAD").trim();
        write("Example.java", "class Example { int pullRequest = 7; }\n");
        git("add", "Example.java");
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "feature edit");

        var result = new GitDiffReader(new RepositoryGit(repository.toString(), base)).read();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.diff()).contains("+class Example { int pullRequest = 7; }");
        assertThat(result.message()).contains(base + "...HEAD");
        assertThat(git("status", "--porcelain=v1")).isEmpty();
    }

    @Test
    void allowsOnlyOneToolCallPerRequest() {
        var tool = new GitDiffTool(reader);
        assertThat(tool.getGitDiff().status()).isEqualTo("SUCCESS");
        assertThatThrownBy(tool::getGitDiff).isInstanceOf(IllegalStateException.class);
        assertThat(new GitDiffTool(reader).getGitDiff().status()).isEqualTo("SUCCESS");
    }

    private void write(String name, String content) throws Exception {
        Path path = repository.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private String git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "-C", repository.toString()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        var process = builder.start();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }
}
