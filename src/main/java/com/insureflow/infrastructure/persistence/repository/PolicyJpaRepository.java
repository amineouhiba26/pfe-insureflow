// PolicyJpaRepository.java
package com.insureflow.infrastructure.persistence.repository;

import com.insureflow.infrastructure.persistence.entity.PolicyJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PolicyJpaRepository extends JpaRepository<PolicyJpaEntity, UUID> {
}