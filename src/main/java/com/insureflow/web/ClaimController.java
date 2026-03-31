package com.insureflow.web;

import com.insureflow.application.dto.ClaimResponse;
import com.insureflow.application.dto.SubmitClaimRequest;
import com.insureflow.domain.model.Claim;
import com.insureflow.domain.port.in.SubmitClaimUseCase;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.infrastructure.PhotoUploadService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
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
    private final PhotoUploadService photoUploadService;

    public ClaimController(SubmitClaimUseCase submitClaimUseCase,
                           ClaimRepository claimRepository,
                           PhotoUploadService photoUploadService) {
        this.submitClaimUseCase = submitClaimUseCase;
        this.claimRepository = claimRepository;
        this.photoUploadService = photoUploadService;
    }

    @PostMapping(value = "/with-photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ClaimResponse> submitWithPhotos(
            @RequestParam("clientId")    UUID clientId,
            @RequestParam("policyId")    UUID policyId,
            @RequestParam("description") String description,
            @RequestParam(value = "photos", required = false) List<MultipartFile> photos,
            @RequestParam(value = "clientEstimatedCost", required = false) BigDecimal clientEstimatedCost )

    {

        // Upload photos to Cloudinary and collect URLs
        List<String> photoUrls = photoUploadService.uploadAll(photos);

        Claim claim = submitClaimUseCase.submit(
                clientId, policyId, description, photoUrls , clientEstimatedCost);

        return ResponseEntity.accepted().body(ClaimResponse.fromDomain(claim));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ClaimResponse> getById(@PathVariable UUID id) {
        return claimRepository.findById(id)
                .map(ClaimResponse::fromDomain)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ClaimResponse> submit(@Valid @RequestBody SubmitClaimRequest request) {
        Claim claim = submitClaimUseCase.submit(
                request.getClientId(),
                request.getPolicyId(),
                request.getDescription(),
                request.getPhotoUrls(),
                request.getClientEstimatedCost()
        );
        return ResponseEntity.accepted().body(ClaimResponse.fromDomain(claim));
    }

    @GetMapping
    public ResponseEntity<List<ClaimResponse>> getByClientId(
            @RequestParam UUID clientId) {
        List<ClaimResponse> claims = claimRepository.findByClientId(clientId)
                .stream()
                .map(ClaimResponse::fromDomain)
                .toList();
        return ResponseEntity.ok(claims);
    }
}