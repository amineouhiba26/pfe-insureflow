package com.insureflow.estimator;

import java.math.BigDecimal;

/**
 * Result returned by CarPartsPricingService.estimatePrice().
 *
 * minPrice         — average of shop1 (cheapest aftermarket), null when all shop1 values are absent
 * avgPrice         — average of shop2 (mid-range)
 * maxPrice         — average of shop3 (OEM/dealer)
 * allShopsAvgPrice — canonical price: per-row average of all non-null shops, averaged across rows.
 *                    Use this as the single ground-truth price for cost summation.
 *                    Formula: AVG( (COALESCE(s1,0)+s2+s3) / nonNullCount ) across matching rows.
 * confidenceLevel  — HIGH (exact car+year+part), MEDIUM (car+part, year ignored), LOW (part only)
 * fallbackUsed     — true when year or car was not matched exactly
 */
public record PriceEstimate(
        BigDecimal minPrice,
        BigDecimal avgPrice,
        BigDecimal maxPrice,
        BigDecimal allShopsAvgPrice,
        String     confidenceLevel,
        String     matchedCar,
        Integer    matchedYear,
        boolean    fallbackUsed
) {}
