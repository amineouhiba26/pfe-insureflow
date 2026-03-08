package com.insureflow.domain.port.out;

import com.insureflow.domain.model.Client;
import java.util.Optional;
import java.util.UUID;

public interface ClientRepository {
    Client save(Client client);
    Optional<Client> findById(UUID id);
    Optional<Client> findByEmail(String email);
}