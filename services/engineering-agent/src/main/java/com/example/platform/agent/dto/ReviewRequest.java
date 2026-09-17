package com.example.platform.agent.dto;

import jakarta.validation.constraints.Size;

/** Omit service only when the changed-file inventory identifies one service. */
public record ReviewRequest(@Size(max = 50) String service) {}
