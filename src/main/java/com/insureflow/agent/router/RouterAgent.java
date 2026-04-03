// RouterAgent.java
package com.insureflow.agent.router;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * RouterAgent is a LangChain4j @AiService.
 *
 * What is @AiService?
 * LangChain4j reads this interface at startup and generates a full
 * implementation automatically — you never write the LLM call code yourself.
 * It handles: prompt construction, Ollama HTTP call, response parsing.
 *
 * @SystemMessage sets the system prompt — the agent's permanent instructions.
 * It tells the LLM exactly what role it plays and what format to return.
 *
 * @UserMessage is the per-call prompt — it receives the actual claim description.
 * {{description}} is a template variable filled in at runtime.
 *
 * Why strict JSON output?
 * We need machine-readable responses. The system prompt explicitly forbids
 * any text outside the JSON block so ResponseParser can extract it cleanly.
 *
 * Why temperature 0.1 in application.yml?
 * Classification tasks need determinism. Low temperature = the model picks
 * the most probable token every time instead of being creative.
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "chatLanguageModel")
public interface RouterAgent {

    @SystemMessage("""
        Tu es un agent de classification de sinistres d'assurance.
        Tu lis une description et tu retournes EXACTEMENT un JSON — rien d'autre.
        
        CATÉGORIES :
        VEHICLE_DAMAGE   → dommage à un véhicule : collision, rayure, bris de glace,
                           crevaison, incendie du véhicule, vol du véhicule complet
        PROPERTY_DAMAGE  → dommage à un bien immobilier ou mobilier : maison,
                           appartement, mobilier, incendie de bâtiment, dégâts des eaux,
                           explosion, inondation intérieure
        HEALTH           → atteinte corporelle : blessure, hospitalisation,
                           frais médicaux, décès, invalidité
        THEFT            → vol de biens (pas du véhicule entier) : cambriolage,
                           vol de téléphone, bijoux, espèces, effraction
        NATURAL_DISASTER → catastrophe naturelle : tremblement de terre, tsunami,
                           tempête, grêle, inondation par crue (cause naturelle externe)
        OTHER            → aucune catégorie ci-dessus ne correspond
        
        RÈGLE ABSOLUE : un incendie de maison = PROPERTY_DAMAGE (pas NATURAL_DISASTER).
        Un vol de voiture entière = VEHICLE_DAMAGE (pas THEFT).
        
        EXEMPLES :
        "pare-choc arraché suite à collision" → VEHICLE_DAMAGE
        "toit effondré après les pluies" → PROPERTY_DAMAGE
        "hospitalisé 3 jours suite à accident" → HEALTH
        "téléphone volé dans ma voiture" → THEFT
        "maison inondée par la crue de l'oued" → NATURAL_DISASTER
        
        RÉPONSE : JSON strict, aucun texte avant ou après, aucun markdown.
        {
          "claimType": "VEHICLE_DAMAGE",
          "confidence": 0.95,
          "reasoning": "une phrase en français expliquant le choix"
        }
        """)
    @UserMessage("Classifie ce sinistre : {{description}}")
    String classify(String description);
}