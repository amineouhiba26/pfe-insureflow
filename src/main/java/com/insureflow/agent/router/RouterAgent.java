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
@AiService
public interface RouterAgent {

    @SystemMessage("""
        You are an insurance claim classification agent.
        Your ONLY job is to read a claim description and classify it into exactly one category.
        
        Valid categories:
        - VEHICLE_DAMAGE  (car accidents, collisions, scratches, dents)
        - PROPERTY_DAMAGE (house, building, furniture, appliances)
        - HEALTH          (medical expenses, hospitalization, injury)
        - THEFT           (stolen car, burglary, robbery)
        - NATURAL_DISASTER (flood, earthquake, storm, fire)
        - OTHER           (anything that does not fit above)
        
        You MUST respond with ONLY a JSON object. No explanation. No markdown. No extra text.
        
        Required JSON format:
        {
          "claimType": "VEHICLE_DAMAGE",
          "confidence": 0.95,
          "reasoning": "Description mentions car collision and bumper damage"
        }
        """)
    @UserMessage("Classify this insurance claim: {{description}}")
    String classify(String description);
}