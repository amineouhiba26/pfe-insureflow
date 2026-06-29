// ValidatorAgentService.java
package com.insureflow.agent.validator;

import com.insureflow.agent.shared.AgentResult;
import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.Policy;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.model.enums.ClaimType;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.domain.port.out.PolicyRepository;
import com.insureflow.domain.port.out.VectorStorePort;
import com.insureflow.infrastructure.messaging.ClaimEvent;
import com.insureflow.infrastructure.messaging.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Listens on claim.validated — the pipeline gate-keeper since the Router was
 * changed to fire only Q_VALIDATED (no longer Q_ESTIMATED in parallel).
 *
 * Flow:
 * 1. [SCOPE GUARD] ClaimScopeChecker verifies insured object matches policy type.
 *    Out-of-scope → write SKIPPED markers for Estimator+Fraud, fire Q_DECISION.
 * 2. [RAG] Retrieve relevant contract chunks from pgvector (topK=5).
 * 3. [LLM] ValidatorAgent determines coverage from contract context.
 * 4. covered=true  → fire Q_ESTIMATED to continue normal pipeline.
 *    covered=false → write SKIPPED markers for Estimator+Fraud, fire Q_DECISION.
 */
@Service
public class ValidatorAgentService {

    private static final Logger log = LoggerFactory.getLogger(ValidatorAgentService.class);

    // Written to estimatorResult / fraudResult when claim is rejected before those steps run.
    // The "status":"SKIPPED" field is consumed by the UI pipeline tracker.
    static final String SKIPPED_ESTIMATOR_JSON =
            "{\"skipped\":true,\"status\":\"SKIPPED\"," +
            "\"reason\":\"N/A \\u2014 Sinistre hors p\\u00e9rim\\u00e8tre du contrat\"," +
            "\"estimatedCost\":null,\"pricingMethod\":\"N/A\"," +
            "\"overallSeverity\":\"N/A\",\"confidence\":0.0}";

    static final String SKIPPED_FRAUD_JSON =
            "{\"skipped\":true,\"status\":\"SKIPPED\"," +
            "\"reason\":\"N/A \\u2014 Sinistre hors p\\u00e9rim\\u00e8tre du contrat\"," +
            "\"anomalyDetected\":false,\"anomalyScore\":0.0," +
            "\"anomalyType\":\"NONE\",\"confidence\":1.0}";

    private final ValidatorAgent   validatorAgent;
    private final VectorStorePort  vectorStorePort;
    private final ClaimRepository  claimRepository;
    private final PolicyRepository policyRepository;
    private final RabbitTemplate   rabbitTemplate;

    public ValidatorAgentService(ValidatorAgent validatorAgent,
                                 VectorStorePort vectorStorePort,
                                 ClaimRepository claimRepository,
                                 PolicyRepository policyRepository,
                                 RabbitTemplate rabbitTemplate) {
        this.validatorAgent   = validatorAgent;
        this.vectorStorePort  = vectorStorePort;
        this.claimRepository  = claimRepository;
        this.policyRepository = policyRepository;
        this.rabbitTemplate   = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_VALIDATED)
    public void onValidated(ClaimEvent event) {
        log.info("[VALIDATOR] Processing claimId={}", event.getClaimId());
        claimRepository.updateStatus(event.getClaimId(), ClaimStatus.VALIDATING);

        AgentResult result = runValidator(event.getDescription(), event.getPolicyId().toString());
        boolean covered = ResponseParser.getBoolean(result.getResultJson(), "covered", true);

        // Persist validator result; if rejected, also write SKIPPED markers for downstream steps.
        claimRepository.findById(event.getClaimId()).ifPresent(claim -> {
            claim.setValidatorResult(result.getResultJson());
            if (!covered) {
                claim.setEstimatorResult(SKIPPED_ESTIMATOR_JSON);
                claim.setFraudResult(SKIPPED_FRAUD_JSON);
                log.warn("[VALIDATOR][SHORT-CIRCUIT] claimId={} — writing SKIPPED for Estimator+Fraud",
                        event.getClaimId());
            }
            claimRepository.save(claim);
        });

        // Pipeline routing: Validator is now the gate-keeper.
        if (covered) {
            log.info("[VALIDATOR] COVERED — forwarding to Estimator for claimId={}", event.getClaimId());
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.Q_ESTIMATED, event);
        } else {
            log.warn("[VALIDATOR][SHORT-CIRCUIT] REJECTED — skipping Estimator+Fraud, " +
                     "going directly to Decision for claimId={}", event.getClaimId());
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.Q_DECISION, event);
        }

        log.info("[VALIDATOR] Completed claimId={} covered={} confidence={}",
                event.getClaimId(), covered, result.getConfidence());
    }

    public AgentResult runValidator(String description, String policyId) {
        try {
            // Step 0: scope guard — verify insured object matches policy type BEFORE RAG
            AgentResult scopeRejection = checkScope(description, policyId);
            if (scopeRejection != null) {
                return scopeRejection;
            }

            // Step 1: retrieve relevant contract chunks from pgvector
            List<String> chunks = vectorStorePort.retrieveRelevantChunks(description, policyId, 5);

            if (chunks.isEmpty()) {
                log.warn("[VALIDATOR] No contract chunks found for policyId={}", policyId);
                String fallback = "{\"covered\":false,\"confidence\":0.3," +
                        "\"coverageSection\":\"N/A\"," +
                        "\"reasoning\":\"No contract document found for this policy\"}";
                return AgentResult.success(fallback, 0.3, "No contract found");
            }

            // Step 2: join chunks into a single context string
            String contractContext = String.join("\n\n---\n\n", chunks);
            log.debug("[VALIDATOR] Using {} contract chunks", chunks.size());

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

    /**
     * Fetches the policy and runs ClaimScopeChecker.
     *
     * @return a rejection AgentResult if the claim is out of scope, null if check passes.
     */
    private AgentResult checkScope(String description, String policyId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(policyId);
        } catch (IllegalArgumentException e) {
            log.warn("[VALIDATOR][SCOPE-GUARD] Invalid policyId format '{}' — skipping scope check", policyId);
            return null;
        }

        Optional<Policy> policyOpt = policyRepository.findById(uuid);
        if (policyOpt.isEmpty()) {
            log.warn("[VALIDATOR][SCOPE-GUARD] Policy {} not found — skipping scope check", policyId);
            return null;
        }

        ClaimType policyType = policyOpt.get().getType();
        ClaimScopeChecker.ScopeCheckResult scope = ClaimScopeChecker.check(policyType, description);

        if (!scope.isInScope()) {
            log.warn("[VALIDATOR][SCOPE-GUARD] REJECTED — policyId={} policyType={} | reason: {}",
                    policyId, policyType, scope.getRejectionReason());
            return AgentResult.success(buildRejectionJson(scope.getRejectionReason()),
                    0.99, scope.getRejectionReason());
        }

        log.debug("[VALIDATOR][SCOPE-GUARD] PASS — policyId={} policyType={}", policyId, policyType);
        return null;
    }

    private String buildRejectionJson(String reason) {
        String escaped = reason.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"covered\":false,\"confidence\":0.99," +
                "\"coverageSection\":\"N/A\"," +
                "\"reasoning\":\"" + escaped + "\"}";
    }
}
