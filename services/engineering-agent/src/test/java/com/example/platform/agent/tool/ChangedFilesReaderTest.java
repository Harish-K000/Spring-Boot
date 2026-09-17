package com.example.platform.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.example.platform.agent.tool.ChangedFilesReader.ChangeStatus.*;
import static org.assertj.core.api.Assertions.*;

class ChangedFilesReaderTest {
    @TempDir Path repository;
    private ChangedFilesReader reader;

    @BeforeEach
    void initialize() throws Exception {
        git(0, "init", "-q");
        write("pom.xml", "<project/>\n");
        write("services/auth-service/Example.java", "class Example { int value = 1; }\n");
        git(0, "add", ".");
        git(0, "commit", "-qm", "initial fixture");
        reader = new ChangedFilesReader(new RepositoryGit(repository.toString()));
    }

    @Test
    void listsBothChangeStatesAndIndividualUntrackedPaths() throws Exception {
        write("services/auth-service/Example.java", "class Example { int value = 2; }\n");
        git(0, "add", ".");
        write("services/auth-service/Example.java", "class Example { int value = 3; }\n");
        write("services/orders-service/new dir/New.java", "UNTRACKED_BODY\n");
        var result = reader.read();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.files()).containsExactly(
                new ChangedFilesReader.ChangedFile("services/auth-service/Example.java", MODIFIED, MODIFIED, false, "auth-service"),
                new ChangedFilesReader.ChangedFile("services/orders-service/new dir/New.java", UNTRACKED, UNTRACKED, false, "orders-service"));
        assertThat(result.changedServices()).containsExactly("auth-service", "orders-service");
        assertThat(result.sharedChanges()).isFalse();
        assertThat(result.toString()).doesNotContain("UNTRACKED_BODY");
    }

    @Test
    void representsRenameAsDeletionAndAddition() throws Exception {
        git(0, "mv", "services/auth-service/Example.java", "services/auth-service/Renamed.java");
        var result = reader.read();
        assertThat(result.files()).containsExactly(
                new ChangedFilesReader.ChangedFile("services/auth-service/Example.java", DELETED, UNCHANGED, false, "auth-service"),
                new ChangedFilesReader.ChangedFile("services/auth-service/Renamed.java", ADDED, UNCHANGED, false, "auth-service"));
    }

    @Test
    void preservesSpacesNewlinesAndUnicodeInPaths() throws Exception {
        String path = "services/auth-service/ leading\tline\nCafé.java";
        Path unusualFile = repository.resolve(path);
        try {
            write(path, "class Example {}\n");
            assertThat(reader.read().files()).extracting(ChangedFilesReader.ChangedFile::path).containsExactly(path);
        } finally {
            // JUnit's recursive @TempDir cleanup can report this legal newline path as an error on Linux runners.
            Files.deleteIfExists(unusualFile);
        }
    }

    @Test
    void excludesSensitiveHiddenGeneratedIgnoredAndOutOfScopePaths() throws Exception {
        write(".gitignore", "Ignored.java\n");
        for (String path : List.of(".env", ".env.java", ".private/Example.java", "secrets/Example.java",
                "credentials/pom.xml", "target/Example.java", "Ignored.java", "application.yaml", "README.md")) {
            write(path, "do not expose\n");
        }
        var result = reader.read();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.files()).isEmpty();
        assertThat(result.message()).contains("within the approved scope");
    }

    @Test
    void flagsSharedChangesWithoutInventingAffectedServices() throws Exception {
        write("pom.xml", "<project><name>changed</name></project>\n");
        write("libs/common-observability/Shared.java", "class Shared {}\n");
        var result = reader.read();
        assertThat(result.sharedChanges()).isTrue();
        assertThat(result.changedServices()).isEmpty();
        assertThat(result.files()).allMatch(file -> file.service() == null);
    }

    @Test
    void handlesUnbornRepositoryAndInvalidRootDifferently() throws Exception {
        Path unborn = Files.createDirectory(repository.resolve("unborn"));
        git(0, "-C", unborn.toString(), "init", "-q");
        Files.writeString(unborn.resolve("New.java"), "class New {}\n");
        var result = new ChangedFilesReader(new RepositoryGit(unborn.toString())).read();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.files()).extracting(ChangedFilesReader.ChangedFile::path).containsExactly("New.java");
        assertThat(new ChangedFilesReader(new RepositoryGit(repository.resolve("missing").toString())).read().status())
                .isEqualTo("ERROR");
    }

    @Test
    void reportsTruncationAndDiscardsIncompletePaths() throws Exception {
        for (int index = 0; index <= ChangedFilesReader.MAX_FILES; index++) write("New" + index + ".java", "class New {}\n");
        var result = reader.read();
        assertThat(result.files()).hasSize(ChangedFilesReader.MAX_FILES);
        assertThat(result.truncated()).isTrue();
        assertThat(result.message()).contains("incomplete");
        var partial = ChangedFilesReader.parse("?? First.java\0?? Partial", true, System.nanoTime());
        assertThat(partial.files()).extracting(ChangedFilesReader.ChangedFile::path).containsExactly("First.java");
        assertThat(partial.truncated()).isTrue();
    }

    @Test
    void identifiesActualMergeConflict() throws Exception {
        git(0, "checkout", "-qb", "left");
        write("services/auth-service/Example.java", "class Left {}\n");
        git(0, "commit", "-qam", "left edit");
        git(0, "checkout", "-qb", "right", "HEAD~1");
        write("services/auth-service/Example.java", "class Right {}\n");
        git(0, "commit", "-qam", "right edit");
        git(1, "merge", "left");
        var result = reader.read();
        assertThat(result.files()).singleElement().satisfies(file -> {
            assertThat(file.conflicted()).isTrue();
            assertThat(file.indexStatus()).isEqualTo(UNMERGED);
            assertThat(file.workTreeStatus()).isEqualTo(UNMERGED);
        });
    }

    @Test
    void listsCommittedPullRequestChangesFromConfiguredBaseOnACleanTree() throws Exception {
        String base = gitOutput("rev-parse", "HEAD").trim();
        write("services/auth-service/Example.java", "class Example { int value = 9; }\n");
        write("services/orders-service/New.java", "class New {}\n");
        git(0, "add", ".");
        git(0, "commit", "-qm", "feature changes");

        var result = new ChangedFilesReader(new RepositoryGit(repository.toString(), base)).read();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.files()).containsExactly(
                new ChangedFilesReader.ChangedFile("services/auth-service/Example.java", MODIFIED, UNCHANGED, false, "auth-service"),
                new ChangedFilesReader.ChangedFile("services/orders-service/New.java", ADDED, UNCHANGED, false, "orders-service"));
        assertThat(result.changedServices()).containsExactly("auth-service", "orders-service");
        assertThat(result.scope()).contains("pull-request base commit and HEAD");
        assertThat(gitOutput("status", "--porcelain=v1")).isEmpty();
    }

    @Test
    void failsClosedForMissingOrMalformedPullRequestBase() {
        assertThat(new ChangedFilesReader(new RepositoryGit(repository.toString(), "a".repeat(40))).read().status())
                .isEqualTo("ERROR");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RepositoryGit(repository.toString(), "HEAD;touch-pwned"))
                .withMessageContaining("full Git commit hash");
    }

    @Test
    void disablesGitHelpersAndLeavesTheIndexAndConfigurationUnchanged() throws Exception {
        write("services/auth-service/Example.java", "class Changed {}\n");
        write(".gitattributes", "*.java filter=custom\n");
        git(0, "config", "core.fsmonitor", "touch SHOULD_NOT_RUN");
        git(0, "config", "filter.custom.clean", "touch SHOULD_NOT_RUN");
        byte[] index = Files.readAllBytes(repository.resolve(".git/index"));
        byte[] config = Files.readAllBytes(repository.resolve(".git/config"));
        assertThat(reader.read().status()).isEqualTo("SUCCESS");
        assertThat(repository.resolve("SHOULD_NOT_RUN")).doesNotExist();
        assertThat(Files.readAllBytes(repository.resolve(".git/index"))).isEqualTo(index);
        assertThat(Files.readAllBytes(repository.resolve(".git/config"))).isEqualTo(config);
    }

    @Test
    void limitsEachToolInstanceToOneCall() {
        var tool = new ChangedFilesTool(reader);
        assertThat(tool.getChangedFiles().status()).isEqualTo("SUCCESS");
        assertThatThrownBy(tool::getChangedFiles).isInstanceOf(IllegalStateException.class);
        assertThat(new ChangedFilesTool(reader).getChangedFiles().status()).isEqualTo("SUCCESS");
    }

    private void write(String name, String content) throws Exception {
        Path path = repository.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private void git(int expectedExit, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false",
                "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-C", repository.toString()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        var process = builder.start();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.exitValue()).as(output).isEqualTo(expectedExit);
    }

    private String gitOutput(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-C", repository.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }
}
