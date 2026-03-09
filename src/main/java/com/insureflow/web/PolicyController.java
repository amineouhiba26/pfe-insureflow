// PolicyController.java
package com.insureflow.web;

import com.insureflow.application.dto.CreatePolicyRequest;
import com.insureflow.domain.model.Policy;
import com.insureflow.domain.port.out.PolicyRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyRepository policyRepository;

    public PolicyController(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

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
}