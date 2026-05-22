package com.insureflow.infrastructure.persistence.repository;

import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.infrastructure.persistence.entity.ClaimJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ClaimJpaRepository extends JpaRepository<ClaimJpaEntity, UUID> {
    List<ClaimJpaEntity> findByClientId(UUID clientId);
    List<ClaimJpaEntity> findByStatus(ClaimStatus status);
    long countByStatus(ClaimStatus status);

    // ── Analytics queries ─────────────────────────────────────────────────────

    @Query("SELECT c.type, COUNT(c) FROM ClaimJpaEntity c WHERE c.type IS NOT NULL GROUP BY c.type")
    List<Object[]> countGroupedByType();

    @Query("SELECT c.status, COUNT(c) FROM ClaimJpaEntity c GROUP BY c.status")
    List<Object[]> countGroupedByStatus();

    @Query(value = """
            SELECT TO_CHAR(submitted_at, 'YYYY-MM') AS month, COUNT(*) AS cnt
            FROM claims
            WHERE submitted_at IS NOT NULL
            GROUP BY TO_CHAR(submitted_at, 'YYYY-MM')
            ORDER BY TO_CHAR(submitted_at, 'YYYY-MM')
            """, nativeQuery = true)
    List<Object[]> countGroupedByMonth();

    @Query(value = """
            SELECT type, AVG(estimated_cost) AS avg_cost
            FROM claims
            WHERE estimated_cost > 0 AND type IS NOT NULL
            GROUP BY type
            """, nativeQuery = true)
    List<Object[]> avgEstimatedCostByType();

    @Query(value = """
            SELECT
                CASE
                    WHEN confidence_score < 0.2 THEN '0.0-0.2'
                    WHEN confidence_score < 0.4 THEN '0.2-0.4'
                    WHEN confidence_score < 0.6 THEN '0.4-0.6'
                    WHEN confidence_score < 0.8 THEN '0.6-0.8'
                    ELSE '0.8-1.0'
                END AS score_range,
                COUNT(*) AS cnt
            FROM claims
            WHERE confidence_score IS NOT NULL
            GROUP BY score_range
            ORDER BY score_range
            """, nativeQuery = true)
    List<Object[]> countGroupedByConfidenceScoreRange();

    @Query("SELECT AVG(c.estimatedCost) FROM ClaimJpaEntity c WHERE c.estimatedCost IS NOT NULL AND c.estimatedCost > 0.0")
    Double findAvgEstimatedCost();

    // BUG 2 FIX: count only REJECTED claims with high confidence_score.
    // Previously queried all statuses, which inflated the count because the fraud agent
    // also sets high confidence on APPROVED decisions (confident it is NOT fraud).
    @Query(value = "SELECT COUNT(*) FROM claims WHERE confidence_score >= 0.6 AND status = 'REJECTED'", nativeQuery = true)
    long countHighRiskClaims();
}