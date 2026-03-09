// ClaimJpaRepository.java
package com.insureflow.infrastructure.persistence.repository;

import com.insureflow.infrastructure.persistence.entity.ClaimJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ClaimJpaRepository extends JpaRepository<ClaimJpaEntity, UUID> {
    List<ClaimJpaEntity> findByClientId(UUID clientId);
}