package com.insureflow.application.dto;

public record AmountByTypeDTO(
        String claimType,
        double averageAmount
) {}
