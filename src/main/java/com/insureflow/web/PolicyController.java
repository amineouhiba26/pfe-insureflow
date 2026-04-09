package com.insureflow.web;

import com.insureflow.application.dto.CreatePolicyRequest;
import com.insureflow.domain.model.Policy;
import com.insureflow.domain.port.out.PolicyRepository;
import com.insureflow.infrastructure.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Policy endpoints.
 *
 * GET  /api/v1/policies/my   → all policies for the authenticated client (JWT)
 * GET  /api/v1/policies/{id} → single policy by ID
 * POST /api/v1/policies      → create a policy (admin / seeding)
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyRepository policyRepository;
    private final JwtService       jwtService;

    public PolicyController(PolicyRepository policyRepository, JwtService jwtService) {
        this.policyRepository = policyRepository;
        this.jwtService       = jwtService;
    }

    /**
     * Returns every policy that belongs to the authenticated client.
     * GET /api/v1/policies/my
     * Authorization: Bearer <jwt>
     */
    @GetMapping("/my")
    public ResponseEntity<List<Map<String, Object>>> getMyPolicies(
            HttpServletRequest request) {

        UUID clientId = extractClientId(request);

        List<Map<String, Object>> policies = policyRepository
                .findByClientId(clientId)
                .stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(policies);
    }

    // ── existing endpoints ────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Policy> create(@Valid @RequestBody CreatePolicyRequest request) {
        Policy policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setClientId(request.getClientId());
        policy.setPolicyNumber(request.getPolicyNumber());
        policy.setType(request.getType());
        policy.setCoverageLimit(request.getCoverageLimit());
        policy.setDeductible(request.getDeductible());
        policy.setStartDate(request.getStartDate());
        policy.setEndDate(request.getEndDate());
        return ResponseEntity.ok(policyRepository.save(policy));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Policy> getById(@PathVariable UUID id) {
        return policyRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toResponse(Policy p) {
        String typeName  = p.getType() != null ? p.getType().name() : "UNKNOWN";
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",            p.getId().toString());
        map.put("policyNumber",  p.getPolicyNumber());
        map.put("type",          typeName);
        map.put("typeLabel",     getTypeLabel(typeName));
        map.put("coverageLimit", p.getCoverageLimit());
        map.put("deductible",    p.getDeductible());
        map.put("startDate",     p.getStartDate() != null ? p.getStartDate().toString() : null);
        map.put("endDate",       p.getEndDate()   != null ? p.getEndDate().toString()   : null);
        map.put("active",        isActive(p));
        return map;
    }

    /** Human-readable French label with emoji for each policy type */
    private String getTypeLabel(String type) {
        return switch (type) {
            case "VEHICLE_DAMAGE"   -> "🚗 Assurance Automobile";
            case "PROPERTY_DAMAGE"  -> "🏠 Assurance Habitation";
            case "HEALTH"           -> "🏥 Assurance Santé";
            case "THEFT"            -> "🔒 Assurance Vol";
            case "NATURAL_DISASTER" -> "🌪️ Assurance Catastrophes Naturelles";
            default                 -> "📋 Assurance Multirisques";
        };
    }

    /** True if today falls between startDate and endDate (inclusive) */
    private boolean isActive(Policy p) {
        if (p.getStartDate() == null || p.getEndDate() == null) return false;
        java.time.LocalDate today = java.time.LocalDate.now();
        return !today.isBefore(p.getStartDate()) && !today.isAfter(p.getEndDate());
    }

    private UUID extractClientId(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return jwtService.extractClientId(auth.substring(7));
        }
        throw new RuntimeException("Token JWT manquant ou invalide");
    }
}