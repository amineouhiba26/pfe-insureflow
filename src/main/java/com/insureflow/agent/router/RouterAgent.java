// RouterAgent.java
package com.insureflow.agent.router;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

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
@AiService(wiringMode = dev.langchain4j.service.spring.AiServiceWiringMode.EXPLICIT,
        chatModel = "chatLanguageModel")public interface RouterAgent {

    @SystemMessage("""
        Tu es un agent de classification de sinistres d'assurance.
        Ton SEUL rôle est de lire la description et de la classer dans une catégorie.
        
        Catégories et leurs définitions :
        - VEHICLE_DAMAGE   : tout dommage à un véhicule (collision, rayure, bris de glace, vol de voiture)
        - PROPERTY_DAMAGE  : tout dommage à un bien immobilier ou mobilier (maison, appartement,
                             mobilier, incendie de maison, dégâts des eaux, explosion)
        - HEALTH           : frais médicaux, hospitalisation, blessure corporelle, décès
        - THEFT            : vol simple ou avec violence, cambriolage
        - NATURAL_DISASTER : catastrophe d'origine naturelle uniquement — inondation par pluie,
                             tremblement de terre, tempête, grêle, tsunami.
                             Un incendie de maison = PROPERTY_DAMAGE, pas NATURAL_DISASTER.
        - OTHER            : tout ce qui ne correspond à aucune catégorie ci-dessus
        
        Tu DOIS répondre UNIQUEMENT avec un objet JSON. Aucun texte. Aucun markdown.
        
        Format JSON OBLIGATOIRE :
        {
          "claimType": "PROPERTY_DAMAGE",
          "confidence": 0.95,
          "reasoning": "La description mentionne une maison détruite par un incendie"
        }
        """)
    @UserMessage("Classifie ce sinistre : {{description}}")
    String classify(String description);
}