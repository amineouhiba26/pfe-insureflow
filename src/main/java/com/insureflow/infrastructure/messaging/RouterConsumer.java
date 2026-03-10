// RouterConsumer.java
package com.insureflow.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Listens on claim.routed — where Router agent results land.
 *
 * Sprint 3 (stub): just logs the event.
 * Sprint 4 (real): calls RouterAgent @AiService to classify ClaimType,
 *                  writes routerResult JSON to the claim in DB,
 *                  publishes result back to Orchestrator.
 */
@Component
public class RouterConsumer {

    private static final Logger log = LoggerFactory.getLogger(RouterConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.Q_ROUTED)
    public void onRouted(ClaimEvent event) {
        log.info("[ROUTER AGENT] Received claimId={} description='{}'",
                event.getClaimId(),
                event.getDescription());
        // Sprint 4: classify claim type via LangChain4j RouterAgent
    }
}