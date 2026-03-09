// SubmitClaimUseCaseImpl.java
package com.insureflow.application.usecase;

import com.insureflow.domain.model.Claim;
import com.insureflow.domain.port.in.SubmitClaimUseCase;
import com.insureflow.domain.port.out.ClaimRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Implements SubmitClaimUseCase.
 *
 * @Service tells Spring to create this bean and manage it.
 *
 * For now this only saves the claim to the database.
 * In Sprint 3 (RabbitMQ) we add: claimEventPublisher.publishSubmitted(claim)
 * which kicks off the async agent pipeline.
 *
 * Notice: this class only knows about domain interfaces (ClaimRepository).
 * It never imports Spring Data JPA or any infrastructure class directly.
 */
@Service
public class SubmitClaimUseCaseImpl implements SubmitClaimUseCase {

    private final ClaimRepository claimRepository;

    public SubmitClaimUseCaseImpl(ClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    @Override
    public Claim submit(UUID clientId, UUID policyId,
                        String description, List<String> photoUrls) {
        // Use the factory method — never construct Claim manually
        Claim claim = Claim.newSubmission(clientId, policyId, description, photoUrls);
        return claimRepository.save(claim);
    }
}