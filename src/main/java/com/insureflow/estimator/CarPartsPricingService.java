package com.insureflow.estimator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CarPartsPricingService {

    private static final Logger log = LoggerFactory.getLogger(CarPartsPricingService.class);

    private final CarPartsPriceRepository repo;

    @Value("${pricing.car-value-factor:4.5}")
    private double carValueFactor = 4.5;

    private static final Map<String, String> PART_ALIASES = Map.ofEntries(
            // ── Bumpers ────────────────────────────────────────────────────────────
            Map.entry("pare-choc avant",           "front bumper"),
            Map.entry("pare choc avant",            "front bumper"),
            Map.entry("front bumper",               "front bumper"),
            Map.entry("pare-choc arrière",          "rear bumper"),
            Map.entry("pare choc arrière",          "rear bumper"),
            Map.entry("pare-choc arriere",          "rear bumper"),
            Map.entry("rear bumper",                "rear bumper"),
            Map.entry("bouclier avant",              "front bumper"),
            Map.entry("bouclier arrière",            "rear bumper"),
            Map.entry("pare-choc latéral gauche",   "side bumper(l)"),
            Map.entry("side bumper left",           "side bumper(l)"),
            Map.entry("side bumper(l)",             "side bumper(l)"),
            Map.entry("pare-choc latéral droit",    "side bumper(r)"),
            Map.entry("side bumper right",          "side bumper(r)"),
            Map.entry("side bumper(r)",             "side bumper(r)"),
            // ── Hood / boot ────────────────────────────────────────────────────────
            Map.entry("capot",                      "car hood"),
            Map.entry("hood",                       "car hood"),
            Map.entry("car hood",                   "car hood"),
            Map.entry("coffre",                     "car boot"),
            Map.entry("trunk",                      "car boot"),
            Map.entry("car boot",                   "car boot"),
            // ── Doors ──────────────────────────────────────────────────────────────
            Map.entry("portière",                   "driver door(f/r)"),
            Map.entry("portiere",                   "driver door(f/r)"),
            Map.entry("door",                       "driver door(f/r)"),
            Map.entry("portière avant gauche",      "driver door(f/r)"),
            Map.entry("driver door",                "driver door(f/r)"),
            Map.entry("driver door(f/r)",           "driver door(f/r)"),
            Map.entry("portière avant droite",      "passenger door (f/l)"),
            Map.entry("passenger door front",       "passenger door (f/l)"),
            Map.entry("passenger door (f/l)",       "passenger door (f/l)"),
            Map.entry("portière arrière gauche",    "passenger door (r/l)"),
            Map.entry("passenger door (r/l)",       "passenger door (r/l)"),
            Map.entry("portière arrière droite",    "passenger door (r/r)"),
            Map.entry("passenger door (r/r)",       "passenger door (r/r)"),
            // ── Glass ──────────────────────────────────────────────────────────────
            Map.entry("pare-brise",                 "windshield"),
            Map.entry("windshield",                 "windshield"),
            // ── Headlights ─────────────────────────────────────────────────────────
            Map.entry("phare avant",                "headlight (l)"),
            Map.entry("phare avant gauche",         "headlight (l)"),
            Map.entry("headlight left",             "headlight (l)"),
            Map.entry("left headlight",             "headlight (l)"),
            Map.entry("headlight (l)",              "headlight (l)"),
            Map.entry("phare avant droit",          "headlight (r)"),
            Map.entry("headlight right",            "headlight (r)"),
            Map.entry("right headlight",            "headlight (r)"),
            Map.entry("headlight (r)",              "headlight (r)"),
            // ── Rear lights ────────────────────────────────────────────────────────
            Map.entry("feu arrière gauche",         "rear light (l)"),
            Map.entry("rear light left",            "rear light (l)"),
            Map.entry("left rear light",            "rear light (l)"),
            Map.entry("rear light (l)",             "rear light (l)"),
            Map.entry("feu arrière droit",          "rear light (r)"),
            Map.entry("rear light right",           "rear light (r)"),
            Map.entry("right rear light",           "rear light (r)"),
            Map.entry("rear light (r)",             "rear light (r)"),
            // ── Fenders ────────────────────────────────────────────────────────────
            Map.entry("aile avant gauche",          "fender (f/l)"),
            Map.entry("fender left",                "fender (f/l)"),
            Map.entry("left fender",                "fender (f/l)"),
            Map.entry("fender (f/l)",               "fender (f/l)"),
            Map.entry("aile avant droite",          "fender (f/r)"),
            Map.entry("fender right",               "fender (f/r)"),
            Map.entry("right fender",               "fender (f/r)"),
            Map.entry("fender (f/r)",               "fender (f/r)"),
            Map.entry("flanc gauche",               "fender (f/l)"),
            Map.entry("flanc avant gauche",         "fender (f/l)"),
            Map.entry("flanc droit",                "fender (f/r)"),
            Map.entry("flanc avant droit",          "fender (f/r)"),
            Map.entry("aile arrière gauche",        "fender (r/l)"),
            Map.entry("fender (r/l)",               "fender (r/l)"),
            Map.entry("aile arrière droite",        "fender (r/r)"),
            Map.entry("fender (r/r)",               "fender (r/r)"),
            // ── Other ──────────────────────────────────────────────────────────────
            Map.entry("rétroviseur",                "side mirror"),
            Map.entry("retroviseur",                "side mirror"),
            Map.entry("side mirror",                "side mirror"),
            Map.entry("toit",                       "roof"),
            Map.entry("roof",                       "roof")
    );

    public CarPartsPricingService(CarPartsPriceRepository repo) {
        this.repo = repo;
    }

    /**
     * Estimates repair cost for a single body part using a 3-level fallback strategy.
     *
     * @param car      vehicle brand (e.g. "Toyota Camry" or "toyota")
     * @param year     model year, may be null
     * @param bodyPart element name from the LLM (French or English)
     * @return PriceEstimate with confidence level, or empty if the part is unknown
     */
    public Optional<PriceEstimate> estimatePrice(String car, Integer year, String bodyPart) {
        String normalizedCar  = normalizeCar(car);
        String normalizedPart = normalizeBodyPart(bodyPart);

        if (normalizedPart.isEmpty()) return Optional.empty();

        // Level 1: exact — car + year + bodyPart
        if (normalizedCar != null && !normalizedCar.isEmpty() && year != null) {
            List<CarPartsPriceEntity> rows =
                    repo.findByCarAndYearAndBodyPart(normalizedCar, year, normalizedPart);
            if (!rows.isEmpty()) {
                log.debug("[PRICING-DB] Exact match: car={} year={} part={} ({} rows)",
                        normalizedCar, year, normalizedPart, rows.size());
                return Optional.of(buildEstimate(rows, "HIGH", normalizedCar, year, false));
            }
        }

        // Level 2: car + part (ignore year)
        if (normalizedCar != null && !normalizedCar.isEmpty()) {
            List<CarPartsPriceEntity> rows =
                    repo.findByCarAndBodyPart(normalizedCar, normalizedPart);
            if (!rows.isEmpty()) {
                log.debug("[PRICING-DB] Car+part match: car={} part={} ({} rows)",
                        normalizedCar, normalizedPart, rows.size());
                return Optional.of(buildEstimate(rows, "MEDIUM", normalizedCar, null, true));
            }
        }

        // Level 3: part only — average across all cars
        List<CarPartsPriceEntity> rows = repo.findByBodyPart(normalizedPart);
        if (!rows.isEmpty()) {
            log.debug("[PRICING-DB] Part-only match: part={} ({} rows)", normalizedPart, rows.size());
            return Optional.of(buildEstimate(rows, "LOW", null, null, true));
        }

        log.debug("[PRICING-DB] No DB data for car={} year={} part={}",
                normalizedCar, year, normalizedPart);
        return Optional.empty();
    }

    /**
     * Estimates a vehicle's market value by summing shop2 prices across all known parts
     * for the given car/year, then multiplying by carValueFactor (default 4.5).
     */
    public BigDecimal estimateCarValue(String car, Integer year) {
        String normalizedCar = normalizeCar(car);
        if (normalizedCar == null || normalizedCar.isEmpty()) return BigDecimal.ZERO;

        BigDecimal sum = BigDecimal.ZERO;

        if (year != null) {
            sum = repo.sumShop2ByCarAndYear(normalizedCar, year).orElse(BigDecimal.ZERO);
        }

        if (sum.compareTo(BigDecimal.ZERO) <= 0) {
            sum = repo.sumShop2ByCar(normalizedCar).orElse(BigDecimal.ZERO);
            if (sum.compareTo(BigDecimal.ZERO) > 0) {
                sum = sum.divide(BigDecimal.valueOf(7), 2, RoundingMode.HALF_UP);
            }
        }

        if (sum.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        return sum.multiply(BigDecimal.valueOf(carValueFactor)).setScale(2, RoundingMode.HALF_UP);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PriceEstimate buildEstimate(List<CarPartsPriceEntity> rows, String confidence,
                                        String matchedCar, Integer matchedYear, boolean fallback) {
        BigDecimal sumMid    = BigDecimal.ZERO;
        BigDecimal sumMax    = BigDecimal.ZERO;
        BigDecimal sumMin    = BigDecimal.ZERO;
        long       shop1Count = 0;
        BigDecimal sumRowAvg = BigDecimal.ZERO;

        for (CarPartsPriceEntity e : rows) {
            sumMid = sumMid.add(e.getShop2Price());
            sumMax = sumMax.add(e.getShop3Price());

            // Per-row average of non-null shops — implements the user-specified formula:
            // (COALESCE(shop1,0)+shop2+shop3) / NULLIF(nonNullCount, 0)
            BigDecimal rowSum  = e.getShop2Price().add(e.getShop3Price());
            int        nonNull = 2;
            if (e.getShop1Price() != null) {
                sumMin = sumMin.add(e.getShop1Price());
                shop1Count++;
                rowSum  = rowSum.add(e.getShop1Price());
                nonNull = 3;
            }
            sumRowAvg = sumRowAvg.add(
                    rowSum.divide(BigDecimal.valueOf(nonNull), 4, RoundingMode.HALF_UP));
        }

        int        n           = rows.size();
        BigDecimal avgMid      = sumMid.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        BigDecimal avgMax      = sumMax.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        BigDecimal avgMin      = shop1Count > 0
                ? sumMin.divide(BigDecimal.valueOf(shop1Count), 2, RoundingMode.HALF_UP)
                : null;
        BigDecimal allShopsAvg = sumRowAvg.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

        return new PriceEstimate(avgMin, avgMid, avgMax, allShopsAvg,
                confidence, matchedCar, matchedYear, fallback);
    }

    /** Extracts just the brand token from a vehicle description like "Toyota Camry" → "toyota". */
    String normalizeCar(String raw) {
        if (raw == null) return "";
        String first = raw.trim().toLowerCase().split("\\s+")[0];
        return switch (first) {
            case "citroën", "citroen" -> "citroen";
            case "volkswagen", "vw"   -> "volkswagen";
            default                   -> first;
        };
    }

    /**
     * Maps any element name (French or English) to its canonical DB body_part value.
     *
     * Priority:
     * 1. Exact alias match (case-insensitive)
     * 2. Keyword fallback — direction-aware for fender and headlight
     */
    public String normalizeBodyPart(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase();

        String mapped = PART_ALIASES.get(s);
        if (mapped != null) return mapped;

        // ── Keyword fallback — order matters ──────────────────────────────────

        if (s.contains("avant") && (s.contains("pare") || s.contains("choc"))) return "front bumper";
        if (s.contains("arrière") && (s.contains("pare") || s.contains("choc"))) return "rear bumper";
        if (s.contains("arriere") && (s.contains("pare") || s.contains("choc"))) return "rear bumper";

        if (s.contains("capot") || s.contains("hood"))  return "car hood";
        if (s.contains("coffre") || s.contains("boot") || s.contains("trunk")) return "car boot";
        if (s.contains("pare-brise") || s.contains("windshield")) return "windshield";

        // Headlight — direction-aware
        if (s.contains("phare") || s.contains("headlight")) {
            if (s.contains("right") || s.contains("droit") || s.contains("(r)")) return "headlight (r)";
            return "headlight (l)";
        }

        // Rear light — direction-aware
        if (s.contains("feu") || s.contains("rear light") || s.contains("taillight")) {
            if (s.contains("right") || s.contains("droit") || s.contains("(r)")) return "rear light (r)";
            return "rear light (l)";
        }

        // Fender — direction-aware (front/rear × left/right)
        if (s.contains("aile") || s.contains("fender")) {
            boolean isRight = s.contains("right") || s.contains("droit")
                    || s.contains("(f/r)") || s.contains("(r/r)");
            boolean isRear  = s.contains("rear") || s.contains("arrière")
                    || s.contains("arriere") || s.contains("(r/");
            if (isRear && isRight)  return "fender (r/r)";
            if (isRear)             return "fender (r/l)";
            if (isRight)            return "fender (f/r)";
            return "fender (f/l)";
        }

        if (s.contains("rétroviseur") || s.contains("retroviseur") || s.contains("mirror"))
            return "side mirror";
        if (s.contains("toit") || s.contains("roof")) return "roof";
        if (s.contains("portière") || s.contains("portiere") || s.contains("door"))
            return "driver door(f/r)";

        return s;
    }
}
