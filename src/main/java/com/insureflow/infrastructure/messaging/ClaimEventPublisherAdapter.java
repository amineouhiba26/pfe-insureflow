package com.insureflow.infrastructure.messaging;

import com.insureflow.domain.model.Claim;
import com.insureflow.domain.port.out.ClaimEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Implements the domain ClaimEventPublisher port using RabbitMQ.
 *
 * @Component = Spring creates and manages this bean.
 *
 * RabbitTemplate.convertAndSend(exchange, routingKey, payload):
 *   - exchange:   which exchange to send to (insureflow.claims)
 *   - routingKey: how the exchange decides which queue to route to (claim.intake)
 *   - payload:    the ClaimEvent object — Jackson serializes it to JSON automatically
 *
 * The domain side (SubmitClaimUseCaseImpl) only sees ClaimEventPublisher.
 * It calls publishSubmitted(claim) and has no idea this class exists.
 * This is the Dependency Inversion Principle in action.
 */
@Component
public class ClaimEventPublisherAdapter implements ClaimEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventPublisherAdapter.class);

    private final RabbitTemplate rabbitTemplate;

    public ClaimEventPublisherAdapter(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publishSubmitted(Claim claim) {
        ClaimEvent event = new ClaimEvent(
                claim.getId(),
                claim.getClientId(),
                claim.getPolicyId(),
                claim.getDescription(),
                claim.getPhotoUrls(),
                claim.getSubmittedAt()
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE,
                RabbitMQConfig.Q_INTAKE,
                event
        );

        log.info("Published claim.submitted event for claimId={}", claim.getId());
    }
}