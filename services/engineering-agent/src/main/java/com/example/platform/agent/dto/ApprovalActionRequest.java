package com.example.platform.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApprovalActionRequest(@NotNull ApprovalAction action,
                                    @NotBlank @Size(max = 500) String reason) {}
