package com.insureflow.agent.validator;

import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.Claim;
import com.insureflow.domain.model.Policy;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.model.enums.ClaimType;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.domain.port.out.PolicyRepository;
import com.insureflow.domain.port.out.VectorStorePort;
import com.insureflow.infrastructure.messaging.ClaimEvent;
import com.insureflow.infrastructure.messaging.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the pipeline short-circuit behavior introduced in ValidatorAgentService.
 *
 * Golden rule: when a claim is rejected (covered=false), neither EstimatorAgent
 * nor FraudAgent must be triggered — verified by asserting that Q_ESTIMATED is
 * never published to RabbitMQ and that Q_DECISION is published instead.
 *
 * Note on verify() syntax: RabbitTemplate has overlapping convertAndSend overloads
 * (String,String,Object) vs (String,Object,CorrelationData). Using any(Object.class)
 * as the third arg resolves the compiler ambiguity by explicitly selecting Object.
 */
@ExtendWith(MockitoExtension.class)
class ValidatorPipelineShortCircuitTest {

    @Mock private ValidatorAgent   validatorAgent;
    @Mock private VectorStorePort  vectorStorePort;
    @Mock private ClaimRepository  claimRepository;
    @Mock private PolicyRepository policyRepository;
    @Mock private RabbitTemplate   rabbitTemplate;

    private ValidatorAgentService service;
    private UUID claimId;
    private UUID policyId;
    private UUID clientId;

    @BeforeEach
    void setUp() {
        service  = new ValidatorAgentService(validatorAgent, vectorStorePort,
                claimRepository, policyRepository, rabbitTemplate);
        claimId  = UUID.randomUUID();
        policyId = UUID.randomUUID();
        clientId = UUID.randomUUID();
    }

    // ── Core bug case: domestic fire on auto policy ───────────────────────────

    @Test
    void domesticFire_onAutoPolicy_estimatorAndFraudNeverTriggered() {
        // GIVEN — auto policy (VEHICLE_DAMAGE)
        stubAutoPolicy();
        stubClaim("Ma maison a brûlé cette nuit. La cuisine et tout le mobilier sont détruits.");
        ClaimEvent event = buildEvent("Ma maison a brûlé cette nuit. La cuisine et tout le mobilier sont détruits.");

        // WHEN
        service.onValidated(event);

        // THEN — Q_DECISION must be fired, never Q_ESTIMATED
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.Q_DECISION), any(Object.class));
        verify(rabbitTemplate, never()).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.Q_ESTIMATED), any(Object.class));

        // AND — SKIPPED markers must be written for the two bypassed agents
        ArgumentCaptor<Claim> captor = ArgumentCaptor.forClass(Claim.class);
        verify(claimRepository).save(captor.capture());
        Claim saved = captor.getValue();

        assertThat(ResponseParser.getBoolean(saved.getValidatorResult(), "covered", true)).isFalse();
        assertThat(ResponseParser.getBoolean(saved.getEstimatorResult(), "skipped", false)).isTrue();
        assertThat(ResponseParser.getString(saved.getEstimatorResult(),  "status",  "")).isEqualTo("SKIPPED");
        assertThat(ResponseParser.getBoolean(saved.getFraudResult(),     "skipped", false)).isTrue();
        assertThat(ResponseParser.getString(saved.getFraudResult(),      "status",  "")).isEqualTo("SKIPPED");

        // AND — neither pgvector nor LLM were consulted (scope guard fired before RAG)
        verify(vectorStorePort, never()).retrieveRelevantChunks(anyString(), anyString(), anyInt());
        verify(validatorAgent,  never()).validate(anyString(), anyString());
    }

    // ── Vol de téléphone on auto policy ──────────────────────────────────────

    @Test
    void phoneTheft_onAutoPolicy_estimatorAndFraudNeverTriggered() {
        stubAutoPolicy();
        stubClaim("On m'a volé mon téléphone portable à la maison.");
        ClaimEvent event = buildEvent("On m'a volé mon téléphone portable à la maison.");

        service.onValidated(event);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.Q_DECISION), any(Object.class));
        verify(rabbitTemplate, never()).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.Q_ESTIMATED), any(Object.class));
        verify(validatorAgent, never()).validate(anyString(), anyString());
    }

    // ── Valid auto claim must reach Estimator ─────────────────────────────────

    @Test
    void carFire_onAutoPolicy_estimatorTriggered_decisionNotFired() {
        stubAutoPolicy();
        stubClaim("Ma voiture a pris feu sur l'autoroute. Moteur calciné.");
        when(vectorStorePort.retrieveRelevantChunks(anyString(), anyString(), anyInt()))
                .thenReturn(List.of("Article 3 — Garantie Incendie: couvre l'incendie du véhicule assuré."));
        when(validatorAgent.validate(anyString(), anyString()))
                .thenReturn("{\"covered\":true,\"confidence\":0.92," +
                        "\"coverageSection\":\"Garantie Incendie\"," +
                        "\"reasoning\":\"Incendie du véhicule couvert\"}");

        ClaimEvent event = buildEvent("Ma voiture a pris feu sur l'autoroute. Moteur calciné.");
        service.onValidated(event);

        // Scope check passes → RAG + LLM run → covered=true → Q_ESTIMATED, NOT Q_DECISION
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.Q_ESTIMATED), any(Object.class));
        verify(rabbitTemplate, never()).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.Q_DECISION), any(Object.class));

        // SKIPPED markers must NOT be set for covered claims
        ArgumentCaptor<Claim> captor = ArgumentCaptor.forClass(Claim.class);
        verify(claimRepository).save(captor.capture());
        Claim saved = captor.getValue();
        assertThat(saved.getEstimatorResult()).isNull();
        assertThat(saved.getFraudResult()).isNull();
    }

    // ── LLM rejects (covered=false, low confidence) → short-circuit ──────────

    @Test
    void llmReject_contractExclusion_skippedMarkersSet_decisionFired() {
        stubAutoPolicy();
        stubClaim("Mon véhicule a eu un accident lors d'une compétition sportive.");
        when(vectorStorePort.retrieveRelevantChunks(anyString(), anyString(), anyInt()))
                .thenReturn(List.of("Article 8 — Exclusions: les compétitions sportives sont exclues."));
        when(validatorAgent.validate(anyString(), anyString()))
                .thenReturn("{\"covered\":false,\"confidence\":0.88," +
                        "\"coverageSection\":\"Article 8 — Exclusions\"," +
                        "\"reasoning\":\"Accident lors d'une compétition sportive — exclu par contrat\"}");

        ClaimEvent event = buildEvent("Mon véhicule a eu un accident lors d'une compétition sportive.");
        service.onValidated(event);

        // LLM rejected (covered=false) → Q_DECISION, SKIPPED markers
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.Q_DECISION), any(Object.class));
        verify(rabbitTemplate, never()).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.Q_ESTIMATED), any(Object.class));

        ArgumentCaptor<Claim> captor = ArgumentCaptor.forClass(Claim.class);
        verify(claimRepository).save(captor.capture());
        Claim saved = captor.getValue();
        assertThat(ResponseParser.getBoolean(saved.getValidatorResult(), "covered", true)).isFalse();
        assertThat(ResponseParser.getBoolean(saved.getEstimatorResult(), "skipped", false)).isTrue();
        assertThat(ResponseParser.getBoolean(saved.getFraudResult(),     "skipped", false)).isTrue();
    }

    // ── Policy not found → scope guard skipped, pipeline proceeds normally ────

    @Test
    void policyNotFound_scopeGuardSkipped_estimatorStillFired() {
        when(policyRepository.findById(policyId)).thenReturn(Optional.empty());
        stubClaim("Ma maison a brûlé.");
        when(vectorStorePort.retrieveRelevantChunks(anyString(), anyString(), anyInt()))
                .thenReturn(List.of("some chunk"));
        when(validatorAgent.validate(anyString(), anyString()))
                .thenReturn("{\"covered\":true,\"confidence\":0.5,\"coverageSection\":\"N/A\"," +
                        "\"reasoning\":\"no context\"}");

        ClaimEvent event = buildEvent("Ma maison a brûlé.");
        service.onValidated(event);

        // Without policy type, scope guard is skipped → LLM says covered=true → Q_ESTIMATED
        verify(vectorStorePort).retrieveRelevantChunks(anyString(), anyString(), anyInt());
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.Q_ESTIMATED), any(Object.class));
    }

    // ── SKIPPED JSON constants are well-formed for UI parsing ─────────────────

    @Test
    void skippedEstimatorJson_isWellFormedForUi() {
        String json = ValidatorAgentService.SKIPPED_ESTIMATOR_JSON;
        assertThat(ResponseParser.getBoolean(json, "skipped",        false)).isTrue();
        assertThat(ResponseParser.getString( json, "status",         "")).isEqualTo("SKIPPED");
        assertThat(ResponseParser.getString( json, "pricingMethod",  "")).isEqualTo("N/A");
        assertThat(ResponseParser.getString( json, "overallSeverity","")).isEqualTo("N/A");
        assertThat(ResponseParser.getDouble( json, "confidence",     1.0)).isEqualTo(0.0);
    }

    @Test
    void skippedFraudJson_isWellFormedForUi() {
        String json = ValidatorAgentService.SKIPPED_FRAUD_JSON;
        assertThat(ResponseParser.getBoolean(json, "skipped",          false)).isTrue();
        assertThat(ResponseParser.getString( json, "status",           "")).isEqualTo("SKIPPED");
        assertThat(ResponseParser.getDouble( json, "anomalyScore",     -1.0)).isEqualTo(0.0);
        assertThat(ResponseParser.getBoolean(json, "anomalyDetected",  true)).isFalse();
        assertThat(ResponseParser.getString( json, "anomalyType",      "")).isEqualTo("NONE");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubAutoPolicy() {
        Policy policy = new Policy();
        policy.setId(policyId);
        policy.setType(ClaimType.VEHICLE_DAMAGE);
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
    }

    private void stubClaim(String description) {
        Claim claim = Claim.newSubmission(clientId, policyId, description, List.of());
        when(claimRepository.findById(claimId)).thenReturn(Optional.of(claim));
        when(claimRepository.updateStatus(eq(claimId), any(ClaimStatus.class))).thenReturn(claim);
    }

    private ClaimEvent buildEvent(String description) {
        return new ClaimEvent(claimId, clientId, policyId,
                description, List.of(), Instant.now(), null);
    }
}
