package com.insureflow.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares all RabbitMQ infrastructure: exchange, queues, DLQs, bindings.
 *
 * Why a Direct Exchange?
 * With a Direct Exchange, a message is routed to the queue whose binding key
 * exactly matches the routing key on the message.
 * Example: message with routingKey="claim.intake" → goes to claim.intake queue only.
 * This gives us precise control — each agent gets only the messages it needs.
 *
 * What is a DLQ (Dead Letter Queue)?
 * If a consumer throws an exception and the message is rejected after retries,
 * RabbitMQ moves it to the DLQ instead of losing it.
 * In Sprint 7 we add a DLQ consumer that logs these for debugging.
 *
 * @Bean methods: Spring calls these once at startup and registers the result
 * as a managed bean. RabbitMQ admin automatically creates queues/exchanges
 * if they don't already exist.
 *
 * Jackson2JsonMessageConverter: makes RabbitTemplate serialize/deserialize
 * Java objects as JSON automatically. Without this, messages are raw bytes.
 */
@Configuration
public class RabbitMQConfig {

    // ── Exchange name ──────────────────────────────────────────
    public static final String EXCHANGE = "insureflow.claims";

    // ── Queue names ────────────────────────────────────────────
    public static final String Q_INTAKE    = "claim.intake";
    public static final String Q_ROUTED    = "claim.routed";
    public static final String Q_VALIDATED = "claim.validated";
    public static final String Q_ESTIMATED = "claim.estimated";
    public static final String Q_FRAUD     = "claim.fraud.checked";
    public static final String Q_DECISION  = "claim.decision";
    public static final String Q_REVIEW    = "claim.human.review";

    // ── DLQ names (dead letter queues) ─────────────────────────
    public static final String DLQ_INTAKE    = "claim.intake.dlq";
    public static final String DLQ_ROUTED    = "claim.routed.dlq";
    public static final String DLQ_VALIDATED = "claim.validated.dlq";
    public static final String DLQ_ESTIMATED = "claim.estimated.dlq";
    public static final String DLQ_FRAUD     = "claim.fraud.checked.dlq";

    // ── The exchange ────────────────────────────────────────────
    @Bean
    public DirectExchange claimsExchange() {
        // durable=true: exchange survives RabbitMQ restart
        return new DirectExchange(EXCHANGE, true, false);
    }

    // ── Helper: build a queue with a DLQ attached ──────────────
    /**
     * Creates a durable queue that, on rejection, sends failed messages
     * to its DLQ instead of dropping them.
     *
     * QueueBuilder.durable() = queue survives broker restart.
     * withArgument("x-dead-letter-exchange", "") = use the default exchange
     * withArgument("x-dead-letter-routing-key", dlqName) = route to DLQ by name
     */
    private Queue queueWithDlq(String queueName, String dlqName) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", dlqName)
                .build();
    }

    private Queue dlq(String dlqName) {
        return QueueBuilder.durable(dlqName).build();
    }

    // ── Queues ──────────────────────────────────────────────────
    @Bean public Queue intakeQueue()    { return queueWithDlq(Q_INTAKE,    DLQ_INTAKE);    }
    @Bean public Queue routedQueue()    { return queueWithDlq(Q_ROUTED,    DLQ_ROUTED);    }
    @Bean public Queue validatedQueue() { return queueWithDlq(Q_VALIDATED, DLQ_VALIDATED); }
    @Bean public Queue estimatedQueue() { return queueWithDlq(Q_ESTIMATED, DLQ_ESTIMATED); }
    @Bean public Queue fraudQueue()     { return queueWithDlq(Q_FRAUD,     DLQ_FRAUD);     }
    @Bean public Queue decisionQueue()  { return QueueBuilder.durable(Q_DECISION).build(); }
    @Bean public Queue reviewQueue()    { return QueueBuilder.durable(Q_REVIEW).build();   }

    // ── DLQs ────────────────────────────────────────────────────
    @Bean public Queue intakeDlq()    { return dlq(DLQ_INTAKE);    }
    @Bean public Queue routedDlq()    { return dlq(DLQ_ROUTED);    }
    @Bean public Queue validatedDlq() { return dlq(DLQ_VALIDATED); }
    @Bean public Queue estimatedDlq() { return dlq(DLQ_ESTIMATED); }
    @Bean public Queue fraudDlq()     { return dlq(DLQ_FRAUD);     }

    // ── Bindings: queue ↔ exchange ↔ routing key ───────────────
    /**
     * A binding tells the exchange: "when you see routingKey X, send to queue Y".
     * The routing key is the same as the queue name here — simple and explicit.
     */
    @Bean public Binding intakeBinding()    { return BindingBuilder.bind(intakeQueue()).to(claimsExchange()).with(Q_INTAKE);    }
    @Bean public Binding routedBinding()    { return BindingBuilder.bind(routedQueue()).to(claimsExchange()).with(Q_ROUTED);    }
    @Bean public Binding validatedBinding() { return BindingBuilder.bind(validatedQueue()).to(claimsExchange()).with(Q_VALIDATED); }
    @Bean public Binding estimatedBinding() { return BindingBuilder.bind(estimatedQueue()).to(claimsExchange()).with(Q_ESTIMATED); }
    @Bean public Binding fraudBinding()     { return BindingBuilder.bind(fraudQueue()).to(claimsExchange()).with(Q_FRAUD);     }
    @Bean public Binding decisionBinding()  { return BindingBuilder.bind(decisionQueue()).to(claimsExchange()).with(Q_DECISION);  }
    @Bean public Binding reviewBinding()    { return BindingBuilder.bind(reviewQueue()).to(claimsExchange()).with(Q_REVIEW);   }

    // ── JSON serialization ──────────────────────────────────────
    /**
     * Without this, RabbitTemplate sends Java objects as serialized bytes.
     * With this, it sends clean JSON that any language can consume.
     * Also used by @RabbitListener to deserialize incoming messages.
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Configures the RabbitTemplate (the class you use to send messages)
     * to use JSON conversion by default.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }
}