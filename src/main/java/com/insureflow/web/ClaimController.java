// ClaimController.java
package com.insureflow.web;

import com.insureflow.application.dto.ClaimResponse;
import com.insureflow.application.dto.SubmitClaimRequest;
import com.insureflow.domain.model.Claim;
import com.insureflow.domain.port.in.SubmitClaimUseCase;
import com.insureflow.domain.port.out.ClaimRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for claim submission and status polling.
 *
 * POST /api/v1/claims        → submit a new claim (triggers AI pipeline in Sprint 3)
 * GET  /api/v1/claims/{id}   → poll claim status and agent results
 *
 * Returns 202 Accepted for submission because processing is async —
 * the claim is saved immediately but AI agents run in the background.
 */
@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {

    private final SubmitClaimUseCase submitClaimUseCase;
    private final ClaimRepository claimRepository;

    public ClaimController(SubmitClaimUseCase submitClaimUseCase,
                           ClaimRepository claimRepository) {
        this.submitClaimUseCase = submitClaimUseCase;
        this.claimRepository = claimRepository;
    }

    @PostMapping
    public ResponseEntity<ClaimResponse> submit(
            @Valid @RequestBody SubmitClaimRequest request) {
        Claim claim = submitClaimUseCase.submit(
                request.getClientId(),
                request.getPolicyId(),
                request.getDescription(),
                request.getPhotoUrls()
        );
        // 202 Accepted = "received, processing started, come back later"
        return ResponseEntity.accepted().body(ClaimResponse.fromDomain(claim));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ClaimResponse> getById(@PathVariable UUID id) {
        return claimRepository.findById(id)
                .map(ClaimResponse::fromDomain)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}