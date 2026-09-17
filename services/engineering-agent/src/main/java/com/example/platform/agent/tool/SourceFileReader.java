package com.example.platform.agent.tool;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.Set;

/** Reads bounded UTF-8 source through directory handles, without following symlinks. */
@Component
@Profile({"mcp-server", "test"})
public class SourceFileReader {
    static final int MAX_BYTES = 12_000;
    private static final String SCOPE = "Current working-tree Java, SQL or pom.xml file, including untracked files. "
            + "Maximum 12,000 bytes; known secret patterns are redacted, not comprehensively scanned. "
            + "This is not an immutable snapshot or evidence of a successful build or test.";
    private final RepositoryGit repository;

    public SourceFileReader(RepositoryGit repository) {
        this.repository = repository;
    }

    public record SourceFileResult(String status, String path, long durationMs, String content,
                                   boolean redacted, String scope, String message) {}

    public SourceFileResult read(String input) {
        long start = System.nanoTime();
        final Path relative;
        try {
            relative = ToolExecutionPolicy.sourcePath(input);
        } catch (IllegalArgumentException ex) {
            return result("DENIED", null, start, "", false, "Path is outside the approved source-file policy.");
        }
        try (var directory = Files.newDirectoryStream(repository.repositoryRoot())) {
            if (!(directory instanceof SecureDirectoryStream<Path> secure)) {
                return result("ERROR", relative.toString(), start, "", false,
                        "This filesystem does not support secure directory traversal; reading is disabled.");
            }
            byte[] bytes = readRelative(secure, relative, 0);
            String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (content.indexOf('\0') >= 0) throw new DeniedReadException();
            String safe = GitDiffReader.redact(content);
            return result("SUCCESS", relative.toString(), start, safe, !safe.equals(content),
                    "Source file read; no build or tests were run.");
        } catch (DeniedReadException | CharacterCodingException ex) {
            return result("DENIED", relative.toString(), start, "", false,
                    "Only regular UTF-8 files of at most 12,000 bytes are allowed; symlinks are denied.");
        } catch (IOException | SecurityException ex) {
            return result("ERROR", relative.toString(), start, "", false,
                    "Cannot safely read this path. Check the repository, file existence and permissions; symlinks are not followed.");
        }
    }

    private byte[] readRelative(SecureDirectoryStream<Path> directory, Path path, int index) throws IOException {
        Path name = path.getName(index);
        var attributes = directory.getFileAttributeView(name, BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (attributes.isSymbolicLink()) throw new DeniedReadException();
        if (index < path.getNameCount() - 1) {
            if (!attributes.isDirectory()) throw new DeniedReadException();
            try (var child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                return readRelative(child, path, index + 1);
            }
        }
        if (!attributes.isRegularFile() || attributes.size() > MAX_BYTES) throw new DeniedReadException();
        try (var channel = directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            // Read one extra byte to detect growth after the size check. Never return partial content.
            ByteBuffer bytes = ByteBuffer.allocate(MAX_BYTES + 1);
            while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
            if (bytes.position() > MAX_BYTES) throw new DeniedReadException();
            bytes.flip();
            byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        }
    }

    private static SourceFileResult result(String status, String path, long start, String content,
                                            boolean redacted, String message) {
        return new SourceFileResult(status, path, (System.nanoTime() - start) / 1_000_000,
                content, redacted, SCOPE, message);
    }

    private static final class DeniedReadException extends IOException {}
}
