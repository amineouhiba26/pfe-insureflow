package com.insureflow.web.admin;

import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.domain.port.out.HumanReviewRepository;
import com.insureflow.domain.model.HumanReviewTask;
import com.insureflow.application.dto.ClaimResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin endpoints — requires ROLE_ADMIN.
 * Protected by SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminClaimsController {

    private final ClaimRepository       claimRepository;
    private final HumanReviewRepository reviewRepository;

    public AdminClaimsController(ClaimRepository claimRepository,
                                 HumanReviewRepository reviewRepository) {
        this.claimRepository  = claimRepository;
        this.reviewRepository = reviewRepository;
    }

    /** All claims — admin sees everything */
    @GetMapping("/claims")
    public ResponseEntity<List<ClaimResponse>> getAllClaims() {
        List<ClaimResponse> claims = claimRepository.findAll()
                .stream()
                .map(ClaimResponse::fromDomain)
                .toList();
        return ResponseEntity.ok(claims);
    }

    /** Claims pending human review */
    @GetMapping("/claims/pending")
    public ResponseEntity<List<ClaimResponse>> getPendingClaims() {
        List<ClaimResponse> claims = claimRepository.findByStatus(ClaimStatus.PENDING_REVIEW)
                .stream()
                .map(ClaimResponse::fromDomain)
                .toList();
        return ResponseEntity.ok(claims);
    }

    /** Approve a claim with optional notes */
    @PostMapping("/claims/{claimId}/approve")
    public ResponseEntity<?> approveClaim(
            @PathVariable UUID claimId,
            @RequestBody(required = false) Map<String, String> body) {

        String notes = body != null ? body.getOrDefault("notes", "") : "";

        return claimRepository.findById(claimId).map(claim -> {
            claim.transitionTo(ClaimStatus.APPROVED);
            if (!notes.isBlank()) {
                claim.setRejectionReason("Approuvé — Notes: " + notes);
            }
            claimRepository.save(claim);

            // Close the review task if exists
            reviewRepository.findByClaimId(claimId).ifPresent(task -> {
                task.setStatus(HumanReviewTask.ReviewStatus.RESOLVED);
                task.setResolutionNote("Approuvé par admin. " + notes);
                reviewRepository.save(task);
            });

            return ResponseEntity.ok(Map.of(
                    "claimId", claimId.toString(),
                    "status",  "APPROVED",
                    "notes",   notes,
                    "message", "Sinistre approuvé"
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Reject a claim with mandatory reason */
    @PostMapping("/claims/{claimId}/reject")
    public ResponseEntity<?> rejectClaim(
            @PathVariable UUID claimId,
            @RequestBody Map<String, String> body) {

        String reason = body.getOrDefault("reason", "").trim();
        String notes  = body.getOrDefault("notes", "").trim();

        if (reason.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Le motif de rejet est obligatoire"));
        }

        return claimRepository.findById(claimId).map(claim -> {
            claim.setRejectionReason(reason);
            claim.transitionTo(ClaimStatus.REJECTED);
            claimRepository.save(claim);

            reviewRepository.findByClaimId(claimId).ifPresent(task -> {
                task.setStatus(HumanReviewTask.ReviewStatus.RESOLVED);
                task.setResolutionNote("Rejeté: " + reason + (notes.isBlank() ? "" : " — " + notes));
                reviewRepository.save(task);
            });

            return ResponseEntity.ok(Map.of(
                    "claimId", claimId.toString(),
                    "status",  "REJECTED",
                    "reason",  reason,
                    "notes",   notes
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Dashboard stats */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        long total       = claimRepository.countAll();
        long approved    = claimRepository.countByStatus(ClaimStatus.APPROVED);
        long rejected    = claimRepository.countByStatus(ClaimStatus.REJECTED);
        long pending     = claimRepository.countByStatus(ClaimStatus.PENDING_REVIEW);
        long processing  = total - approved - rejected - pending;

        return ResponseEntity.ok(Map.of(
                "total",      total,
                "approved",   approved,
                "rejected",   rejected,
                "pending",    pending,
                "processing", processing
        ));
    }
}