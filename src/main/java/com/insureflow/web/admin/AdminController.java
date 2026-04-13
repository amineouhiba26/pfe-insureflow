package com.insureflow.web.admin;

import com.insureflow.application.dto.ClaimResponse;
import com.insureflow.domain.model.Client;
import com.insureflow.domain.model.HumanReviewTask;
import com.insureflow.domain.model.Policy;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.model.enums.ClaimType;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.domain.port.out.ClientRepository;
import com.insureflow.domain.port.out.HumanReviewRepository;
import com.insureflow.domain.port.out.PolicyRepository;
import com.insureflow.infrastructure.ai.rag.DocumentIngestionAdapter;
import com.insureflow.infrastructure.security.KeycloakAdminService;
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
 * Unified Admin endpoints — handles claims management, setup, and records.
 * Protected by ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ClaimRepository       claimRepository;
    private final HumanReviewRepository reviewRepository;
    private final ClientRepository      clientRepository;
    private final PolicyRepository      policyRepository;
    private final DocumentIngestionAdapter ingestionAdapter;
    private final KeycloakAdminService keycloakAdminService;

    public AdminController(ClaimRepository claimRepository,
                           HumanReviewRepository reviewRepository,
                           ClientRepository clientRepository,
                           PolicyRepository policyRepository,
                           DocumentIngestionAdapter ingestionAdapter,
                           KeycloakAdminService keycloakAdminService) {
        this.claimRepository  = claimRepository;
        this.reviewRepository = reviewRepository;
        this.clientRepository  = clientRepository;
        this.policyRepository  = policyRepository;
        this.ingestionAdapter  = ingestionAdapter;
        this.keycloakAdminService = keycloakAdminService;
    }

    // ── Claims Management ─────────────────────────────────────────────────────

    @GetMapping("/claims")
    public ResponseEntity<List<ClaimResponse>> getAllClaims() {
        return ResponseEntity.ok(claimRepository.findAll().stream()
                .map(ClaimResponse::fromDomain).toList());
    }

    @GetMapping("/claims/pending")
    public ResponseEntity<List<ClaimResponse>> getPendingClaims() {
        return ResponseEntity.ok(claimRepository.findByStatus(ClaimStatus.PENDING_REVIEW).stream()
                .map(ClaimResponse::fromDomain).toList());
    }

    @PostMapping("/claims/{claimId}/approve")
    public ResponseEntity<?> approveClaim(@PathVariable UUID claimId,
                                          @RequestBody(required = false) Map<String, String> body) {
        String notes = body != null ? body.getOrDefault("notes", "") : "";
        return claimRepository.findById(claimId).map(claim -> {
            claim.transitionTo(ClaimStatus.APPROVED);
            if (!notes.isBlank()) claim.setRejectionReason("Approuvé — Notes: " + notes);
            claimRepository.save(claim);

            reviewRepository.findByClaimId(claimId).ifPresent(task -> {
                task.setStatus(HumanReviewTask.ReviewStatus.RESOLVED);
                task.setResolutionNote("Approuvé par admin. " + notes);
                reviewRepository.save(task);
            });
            return ResponseEntity.ok(Map.of("message", "Sinistre approuvé"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/claims/{claimId}/reject")
    public ResponseEntity<?> rejectClaim(@PathVariable UUID claimId,
                                         @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "").trim();
        if (reason.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Motif obligatoire"));

        return claimRepository.findById(claimId).map(claim -> {
            claim.setRejectionReason(reason);
            claim.transitionTo(ClaimStatus.REJECTED);
            claimRepository.save(claim);

            reviewRepository.findByClaimId(claimId).ifPresent(task -> {
                task.setStatus(HumanReviewTask.ReviewStatus.RESOLVED);
                task.setResolutionNote("Rejeté: " + reason);
                reviewRepository.save(task);
            });
            return ResponseEntity.ok(Map.of("message", "Sinistre rejeté"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        long total    = claimRepository.countAll();
        long approved = claimRepository.countByStatus(ClaimStatus.APPROVED);
        long rejected = claimRepository.countByStatus(ClaimStatus.REJECTED);
        long pending  = claimRepository.countByStatus(ClaimStatus.PENDING_REVIEW);
        return ResponseEntity.ok(Map.of(
                "total", total, "approved", approved, "rejected", rejected, "pending", pending,
                "processing", total - approved - rejected - pending
        ));
    }

    // ── Setup (Clients & Policies) ────────────────────────────────────────────

    @PostMapping("/clients")
    public ResponseEntity<?> createClient(@RequestBody CreateClientRequest req) {
        if (clientRepository.findByNationalId(req.cin()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Client déjà existant (CIN)"));
        }
        Client client = new Client();
        client.setFullName(req.fullName());
        client.setEmail(req.email() != null ? req.email() : req.fullName().toLowerCase().replace(" ", ".") + "@insureflow.com");
        client.setPhone(req.phone());
        client.setNationalId(req.cin());
        Client saved = clientRepository.save(client);

        keycloakAdminService.createUser(req.fullName(), saved.getEmail(), req.cin(), req.phone());
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/clients")
    public ResponseEntity<?> getAllClients() {
        return ResponseEntity.ok(clientRepository.findAll());
    }

    @PostMapping("/policies")
    public ResponseEntity<?> createPolicy(@RequestBody CreatePolicyRequest req) {
        Policy policy = new Policy();
        policy.setClientId(UUID.fromString(req.clientId()));
        policy.setPolicyNumber(req.policyNumber());
        policy.setType(ClaimType.valueOf(req.type()));
        policy.setCoverageLimit(req.coverageLimit());
        policy.setDeductible(req.deductible());
        policy.setStartDate(LocalDate.parse(req.startDate()));
        policy.setEndDate(LocalDate.parse(req.endDate()));
        return ResponseEntity.ok(policyRepository.save(policy));
    }

    @GetMapping("/policies")
    public ResponseEntity<?> getAllPolicies() {
        return ResponseEntity.ok(policyRepository.findAll());
    }

    @PostMapping(value = "/contracts/{policyId}/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> ingestContract(@PathVariable UUID policyId, @RequestParam("file") MultipartFile file) throws Exception {
        ingestionAdapter.ingestDocument(policyId.toString(), file.getBytes(), file.getOriginalFilename());
        return ResponseEntity.ok(Map.of("message", "Contrat ingéré"));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────
    public record CreateClientRequest(String fullName, String email, String phone, String cin) {}
    public record CreatePolicyRequest(String clientId, String policyNumber, String type, BigDecimal coverageLimit, BigDecimal deductible, String startDate, String endDate) {}
}
