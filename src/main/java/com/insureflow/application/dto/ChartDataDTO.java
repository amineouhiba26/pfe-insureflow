package com.insureflow.application.dto;

public record ChartDataDTO<T>(
        String label,
        T value
) {}
