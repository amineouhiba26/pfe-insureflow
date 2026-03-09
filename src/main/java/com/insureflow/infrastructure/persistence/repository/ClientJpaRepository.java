// ClientJpaRepository.java
package com.insureflow.infrastructure.persistence.repository;

import com.insureflow.infrastructure.persistence.entity.ClientJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data generates the full implementation of this interface at startup.
 * You get save(), findById(), findAll(), delete() for free.
 * Any method you declare following the naming convention also gets generated:
 * findByEmail() → Spring generates "SELECT * FROM clients WHERE email = ?"
 * No SQL needed.
 */
@Repository
public interface ClientJpaRepository extends JpaRepository<ClientJpaEntity, UUID> {
    Optional<ClientJpaEntity> findByEmail(String email);
}