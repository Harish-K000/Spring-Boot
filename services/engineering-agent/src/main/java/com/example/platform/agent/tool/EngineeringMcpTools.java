package com.example.platform.agent.tool;

import com.example.platform.agent.dto.SecurityResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** Only these fixed methods are published by the loopback MCP server. */
@Configuration
@Profile("mcp-server")
public class EngineeringMcpTools {
    @Bean
    ToolCallbackProvider engineeringToolCallbacks(ChangedFilesReader files, GitDiffReader diff,
            SourceFileReader source, BuildRunner build, TestRunner tests, SecurityRunner security,
            RepositoryGit git) {
        return MethodToolCallbackProvider.builder().toolObjects(
                new ApprovedTools(files, diff, source, build, tests, security, git)).build();
    }

    public record HeadCommitResult(String commitHash) {}

    public static final class ApprovedTools {
        private final ChangedFilesReader files;
        private final GitDiffReader diff;
        private final SourceFileReader source;
        private final BuildRunner build;
        private final TestRunner tests;
        private final SecurityRunner security;
        private final RepositoryGit git;

        ApprovedTools(ChangedFilesReader files, GitDiffReader diff, SourceFileReader source,
                      BuildRunner build, TestRunner tests, SecurityRunner security, RepositoryGit git) {
            this.files = files; this.diff = diff; this.source = source;
            this.build = build; this.tests = tests; this.security = security; this.git = git;
        }

        @Tool(description = "List approved changed repository paths and statuses; no file contents.")
        public ChangedFilesReader.ChangedFilesResult getChangedFiles() { return files.read(); }

        @Tool(description = "Read bounded, redacted tracked Git diff from the approved repository scope.")
        public GitDiffReader.GitDiffResult getGitDiff() { return diff.read(); }

        @Tool(description = "Read one approved repository-relative Java, SQL or pom.xml source path.")
        public SourceFileReader.SourceFileResult readSourceFile(
                @ToolParam(description = "Approved repository-relative source path") String path) {
            return source.read(path);
        }

        @Tool(description = "Compile one registered service with the fixed Maven wrapper operation.")
        public BuildRunner.BuildResult runBuild(
                @ToolParam(description = "Exact registered service name") String service) {
            return build.run(service);
        }

        @Tool(description = "Run the fixed tests for one registered service and report exact counts.")
        public TestRunner.TestResult runTests(
                @ToolParam(description = "Exact registered service name") String service) {
            return tests.run(service);
        }

        @Tool(description = "Run the fixed bounded scanners for one registered service; secret values withheld.")
        public SecurityResult securityScan(
                @ToolParam(description = "Exact registered service name") String service) {
            return security.run(service);
        }

        @Tool(description = "Get the current Git HEAD commit hash for compact review audit metadata.")
        public HeadCommitResult getHeadCommit() { return new HeadCommitResult(git.headCommit()); }
    }
}
