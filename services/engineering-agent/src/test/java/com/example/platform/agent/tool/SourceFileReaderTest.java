package com.example.platform.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class SourceFileReaderTest {
    @TempDir Path root;
    @TempDir Path outside;
    private SourceFileReader reader;

    @BeforeEach void setup() throws Exception {
        Files.createDirectory(root.resolve(".git"));
        reader = new SourceFileReader(new RepositoryGit(root.toString()));
    }

    @Test void readsUntrackedSourceWithoutModifyingIt() throws Exception {
        String text = "// café\nclass Example {}\n";
        Path source = write("src/Example.java", text);
        var result = reader.read("src/Example.java");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.path()).isEqualTo("src/Example.java");
        assertThat(result.content()).isEqualTo(text);
        assertThat(result.redacted()).isFalse();
        assertThat(Files.readString(source)).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pom.xml", "src/schema.sql", "src/Example.java"})
    void permitsEachApprovedFileType(String path) throws Exception {
        write(path, "example");
        assertThat(reader.read(path).status()).isEqualTo("SUCCESS");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "/etc/pom.xml", "../Outside.java", "src/../../Outside.java", "src/./Example.java",
            ".env", ".ssh/Example.java", ".aws/pom.xml", "src/.hidden/Example.java", "src/Secrets.java",
            "src/CREDENTIALS/pom.xml", "target/Example.java", "src/node_modules/pom.xml", "application.yaml",
            "src/Example.class", "src\\Example.java", "src/%2e%2e/Example.java", "file:Example.java",
            "src//Example.java", "src/Example.java/", "src/Example\n.java"})
    void deniesDisallowedPaths(String path) {
        var result = reader.read(path);
        assertThat(result.status()).isEqualTo("DENIED");
        assertThat(result.content()).isEmpty();
        assertThat(result.path()).isNull();
    }

    @Test void deniesBothFileAndDirectorySymlinksEvenInsideRepository() throws Exception {
        Path external = outside.resolve("Outside.java");
        Files.writeString(external, "outside marker");
        Files.createSymbolicLink(root.resolve("Link.java"), external);
        Files.createSymbolicLink(root.resolve("linked"), outside);
        Path internal = write("Inside.java", "inside marker");
        Files.createSymbolicLink(root.resolve("Internal.java"), internal);
        for (String path : new String[]{"Link.java", "linked/Outside.java", "Internal.java"}) {
            assertThat(reader.read(path).status()).isEqualTo("DENIED");
            assertThat(reader.read(path).content()).isEmpty();
        }
    }

    @Test void rejectsDirectoriesAndMissingFilesWithoutLeakingExceptionDetails() throws Exception {
        Files.createDirectory(root.resolve("Directory.java"));
        assertThat(reader.read("Directory.java").status()).isEqualTo("DENIED");
        var missing = reader.read("Missing.java");
        assertThat(missing.status()).isEqualTo("ERROR");
        assertThat(missing.content()).isEmpty();
        assertThat(missing.message()).doesNotContain(root.toString());
    }

    @Test void enforcesByteLimitWithoutReturningPartialContent() throws Exception {
        write("Exact.java", "x".repeat(SourceFileReader.MAX_BYTES));
        write("Large.java", "x".repeat(SourceFileReader.MAX_BYTES + 1));
        write("Unicode.java", "é".repeat(SourceFileReader.MAX_BYTES));
        assertThat(reader.read("Exact.java").content()).hasSize(SourceFileReader.MAX_BYTES);
        for (String path : new String[]{"Large.java", "Unicode.java"}) {
            assertThat(reader.read(path).status()).isEqualTo("DENIED");
            assertThat(reader.read(path).content()).isEmpty();
        }
    }

    @Test void rejectsBinaryAndMalformedUtf8() throws Exception {
        Files.write(root.resolve("Binary.java"), new byte[]{'a', 0, 'b'});
        Files.write(root.resolve("Invalid.java"), new byte[]{(byte) 0xc3, 0x28});
        assertThat(reader.read("Binary.java").status()).isEqualTo("DENIED");
        assertThat(reader.read("Invalid.java").status()).isEqualTo("DENIED");
    }

    @Test void redactsSensitiveContentBeforeReturningIt() throws Exception {
        String source = "class Example {\nString password = \"private-value\";\n}\n"
                + "-----BEGIN PRIVATE KEY-----\nprivate-block\n-----END PRIVATE KEY-----\n"
                + "// ghp_abcdefghijklmnopqrstuvwxyz123456\n";
        Path file = write("Example.java", source);
        var result = reader.read("Example.java");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.redacted()).isTrue();
        assertThat(result.content()).contains("class Example", "REDACTED")
                .doesNotContain("private-value", "private-block", "ghp_abcdef");
        assertThat(Files.readString(file)).isEqualTo(source);
    }

    @Test void reportsInvalidRepositoryAsAnError() {
        var result = new SourceFileReader(new RepositoryGit(outside.toString())).read("Example.java");
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.content()).isEmpty();
    }

    @Test void countsDeniedAttemptsAndResetsBudgetWithANewWrapper() throws Exception {
        write("Example.java", "class Example {}");
        var tool = new SourceFileTool(reader);
        assertThat(tool.readSourceFile("../Example.java").status()).isEqualTo("DENIED");
        assertThatThrownBy(() -> tool.readSourceFile("Example.java")).isInstanceOf(IllegalStateException.class);
        assertThat(new SourceFileTool(reader).readSourceFile("Example.java").status()).isEqualTo("SUCCESS");
    }

    private Path write(String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
