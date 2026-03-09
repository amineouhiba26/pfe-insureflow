// ResolveReviewUseCaseImpl.java
package com.insureflow.application.usecase;

import com.insureflow.domain.model.HumanReviewTask;
import com.insureflow.domain.model.HumanReviewTask.ReviewStatus;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.port.in.ResolveReviewUseCase;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.domain.port.out.HumanReviewRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Handles an adjuster's decision on a flagged claim.
 * Finds the review task, updates it, then updates the claim status accordingly.
 */
@Service
public class ResolveReviewUseCaseImpl implements ResolveReviewUseCase {

    private final HumanReviewRepository reviewRepository;
    private final ClaimRepository claimRepository;

    public ResolveReviewUseCaseImpl(HumanReviewRepository reviewRepository,
                                    ClaimRepository claimRepository) {
        this.reviewRepository = reviewRepository;
        this.claimRepository = claimRepository;
    }

    @Override
    public void resolve(UUID reviewTaskId, ReviewStatus decision,
                        String assignedTo, String adjusterNotes) {

        HumanReviewTask task = reviewRepository.findById(reviewTaskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Review task not found: " + reviewTaskId));

        // Update the review task
        task.setStatus(decision);
        task.setAssignedTo(assignedTo);
        task.setAdjusterNotes(adjusterNotes);
        task.setResolvedAt(Instant.now());
        reviewRepository.save(task);

        // Update the claim to match the adjuster's decision
        ClaimStatus newClaimStatus = decision == ReviewStatus.APPROVED
                ? ClaimStatus.APPROVED
                : ClaimStatus.REJECTED;

        claimRepository.updateStatus(task.getClaimId(), newClaimStatus);
    }
}