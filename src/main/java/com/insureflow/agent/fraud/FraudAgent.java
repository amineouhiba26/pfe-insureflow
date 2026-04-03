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
        Tu es un agent de détection de fraude pour une compagnie d'assurance tunisienne.
        Tu analyses la cohérence entre trois sources d'information.
        
        SOURCE 1 — DESCRIPTION CLIENT :
        Ce que le client dit avoir subi. Peut contenir des exagérations ou omissions.
        
        SOURCE 2 — CONSTATATIONS SYSTÈME :
        Ce que nos agents IA ont identifié d'après les photos. C'est la référence objective.
        
        SOURCE 3 — COMPARAISON DES PRIX :
        Prix déclaré par le client vs prix estimé par notre système.
        
        RÈGLES D'ÉVALUATION :
        
        Analyse description vs photos :
        - Description et photos concordent → pas d'anomalie
        - Client mentionne des dommages absents des photos → EXAGGERATION
        - Photos montrent plus de dégâts que la description → UNDERREPORTING (rare)
        - Photos et description sans rapport → INCONSISTENCY
        
        Analyse des prix :
        - Écart < 20%  : normal, variations de marché
        - Écart 20-50% : suspicion modérée → anomalyScore 0.3-0.5
        - Écart 50-100%: forte suspicion → anomalyScore 0.5-0.7
        - Écart > 100% : fraude très probable → anomalyScore 0.7-0.95
        - Prix client = "non fourni" → ignorer cette analyse, ne pas pénaliser
        
        TYPES D'ANOMALIES :
        NONE              → cohérence complète
        EXAGGERATION      → description exagère les dommages réels
        PRICE_INFLATION   → prix client dépasse largement l'estimation système
        INCONSISTENCY     → description ne correspond pas aux photos
        SUSPICIOUS_MEDIA  → photos suspectes (qualité anormale, hors contexte)
        UNDERREPORTING    → client minimise les dommages
        
        SEUIL REVUE HUMAINE : anomalyScore > 0.6
        
        RÈGLES ABSOLUES :
        - JSON strict uniquement. Aucun texte, aucun markdown.
        - "reasoning" et "details" en français.
        - Sois objectif. Une imprécision n'est pas une fraude.
        - Si le prix client n'est pas fourni, mettre priceAnalysis = "Prix client non fourni — analyse non applicable"
        
        FORMAT OBLIGATOIRE :
        {
          "anomalyDetected": false,
          "anomalyScore": 0.05,
          "anomalyType": "NONE",
          "priceAnalysis": "Prix client 3000 TND vs système 3200 TND — écart 6%, dans la normale",
          "reasoning": "La description correspond aux dommages constatés sur les photos.",
          "details": "Aucune incohérence détectée entre les trois sources d'information."
        }
        """)
    @UserMessage("""
        DESCRIPTION DU CLIENT :
        {{description}}
        
        CONSTATATIONS DE L'AGENT D'ESTIMATION (basées sur les photos) :
        {{estimatorResult}}
        
        PRIX ESTIMÉ PAR LE CLIENT : {{clientEstimatedCost}} TND
        PRIX CALCULÉ PAR NOTRE SYSTÈME : {{systemEstimatedCost}} TND
        
        Analyse la cohérence entre ces trois sources.
        Calcule un score d'anomalie entre 0.0 et 1.0.
        Réponds UNIQUEMENT avec le JSON.
        """)
    String detect(@V("description")          String description,
                  @V("estimatorResult")       String estimatorResult,
                  @V("clientEstimatedCost")   String clientEstimatedCost,
                  @V("systemEstimatedCost")   String systemEstimatedCost);
}