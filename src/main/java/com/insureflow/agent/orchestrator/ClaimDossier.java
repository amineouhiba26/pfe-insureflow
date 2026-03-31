// ClaimDossier.java
package com.insureflow.agent.orchestrator;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks all agent results for a single claim.
 * Held in memory in OrchestratorConsumer's ConcurrentHashMap.
 *
 * We expect results from 3 agents before running DecisionMatrix:
 * - RouterAgent    (writes type to claim, no separate result message)
 * - ValidatorAgent (writes validatorResult to claim)
 * - FraudAgent     (writes fraudResult, then publishes to Q_DECISION)
 *
 * In our architecture, FraudAgent is the last to finish and directly
 * triggers the DecisionMatrix via Q_DECISION queue.
 * The Orchestrator reads all results from the DB at that point.
 */
public class ClaimDossier {

    private final UUID          claimId;
    private final long          createdAt;
    private final AtomicInteger completedAgents = new AtomicInteger(0);

    public ClaimDossier(UUID claimId) {
        this.claimId   = claimId;
        this.createdAt = System.currentTimeMillis();
    }

    public UUID getClaimId()   { return claimId; }
    public long getCreatedAt() { return createdAt; }
    public int  increment()    { return completedAgents.incrementAndGet(); }
    public int  getCompleted() { return completedAgents.get(); }
}