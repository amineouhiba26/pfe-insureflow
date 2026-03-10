// FraudConsumer.java
package com.insureflow.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Listens on claim.fraud.checked — where Fraud agent results land.
 *
 * Sprint 3 (stub): just logs.
 * Sprint 6 (real): calls FraudAgent @AiService — compares client description
 *                  vs estimator photo findings, returns anomalyScore + AnomalyType.
 */
@Component
public class FraudConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.Q_FRAUD)
    public void onFraudChecked(ClaimEvent event) {
        log.info("[FRAUD AGENT] Received claimId={}", event.getClaimId());
        // Sprint 6: cross-examine description vs estimator result
    }
}