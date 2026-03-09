// ClientController.java
package com.insureflow.web;

import com.insureflow.application.dto.CreateClientRequest;
import com.insureflow.domain.model.Client;
import com.insureflow.domain.port.out.ClientRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for client management.
 *
 * @RestController = @Controller + @ResponseBody on every method.
 * Means every method return value is serialized to JSON automatically.
 *
 * @RequestMapping sets the base URL for all methods in this class.
 *
 * Notice: the controller calls ClientRepository directly for simple CRUD.
 * It only goes through a UseCase for operations with business logic
 * (like submitting a claim, which triggers the AI pipeline).
 */
@RestController
@RequestMapping("/api/v1/clients")
public class ClientController {

    private final ClientRepository clientRepository;

    public ClientController(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    @PostMapping
    public ResponseEntity<Client> create(@Valid @RequestBody CreateClientRequest request) {
        Client client = new Client(
                UUID.randomUUID(),
                request.getFullName(),
                request.getEmail(),
                request.getPhone(),
                request.getNationalId()
        );
        return ResponseEntity.ok(clientRepository.save(client));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Client> getById(@PathVariable UUID id) {
        return clientRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}