// PolicyRepositoryAdapter.java
package com.insureflow.infrastructure.persistence.adapter;

import com.insureflow.domain.model.Policy;
import com.insureflow.domain.port.out.PolicyRepository;
import com.insureflow.infrastructure.persistence.entity.PolicyJpaEntity;
import com.insureflow.infrastructure.persistence.repository.PolicyJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class PolicyRepositoryAdapter implements PolicyRepository {

    private final PolicyJpaRepository jpaRepository;

    public PolicyRepositoryAdapter(PolicyJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Policy save(Policy policy) {
        return toDomain(jpaRepository.save(toEntity(policy)));
    }

    @Override
    public Optional<Policy> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    private PolicyJpaEntity toEntity(Policy p) {
        PolicyJpaEntity e = new PolicyJpaEntity();
        e.setId(p.getId());
        e.setClientId(p.getClientId());
        e.setPolicyNumber(p.getPolicyNumber());
        e.setType(p.getType());
        e.setCoverageLimit(p.getCoverageLimit());
        e.setDeductible(p.getDeductible());
        e.setStartDate(p.getStartDate());
        e.setEndDate(p.getEndDate());
        e.setContractDocumentPath(p.getContractDocumentPath());
        return e;
    }

    private Policy toDomain(PolicyJpaEntity e) {
        Policy p = new Policy();
        p.setId(e.getId());
        p.setClientId(e.getClientId());
        p.setPolicyNumber(e.getPolicyNumber());
        p.setType(e.getType());
        p.setCoverageLimit(e.getCoverageLimit());
        p.setDeductible(e.getDeductible());
        p.setStartDate(e.getStartDate());
        p.setEndDate(e.getEndDate());
        p.setContractDocumentPath(e.getContractDocumentPath());
        p.setCreatedAt(e.getCreatedAt());
        return p;
    }
}