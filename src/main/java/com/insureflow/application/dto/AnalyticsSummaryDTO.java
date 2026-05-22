package com.insureflow.application.dto;

public record AnalyticsSummaryDTO(
        long totalClaims,
        long approvedClaims,
        long rejectedClaims,
        long pendingClaims,
        long avgProcessingTimeSeconds,
        double avgEstimatedAmount,
        long fraudFlaggedClaims
) {}
