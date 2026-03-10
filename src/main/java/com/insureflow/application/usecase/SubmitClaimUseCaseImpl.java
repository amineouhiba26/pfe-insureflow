package com.insureflow.application.usecase;

import com.insureflow.domain.model.Claim;
import com.insureflow.domain.port.in.SubmitClaimUseCase;
import com.insureflow.domain.port.out.ClaimEventPublisher;
import com.insureflow.domain.port.out.ClaimRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Now does two things after a claim is submitted:
 * 1. Saves the claim to Postgres (same as Sprint 2)
 * 2. Publishes a ClaimEvent to RabbitMQ → triggers the async AI pipeline
 *
 * The order matters: save FIRST, publish SECOND.
 * If we published first and the DB save failed, agents would process
 * a claim that doesn't exist in the database.
 */
@Service
public class SubmitClaimUseCaseImpl implements SubmitClaimUseCase {

    private final ClaimRepository    claimRepository;
    private final ClaimEventPublisher eventPublisher;

    public SubmitClaimUseCaseImpl(ClaimRepository claimRepository,
                                  ClaimEventPublisher eventPublisher) {
        this.claimRepository = claimRepository;
        this.eventPublisher  = eventPublisher;
    }

    @Override
    public Claim submit(UUID clientId, UUID policyId,
                        String description, List<String> photoUrls) {
        Claim claim = Claim.newSubmission(clientId, policyId, description, photoUrls);
        Claim saved = claimRepository.save(claim);          // 1. persist first
        eventPublisher.publishSubmitted(saved);              // 2. then publish
        return saved;
    }
}