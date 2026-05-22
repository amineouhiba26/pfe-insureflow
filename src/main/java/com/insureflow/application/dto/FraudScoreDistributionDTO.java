package com.insureflow.application.dto;

public record FraudScoreDistributionDTO(
        String range,
        long count
) {}
