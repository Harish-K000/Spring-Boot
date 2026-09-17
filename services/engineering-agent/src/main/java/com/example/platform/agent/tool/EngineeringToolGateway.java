package com.example.platform.agent.tool;

import com.example.platform.agent.dto.SecurityResult;

/** The agent's fixed, read-only and bounded engineering operations. */
public interface EngineeringToolGateway {
    ChangedFilesReader.ChangedFilesResult changedFiles();
    GitDiffReader.GitDiffResult gitDiff();
    SourceFileReader.SourceFileResult sourceFile(String path);
    BuildRunner.BuildResult build(String service);
    TestRunner.TestResult tests(String service);
    SecurityResult security(String service);
    String headCommit();
}
