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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class RouterAgentService {

    private static final Logger log = LoggerFactory.getLogger(RouterAgentService.class);

    private final RouterAgent     routerAgent;
    private final ClaimRepository claimRepository;
    private final RabbitTemplate  rabbitTemplate;

    public RouterAgentService(RouterAgent routerAgent,
                              ClaimRepository claimRepository,
                              RabbitTemplate rabbitTemplate) {
        this.routerAgent     = routerAgent;
        this.claimRepository = claimRepository;
        this.rabbitTemplate  = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_ROUTED)
    public void onRouted(ClaimEvent event) {
        log.info("[ROUTER] Processing claimId={}", event.getClaimId());
        claimRepository.updateStatus(event.getClaimId(), ClaimStatus.ROUTING);

        AgentResult result = runRouter(event.getDescription());

        String claimTypeStr = ResponseParser.getString(result.getResultJson(), "claimType", "OTHER");
        ClaimType claimType = parseClaimType(claimTypeStr);

        claimRepository.findById(event.getClaimId()).ifPresent(claim -> {
            claim.setType(claimType);
            claim.setRouterResult(result.getResultJson());
            claim.transitionTo(ClaimStatus.VALIDATING);
            claimRepository.save(claim);
        });

        // Trigger Validator only — Validator decides whether to continue to Estimator
        // or short-circuit to Decision (for out-of-scope claims).
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.Q_VALIDATED, event);

        log.info("[ROUTER] Done claimId={} -> type={}, triggered validator (sequential gate)",
                event.getClaimId(), claimType);
    }

    public AgentResult runRouter(String description) {
        try {
            String raw  = routerAgent.classify(description);
            String json = ResponseParser.extractJson(raw);
            double conf = ResponseParser.getDouble(json, "confidence", 0.5);
            String rsn  = ResponseParser.getString(json, "reasoning", "");
            return AgentResult.success(json, conf, rsn);
        } catch (Exception e) {
            log.error("[ROUTER] Agent failed: {}", e.getMessage());
            return AgentResult.failure(e.getMessage());
        }
    }

    private ClaimType parseClaimType(String value) {
        try { return ClaimType.valueOf(value.toUpperCase().trim()); }
        catch (Exception e) { return ClaimType.OTHER; }
    }
}