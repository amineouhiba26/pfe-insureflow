package com.insureflow.web.admin;

import com.insureflow.domain.model.Client;
import com.insureflow.domain.model.Policy;
import com.insureflow.domain.port.out.ClientRepository;
import com.insureflow.domain.port.out.PolicyRepository;
import com.insureflow.infrastructure.ai.rag.DocumentIngestionAdapter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin setup endpoints — create clients, policies, ingest contracts.
 * All protected by ROLE_ADMIN via SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminSetupController {

    private final ClientRepository       clientRepository;
    private final PolicyRepository       policyRepository;
    private final DocumentIngestionAdapter ingestionAdapter;

    public AdminSetupController(ClientRepository clientRepository,
                                 PolicyRepository policyRepository,
                                 DocumentIngestionAdapter ingestionAdapter) {
        this.clientRepository  = clientRepository;
        this.policyRepository  = policyRepository;
        this.ingestionAdapter  = ingestionAdapter;
    }

    // ── Clients ───────────────────────────────────────────────────────────────

    @PostMapping("/clients")
    public ResponseEntity<?> createClient(@RequestBody CreateClientRequest req) {
        // Check if CIN already exists
        if (clientRepository.findByNationalId(req.cin()).isPresent()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Un client avec ce CIN existe déjà"));
        }

        Client client = new Client();
        client.setFullName(req.fullName());
        client.setEmail(req.email());
        client.setPhone(req.phone());
        client.setNationalId(req.cin());

        Client saved = clientRepository.save(client);

        return ResponseEntity.ok(Map.of(
                "id",       saved.getId().toString(),
                "fullName", saved.getFullName(),
                "email",    saved.getEmail(),
                "cin",      saved.getNationalId(),
                "message",  "Client créé avec succès"
        ));
    }

    @GetMapping("/clients")
    public ResponseEntity<?> getAllClients() {
        List<Map<String, Object>> clients = clientRepository.findAll()
                .stream()
                .map(c -> Map.<String, Object>of(
                        "id",       c.getId().toString(),
                        "fullName", c.getFullName(),
                        "email",    c.getEmail() != null ? c.getEmail() : "",
                        "phone",    c.getPhone() != null ? c.getPhone() : "",
                        "cin",      c.getNationalId() != null ? c.getNationalId() : ""
                ))
                .toList();
        return ResponseEntity.ok(clients);
    }

    // ── Policies ──────────────────────────────────────────────────────────────

    @PostMapping("/policies")
    public ResponseEntity<?> createPolicy(@RequestBody CreatePolicyRequest req) {
        Policy policy = new Policy();
        policy.setClientId(UUID.fromString(req.clientId()));
        policy.setPolicyNumber(req.policyNumber());
        policy.setType(com.insureflow.domain.model.enums.ClaimType.valueOf(req.type()));
        policy.setCoverageLimit(req.coverageLimit());
        policy.setDeductible(req.deductible());
        policy.setStartDate(LocalDate.parse(req.startDate()));
        policy.setEndDate(LocalDate.parse(req.endDate()));

        Policy saved = policyRepository.save(policy);

        return ResponseEntity.ok(Map.of(
                "id",           saved.getId().toString(),
                "policyNumber", saved.getPolicyNumber(),
                "clientId",     req.clientId(),
                "type",         req.type(),
                "message",      "Police créée avec succès"
        ));
    }

    @GetMapping("/policies")
    public ResponseEntity<?> getAllPolicies() {
        List<Map<String, Object>> policies = policyRepository.findAll()
                .stream()
                .map(p -> Map.<String, Object>of(
                        "id",            p.getId().toString(),
                        "policyNumber",  p.getPolicyNumber(),
                        "clientId",      p.getClientId().toString(),
                        "type",          p.getType() != null ? p.getType().name() : "",
                        "coverageLimit", p.getCoverageLimit(),
                        "deductible",    p.getDeductible(),
                        "startDate",     p.getStartDate() != null ? p.getStartDate().toString() : "",
                        "endDate",       p.getEndDate() != null ? p.getEndDate().toString() : ""
                ))
                .toList();
        return ResponseEntity.ok(policies);
    }

    // ── Contract ingestion ────────────────────────────────────────────────────

    @PostMapping(value = "/contracts/{policyId}/ingest",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> ingestContract(
            @PathVariable UUID policyId,
            @RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Fichier vide"));
            }

            if (!file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Seuls les fichiers PDF sont acceptés"));
            }

            ingestionAdapter.ingestDocument(
                    policyId.toString(),
                    file.getBytes(),
                    file.getOriginalFilename()
            );

            return ResponseEntity.ok(Map.of(
                    "policyId", policyId.toString(),
                    "fileName", file.getOriginalFilename(),
                    "message",  "Contrat ingéré avec succès dans le système RAG"
            ));

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Erreur lors de l'ingestion: " + e.getMessage()));
        }
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record CreateClientRequest(
            String fullName,
            String email,
            String phone,
            String cin) {}

    public record CreatePolicyRequest(
            String clientId,
            String policyNumber,
            String type,
            BigDecimal coverageLimit,
            BigDecimal deductible,
            String startDate,
            String endDate) {}
}
