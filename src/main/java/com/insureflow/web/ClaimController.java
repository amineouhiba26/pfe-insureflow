package com.insureflow.web;

import com.insureflow.application.dto.ClaimResponse;
import com.insureflow.application.dto.SubmitClaimRequest;
import com.insureflow.domain.model.Claim;
import com.insureflow.domain.port.in.SubmitClaimUseCase;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.infrastructure.PhotoUploadService;
import com.insureflow.infrastructure.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final JwtService         jwtService;

    public ClaimController(SubmitClaimUseCase submitClaimUseCase,
                           ClaimRepository claimRepository,
                           PhotoUploadService photoUploadService,
                           JwtService jwtService) {
        this.submitClaimUseCase = submitClaimUseCase;
        this.claimRepository    = claimRepository;
        this.photoUploadService = photoUploadService;
        this.jwtService         = jwtService;
    }

    /**
     * Submit claim with photos from device — multipart.
     * POST /api/v1/claims/with-photos
     * clientId extracted from JWT — not sent in request body.
     */
    @PostMapping(value = "/with-photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ClaimResponse> submitWithPhotos(
            @RequestParam("policyId")    UUID policyId,
            @RequestParam("description") String description,
            @RequestParam(value = "clientEstimatedCost", required = false)
            BigDecimal clientEstimatedCost,
            @RequestParam(value = "photos", required = false)
            List<MultipartFile> photos,
            HttpServletRequest httpRequest) {

        UUID clientId  = extractClientId(httpRequest);
        List<String> photoUrls = photoUploadService.uploadAll(photos);

        Claim claim = submitClaimUseCase.submit(
                clientId, policyId, description, photoUrls, clientEstimatedCost);

        return ResponseEntity.accepted().body(ClaimResponse.fromDomain(claim));
    }

    /**
     * Submit claim as JSON — no photos.
     * POST /api/v1/claims
     * clientId extracted from JWT — not sent in request body.
     */
    @PostMapping
    public ResponseEntity<ClaimResponse> submit(
            @Valid @RequestBody SubmitClaimRequest request,
            HttpServletRequest httpRequest) {

        UUID clientId = extractClientId(httpRequest);

        Claim claim = submitClaimUseCase.submit(
                clientId,
                request.getPolicyId(),
                request.getDescription(),
                request.getPhotoUrls(),
                request.getClientEstimatedCost()
        );
        return ResponseEntity.accepted().body(ClaimResponse.fromDomain(claim));
    }

    /**
     * Get claim by ID.
     * GET /api/v1/claims/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ClaimResponse> getById(@PathVariable UUID id) {
        return claimRepository.findById(id)
                .map(ClaimResponse::fromDomain)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all claims for the authenticated client.
     * GET /api/v1/claims
     */
    @GetMapping
    public ResponseEntity<List<ClaimResponse>> getMyClims(
            HttpServletRequest httpRequest) {
        UUID clientId = extractClientId(httpRequest);
        List<ClaimResponse> claims = claimRepository.findByClientId(clientId)
                .stream()
                .map(ClaimResponse::fromDomain)
                .toList();
        return ResponseEntity.ok(claims);
    }

    private UUID extractClientId(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return jwtService.extractClientId(auth.substring(7));
        }
        throw new RuntimeException("Token JWT manquant ou invalide");
    }
}