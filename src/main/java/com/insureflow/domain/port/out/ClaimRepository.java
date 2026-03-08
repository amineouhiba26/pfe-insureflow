package com.insureflow.domain.port.out;

import com.insureflow.domain.model.Claim;
import com.insureflow.domain.model.enums.ClaimStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — the domain's view of persistence.
 * The domain says "I need to save and find claims".
 * It doesn't care if it's Postgres, MongoDB, or an in-memory map.
 * The infrastructure adapter implements this interface.
 */
public interface ClaimRepository {
    Claim save(Claim claim);
    Optional<Claim> findById(UUID id);
    List<Claim> findByClientId(UUID clientId);
    Claim updateStatus(UUID id, ClaimStatus status);
}