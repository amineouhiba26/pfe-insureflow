package com.insureflow.estimator;

import java.math.BigDecimal;
import java.util.List;

/**
 * Structured result produced by the estimator pipeline for a single claim.
 *
 * partBreakdown  — one entry per damaged element: "<part> (<severity>): min–max TND [source]"
 * totalEstimate  — midpoint of the estimated cost range in TND
 * currency       — always "TND"
 * confidenceLevel — "high", "medium", "low", or "none"
 * dataSource      — primary pricing source: "db", "serpapi", "mixed", "llm_fallback", or "unavailable"
 */
public record EstimationResult(
        List<String> partBreakdown,
        BigDecimal   totalEstimate,
        String       currency,
        String       confidenceLevel,
        String       dataSource
) {}
