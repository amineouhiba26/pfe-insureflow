// OrchestratorConsumer.java
package com.insureflow.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Listens on claim.intake — the first queue in the pipeline.
 *
 * Responsibility (Sprint 3 stub):
 *   - Receive the ClaimEvent
 *   - Fan it out to the 4 agent queues in parallel
 *
 * Real responsibility (Sprint 6):
 *   - Track results from all 4 agents using ConcurrentHashMap + correlationId
 *   - When all 4 have responded, run DecisionMatrix
 *
 * @RabbitListener(queues = "...") tells Spring to call this method
 * every time a message arrives on that queue. Spring uses the
 * Jackson2JsonMessageConverter we configured to deserialize the
 * JSON back into a ClaimEvent object automatically.
 */
@Component
public class OrchestratorConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorConsumer.class);

    private final RabbitTemplate rabbitTemplate;

    public OrchestratorConsumer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_INTAKE)
    public void onClaimIntake(ClaimEvent event) {
        log.info("[ORCHESTRATOR] Received claim claimId={} correlationId={}",
                event.getClaimId(), event.getCorrelationId());

        // Fan out to all 4 agent queues in parallel
        // Router and Validator both get the text event
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.Q_ROUTED,    event);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.Q_VALIDATED, event);
        // Estimator gets the photo event
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.Q_ESTIMATED, event);

        log.info("[ORCHESTRATOR] Fanned out claimId={} to router, validator, estimator queues",
                event.getClaimId());
    }
}