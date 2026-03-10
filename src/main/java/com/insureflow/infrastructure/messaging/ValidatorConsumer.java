// ValidatorConsumer.java
package com.insureflow.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Listens on claim.validated — where Validator agent results land.
 *
 * Sprint 3 (stub): just logs.
 * Sprint 4 (real): calls ValidatorAgent with RAG — retrieves contract chunks
 *                  from pgvector filtered by policyId, checks coverage.
 */
@Component
public class ValidatorConsumer {

    private static final Logger log = LoggerFactory.getLogger(ValidatorConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.Q_VALIDATED)
    public void onValidated(ClaimEvent event) {
        log.info("[VALIDATOR AGENT] Received claimId={} policyId={}",
                event.getClaimId(),
                event.getPolicyId());
        // Sprint 4: validate coverage via Spring AI pgvector RAG
    }
}