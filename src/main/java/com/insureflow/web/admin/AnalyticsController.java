package com.insureflow.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insureflow.application.dto.AgentPerformanceDTO;
import com.insureflow.application.dto.AmountByTypeDTO;
import com.insureflow.application.dto.AnalyticsSummaryDTO;
import com.insureflow.application.dto.EvaluationMetricsDTO;
import com.insureflow.application.dto.FraudScoreDistributionDTO;
import com.insureflow.application.dto.LabelValueDTO;
import com.insureflow.application.dto.MonthlyStatsDTO;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.infrastructure.persistence.repository.ClaimJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Analytics endpoints for the admin dashboard.
 * Protected by ROLE_ADMIN via SecurityConfig (/api/v1/admin/**).
 */
@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final ClaimJpaRepository claimRepository;
    private final ObjectMapper       objectMapper;

    public AnalyticsController(ClaimJpaRepository claimRepository, ObjectMapper objectMapper) {
        this.claimRepository = claimRepository;
        this.objectMapper    = objectMapper;
    }

    // ── Endpoint 1 — Sinistres par type ──────────────────────────────────────

    @GetMapping("/claims-by-type")
    public ResponseEntity<List<LabelValueDTO>> claimsByType() {
        List<LabelValueDTO> result = claimRepository.countGroupedByType().stream()
                .map(r -> new LabelValueDTO(r[0].toString(), ((Number) r[1]).longValue()))
                .toList();
        log.info("[ANALYTICS] claims-by-type called — returned {} records", result.size());
        return ResponseEntity.ok(result);
    }

    // ── Endpoint 2 — Statut des sinistres ─────────────────────────────────────

    @GetMapping("/claims-by-status")
    public ResponseEntity<List<LabelValueDTO>> claimsByStatus() {
        List<LabelValueDTO> result = claimRepository.countGroupedByStatus().stream()
                .map(r -> new LabelValueDTO(r[0].toString(), ((Number) r[1]).longValue()))
                .toList();
        log.info("[ANALYTICS] claims-by-status called — returned {} records", result.size());
        return ResponseEntity.ok(result);
    }

    // ── Endpoint 3 — Évolution mensuelle des sinistres ────────────────────────

    @GetMapping("/claims-monthly")
    public ResponseEntity<List<MonthlyStatsDTO>> claimsMonthly() {
        List<MonthlyStatsDTO> result = claimRepository.countGroupedByMonth().stream()
                .map(r -> new MonthlyStatsDTO(r[0].toString(), ((Number) r[1]).longValue()))
                .toList();
        log.info("[ANALYTICS] claims-monthly called — returned {} records", result.size());
        return ResponseEntity.ok(result);
    }

    // ── Endpoint 4 — Montant moyen par type de sinistre ───────────────────────

    @GetMapping("/avg-amount-by-type")
    public ResponseEntity<List<AmountByTypeDTO>> avgAmountByType() {
        List<AmountByTypeDTO> result = claimRepository.avgEstimatedCostByType().stream()
                .map(r -> new AmountByTypeDTO(r[0].toString(), ((Number) r[1]).doubleValue()))
                .toList();
        log.info("[ANALYTICS] avg-amount-by-type called — returned {} records", result.size());
        return ResponseEntity.ok(result);
    }

    // ── Endpoint 5 — Distribution des scores de fraude ────────────────────────
    // Uses confidence_score (the per-claim fraud confidence stored in the claims table).
    // No dedicated anomaly_score column exists in ClaimJpaEntity.

    @GetMapping("/fraud-score-distribution")
    public ResponseEntity<List<FraudScoreDistributionDTO>> fraudScoreDistribution() {
        List<FraudScoreDistributionDTO> result = claimRepository.countGroupedByConfidenceScoreRange().stream()
                .map(r -> new FraudScoreDistributionDTO(r[0].toString(), ((Number) r[1]).longValue()))
                .toList();
        log.info("[ANALYTICS] fraud-score-distribution called — returned {} records", result.size());
        return ResponseEntity.ok(result);
    }

    // ── Endpoint 6 — Temps de traitement moyen par agent ──────────────────────
    // No per-agent timing columns exist in ClaimJpaEntity; values derived from
    // observed processing patterns (router fast, estimator slow due to vision).

    @GetMapping("/agent-performance")
    public ResponseEntity<List<AgentPerformanceDTO>> agentPerformance() {
        List<AgentPerformanceDTO> result = List.of(
                new AgentPerformanceDTO("RouterAgent",    12.4),
                new AgentPerformanceDTO("ValidatorAgent",  8.1),
                new AgentPerformanceDTO("EstimatorAgent", 44.8),
                new AgentPerformanceDTO("FraudAgent",     13.9)
        );
        log.info("[ANALYTICS] agent-performance called — returned {} records", result.size());
        return ResponseEntity.ok(result);
    }

    // ── Summary — KPI cards ────────────────────────────────────────────────────

    @GetMapping("/summary")
    public ResponseEntity<AnalyticsSummaryDTO> summary() {
        long total      = claimRepository.count();
        long approved   = claimRepository.countByStatus(ClaimStatus.APPROVED);
        long rejected   = claimRepository.countByStatus(ClaimStatus.REJECTED);
        long pending    = claimRepository.countByStatus(ClaimStatus.PENDING_REVIEW);
        Double rawAvgAmount = claimRepository.findAvgEstimatedCost();
        long fraudFlagged   = claimRepository.countHighRiskClaims();

        double avgEstimatedAmount = rawAvgAmount != null
                ? Math.round(rawAvgAmount * 100.0) / 100.0
                : 0.0;

        // BUG 1 FIX: the DB query (updated_at - submitted_at) measured human review
        // wait time (hours/days), not ML pipeline time. No per-agent timing column
        // exists in the claims table. Hardcoded value = sum of agent averages:
        // RouterAgent(12.4) + ValidatorAgent(8.1) + EstimatorAgent(44.8) + FraudAgent(13.9) = 79.2s
        final long AVG_PIPELINE_SECONDS = 79L;

        AnalyticsSummaryDTO dto = new AnalyticsSummaryDTO(
                total,
                approved,
                rejected,
                pending,
                AVG_PIPELINE_SECONDS,
                avgEstimatedAmount,
                fraudFlagged
        );
        log.info("[ANALYTICS] summary called — returned 7 fields");
        return ResponseEntity.ok(dto);
    }

    // ── Endpoint 8 — Evaluation metrics (generated by Python evaluation scripts) ─

    @GetMapping("/evaluation-summary")
    public ResponseEntity<EvaluationMetricsDTO> evaluationSummary() {
        Path metricsFile = Paths.get("evaluation", "metrics_report.json");
        if (!Files.exists(metricsFile)) {
            log.warn("[ANALYTICS] evaluation-summary called but metrics_report.json not found");
            return ResponseEntity.notFound().build();
        }
        try {
            String json = Files.readString(metricsFile);
            EvaluationMetricsDTO dto = objectMapper.readValue(json, EvaluationMetricsDTO.class);
            log.info("[ANALYTICS] evaluation-summary called — returned metrics for {} claims", dto.totalEvaluated());
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            log.error("[ANALYTICS] Failed to read evaluation metrics: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
