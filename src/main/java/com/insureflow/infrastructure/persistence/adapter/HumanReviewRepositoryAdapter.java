// HumanReviewRepositoryAdapter.java
package com.insureflow.infrastructure.persistence.adapter;

import com.insureflow.domain.model.HumanReviewTask;
import com.insureflow.domain.model.HumanReviewTask.ReviewStatus;
import com.insureflow.domain.port.out.HumanReviewRepository;
import com.insureflow.infrastructure.persistence.entity.HumanReviewJpaEntity;
import com.insureflow.infrastructure.persistence.repository.HumanReviewJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class HumanReviewRepositoryAdapter implements HumanReviewRepository {

    private final HumanReviewJpaRepository jpaRepository;

    public HumanReviewRepositoryAdapter(HumanReviewJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public HumanReviewTask save(HumanReviewTask task) {
        return toDomain(jpaRepository.save(toEntity(task)));
    }

    @Override
    public Optional<HumanReviewTask> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<HumanReviewTask> findByClaimId(UUID claimId) {
        return jpaRepository.findByClaimId(claimId).map(this::toDomain);
    }

    @Override
    public List<HumanReviewTask> findAllPending() {
        return jpaRepository.findByStatus(ReviewStatus.PENDING)
                .stream().map(this::toDomain).toList();
    }

    private HumanReviewJpaEntity toEntity(HumanReviewTask t) {
        HumanReviewJpaEntity e = new HumanReviewJpaEntity();
        e.setId(t.getId());
        e.setClaimId(t.getClaimId());
        e.setReason(t.getReason());
        e.setStatus(t.getStatus());
        e.setAssignedTo(t.getAssignedTo());
        e.setAdjusterNotes(t.getAdjusterNotes());
        e.setResolvedAt(t.getResolvedAt());
        return e;
    }

    private HumanReviewTask toDomain(HumanReviewJpaEntity e) {
        HumanReviewTask t = new HumanReviewTask();
        t.setId(e.getId());
        t.setClaimId(e.getClaimId());
        t.setReason(e.getReason());
        t.setStatus(e.getStatus());
        t.setAssignedTo(e.getAssignedTo());
        t.setAdjusterNotes(e.getAdjusterNotes());
        t.setCreatedAt(e.getCreatedAt());
        t.setResolvedAt(e.getResolvedAt());
        return t;
    }
}