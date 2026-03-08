package com.insureflow.domain.port.in;

import com.insureflow.domain.model.Claim;

import java.util.List;
import java.util.UUID;

/**
 * Inbound port — entry point for submitting a new claim.
 * The REST controller depends on this interface, never on the implementation.
 *
 * Why an interface? So you can swap the implementation in tests
 * without changing the controller at all.
 */
public interface SubmitClaimUseCase {
    Claim submit(UUID clientId, UUID policyId,
                 String description, List<String> photoUrls);
}