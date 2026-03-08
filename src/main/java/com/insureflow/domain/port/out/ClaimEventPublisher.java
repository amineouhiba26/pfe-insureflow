package com.insureflow.domain.port.out;

import com.insureflow.domain.model.Claim;

/**
 * Outbound port — the domain says "publish this claim as an event".
 * The RabbitMQ adapter implements this.
 * Domain code never imports spring-amqp.
 */
public interface ClaimEventPublisher {
    void publishSubmitted(Claim claim);
}