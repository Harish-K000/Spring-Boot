package com.example.platform.agent.tool;

import com.example.platform.agent.dto.SecurityResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deterministic in-process implementation for tests. */
@Component
@ConditionalOnProperty(name = "agent.execution.mode", havingValue = "local")
public class LocalEngineeringToolGateway implements EngineeringToolGateway {
    private final ChangedFilesReader files;
    private final GitDiffReader diff;
    private final SourceFileReader source;
    private final BuildRunner build;
    private final TestRunner tests;
    private final SecurityRunner security;
    private final RepositoryGit git;

    public LocalEngineeringToolGateway(ChangedFilesReader files, GitDiffReader diff,
                                       SourceFileReader source, BuildRunner build,
                                       TestRunner tests, SecurityRunner security, RepositoryGit git) {
        this.files = files;
        this.diff = diff;
        this.source = source;
        this.build = build;
        this.tests = tests;
        this.security = security;
        this.git = git;
    }

    public ChangedFilesReader.ChangedFilesResult changedFiles() { return files.read(); }
    public GitDiffReader.GitDiffResult gitDiff() { return diff.read(); }
    public SourceFileReader.SourceFileResult sourceFile(String path) { return source.read(path); }
    public BuildRunner.BuildResult build(String service) { return build.run(service); }
    public TestRunner.TestResult tests(String service) { return tests.run(service); }
    public SecurityResult security(String service) { return security.run(service); }
    public String headCommit() { return git.headCommit(); }
}
