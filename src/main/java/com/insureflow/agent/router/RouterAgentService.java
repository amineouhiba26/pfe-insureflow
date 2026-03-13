// RouterAgentService.java
package com.insureflow.agent.router;

import com.insureflow.agent.shared.AgentResult;
import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.model.enums.ClaimType;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.infrastructure.messaging.ClaimEvent;
import com.insureflow.infrastructure.messaging.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

/**
 * Listens on claim.routed, calls RouterAgent, persists the result.
 *
 * Why is this a @Service separate from the @AiService interface?
 * The @AiService (RouterAgent) is a pure AI call — it just takes text and returns text.
 * This service handles all the surrounding concerns:
 *   - reading from RabbitMQ
 *   - calling the agent
 *   - parsing the JSON response
 *   - updating the claim in the database
 *   - error handling
 *
 * This separation makes the agent easy to test independently.
 *
 * Flow:
 * 1. Receive ClaimEvent from claim.routed queue
 * 2. Update claim status → ROUTING
 * 3. Call RouterAgent.classify(description) → raw JSON string from LLM
 * 4. Extract JSON, parse claimType + confidence
 * 5. Update claim: set type, set routerResult, set status → VALIDATING
 */
@Service
public class RouterAgentService {

    private static final Logger log = LoggerFactory.getLogger(RouterAgentService.class);

    private final RouterAgent     routerAgent;
    private final ClaimRepository claimRepository;

    public RouterAgentService(RouterAgent routerAgent,
                              ClaimRepository claimRepository) {
        this.routerAgent     = routerAgent;
        this.claimRepository = claimRepository;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_ROUTED)
    public void onRouted(ClaimEvent event) {
        log.info("[ROUTER] Processing claimId={}", event.getClaimId());

        // Update status so the client knows routing is in progress
        claimRepository.updateStatus(event.getClaimId(), ClaimStatus.ROUTING);

        AgentResult result = runRouter(event.getDescription());

        // Parse the claim type from the JSON result
        String claimTypeStr = ResponseParser.getString(
                result.getResultJson(), "claimType", "OTHER");
        ClaimType claimType = parseClaimType(claimTypeStr);

        // Persist the result — find claim, update fields, save
        claimRepository.findById(event.getClaimId()).ifPresent(claim -> {
            claim.setType(claimType);
            claim.setRouterResult(result.getResultJson());
            claim.transitionTo(ClaimStatus.VALIDATING);
            claimRepository.save(claim);
        });

        log.info("[ROUTER] Completed claimId={} type={} confidence={}",
                event.getClaimId(), claimType, result.getConfidence());
    }

    /**
     * Isolated method — makes it easy to unit test the agent call
     * without needing RabbitMQ or a database.
     */
    public AgentResult runRouter(String description) {
        try {
            String raw  = routerAgent.classify(description);
            String json = ResponseParser.extractJson(raw);
            double conf = ResponseParser.getDouble(json, "confidence", 0.5);
            String rsn  = ResponseParser.getString(json, "reasoning", "");
            log.debug("[ROUTER] Raw LLM response: {}", raw);
            return AgentResult.success(json, conf, rsn);
        } catch (Exception e) {
            log.error("[ROUTER] Agent failed for description='{}': {}", description, e.getMessage());
            return AgentResult.failure(e.getMessage());
        }
    }

    private ClaimType parseClaimType(String value) {
        try {
            return ClaimType.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            log.warn("[ROUTER] Unknown claim type '{}', defaulting to OTHER", value);
            return ClaimType.OTHER;
        }
    }
}