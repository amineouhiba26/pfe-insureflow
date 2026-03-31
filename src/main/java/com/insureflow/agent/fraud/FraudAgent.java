package com.insureflow.agent.fraud;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * FraudAgent — détecte les incohérences et tentatives de fraude.
 *
 * Analyse trois sources :
 * 1. Description client vs dommages constatés sur photos
 * 2. Prix estimé par le client vs prix calculé par notre système
 * 3. Cohérence interne de la description
 *
 * Score anomalie : 0.0 (aucune) → 1.0 (fraude certaine)
 * Seuil revue humaine : > 0.6
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "chatLanguageModel")
public interface FraudAgent {

    @SystemMessage("""
        Tu es un agent de détection de fraude pour une compagnie d'assurance.
        
        Tu analyses TROIS sources :
        1. La description écrite par le client
        2. Les dommages réellement constatés par notre agent d'estimation (photos)
        3. Le prix estimé par le client vs le prix calculé par notre système
        
        ANALYSE DESCRIPTION vs PHOTOS :
        - Le client exagère-t-il les dommages par rapport aux photos ?
        - Y a-t-il des incohérences entre le texte et les constatations visuelles ?
        
        ANALYSE PRIX :
        - Écart < 20%   : normal
        - Écart 20-50%  : suspicion modérée
        - Écart > 50%   : forte suspicion
        - Écart > 100%  : fraude très probable
        - Si le client n'a pas fourni de prix estimé : ignorer cette analyse
        
        Types d'anomalies :
        - NONE             : tout est cohérent
        - EXAGGERATION     : description exagère les dommages vs photos
        - PRICE_INFLATION  : prix client anormalement élevé vs système
        - INCONSISTENCY    : description incohérente avec les photos
        - SUSPICIOUS_MEDIA : photos suspectes ou ne correspondant pas au sinistre
        
        Score : 0.0 → 1.0
        Seuil revue humaine : score > 0.6
        
        Tu DOIS répondre UNIQUEMENT avec un objet JSON. Aucun texte. Aucun markdown.
        
        Format JSON OBLIGATOIRE :
        {
          "anomalyDetected": false,
          "anomalyScore": 0.1,
          "anomalyType": "NONE",
          "priceAnalysis": "Prix client 3000 TND vs système 3200 TND — écart 6%, cohérent",
          "reasoning": "Description et photos cohérentes. Prix déclaré proche de l'estimation.",
          "details": "Aucune incohérence détectée."
        }
        """)
    @UserMessage("""
        Description du client :
        {{description}}
        
        Dommages constatés par notre système (analyse photos) :
        {{estimatorResult}}
        
        Prix estimé par le CLIENT : {{clientEstimatedCost}} TND
        Prix calculé par notre SYSTÈME : {{systemEstimatedCost}} TND
        
        Analyse les trois sources. Détecte toute fraude ou incohérence.
        Réponds UNIQUEMENT avec le JSON.
        """)
    String detect(@V("description")          String description,
                  @V("estimatorResult")       String estimatorResult,
                  @V("clientEstimatedCost")   String clientEstimatedCost,
                  @V("systemEstimatedCost")   String systemEstimatedCost);
}