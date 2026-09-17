package com.example.platform.agent.dto;

import com.example.platform.agent.tool.BuildRunner;
import com.example.platform.agent.tool.ChangedFilesReader;
import com.example.platform.agent.tool.GitDiffReader;
import com.example.platform.agent.tool.SourceFileReader;
import com.example.platform.agent.tool.TestRunner;

import java.util.List;

/** Review-flow evidence, a structured report and optional model observations. */
public record ReviewResponse(String reviewId, String status, String service, List<String> changedServices,
                             ChangedFilesReader.ChangedFilesResult changedFiles,
                             GitDiffReader.GitDiffResult gitDiff,
                             SourceFileReader.SourceFileResult sourceFile,
                             BuildRunner.BuildResult build,
                             TestRunner.TestResult tests,
                             SecurityResult security,
                             String analysis, String analysisStatus, int rejectedModelFindings,
                             ReviewReport report,
                             ApprovalInvitation approval,
                             List<String> notes, long durationMs) {}
