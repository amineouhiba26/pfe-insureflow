package com.insureflow.infrastructure.decision;

import com.insureflow.agent.orchestrator.ConfidenceCalculator;
import com.insureflow.agent.orchestrator.DecisionMatrix;
import com.insureflow.domain.model.Claim;
import com.insureflow.domain.model.HumanReviewTask;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.domain.port.out.HumanReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    private final DecisionMatrix       decisionMatrix;
    private final ConfidenceCalculator confidenceCalculator;
    private final ClaimRepository      claimRepository;
    private final HumanReviewRepository reviewRepository;

    public DecisionService(DecisionMatrix decisionMatrix,
                           ConfidenceCalculator confidenceCalculator,
                           ClaimRepository claimRepository,
                           HumanReviewRepository reviewRepository) {
        this.decisionMatrix       = decisionMatrix;
        this.confidenceCalculator = confidenceCalculator;
        this.claimRepository      = claimRepository;
        this.reviewRepository     = reviewRepository;
    }

    public ClaimStatus decide(Claim claim) {
        log.info("[DECISION-SERVICE] Evaluating decision for claimId={}", claim.getId());

        double confidence = confidenceCalculator.compute(claim);
        claim.setConfidenceScore(confidence);

        DecisionMatrix.DecisionResult result = decisionMatrix.evaluate(claim, confidence);

        if (result.decision() == DecisionMatrix.Decision.REJECTED) {
            // Deterministic scope-guard rejection — auto-reject without human review.
            // The claim is hors périmètre with confidence >= 0.99 (keyword-based, not LLM).
            claim.setRejectionReason(result.reason());
            claim.transitionTo(ClaimStatus.REJECTED);
            claimRepository.save(claim);
            log.info("[DECISION-SERVICE] AUTO-REJECTED (hors périmètre) — claimId={} flag={} reason={}",
                    claim.getId(), result.flag(), result.reason());
            return ClaimStatus.REJECTED;
        }

        // PENDING_REVIEW — human adjuster required for all other cases.
        claim.transitionTo(ClaimStatus.PENDING_REVIEW);
        claimRepository.save(claim);
        reviewRepository.save(HumanReviewTask.createFor(claim.getId(), result.reason()));
        log.info("[DECISION-SERVICE] PENDING_REVIEW — flag={} reason={} claimId={}",
                result.flag(), result.reason(), claim.getId());

        log.info("[DECISION-SERVICE] Final status: {} for claimId={}",
                ClaimStatus.PENDING_REVIEW, claim.getId());
        return ClaimStatus.PENDING_REVIEW;
    }
}
