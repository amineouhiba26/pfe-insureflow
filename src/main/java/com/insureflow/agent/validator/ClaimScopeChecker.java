package com.insureflow.agent.validator;

import com.insureflow.domain.model.enums.ClaimType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Pre-RAG scope guard: verifies that the insured object described in a claim
 * matches the type of asset covered by the policy.
 *
 * Guards against false positives where cosine similarity matches a guarantee
 * by name (e.g., "Incendie") without verifying that the damaged object
 * (e.g., a house) is actually the insured asset (e.g., a vehicle on an auto contract).
 *
 * Called BEFORE the RAG + LLM pipeline in ValidatorAgentService.
 * Uses keyword matching — deterministic, fast, zero extra LLM tokens.
 */
public final class ClaimScopeChecker {

    private static final Logger log = LoggerFactory.getLogger(ClaimScopeChecker.class);

    // Tokens that indicate the damaged/stolen object is a vehicle
    static final List<String> VEHICLE_TOKENS = List.of(
            "voiture", "auto", "automobile", "véhicule", "vehicule",
            "moto", "motocycle", "motocyclette", "scooter",
            "camion", "camionnette", "bus", "tricycle",
            "pare-brise", "parebrise", "portière", "portiere",
            "moteur", "roue", "pneu", "carrosserie", "capot",
            "phare", "coffre", "volant", "tableau de bord",
            "immatriculation", "plaque", "habitacle", "châssis", "chassis"
    );

    // Tokens that indicate the damaged object is a building or furniture
    static final List<String> PROPERTY_TOKENS = List.of(
            "maison", "domicile", "cuisine", "logement", "appartement",
            "immeuble", "bâtiment", "batiment", "chambre", "toit",
            "façade", "facade", "mur", "mobilier", "meuble",
            "canapé", "canape", "réfrigérateur", "refrigerateur",
            "lave-linge", "lave linge", "télévision", "television",
            "vêtement", "vetement", "salon", "salle de bain", "couloir",
            "jardin", "fenêtre", "fenetre", "porte d'entrée", "habitation",
            "résidence", "residence", "chaudière", "chaudiere",
            "plomberie", "électroménager", "electromenager"
    );

    // Tokens that indicate the damaged/stolen object is a personal portable item
    static final List<String> PERSONAL_ITEM_TOKENS = List.of(
            "téléphone", "telephone", "portable", "smartphone",
            "ordinateur", "laptop", "montre", "bijou", "bijoux",
            "sac", "portefeuille", "tablette", "appareil photo",
            "console", "argent liquide", "espèces", "especes"
    );

    private ClaimScopeChecker() {}

    /**
     * Checks whether the claim description's insured object is coherent with
     * the policy type.
     *
     * @param policyType the ClaimType of the policy (what category of asset it covers)
     * @param description the raw claim description submitted by the client
     * @return ScopeCheckResult.inScope() if the claim passes, or
     *         ScopeCheckResult.outOfScope(reason) with an explicit French rejection message
     */
    public static ScopeCheckResult check(ClaimType policyType, String description) {
        if (description == null || description.isBlank()) {
            return ScopeCheckResult.inScope();
        }

        String desc = description.toLowerCase();

        if (policyType == ClaimType.VEHICLE_DAMAGE) {
            boolean hasVehicle  = VEHICLE_TOKENS.stream().anyMatch(desc::contains);
            boolean hasProperty = PROPERTY_TOKENS.stream().anyMatch(desc::contains);
            boolean hasPersonal = PERSONAL_ITEM_TOKENS.stream().anyMatch(desc::contains);

            log.debug("[SCOPE-GUARD] VEHICLE_DAMAGE — hasVehicle={} hasProperty={} hasPersonal={} desc={}",
                    hasVehicle, hasProperty, hasPersonal,
                    desc.substring(0, Math.min(100, desc.length())));

            if (!hasVehicle && (hasProperty || hasPersonal)) {
                String category = hasProperty ? "bien immobilier/mobilier" : "objet personnel";
                String reason = "Objet du sinistre hors périmètre du contrat"
                        + " (assurance automobile — véhicule uniquement)."
                        + " La description mentionne un " + category
                        + ", non le véhicule assuré.";
                return ScopeCheckResult.outOfScope(reason);
            }
        }

        return ScopeCheckResult.inScope();
    }

    // ── Result type ────────────────────────────────────────────────────────────

    public static final class ScopeCheckResult {
        private final boolean inScope;
        private final String  rejectionReason;

        private ScopeCheckResult(boolean inScope, String rejectionReason) {
            this.inScope         = inScope;
            this.rejectionReason = rejectionReason;
        }

        public static ScopeCheckResult inScope()                   { return new ScopeCheckResult(true,  null);   }
        public static ScopeCheckResult outOfScope(String reason)   { return new ScopeCheckResult(false, reason); }

        public boolean isInScope()          { return inScope;          }
        public String  getRejectionReason() { return rejectionReason;  }
    }
}
