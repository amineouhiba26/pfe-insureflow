package com.insureflow.agent.validator;

import com.insureflow.agent.shared.AgentResult;
import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.Policy;
import com.insureflow.domain.model.enums.ClaimType;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.domain.port.out.PolicyRepository;
import com.insureflow.domain.port.out.VectorStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that the scope guard in ValidatorAgentService hard-rejects out-of-scope
 * claims BEFORE touching RAG or the LLM.
 *
 * Verifies the reported bug: domestic fire on an auto contract must return
 * covered=false, not the previously erroneous covered=true.
 */
@ExtendWith(MockitoExtension.class)
class ValidatorAgentServiceScopeTest {

    @Mock private ValidatorAgent   validatorAgent;
    @Mock private VectorStorePort  vectorStorePort;
    @Mock private ClaimRepository  claimRepository;
    @Mock private PolicyRepository policyRepository;
    @Mock private RabbitTemplate   rabbitTemplate;

    private ValidatorAgentService service;
    private UUID policyId;

    @BeforeEach
    void setUp() {
        service  = new ValidatorAgentService(validatorAgent, vectorStorePort,
                claimRepository, policyRepository, rabbitTemplate);
        policyId = UUID.randomUUID();
    }

    // ── Bug reproduction: domestic fire on an auto contract ───────────────────

    @Test
    void domesticFireOnAutoPolicy_mustBeRejectedNotValidated() {
        // GIVEN — auto policy (VEHICLE_DAMAGE)
        stubPolicy(ClaimType.VEHICLE_DAMAGE);

        // WHEN — claim describes a domestic fire with no vehicle mention
        String description = "Ma maison a brûlé cette nuit. La cuisine et tout le mobilier "
                + "sont détruits par l'incendie. Les dégâts sont importants.";
        AgentResult result = service.runValidator(description, policyId.toString());

        // THEN — scope guard must reject before RAG
        assertThat(ResponseParser.getBoolean(result.getResultJson(), "covered", true))
                .isFalse();
        assertThat(result.getConfidence()).isGreaterThan(0.95); // deterministic rejection
        assertThat(ResponseParser.getString(result.getResultJson(), "reasoning", ""))
                .contains("assurance automobile");

        // AND — the LLM and pgvector must NOT have been called
        verify(vectorStorePort, never()).retrieveRelevantChunks(anyString(), anyString(), anyInt());
        verify(validatorAgent,  never()).validate(anyString(), anyString());
    }

    // ── Vol de téléphone sur contrat auto ─────────────────────────────────────

    @Test
    void phoneStolenAtHome_onAutoPolicy_mustBeRejected() {
        stubPolicy(ClaimType.VEHICLE_DAMAGE);

        String description = "On m'a volé mon téléphone portable à la maison pendant mon absence.";
        AgentResult result = service.runValidator(description, policyId.toString());

        assertThat(ResponseParser.getBoolean(result.getResultJson(), "covered", true)).isFalse();
        verify(vectorStorePort, never()).retrieveRelevantChunks(anyString(), anyString(), anyInt());
        verify(validatorAgent,  never()).validate(anyString(), anyString());
    }

    // ── Bris de glace à domicile sur contrat auto ─────────────────────────────

    @Test
    void brokenWindowAtHome_onAutoPolicy_mustBeRejected() {
        stubPolicy(ClaimType.VEHICLE_DAMAGE);

        String description = "La fenêtre de mon salon a été brisée lors d'une tentative de cambriolage.";
        AgentResult result = service.runValidator(description, policyId.toString());

        assertThat(ResponseParser.getBoolean(result.getResultJson(), "covered", true)).isFalse();
        verify(validatorAgent, never()).validate(anyString(), anyString());
    }

    // ── Valid auto claims must pass through to RAG ────────────────────────────

    @Test
    void carFireClaim_onAutoPolicy_mustReachRag() {
        stubPolicy(ClaimType.VEHICLE_DAMAGE);
        // Stub RAG to return some chunks
        when(vectorStorePort.retrieveRelevantChunks(anyString(), anyString(), anyInt()))
                .thenReturn(List.of("Article 3 — Garantie Incendie : couvre le véhicule assuré..."));
        when(validatorAgent.validate(anyString(), anyString()))
                .thenReturn("{\"covered\":true,\"confidence\":0.92,\"coverageSection\":\"Garantie Incendie\",\"reasoning\":\"Incendie du véhicule\"}");

        String description = "Ma voiture a pris feu sur l'autoroute. Moteur et carrosserie calcinés.";
        AgentResult result = service.runValidator(description, policyId.toString());

        // Scope guard passes — result comes from LLM
        verify(vectorStorePort).retrieveRelevantChunks(anyString(), anyString(), anyInt());
        verify(validatorAgent).validate(anyString(), anyString());
        assertThat(ResponseParser.getBoolean(result.getResultJson(), "covered", false)).isTrue();
    }

    @Test
    void stolenVehicleClaim_onAutoPolicy_mustReachRag() {
        stubPolicy(ClaimType.VEHICLE_DAMAGE);
        when(vectorStorePort.retrieveRelevantChunks(anyString(), anyString(), anyInt()))
                .thenReturn(List.of("Article 5 — Garantie Vol : vol ou tentative de vol du véhicule..."));
        when(validatorAgent.validate(anyString(), anyString()))
                .thenReturn("{\"covered\":true,\"confidence\":0.90,\"coverageSection\":\"Garantie Vol\",\"reasoning\":\"Véhicule volé\"}");

        String description = "Mon véhicule a été volé sur le parking cette nuit, plaque immatriculation TU 123.";
        service.runValidator(description, policyId.toString());

        verify(vectorStorePort).retrieveRelevantChunks(anyString(), anyString(), anyInt());
        verify(validatorAgent).validate(anyString(), anyString());
    }

    // ── Policy not found → scope check skipped → proceeds to RAG ─────────────

    @Test
    void policyNotFound_scopeCheckSkipped_proceedsToRag() {
        when(policyRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(vectorStorePort.retrieveRelevantChunks(anyString(), anyString(), anyInt()))
                .thenReturn(List.of("some chunk"));
        when(validatorAgent.validate(anyString(), anyString()))
                .thenReturn("{\"covered\":false,\"confidence\":0.5,\"coverageSection\":\"N/A\",\"reasoning\":\"unclear\"}");

        // Even a domestic fire description reaches RAG when policy isn't found
        String description = "Ma maison a brûlé.";
        service.runValidator(description, policyId.toString());

        verify(vectorStorePort).retrieveRelevantChunks(anyString(), anyString(), anyInt());
    }

    // ── PROPERTY_DAMAGE policy — domestic fire must reach RAG (no false rejection)

    @Test
    void domesticFire_onPropertyPolicy_mustNotBeRejectedByScopeGuard() {
        stubPolicy(ClaimType.PROPERTY_DAMAGE);
        when(vectorStorePort.retrieveRelevantChunks(anyString(), anyString(), anyInt()))
                .thenReturn(List.of("Article 2 — Incendie habitation..."));
        when(validatorAgent.validate(anyString(), anyString()))
                .thenReturn("{\"covered\":true,\"confidence\":0.95,\"coverageSection\":\"Incendie habitation\",\"reasoning\":\"Bien immobilier couvert\"}");

        String description = "Ma maison a brûlé cette nuit. La cuisine et tout le mobilier sont détruits.";
        AgentResult result = service.runValidator(description, policyId.toString());

        // Scope guard must NOT reject this — it's a property policy
        verify(vectorStorePort).retrieveRelevantChunks(anyString(), anyString(), anyInt());
        assertThat(ResponseParser.getBoolean(result.getResultJson(), "covered", false)).isTrue();
    }

    // ── Rejection JSON must be well-formed ───────────────────────────────────

    @Test
    void rejectionJson_isCoveredFalseAndWellFormed() {
        stubPolicy(ClaimType.VEHICLE_DAMAGE);

        String description = "Mon mobilier de bureau a été endommagé par une fuite d'eau.";
        AgentResult result = service.runValidator(description, policyId.toString());

        String json = result.getResultJson();
        assertThat(ResponseParser.getBoolean(json, "covered", true)).isFalse();
        assertThat(ResponseParser.getString(json, "coverageSection", "")).isEqualTo("N/A");
        assertThat(ResponseParser.getString(json, "reasoning", "")).isNotBlank();
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.99);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubPolicy(ClaimType type) {
        Policy policy = new Policy();
        policy.setId(policyId);
        policy.setType(type);
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
    }
}
