// ClientRepositoryAdapter.java
package com.insureflow.infrastructure.persistence.adapter;

import com.insureflow.domain.model.Client;
import com.insureflow.domain.port.out.ClientRepository;
import com.insureflow.infrastructure.persistence.entity.ClientJpaEntity;
import com.insureflow.infrastructure.persistence.repository.ClientJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Implements the domain ClientRepository port using Spring Data JPA.
 *
 * @Component makes Spring create an instance of this class automatically.
 *
 * The two private methods toEntity() and toDomain() handle mapping.
 * toEntity()  → converts domain Client → JPA entity (before saving to DB)
 * toDomain()  → converts JPA entity → domain Client (after loading from DB)
 *
 * This mapping is manual on purpose — no magic framework.
 * You can see exactly what fields are mapped and catch bugs easily.
 */
@Component
public class ClientRepositoryAdapter implements ClientRepository {

    private final ClientJpaRepository jpaRepository;

    public ClientRepositoryAdapter(ClientJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Client save(Client client) {
        ClientJpaEntity entity = toEntity(client);
        ClientJpaEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<Client> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Client> findByEmail(String email) {
        return jpaRepository.findByEmail(email).map(this::toDomain);
    }

    private ClientJpaEntity toEntity(Client c) {
        ClientJpaEntity e = new ClientJpaEntity();
        e.setId(c.getId());
        e.setFullName(c.getFullName());
        e.setEmail(c.getEmail());
        e.setPhone(c.getPhone());
        e.setNationalId(c.getNationalId());
        return e;
    }

    private Client toDomain(ClientJpaEntity e) {
        Client c = new Client();
        c.setId(e.getId());
        c.setFullName(e.getFullName());
        c.setEmail(e.getEmail());
        c.setPhone(e.getPhone());
        c.setNationalId(e.getNationalId());
        c.setCreatedAt(e.getCreatedAt());
        return c;
    }
}