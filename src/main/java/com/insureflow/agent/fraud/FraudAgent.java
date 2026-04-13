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
        Ce que le client dit avoir subi.
        
        SOURCE 2 — CONSTATATIONS SYSTÈME :
        Ce que nos agents IA ont identifié d'après les photos. Référence objective.
        
        SOURCE 3 — COMPARAISON DES PRIX :
        Prix déclaré par le client vs prix estimé par notre système.
        
        RÈGLE CRITIQUE SUR LES PRIX :
        La fraude par inflation de prix = le client DEMANDE PLUS que ce que le système estime.
        
        DIRECTION DE L'ÉCART — TRÈS IMPORTANT :
        - Client > Système : client demande plus → suspicion de PRICE_INFLATION
        - Client < Système : client demande moins → c'est normal, pas de fraude
        - Client = Système : parfaitement cohérent
        
        Seuils pour PRICE_INFLATION (uniquement si client > système) :
        - Écart < 20%  : normal → anomalyScore 0.0-0.1
        - Écart 20-50% : suspicion modérée → anomalyScore 0.2-0.4
        - Écart 50-100%: forte suspicion → anomalyScore 0.5-0.7
        - Écart > 100% : fraude très probable → anomalyScore 0.7-0.95
        
        Si le client déclare MOINS que le système → priceAnalysis normale, pas de pénalisation.
        Si le prix client n'est pas fourni → priceAnalysis = "Prix client non fourni — non applicable"
        
        ANALYSE DESCRIPTION vs PHOTOS :
        - Concordance → NONE
        - Client exagère vs photos → EXAGGERATION
        - Photos montrent plus que description → UNDERREPORTING
        - Aucun rapport → INCONSISTENCY
        
        TYPES D'ANOMALIES :
        NONE, EXAGGERATION, PRICE_INFLATION, INCONSISTENCY, SUSPICIOUS_MEDIA, UNDERREPORTING
        
        RÈGLES ABSOLUES :
        - JSON strict. Aucun texte, aucun markdown.
        - "reasoning" et "details" en français.
        - Un client qui déclare MOINS que le système n'est PAS fraudeur.
        - Sois objectif et précis dans le calcul de l'écart.
        
        FORMAT OBLIGATOIRE :
        {
          "anomalyDetected": false,
          "anomalyScore": 0.05,
          "anomalyType": "NONE",
          "priceAnalysis": "Prix client 3000 TND vs système 3200 TND — client déclare moins, aucune inflation",
          "reasoning": "La description correspond aux dommages constatés. Le client n'exagère pas le montant.",
          "details": "Aucune incohérence détectée."
        }
        """)
    @UserMessage("""
        DESCRIPTION DU CLIENT :
        {{description}}
        
        CONSTATATIONS DE L'AGENT D'ESTIMATION (basées sur les photos) :
        {{estimatorResult}}
        
        PRIX ESTIMÉ PAR LE CLIENT : {{clientEstimatedCost}} TND
        PRIX CALCULÉ PAR NOTRE SYSTÈME : {{systemEstimatedCost}} TND
        
        ANALYSE DE DIRECTION PRÉ-CALCULÉE :
        {{priceDirection}}
        
        Utilise cette analyse de direction pour évaluer le risque de fraude.
        Rappel : PRICE_INFLATION = client demande PLUS que le système.
        Si client < système → ce n'est PAS de la fraude sur les prix.
        
        Réponds UNIQUEMENT avec le JSON.
        """)
    String detect(@V("description")          String description,
                  @V("estimatorResult")       String estimatorResult,
                  @V("clientEstimatedCost")   String clientEstimatedCost,
                  @V("systemEstimatedCost")   String systemEstimatedCost,
                  @V("priceDirection")        String priceDirection);
}