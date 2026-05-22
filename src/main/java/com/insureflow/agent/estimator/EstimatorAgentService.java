package com.insureflow.agent.estimator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.insureflow.agent.shared.AgentResult;
import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.model.enums.Severity;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.estimator.CarPartsPricingService;
import com.insureflow.estimator.NonVehiclePricingService;
import com.insureflow.estimator.PriceEstimate;
import com.insureflow.estimator.SimilarClaimsService;
import com.insureflow.infrastructure.messaging.ClaimEvent;
import com.insureflow.infrastructure.messaging.RabbitMQConfig;
import com.insureflow.infrastructure.pricing.PricingResearchService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class EstimatorAgentService {

    private static final Logger log = LoggerFactory.getLogger(EstimatorAgentService.class);

    private final EstimatorAgent           estimatorAgent;
    private final VisionAnalysisService    visionAnalysisService;
    private final PricingResearchService   pricingResearchService;
    private final CarPartsPricingService   carPartsPricingService;
    private final NonVehiclePricingService nonVehiclePricingService;
    private final SimilarClaimsService     similarClaimsService;
    private final ClaimRepository          claimRepository;
    private final ImageQualityService      imageQualityService;
    private final RabbitTemplate           rabbitTemplate;
    private final ChatLanguageModel        chatModel;
    private final ObjectMapper             mapper = new ObjectMapper();

    // Known brand names that appear as `car` keys in the synthetic DB rows
    private static final String[] KNOWN_BRANDS = {
            "toyota", "renault", "peugeot", "volkswagen", "hyundai", "kia",
            "ford", "fiat", "citroen", "citroën", "dacia", "mercedes", "bmw",
            "audi", "nissan", "mitsubishi", "honda", "mazda", "suzuki",
            "chevrolet", "seat", "opel", "skoda"
    };

    // Kaggle rows use the model name as `car` key — recognise those too
    private static final String[] KNOWN_MODELS = {
            "camry", "corolla", "yaris", "hilux", "elantra", "sonata", "accent",
            "tucson", "sportage", "picanto", "clio", "duster", "symbol",
            "308", "208", "golf", "punto", "ranger", "focus", "transit"
    };

    private static final Pattern VEHICLE_INFO_PATTERN = Pattern.compile(
            "(Ford Ranger|Ford Focus|Ford Transit|Toyota Hilux|Toyota Corolla|" +
            "Peugeot 208|Peugeot 308|Renault Clio|Renault Duster|Renault Symbol|" +
            "Volkswagen Golf|Hyundai Tucson|Hyundai i10|Kia Sportage|Kia Picanto|" +
            "Fiat Punto|Fiat 500|Citroën C3|Citroën Berlingo|" +
            "Mercedes Classe|BMW Série|Audi A|Nissan Qashqai|Mitsubishi L200)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20[12][0-9])\\b");
    private static final Pattern LLM_RANGE_PATTERN = Pattern.compile("(\\d{2,7})\\s*[-–]\\s*(\\d{2,7})");
    private static final BigDecimal DEFAULT_CAR_VALUE_TND = BigDecimal.valueOf(25_000);
    private static final double TOTAL_LOSS_RATIO = 0.85;

    public EstimatorAgentService(EstimatorAgent estimatorAgent,
                                 VisionAnalysisService visionAnalysisService,
                                 PricingResearchService pricingResearchService,
                                 CarPartsPricingService carPartsPricingService,
                                 NonVehiclePricingService nonVehiclePricingService,
                                 SimilarClaimsService similarClaimsService,
                                 ClaimRepository claimRepository,
                                 ImageQualityService imageQualityService,
                                 RabbitTemplate rabbitTemplate,
                                 ChatLanguageModel chatModel) {
        this.estimatorAgent          = estimatorAgent;
        this.visionAnalysisService   = visionAnalysisService;
        this.pricingResearchService  = pricingResearchService;
        this.carPartsPricingService  = carPartsPricingService;
        this.nonVehiclePricingService = nonVehiclePricingService;
        this.similarClaimsService    = similarClaimsService;
        this.claimRepository         = claimRepository;
        this.imageQualityService     = imageQualityService;
        this.rabbitTemplate          = rabbitTemplate;
        this.chatModel               = chatModel;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_ESTIMATED)
    public void onEstimated(ClaimEvent event) {
        log.info("[ESTIMATOR] Processing claimId={}", event.getClaimId());
        claimRepository.updateStatus(event.getClaimId(), ClaimStatus.ESTIMATING);

        AgentResult result = runEstimator(event);

        claimRepository.findById(event.getClaimId()).ifPresent(claim -> {
            claim.setEstimatorResult(result.getResultJson());
            BigDecimal cost = parseTotalCost(result.getResultJson());
            if (cost != null) claim.setEstimatedCost(cost);
            claimRepository.save(claim);
        });

        log.info("[ESTIMATOR] Completed claimId={}", event.getClaimId());
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.Q_FRAUD, event);
    }

    public AgentResult runEstimator(ClaimEvent event) {
        try {
            List<String> photoUrls   = event.getPhotoUrls();
            double       imageScore  = imageQualityService.evaluate(photoUrls);
            String       claimType   = resolveClaimType(event);
            String       vehicleInfo = extractVehicleInfo(event.getDescription(), claimType);
            boolean      hasPhotos   = photoUrls != null && !photoUrls.isEmpty();

            // Step 1: Text analysis — always runs, provides the base JSON
            log.info("[ESTIMATOR] Text analysis for base result");
            String textRaw = estimatorAgent.analyse(
                    claimType,
                    event.getDescription(),
                    hasPhotos ? String.join("\n", photoUrls) : "Aucune photo"
            );

            // Step 2: Vision analysis — supplements text, never replaces it
            boolean visionUsed = false;
            String raw = textRaw;
            if (hasPhotos) {
                log.info("[ESTIMATOR] Vision analysis with llama3.2-vision");
                String visionRaw = visionAnalysisService.analyse(photoUrls, claimType);
                if (visionRaw != null) {
                    raw = mergeRawAnalysis(textRaw, visionRaw);
                    visionUsed = true;
                }
            }

            String               json            = ResponseParser.extractJson(raw);
            json                                 = correctSeverity(json, event.getDescription(), claimType);
            List<DamagedElement> elements        = parseDamagedElements(json);
            Severity             overallSeverity = parseOverallSeverity(json);

            // Step 3: Price each element — DB first, SerpAPI fallback
            CostEstimate costs = lookupCosts(elements, claimType, vehicleInfo,
                    overallSeverity, event.getDescription());

            // Step 4: Similarity search — non-blocking, enriches LLM context
            List<SimilarClaimsService.SimilarClaim> similar =
                    similarClaimsService.findSimilar(event.getDescription(), claimType, 3);
            String similarContext = buildSimilarClaimsContext(similar);

            String enriched = buildResultJson(json, costs, imageScore, claimType, visionUsed, similarContext);
            double conf = ResponseParser.getDouble(json, "confidence", 0.5);

            return AgentResult.success(enriched, conf, "");

        } catch (Exception e) {
            log.error("[ESTIMATOR] Failed claimId={}: {}", event.getClaimId(), e.getMessage());
            return AgentResult.failure(e.getMessage());
        }
    }

    // ── Pricing — DB first, SerpAPI fallback, LLM last resort ────────────────

    private CostEstimate lookupCosts(List<DamagedElement> elements,
                                     String claimType,
                                     String vehicleInfo,
                                     Severity overallSeverity,
                                     String description) {

        String  carBrand  = extractCarBrand(vehicleInfo);
        Integer modelYear = extractYear(description);

        // Bug fix: extractVehicleInfo only matches full "Brand Model" patterns (e.g. "Toyota Corolla").
        // When the description says "Toyota 2021" without a model name, vehicleInfo is null and
        // carBrand is empty, causing the entire DB path to be skipped.  Scan the description
        // text directly so we never miss a DB lookup when the car brand is mentioned.
        if (carBrand.isEmpty() && "VEHICLE_DAMAGE".equals(claimType)) {
            carBrand = extractCarBrandFromDescription(description);
            if (!carBrand.isEmpty()) {
                log.info("[ESTIMATOR] [DIAG] carBrand from description: '{}'", carBrand);
            }
        }

        // ── TOTAL_LOSS short-circuit ───────────────────────────────────────────
        boolean anyPartIsTotalLoss = elements.stream()
                .anyMatch(e -> e.severity() == Severity.TOTAL_LOSS);
        if ((overallSeverity == Severity.TOTAL_LOSS || anyPartIsTotalLoss)
                && "VEHICLE_DAMAGE".equals(claimType)) {
            return buildVehicleTotalLossEstimate(carBrand, modelYear, vehicleInfo, claimType);
        }

        List<DamagedElement> toPrice;
        if (overallSeverity == Severity.TOTAL_LOSS && "PROPERTY_DAMAGE".equals(claimType)) {
            toPrice = List.of(new DamagedElement("bâtiment", Severity.TOTAL_LOSS));
            log.info("[ESTIMATOR] TOTAL_LOSS property → pricing single bâtiment element");
        } else {
            toPrice = deduplicateByDbKey(new ArrayList<>(elements));
        }

        // ── Diagnostic: what we're about to price ─────────────────────────────
        log.info("[ESTIMATOR] [DIAG] vehicleInfo='{}' carBrand='{}' modelYear={} claimType={}",
                vehicleInfo, carBrand, modelYear, claimType);
        log.info("[ESTIMATOR] [DIAG] Parts to price ({}): {}",
                toPrice.size(),
                toPrice.stream()
                        .map(e -> e.name() + "(" + e.severity() + ")")
                        .collect(Collectors.joining(", ")));

        BigDecimal   totalMin    = BigDecimal.ZERO;
        BigDecimal   totalMax    = BigDecimal.ZERO;
        BigDecimal   dbFloorTotal = BigDecimal.ZERO; // accumulates allShopsAvg for every DB hit
        List<String> breakdown   = new ArrayList<>();
        int          dbHits      = 0;
        int          serpHits    = 0;

        for (DamagedElement el : toPrice) {
            boolean priced = false;

            // ── DB — primary source ───────────────────────────────────────────
            if ("VEHICLE_DAMAGE".equals(claimType) && !carBrand.isEmpty()) {
                try {
                    Optional<PriceEstimate> dbHit =
                            carPartsPricingService.estimatePrice(carBrand, modelYear, el.name());
                    if (dbHit.isPresent()) {
                        PriceEstimate pe         = dbHit.get();
                        BigDecimal    allShopsAvg = pe.allShopsAvgPrice();
                        // Bug 1 fix: use shop1-avg as min-bound and shop3-avg as max-bound.
                        // allShopsAvgPrice is the midpoint ground-truth for the DB floor guard.
                        BigDecimal    partMin     = pe.minPrice()  != null ? pe.minPrice()  : allShopsAvg;
                        BigDecimal    partMax     = pe.maxPrice()  != null ? pe.maxPrice()  : allShopsAvg;
                        dbFloorTotal = dbFloorTotal.add(allShopsAvg);
                        totalMin     = totalMin.add(partMin);
                        totalMax     = totalMax.add(partMax);
                        breakdown.add(String.format("%s (%s): %.2f–%.2f TND (mid %.2f) [DB/%s]",
                                el.name(), el.severity(),
                                partMin, partMax, allShopsAvg, pe.confidenceLevel()));
                        dbHits++;
                        priced = true;
                        log.info("[ESTIMATOR] [DIAG] part='{}' → dbKey mapped | " +
                                 "shop1_avg={} | shop2_avg={} | shop3_avg={} | all_avg={} | conf={}",
                                el.name(),
                                pe.minPrice()  != null ? String.format("%.2f", pe.minPrice())  : "null",
                                pe.avgPrice()  != null ? String.format("%.2f", pe.avgPrice())  : "null",
                                pe.maxPrice()  != null ? String.format("%.2f", pe.maxPrice())  : "null",
                                String.format("%.2f", allShopsAvg),
                                pe.confidenceLevel());
                    }
                } catch (Exception ex) {
                    log.warn("[ESTIMATOR] DB lookup failed '{}': {}", el.name(), ex.getMessage());
                }
            }

            // ── NonVehicle DB — for THEFT / PROPERTY_DAMAGE / NATURAL_DISASTER / HEALTH ─
            if (!priced && !"VEHICLE_DAMAGE".equals(claimType)) {
                try {
                    Optional<PriceEstimate> nvHit =
                            nonVehiclePricingService.findByClaimTypeAndDescription(
                                    claimType, el.name() + " " + description);
                    if (nvHit.isPresent()) {
                        PriceEstimate pe  = nvHit.get();
                        BigDecimal avg    = pe.allShopsAvgPrice();
                        BigDecimal partMin = pe.minPrice() != null ? pe.minPrice() : avg;
                        BigDecimal partMax = pe.maxPrice() != null ? pe.maxPrice() : avg;
                        dbFloorTotal = dbFloorTotal.add(avg);
                        totalMin     = totalMin.add(partMin);
                        totalMax     = totalMax.add(partMax);
                        breakdown.add(String.format("%s (%s): %.2f–%.2f TND (mid %.2f) [NonVehicleDB/%s]",
                                el.name(), el.severity(), partMin, partMax, avg, pe.confidenceLevel()));
                        dbHits++;
                        priced = true;
                    } else {
                        log.warn("[ESTIMATOR] NonVehicle DB: no match for type={} element='{}'",
                                claimType, el.name());
                    }
                } catch (Exception ex) {
                    log.warn("[ESTIMATOR] NonVehicle DB lookup failed '{}': {}", el.name(), ex.getMessage());
                }
            }

            // ── SerpAPI — fallback when DB has no data ────────────────────────
            if (!priced) {
                try {
                    Optional<PricingResearchService.PriceRange> found =
                            pricingResearchService.searchRepairCost(
                                    el.name(), el.severity().name(), vehicleInfo, claimType);
                    if (found.isPresent()) {
                        PricingResearchService.PriceRange p = found.get();
                        totalMin = totalMin.add(p.min());
                        totalMax = totalMax.add(p.max());
                        breakdown.add(String.format("%s (%s): %.0f–%.0f TND [%s]",
                                el.name(), el.severity(), p.min(), p.max(), p.source()));
                        serpHits++;
                        log.info("[ESTIMATOR] SerpAPI '{}': {}-{} TND", el.name(), p.min(), p.max());
                    } else {
                        breakdown.add(String.format("%s (%s): recherche infructueuse",
                                el.name(), el.severity()));
                        log.warn("[ESTIMATOR] No price — '{}'", el.name());
                    }
                } catch (Exception ex) {
                    log.warn("[ESTIMATOR] SerpAPI failed '{}': {}", el.name(), ex.getMessage());
                    breakdown.add(String.format("%s (%s): SerpAPI indisponible",
                            el.name(), el.severity()));
                }
            }
        }

        // ── Determine pricing method ──────────────────────────────────────────
        int    totalHits       = dbHits + serpHits;
        String pricingMethod;
        String pricingConfidence;

        if (totalHits == 0) {
            log.warn("[ESTIMATOR] Zero hits — LLM fallback (dbFloor={} TND)", dbFloorTotal);
            PricingResearchService.PriceRange llm =
                    llmFallback(overallSeverity, claimType, vehicleInfo, description, dbFloorTotal);
            if (llm != null) {
                totalMin = llm.min();
                totalMax = llm.max();
                breakdown.add(String.format("Estimation LLM (%s): %.0f–%.0f TND [NON FIABLE]",
                        overallSeverity, llm.min(), llm.max()));
                pricingMethod     = "llm_fallback";
                pricingConfidence = "low";
                log.info("[ESTIMATOR] [DIAG] LLM output: {}-{} TND (midpoint: {} TND)",
                        llm.min(), llm.max(),
                        llm.min().add(llm.max()).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
            } else {
                breakdown.add(String.format("Estimation impossible (%s): toutes sources indisponibles",
                        overallSeverity));
                pricingMethod     = "unavailable";
                pricingConfidence = "none";
                log.warn("[ESTIMATOR] Pricing unavailable [claimType={} severity={}]",
                        claimType, overallSeverity);
            }
        } else if (dbHits > 0 && serpHits == 0) {
            pricingMethod     = "db";
            pricingConfidence = "high";
        } else if (dbHits > 0) {
            pricingMethod     = "mixed";
            pricingConfidence = "medium";
        } else {
            pricingMethod     = totalHits < toPrice.size() ? "mixed" : "serp";
            pricingConfidence = totalHits < toPrice.size() ? "medium" : "high";
        }

        BigDecimal midpoint = totalMin.add(totalMax)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        log.info("[ESTIMATOR] [DIAG] DB floor: {} TND | raw estimate: {} TND | " +
                 "parts priced: {}/{} (db={} serp={})",
                dbFloorTotal, midpoint, totalHits, toPrice.size(), dbHits, serpHits);

        // ── Validation guard ──────────────────────────────────────────────────
        // The DB all-shops average is ground truth. If any pricing source (SerpAPI or LLM)
        // returned less than 50% of that floor, override with the DB total.
        if (dbFloorTotal.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal floor50 = dbFloorTotal.multiply(BigDecimal.valueOf(0.5))
                    .setScale(2, RoundingMode.HALF_UP);
            if (midpoint.compareTo(floor50) < 0) {
                log.warn("[ESTIMATOR] GUARD: estimate {} TND < 50% of DB floor {} TND → " +
                         "overriding to {} TND", midpoint, dbFloorTotal, dbFloorTotal);
                totalMin          = dbFloorTotal;
                totalMax          = dbFloorTotal;
                midpoint          = dbFloorTotal;
                if ("llm_fallback".equals(pricingMethod)) pricingMethod = "db";
                pricingConfidence = "medium";
                breakdown.add(String.format(
                        "⚠ Estimation corrigée par plancher DB: %.2f TND", dbFloorTotal));
            }
        }

        log.info("[ESTIMATOR] [DIAG] Final estimate: {} TND [method={} confidence={}]",
                midpoint, pricingMethod, pricingConfidence);

        return new CostEstimate(totalMin, totalMax, midpoint, breakdown,
                pricingMethod, pricingConfidence);
    }

    /**
     * TOTAL_LOSS vehicle estimate: car market value (from DB) × 85 %.
     * Falls back to SerpAPI if the DB has no data for the given car/year.
     */
    private CostEstimate buildVehicleTotalLossEstimate(String carBrand, Integer modelYear,
                                                       String vehicleInfo, String claimType) {
        BigDecimal carValue   = BigDecimal.ZERO;
        String     dataSource = "db";

        try {
            carValue = carPartsPricingService.estimateCarValue(carBrand, modelYear);
            if (carValue.compareTo(BigDecimal.ZERO) > 0) {
                log.info("[ESTIMATOR] TOTAL_LOSS car value from DB (car={} year={}): {} TND",
                        carBrand, modelYear, carValue);
            }
        } catch (Exception e) {
            log.warn("[ESTIMATOR] estimateCarValue failed: {}", e.getMessage());
        }

        if (carValue.compareTo(BigDecimal.ZERO) <= 0) {
            try {
                String vehicleName = vehicleInfo != null ? vehicleInfo : "véhicule";
                Optional<PricingResearchService.PriceRange> serpResult =
                        pricingResearchService.searchRepairCost(
                                vehicleName, "TOTAL_LOSS", vehicleInfo, claimType);
                if (serpResult.isPresent()) {
                    carValue   = serpResult.get().max();
                    dataSource = "serpapi";
                    log.info("[ESTIMATOR] TOTAL_LOSS car value from SerpAPI: {} TND", carValue);
                }
            } catch (Exception e) {
                log.warn("[ESTIMATOR] SerpAPI TOTAL_LOSS fallback failed: {}", e.getMessage());
            }
        }

        if (carValue.compareTo(BigDecimal.ZERO) <= 0) {
            carValue   = DEFAULT_CAR_VALUE_TND;
            dataSource = "db";
            log.warn("[ESTIMATOR] TOTAL_LOSS: no market value found, defaulting to 25 000 TND");
        }

        BigDecimal totalLoss = carValue.multiply(BigDecimal.valueOf(TOTAL_LOSS_RATIO))
                .setScale(2, RoundingMode.HALF_UP);

        String label = (carBrand == null || carBrand.isEmpty()) ? "véhicule"
                : carBrand + (modelYear != null ? " " + modelYear : "");
        String line  = String.format(
                "PERTE TOTALE (%s): valeur estimée %.0f TND × 85%% = %.2f TND [%s]",
                label, carValue, totalLoss, dataSource.toUpperCase());

        return new CostEstimate(totalLoss, totalLoss, totalLoss,
                List.of(line), dataSource, "medium");
    }

    /**
     * LLM fallback — called ONLY when ALL SerpAPI queries failed.
     *
     * @param dbFloor  Sum of DB all-shops-avg prices for this claim (may be 0 when DB also missed).
     *                 When non-zero it is injected into the prompt as a mandatory minimum, so the
     *                 LLM cannot produce a figure below it.
     */
    private PricingResearchService.PriceRange llmFallback(Severity severity, String claimType,
                                                          String vehicleInfo, String description,
                                                          BigDecimal dbFloor) {
        try {
            String vehicle = vehicleInfo != null ? vehicleInfo : "véhicule standard";

            // When we have a DB floor, pass it explicitly so the LLM treats it as a hard minimum
            String dbContext = (dbFloor != null && dbFloor.compareTo(BigDecimal.ZERO) > 0)
                    ? String.format(
                            "RÉFÉRENCE BASE DE DONNÉES: %.0f TND.\n" +
                            "Votre estimation DOIT être au moins égale à ce montant.\n",
                            dbFloor)
                    : "";

            String prompt = String.format(
                    "Tu es un expert en coûts de réparation automobile en Tunisie.\n%s" +
                    "Sinistre: %s. Véhicule: %s. Sévérité: %s.\nDescription: %s\n\n" +
                    "Fournis UNIQUEMENT le coût total estimé en dinars tunisiens (TND) " +
                    "sous la forme MIN–MAX (exemple: 800–2500). Aucun autre texte.",
                    dbContext, claimType, vehicle, severity.name(),
                    description != null ? description : "Non fournie");

            Response<AiMessage> resp = chatModel.generate(UserMessage.from(prompt));
            String raw = resp.content().text().trim();
            log.info("[ESTIMATOR] LLM fallback raw: '{}'", raw);

            Matcher rangeMatcher = LLM_RANGE_PATTERN.matcher(raw);
            List<long[]> found = new ArrayList<>();
            while (rangeMatcher.find()) {
                long min = Long.parseLong(rangeMatcher.group(1));
                long max = Long.parseLong(rangeMatcher.group(2));
                if (min >= 1990 && min <= 2030) continue;
                if (max > min && (double) max / min <= 15.0) found.add(new long[]{min, max});
            }

            if (!found.isEmpty()) {
                long fMin = found.stream().mapToLong(r -> r[0]).min().orElse(0);
                long fMax = found.stream().mapToLong(r -> r[1]).max().orElse(0);
                BigDecimal min = BigDecimal.valueOf(fMin);
                BigDecimal max = BigDecimal.valueOf(fMax);
                BigDecimal mid = min.add(max).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                log.info("[ESTIMATOR] [DIAG] LLM fallback parsed range: {}-{} TND (mid={})",
                        min, max, mid);
                return new PricingResearchService.PriceRange(
                        min, max, mid, "estimation LLM",
                        "LLaMA 3.1 — estimation non vérifiée (non fiable)", "TND");
            }

        } catch (Exception e) {
            log.error("[ESTIMATOR] LLM fallback failed: {}", e.getMessage());
        }
        return null;
    }

    // ── Severity post-processing ──────────────────────────────────────────────

    private String correctSeverity(String json, String description, String claimType) {
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject()) return json;

            Severity current       = parseSeverity(root.path("overallSeverity").asText("MODERATE"));
            int      totalLossCount = 0;
            int      severeCount    = 0;
            int      totalElements  = 0;

            JsonNode elements = root.path("damagedElements");
            if (elements.isArray()) {
                totalElements = elements.size();
                for (JsonNode item : elements) {
                    Severity s = parseSeverity(item.path("severity").asText("MINOR"));
                    if (s == Severity.TOTAL_LOSS) totalLossCount++;
                    if (s == Severity.SEVERE || s == Severity.TOTAL_LOSS) severeCount++;
                }
            }

            boolean structuralKeywords = false;
            String desc = description == null ? "" : description.toLowerCase();
            for (String kw : List.of(
                    "châssis tordu", "châssis plié", "habitacle écrasé", "moteur éjecté",
                    "toit effondré", "retourné", "renversé", "complètement détruit",
                    "completely destroyed", "perte totale", "épave", "irréparable",
                    "destruction totale", "véhicule inutilisable",
                    "incendie", "feu", "brûlé", "brulé", "fire", "burnt", "burned",
                    "court-circuit", "inondation", "effondrement", "salle détruite",
                    "bâtiment détruit", "dégâts importants", "reconstruction nécessaire",
                    "structure compromise", "murs calcinés", "plafond effondré",
                    "électrique", "explosion", "fumée", "soot", "smoke damage")) {
                if (desc.contains(kw)) { structuralKeywords = true; break; }
            }

            boolean isPropertyFire = "PROPERTY_DAMAGE".equals(claimType) &&
                    (desc.contains("incendie") || desc.contains("feu") ||
                            desc.contains("brûlé") || desc.contains("brulé") ||
                            desc.contains("fire") || desc.contains("inondation") ||
                            desc.contains("explosion") || desc.contains("court-circuit"));

            boolean isFireOrDisaster = "PROPERTY_DAMAGE".equals(claimType) &&
                    (desc.contains("incendie") || desc.contains("feu") ||
                     desc.contains("brûlé") || desc.contains("brulé") ||
                     desc.contains("fire") || desc.contains("court-circuit") ||
                     desc.contains("inondation") || desc.contains("explosion") ||
                     desc.contains("dégâts importants") || desc.contains("catastrophe") ||
                     desc.contains("sinistre important"));

            if (isFireOrDisaster) {
                current = Severity.TOTAL_LOSS;
                log.info("[ESTIMATOR] Fire/disaster on PROPERTY_DAMAGE → forced TOTAL_LOSS");
            }

            if (!isFireOrDisaster && totalLossCount >= 3) {
                current = Severity.TOTAL_LOSS;
            } else if (!isFireOrDisaster && structuralKeywords && totalLossCount >= 1) {
                current = Severity.TOTAL_LOSS;
            } else if (!isFireOrDisaster && totalElements > 0
                    && (double) severeCount / totalElements > 0.6
                    && totalLossCount >= 2) {
                current = Severity.TOTAL_LOSS;
            } else if (current == Severity.TOTAL_LOSS && totalLossCount < 2
                    && !structuralKeywords && !isPropertyFire && !isFireOrDisaster) {
                if (severeCount >= 2) {
                    current = Severity.SEVERE;
                    log.info("[ESTIMATOR] Downgraded spurious TOTAL_LOSS → SEVERE " +
                            "(only {} TOTAL_LOSS elements, no structural keywords)", totalLossCount);
                }
            }

            if (severeCount >= 3 && current != Severity.TOTAL_LOSS) {
                if (current == Severity.MINOR || current == Severity.MODERATE)
                    current = Severity.SEVERE;
            }

            if (isPropertyFire && (current == Severity.MINOR || current == Severity.MODERATE)) {
                current = Severity.SEVERE;
                log.info("[ESTIMATOR] Upgraded to SEVERE minimum: property fire claim");
            }

            ((com.fasterxml.jackson.databind.node.ObjectNode) root)
                    .put("overallSeverity", current.name());
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[ESTIMATOR] correctSeverity failed: {}", e.getMessage());
            return json;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveClaimType(ClaimEvent event) {
        return claimRepository.findById(event.getClaimId())
                .map(c -> c.getType() != null ? c.getType().name() : "UNKNOWN")
                .orElse("UNKNOWN");
    }

    private String extractVehicleInfo(String description, String claimType) {
        if (!"VEHICLE_DAMAGE".equals(claimType) || description == null) return null;
        Matcher m = VEHICLE_INFO_PATTERN.matcher(description);
        return m.find() ? m.group(0).trim() : null;
    }

    /** Extracts the lowercase brand token from a matched vehicle name like "Toyota Corolla" → "toyota". */
    private String extractCarBrand(String vehicleInfo) {
        if (vehicleInfo == null || vehicleInfo.isBlank()) return "";
        return vehicleInfo.trim().toLowerCase().split("\\s+")[0];
    }

    /**
     * Scans free-text description for known vehicle brands or Kaggle model keys.
     * Used when extractVehicleInfo() returns null (no full "Brand Model" pattern matched),
     * which happens for descriptions like "Toyota 2021" that omit the model name.
     */
    private String extractCarBrandFromDescription(String description) {
        if (description == null) return "";
        String d = description.toLowerCase();
        for (String brand : KNOWN_BRANDS) {
            if (d.contains(brand)) return brand;
        }
        for (String model : KNOWN_MODELS) {
            if (d.contains(model)) return model;
        }
        return "";
    }

    /** Extracts a 4-digit model year (2010–2029) from free-text description. */
    private Integer extractYear(String description) {
        if (description == null) return null;
        Matcher m = YEAR_PATTERN.matcher(description);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private List<DamagedElement> parseDamagedElements(String json) {
        List<DamagedElement> elements = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(json).path("damagedElements");
            if (!arr.isArray()) return elements;
            for (JsonNode item : arr) {
                String name = item.has("element")
                        ? item.path("element").asText("")
                        : item.path("part").asText("");
                Severity severity = parseSeverity(item.path("severity").asText("MINOR"));
                if (!name.isBlank()) elements.add(new DamagedElement(name.trim(), severity));
            }
        } catch (Exception e) {
            log.warn("[ESTIMATOR] Could not parse elements: {}", e.getMessage());
        }
        return elements;
    }

    private Severity parseSeverity(String value) {
        try { return Severity.valueOf(value.toUpperCase().trim()); }
        catch (Exception e) { return Severity.MINOR; }
    }

    private Severity parseOverallSeverity(String json) {
        try { return parseSeverity(mapper.readTree(json).path("overallSeverity").asText("MODERATE")); }
        catch (Exception e) { return Severity.MODERATE; }
    }

    private BigDecimal parseTotalCost(String json) {
        try {
            String cost = mapper.readTree(json).path("estimatedCost").asText(null);
            if (cost == null || cost.equals("null")) return null;
            BigDecimal val = new BigDecimal(cost);
            return val.compareTo(BigDecimal.ZERO) == 0 ? null : val;
        } catch (Exception e) { return null; }
    }

    private String buildResultJson(String llmJson, CostEstimate costs,
                                   double imageQuality, String claimType,
                                   boolean visionUsed, String similarContext) {
        try {
            JsonNode llm         = mapper.readTree(llmJson);
            boolean  unavailable = "unavailable".equals(costs.pricingMethod());

            String dataSource = switch (costs.pricingMethod()) {
                case "db"           -> "db";
                case "serp"         -> "serpapi";
                case "mixed"        -> "mixed";
                case "llm_fallback" -> "llm_fallback";
                default             -> costs.pricingMethod();
            };

            ObjectNode node = mapper.createObjectNode()
                    .put("claimType",         claimType)
                    .put("overallSeverity",   llm.path("overallSeverity").asText("MODERATE"))
                    .put("currency",          "TND")
                    .put("pricingMethod",     costs.pricingMethod())
                    .put("pricingConfidence", costs.pricingConfidence())
                    .put("confidenceLevel",   costs.pricingConfidence().toUpperCase())
                    .put("dataSource",        dataSource)
                    .put("imageQualityScore", imageQuality)
                    .put("analysisMethod",    visionUsed ? "llama3.2-vision" : "llama3.1-textual")
                    .put("confidence",        llm.path("confidence").asDouble(0.5))
                    .put("reasoning",         llm.path("reasoning").asText(""));

            if (unavailable) {
                node.putNull("estimatedCostMin");
                node.putNull("estimatedCostMax");
                node.putNull("estimatedCost");
            } else {
                node.put("estimatedCostMin", costs.min().toString());
                node.put("estimatedCostMax", costs.max().toString());
                node.put("estimatedCost",    costs.midpoint().toString());
            }
            node.set("costBreakdown", mapper.valueToTree(costs.breakdown()));

            if (similarContext != null && !similarContext.isBlank()) {
                node.put("similarClaimsContext", similarContext);
            }

            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return llmJson;
        }
    }

    private String buildSimilarClaimsContext(List<SimilarClaimsService.SimilarClaim> similar) {
        if (similar == null || similar.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("Sinistres similaires traités:\n");
        for (int i = 0; i < similar.size(); i++) {
            SimilarClaimsService.SimilarClaim s = similar.get(i);
            int scorePct = (int) Math.round(s.similarity() * 100);
            sb.append(String.format("%d. [%s] %s → %.2f TND (similarité: %d%%)%n",
                    i + 1,
                    s.decision(),
                    s.description() != null
                            ? s.description().substring(0, Math.min(80, s.description().length()))
                            : "N/A",
                    s.estimatedAmount(),
                    scorePct));
        }

        // DIAG log
        if (!similar.isEmpty()) {
            String diagLine = similar.stream()
                    .map(s -> s.decision() + "@" + (int) Math.round(s.similarity() * 100) + "%")
                    .collect(Collectors.joining(" | "));
            log.info("[ESTIMATOR] Similar claims found: {}", diagLine);
        }

        return sb.toString().trim();
    }

    /**
     * Deduplicates parts by their normalized DB key so that synonyms like "phare gauche"
     * and "phare avant" — both resolving to "headlight (l)" — are priced only once.
     * When two parts share a DB key, the one with the higher severity is kept.
     */
    private List<DamagedElement> deduplicateByDbKey(List<DamagedElement> elements) {
        Map<String, DamagedElement> byKey = new LinkedHashMap<>();
        for (DamagedElement el : elements) {
            String key = carPartsPricingService.normalizeBodyPart(el.name());
            byKey.merge(key, el, (existing, incoming) ->
                    incoming.severity().ordinal() > existing.severity().ordinal() ? incoming : existing);
        }
        if (byKey.size() < elements.size()) {
            log.info("[ESTIMATOR] Deduplicated {} → {} parts by DB key", elements.size(), byKey.size());
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Merges vision-detected parts into the text-analysis JSON.
     * Text parts are never removed; vision parts are appended only when not already present.
     * Deduplication is by lowercased, trimmed part name.
     */
    private String mergeRawAnalysis(String textRaw, String visionRaw) {
        try {
            JsonNode baseNode   = mapper.readTree(ResponseParser.extractJson(textRaw));
            JsonNode visionNode = mapper.readTree(ResponseParser.extractJson(visionRaw));

            if (!baseNode.isObject()) return textRaw;

            Set<String> seen = new HashSet<>();
            JsonNode baseArr = baseNode.path("damagedElements");
            if (baseArr.isArray()) {
                for (JsonNode item : baseArr) {
                    String name = item.has("element")
                            ? item.path("element").asText("") : item.path("part").asText("");
                    seen.add(name.toLowerCase().trim());
                }
            }

            JsonNode visionArr = visionNode.path("damagedElements");
            if (visionArr.isArray() && baseArr.isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode merged =
                        (com.fasterxml.jackson.databind.node.ArrayNode) baseArr;
                for (JsonNode item : visionArr) {
                    String name = item.has("element")
                            ? item.path("element").asText("") : item.path("part").asText("");
                    String key = name.toLowerCase().trim();
                    if (!key.isEmpty() && seen.add(key)) {
                        merged.add(item);
                        log.info("[ESTIMATOR] Vision added new part: '{}'", name);
                    }
                }
            }

            return mapper.writeValueAsString(baseNode);
        } catch (Exception e) {
            log.warn("[ESTIMATOR] mergeRawAnalysis failed, using text only: {}", e.getMessage());
            return textRaw;
        }
    }

    private record DamagedElement(String name, Severity severity) {}

    private record CostEstimate(
            BigDecimal min, BigDecimal max, BigDecimal midpoint,
            List<String> breakdown, String pricingMethod, String pricingConfidence) {}
}
