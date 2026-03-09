// ClaimRepositoryAdapter.java
package com.insureflow.infrastructure.persistence.adapter;

import com.insureflow.domain.model.Claim;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.infrastructure.persistence.entity.ClaimJpaEntity;
import com.insureflow.infrastructure.persistence.repository.ClaimJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ClaimRepositoryAdapter implements ClaimRepository {

    private final ClaimJpaRepository jpaRepository;

    public ClaimRepositoryAdapter(ClaimJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Claim save(Claim claim) {
        return toDomain(jpaRepository.save(toEntity(claim)));
    }

    @Override
    public Optional<Claim> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<Claim> findByClientId(UUID clientId) {
        return jpaRepository.findByClientId(clientId)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public Claim updateStatus(UUID id, ClaimStatus status) {
        ClaimJpaEntity entity = jpaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Claim not found: " + id));
        entity.setStatus(status);
        return toDomain(jpaRepository.save(entity));
    }

    private ClaimJpaEntity toEntity(Claim c) {
        ClaimJpaEntity e = new ClaimJpaEntity();
        e.setId(c.getId());
        e.setClientId(c.getClientId());
        e.setPolicyId(c.getPolicyId());
        e.setType(c.getType());
        e.setStatus(c.getStatus());
        e.setDescription(c.getDescription());
        e.setPhotoUrls(c.getPhotoUrls());
        e.setRouterResult(c.getRouterResult());
        e.setValidatorResult(c.getValidatorResult());
        e.setEstimatorResult(c.getEstimatorResult());
        e.setFraudResult(c.getFraudResult());
        e.setEstimatedCost(c.getEstimatedCost());
        e.setFinalCost(c.getFinalCost());
        e.setRejectionReason(c.getRejectionReason());
        e.setConfidenceScore(c.getConfidenceScore());
        return e;
    }

    private Claim toDomain(ClaimJpaEntity e) {
        Claim c = new Claim();
        c.setId(e.getId());
        c.setClientId(e.getClientId());
        c.setPolicyId(e.getPolicyId());
        c.setType(e.getType());
        c.setStatus(e.getStatus());
        c.setDescription(e.getDescription());
        c.setPhotoUrls(e.getPhotoUrls());
        c.setRouterResult(e.getRouterResult());
        c.setValidatorResult(e.getValidatorResult());
        c.setEstimatorResult(e.getEstimatorResult());
        c.setFraudResult(e.getFraudResult());
        c.setEstimatedCost(e.getEstimatedCost());
        c.setFinalCost(e.getFinalCost());
        c.setRejectionReason(e.getRejectionReason());
        c.setConfidenceScore(e.getConfidenceScore());
        c.setSubmittedAt(e.getSubmittedAt());
        c.setUpdatedAt(e.getUpdatedAt());
        return c;
    }
}