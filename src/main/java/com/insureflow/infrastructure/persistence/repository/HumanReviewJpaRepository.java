// HumanReviewJpaRepository.java
package com.insureflow.infrastructure.persistence.repository;

import com.insureflow.domain.model.HumanReviewTask.ReviewStatus;
import com.insureflow.infrastructure.persistence.entity.HumanReviewJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HumanReviewJpaRepository extends JpaRepository<HumanReviewJpaEntity, UUID> {
    List<HumanReviewJpaEntity> findByStatus(ReviewStatus status);
    Optional<HumanReviewJpaEntity> findByClaimId(UUID claimId);
}