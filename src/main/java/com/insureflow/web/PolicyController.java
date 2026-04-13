package com.insureflow.web;

import com.insureflow.domain.port.out.PolicyRepository;
import com.insureflow.infrastructure.security.JwtUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyRepository  policyRepository;
    private final JwtUtils           jwtUtils;

    public PolicyController(PolicyRepository policyRepository,
                            JwtUtils jwtUtils) {
        this.policyRepository = policyRepository;
        this.jwtUtils           = jwtUtils;
    }

    @GetMapping("/my")
    public ResponseEntity<List<Map<String, Object>>> getMyPolicies(
            @AuthenticationPrincipal Jwt jwt) {

        UUID clientId = jwtUtils.extractClientId(jwt);

        List<Map<String, Object>> policies = policyRepository
                .findByClientId(clientId)
                .stream()
                .map(p -> Map.<String, Object>of(
                        "id",            p.getId().toString(),
                        "policyNumber",  p.getPolicyNumber(),
                        "type",          p.getType() != null ? p.getType().name() : "UNKNOWN",
                        "coverageLimit", p.getCoverageLimit(),
                        "deductible",    p.getDeductible(),
                        "startDate",     p.getStartDate() != null ? p.getStartDate().toString() : "",
                        "endDate",       p.getEndDate() != null ? p.getEndDate().toString() : "",
                        "typeLabel",     getTypeLabel(p.getType() != null ? p.getType().name() : "")
                ))
                .toList();

        return ResponseEntity.ok(policies);
    }

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
}