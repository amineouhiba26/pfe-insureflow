package com.insureflow.agent.validator;

import com.insureflow.domain.model.enums.ClaimType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for ClaimScopeChecker — no Spring context, no mocks.
 *
 * Golden rule being tested: a VEHICLE_DAMAGE (auto) policy must REJECT any
 * claim whose description targets a building, furniture, or personal item,
 * even when the guarantee NAME happens to match (e.g., "Incendie").
 */
class ClaimScopeCheckerTest {

    // ── VEHICLE_DAMAGE policy — out-of-scope cases (the bug scenario) ─────────

    @Test
    void vehiclePolicy_houseFire_isOutOfScope() {
        // Exact reproduction of the reported bug: domestic fire on an auto contract
        String description = "Ma maison a brûlé cette nuit. La cuisine et tout le mobilier sont "
                + "détruits par l'incendie. Les dégâts sont importants.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, description);

        assertThat(result.isInScope()).isFalse();
        assertThat(result.getRejectionReason())
                .contains("assurance automobile")
                .contains("véhicule uniquement");
    }

    @Test
    void vehiclePolicy_phoneStolenAtHome_isOutOfScope() {
        // "vol de mon téléphone à la maison" must not match the "Vol" guarantee on an auto contract
        String description = "On m'a volé mon téléphone portable à la maison pendant mon absence.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, description);

        assertThat(result.isInScope()).isFalse();
        assertThat(result.getRejectionReason()).contains("assurance automobile");
    }

    @Test
    void vehiclePolicy_brokenWindowAtHome_isOutOfScope() {
        // "bris de glace" guarantee on auto contract must not cover a broken home window
        String description = "La fenêtre de mon salon a été cassée par un cambrioleur.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, description);

        assertThat(result.isInScope()).isFalse();
    }

    @Test
    void vehiclePolicy_laptopStolen_isOutOfScope() {
        // Personal item theft should be rejected on auto policy
        String description = "Mon ordinateur portable et ma montre ont été dérobés.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, description);

        assertThat(result.isInScope()).isFalse();
    }

    // ── VEHICLE_DAMAGE policy — in-scope cases (must NOT be broken by fix) ───

    @Test
    void vehiclePolicy_carFire_isInScope() {
        String description = "Ma voiture a pris feu sur l'autoroute. Le moteur et la carrosserie "
                + "sont complètement calcinés.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, description);

        assertThat(result.isInScope()).isTrue();
    }

    @Test
    void vehiclePolicy_stolenVehicle_isInScope() {
        String description = "Mon véhicule a été volé sur le parking de mon immeuble cette nuit.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, description);

        // "immeuble" is in the property tokens, but "véhicule" is also present → in scope
        assertThat(result.isInScope()).isTrue();
    }

    @Test
    void vehiclePolicy_windshieldCracked_isInScope() {
        String description = "Un caillou a fissuré mon pare-brise sur la route nationale.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, description);

        assertThat(result.isInScope()).isTrue();
    }

    @Test
    void vehiclePolicy_collision_isInScope() {
        String description = "J'ai eu un accident de voiture au carrefour, portière avant gauche enfoncée.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, description);

        assertThat(result.isInScope()).isTrue();
    }

    @Test
    void vehiclePolicy_carMentionedAlongWithLocation_isInScope() {
        // Vehicle mentioned even if a location word is also present
        String description = "Mon auto a été endommagée dans le parking souterrain de mon immeuble.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, description);

        assertThat(result.isInScope()).isTrue();
    }

    // ── Other policy types — scope check should not interfere ─────────────────

    @Test
    void propertyDamagePolicy_houseFire_isInScope() {
        // A PROPERTY_DAMAGE policy covers buildings — fire at home should pass
        String description = "Ma maison a brûlé. La cuisine et tout le mobilier sont détruits.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.PROPERTY_DAMAGE, description);

        assertThat(result.isInScope()).isTrue();
    }

    @Test
    void healthPolicy_hospitalization_isInScope() {
        String description = "J'ai été hospitalisé suite à un accident, frais médicaux importants.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.HEALTH, description);

        assertThat(result.isInScope()).isTrue();
    }

    // ── Edge cases ─────────────────────────────────────────────────────────────

    @Test
    void nullDescription_isInScope() {
        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, null);

        assertThat(result.isInScope()).isTrue();
    }

    @Test
    void blankDescription_isInScope() {
        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, "   ");

        assertThat(result.isInScope()).isTrue();
    }

    @Test
    void vehiclePolicy_ambiguousDescription_noKeywords_isInScope() {
        // When neither vehicle nor property keywords are found, let RAG + LLM handle it
        String description = "J'ai subi des dommages importants suite à l'incident.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, description);

        assertThat(result.isInScope()).isTrue();
    }

    @Test
    void outOfScope_rejectionReasonMentionsPolicyScope() {
        String description = "Mon canapé et mon réfrigérateur ont été abîmés par une fuite d'eau.";

        ClaimScopeChecker.ScopeCheckResult result =
                ClaimScopeChecker.check(ClaimType.VEHICLE_DAMAGE, description);

        assertThat(result.isInScope()).isFalse();
        assertThat(result.getRejectionReason())
                .isNotBlank()
                .contains("véhicule");
    }
}
