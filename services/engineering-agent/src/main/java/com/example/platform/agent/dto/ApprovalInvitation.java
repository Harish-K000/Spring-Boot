package com.example.platform.agent.dto;

/** Returned once with a completed review. The token is required for its action endpoint. */
public record ApprovalInvitation(String reviewId, String status, String recommendedAction,
                                 String actionToken) {}
