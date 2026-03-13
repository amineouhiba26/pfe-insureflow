// ValidatorAgentService.java
package com.insureflow.agent.validator;

import com.insureflow.agent.shared.AgentResult;
import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.domain.port.out.VectorStorePort;
import com.insureflow.infrastructure.messaging.ClaimEvent;
import com.insureflow.infrastructure.messaging.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Listens on claim.validated, runs RAG + ValidatorAgent, persists result.
 *
 * The RAG flow in detail:
 * 1. Receive ClaimEvent (has policyId)
 * 2. Call VectorStorePort.retrieveRelevantChunks(description, policyId, topK=5)
 *    → Spring AI searches pgvector for the 5 chunks most semantically similar
 *      to the description, filtered to only this client's contract
 * 3. Join the chunks into a single string
 * 4. Pass chunks + description to ValidatorAgent
 * 5. Parse covered + confidence from the JSON response
 * 6. Save validatorResult to DB, update claim status
 *
 * What if no contract was uploaded?
 * retrieveRelevantChunks returns an empty list.
 * We fall back to "no contract found" and set covered=false with low confidence
 * which will trigger human review in the DecisionMatrix.
 */
@Service
public class ValidatorAgentService {

    private static final Logger log = LoggerFactory.getLogger(ValidatorAgentService.class);

    private final ValidatorAgent  validatorAgent;
    private final VectorStorePort vectorStorePort;
    private final ClaimRepository claimRepository;

    public ValidatorAgentService(ValidatorAgent validatorAgent,
                                 VectorStorePort vectorStorePort,
                                 ClaimRepository claimRepository) {
        this.validatorAgent  = validatorAgent;
        this.vectorStorePort = vectorStorePort;
        this.claimRepository = claimRepository;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_VALIDATED)
    public void onValidated(ClaimEvent event) {
        log.info("[VALIDATOR] Processing claimId={}", event.getClaimId());

        claimRepository.updateStatus(event.getClaimId(), ClaimStatus.VALIDATING);

        AgentResult result = runValidator(
                event.getDescription(),
                event.getPolicyId().toString()
        );

        claimRepository.findById(event.getClaimId()).ifPresent(claim -> {
            claim.setValidatorResult(result.getResultJson());
            // Don't change status here — Orchestrator coordinates status
            // based on ALL agents finishing (Sprint 6)
            claimRepository.save(claim);
        });

        log.info("[VALIDATOR] Completed claimId={} covered={} confidence={}",
                event.getClaimId(),
                ResponseParser.getBoolean(result.getResultJson(), "covered", false),
                result.getConfidence());
    }

    public AgentResult runValidator(String description, String policyId) {
        try {
            // Step 1: retrieve relevant contract chunks from pgvector
            List<String> chunks = vectorStorePort.retrieveRelevantChunks(
                    description, policyId, 5);

            if (chunks.isEmpty()) {
                log.warn("[VALIDATOR] No contract chunks found for policyId={}", policyId);
                String fallback = """
                    {"covered":false,"confidence":0.3,
                     "coverageSection":"N/A",
                     "reasoning":"No contract document found for this policy"}
                    """;
                return AgentResult.success(fallback, 0.3, "No contract found");
            }

            // Step 2: join chunks into a single context string
            String contractContext = String.join("\n\n---\n\n", chunks);
            log.debug("[VALIDATOR] Using {} contract chunks for claimId", chunks.size());

            // Step 3: call the LLM
            String raw  = validatorAgent.validate(contractContext, description);
            String json = ResponseParser.extractJson(raw);
            double conf = ResponseParser.getDouble(json, "confidence", 0.5);
            String rsn  = ResponseParser.getString(json, "reasoning", "");
            log.debug("[VALIDATOR] Raw LLM response: {}", raw);
            return AgentResult.success(json, conf, rsn);

        } catch (Exception e) {
            log.error("[VALIDATOR] Agent failed: {}", e.getMessage());
            return AgentResult.failure(e.getMessage());
        }
    }
}