// HumanReviewController.java
package com.insureflow.web;

import com.insureflow.application.dto.ReviewDecisionRequest;
import com.insureflow.domain.model.HumanReviewTask;
import com.insureflow.domain.port.in.ResolveReviewUseCase;
import com.insureflow.domain.port.out.HumanReviewRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reviews")
public class HumanReviewController {

    private final HumanReviewRepository reviewRepository;
    private final ResolveReviewUseCase resolveReviewUseCase;

    public HumanReviewController(HumanReviewRepository reviewRepository,
                                 ResolveReviewUseCase resolveReviewUseCase) {
        this.reviewRepository = reviewRepository;
        this.resolveReviewUseCase = resolveReviewUseCase;
    }

    @GetMapping("/pending")
    public ResponseEntity<List<HumanReviewTask>> getPending() {
        return ResponseEntity.ok(reviewRepository.findAllPending());
    }

    @PutMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewDecisionRequest request) {
        resolveReviewUseCase.resolve(
                id,
                request.getDecision(),
                request.getAssignedTo(),
                request.getAdjusterNotes()
        );
        return ResponseEntity.ok().build();
    }
}