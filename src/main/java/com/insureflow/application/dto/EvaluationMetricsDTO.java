package com.insureflow.application.dto;

public record EvaluationMetricsDTO(
        String generatedAt,
        int    totalEvaluated,

        // Classification
        double classificationAccuracy,
        int    classificationCorrect,

        // Estimation
        double estimationMAE,
        double estimationMAPE,
        double estimationMedianPct,
        int    withinTenPercent,
        int    withinTwentyFivePercent,
        int    withinFiftyPercent,

        // Decision
        double decisionAccuracy,
        int    decisionCorrect,

        // Timing
        double avgProcessingSeconds,
        double minProcessingSeconds,
        double maxProcessingSeconds,
        double p95ProcessingSeconds,
        int    speedImprovementFactor
) {}
