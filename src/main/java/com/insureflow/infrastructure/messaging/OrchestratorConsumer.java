package com.insureflow.infrastructure.messaging;

import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.infrastructure.decision.DecisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrchestratorConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorConsumer.class);

    private final RabbitTemplate  rabbitTemplate;
    private final ClaimRepository claimRepository;
    private final DecisionService decisionService;

    public OrchestratorConsumer(RabbitTemplate rabbitTemplate,
                                ClaimRepository claimRepository,
                                DecisionService decisionService) {
        this.rabbitTemplate  = rabbitTemplate;
        this.claimRepository = claimRepository;
        this.decisionService = decisionService;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_INTAKE)
    public void onClaimIntake(ClaimEvent event) {
        log.info("[ORCHESTRATOR] Received claimId={}", event.getClaimId());
        rabbitTemplate.convertAndSend(EXCHANGE, Q_ROUTED, event);
    }

    @RabbitListener(queues = RabbitMQConfig.Q_DECISION)
    public void onDecision(ClaimEvent event) {
        log.info("[ORCHESTRATOR] onDecision received claimId={}", event.getClaimId());
        claimRepository.findById(event.getClaimId())
                .ifPresentOrElse(
                    decisionService::decide,
                    () -> log.error("[ORCHESTRATOR] Claim not found: {}", event.getClaimId())
                );
    }

    private static final String EXCHANGE    = RabbitMQConfig.EXCHANGE;
    private static final String Q_ROUTED    = RabbitMQConfig.Q_ROUTED;
}