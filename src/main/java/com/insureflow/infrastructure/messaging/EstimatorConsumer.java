// EstimatorConsumer.java
package com.insureflow.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Listens on claim.estimated — where Estimator agent results land.
 *
 * Sprint 3 (stub): just logs.
 * Sprint 5 (real): calls llama3.2-vision via Ollama on each photo,
 *                  identifies damaged parts + severity,
 *                  queries repair_costs table for price range,
 *                  then publishes result to Orchestrator AND triggers Fraud agent.
 */
@Component
public class EstimatorConsumer {

    private static final Logger log = LoggerFactory.getLogger(EstimatorConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.Q_ESTIMATED)
    public void onEstimated(ClaimEvent event) {
        log.info("[ESTIMATOR AGENT] Received claimId={} photos={}",
                event.getClaimId(),
                event.getPhotoUrls());
        // Sprint 5: analyse photos via llama3.2-vision + repair_costs lookup
    }
}