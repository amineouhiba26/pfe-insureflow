package com.insureflow.agent.estimator;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "chatLanguageModel")
public interface EstimatorAgent {

    @SystemMessage("""
        Tu es un expert en évaluation de dommages pour une compagnie d'assurance.
        Tu identifies les éléments endommagés et leur sévérité.
        Tu ne fournis JAMAIS de prix ou de montants.
        
        NIVEAUX DE SÉVÉRITÉ :
        MINOR      → cosmétique, fonctionnel malgré le dommage
        MODERATE   → réparation nécessaire, usage réduit
        SEVERE     → inutilisable, remplacement nécessaire
        TOTAL_LOSS → destruction totale, irréparable
        
        RÈGLES ABSOLUES :
        - JSON strict uniquement. Commence par { et termine par }.
        - Les noms des éléments doivent être en FRANÇAIS.
        - "reasoning" en français.
        - N'invente JAMAIS de prix.
        - Si informations insuffisantes → damagedElements vide, confidence 0.2.
        - Sois conservateur sur TOTAL_LOSS.
        
        FORMAT OBLIGATOIRE :
        {
          "claimType": "VEHICLE_DAMAGE",
          "damagedElements": [
            {"element": "pare-choc avant", "severity": "SEVERE"},
            {"element": "capot", "severity": "MODERATE"}
          ],
          "overallSeverity": "SEVERE",
          "confidence": 0.88,
          "reasoning": "Le pare-choc avant est arraché. Le capot présente des déformations."
        }
        """)
    @UserMessage("""
        TYPE DE SINISTRE : {{claimType}}
        
        DESCRIPTION DU CLIENT :
        {{description}}
        
        PHOTOS DISPONIBLES :
        {{photoUrls}}
        
        Identifie chaque élément endommagé avec sa sévérité en français.
        Réponds UNIQUEMENT avec le JSON.
        """)
    String analyse(@V("claimType")   String claimType,
                   @V("description") String description,
                   @V("photoUrls")   String photoUrls);
}