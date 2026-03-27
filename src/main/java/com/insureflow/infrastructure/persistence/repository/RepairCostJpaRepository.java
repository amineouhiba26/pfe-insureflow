package com.insureflow.infrastructure.persistence.repository;

import com.insureflow.domain.model.enums.Severity;
import com.insureflow.infrastructure.persistence.entity.RepairCostJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for repair cost lookups.
 *
 * The EstimatorAgent uses this to get deterministic prices.
 * The LLM identifies which parts are damaged and how severely.
 * This table provides the actual cost range — never the LLM.
 */
@Repository
public interface RepairCostJpaRepository extends JpaRepository<RepairCostJpaEntity, UUID> {

    // Find cost for a specific part + severity combination
    Optional<RepairCostJpaEntity> findByPartNameIgnoreCaseAndSeverity(
            String partName, Severity severity);
}