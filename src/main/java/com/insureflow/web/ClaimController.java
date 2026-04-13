package com.insureflow.web;

import com.insureflow.application.dto.ClaimResponse;
import com.insureflow.application.dto.SubmitClaimRequest;
import com.insureflow.domain.model.Claim;
import com.insureflow.domain.port.in.SubmitClaimUseCase;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.infrastructure.PhotoUploadService;
import com.insureflow.infrastructure.security.JwtUtils;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {

    private final SubmitClaimUseCase submitClaimUseCase;
    private final ClaimRepository    claimRepository;
    private final PhotoUploadService photoUploadService;
    private final JwtUtils           jwtUtils;

    public ClaimController(SubmitClaimUseCase submitClaimUseCase,
                           ClaimRepository claimRepository,
                           PhotoUploadService photoUploadService,
                           JwtUtils jwtUtils) {
        this.submitClaimUseCase = submitClaimUseCase;
        this.claimRepository    = claimRepository;
        this.photoUploadService = photoUploadService;
        this.jwtUtils           = jwtUtils;
    }

    @PostMapping(value = "/with-photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ClaimResponse> submitWithPhotos(
            @RequestParam("policyId")    UUID policyId,
            @RequestParam("description") String description,
            @RequestParam(value = "clientEstimatedCost", required = false)
                BigDecimal clientEstimatedCost,
            @RequestParam(value = "photos", required = false)
                List<MultipartFile> photos,
            @AuthenticationPrincipal Jwt jwt) {

        UUID clientId = jwtUtils.extractClientId(jwt);
        List<String> photoUrls = photoUploadService.uploadAll(photos);
        Claim claim = submitClaimUseCase.submit(
                clientId, policyId, description, photoUrls, clientEstimatedCost);
        return ResponseEntity.accepted().body(ClaimResponse.fromDomain(claim));
    }

    @PostMapping
    public ResponseEntity<ClaimResponse> submit(
            @Valid @RequestBody SubmitClaimRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        UUID clientId = jwtUtils.extractClientId(jwt);
        Claim claim = submitClaimUseCase.submit(
                clientId,
                request.getPolicyId(),
                request.getDescription(),
                request.getPhotoUrls(),
                request.getClientEstimatedCost()
        );
        return ResponseEntity.accepted().body(ClaimResponse.fromDomain(claim));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ClaimResponse> getById(@PathVariable UUID id) {
        return claimRepository.findById(id)
                .map(ClaimResponse::fromDomain)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<ClaimResponse>> getMyClaims(
            @AuthenticationPrincipal Jwt jwt) {
        UUID clientId = jwtUtils.extractClientId(jwt);
        return ResponseEntity.ok(
                claimRepository.findByClientId(clientId)
                        .stream()
                        .map(ClaimResponse::fromDomain)
                        .toList()
        );
    }
}