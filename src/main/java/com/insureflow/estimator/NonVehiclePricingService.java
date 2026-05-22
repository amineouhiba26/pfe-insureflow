package com.insureflow.estimator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class NonVehiclePricingService {

    private static final Logger log = LoggerFactory.getLogger(NonVehiclePricingService.class);

    private final NonVehiclePriceRepository repo;

    public NonVehiclePricingService(NonVehiclePriceRepository repo) {
        this.repo = repo;
    }

    /**
     * Returns a PriceEstimate for non-vehicle claims by fuzzy-matching the description
     * against category and item_description columns (ILIKE).
     *
     * Strategy:
     *  1. Try each significant keyword extracted from the description
     *  2. Fall back to claim-type-wide average if no keyword matches
     */
    public Optional<PriceEstimate> findByClaimTypeAndDescription(String claimType,
                                                                  String description) {
        if (claimType == null || description == null) return Optional.empty();

        String normalizedType = claimType.toUpperCase();
        String[] keywords = extractKeywords(description);

        for (String kw : keywords) {
            if (kw.length() < 3) continue;
            List<NonVehiclePriceEntity> rows = repo.findBestMatch(normalizedType, kw);
            if (!rows.isEmpty()) {
                NonVehiclePriceEntity best = rows.get(0);
                log.info("[DIAG] NonVehicle pricing: category={} → min={} avg={} max={} TND",
                        best.getCategory(),
                        best.getMinPriceTnd(), best.getAvgPriceTnd(), best.getMaxPriceTnd());
                return Optional.of(toEstimate(best));
            }
        }

        // Type-level fallback: average across all entries for this claim type
        List<NonVehiclePriceEntity> fallback = repo.findByClaimType(normalizedType);
        if (!fallback.isEmpty()) {
            BigDecimal avgMin = average(fallback.stream().map(NonVehiclePriceEntity::getMinPriceTnd).toList());
            BigDecimal avgAvg = average(fallback.stream().map(NonVehiclePriceEntity::getAvgPriceTnd).toList());
            BigDecimal avgMax = average(fallback.stream().map(NonVehiclePriceEntity::getMaxPriceTnd).toList());
            log.warn("[ESTIMATOR] NonVehicle: no keyword match for '{}' in {} — using type average: {} TND",
                    description.substring(0, Math.min(40, description.length())), normalizedType, avgAvg);
            log.info("[DIAG] NonVehicle pricing: category=type_fallback → min={} avg={} max={} TND",
                    avgMin, avgAvg, avgMax);
            return Optional.of(new PriceEstimate(avgMin, avgAvg, avgMax, avgAvg,
                    "LOW", null, null, true));
        }

        return Optional.empty();
    }

    private String[] extractKeywords(String description) {
        // Split on spaces and punctuation, deduplicate, longest first
        return Arrays.stream(description.toLowerCase()
                        .split("[\\s,;.!?()/\\-]+"))
                .distinct()
                .filter(w -> w.length() >= 4)
                .sorted((a, b) -> b.length() - a.length())
                .toArray(String[]::new);
    }

    private PriceEstimate toEstimate(NonVehiclePriceEntity e) {
        return new PriceEstimate(
                e.getMinPriceTnd(),
                e.getAvgPriceTnd(),
                e.getMaxPriceTnd(),
                e.getAvgPriceTnd(),
                "MEDIUM",
                null, null, false
        );
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, java.math.RoundingMode.HALF_UP);
    }
}
