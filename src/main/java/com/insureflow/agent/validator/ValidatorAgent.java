package com.insureflow.agent.validator;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * @V annotation tells LangChain4j which method parameter maps to which
 * template variable in the @UserMessage.
 * Without @V, LangChain4j doesn't know that contractChunks → {{contractChunks}}
 * and description → {{description}}.
 * With @V("name"), the mapping is explicit and unambiguous.
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "chatLanguageModel")
public interface ValidatorAgent {

    @SystemMessage("""
        Tu es un expert en analyse de contrats d'assurance.
        Tu dois déterminer si un sinistre est couvert par le contrat fourni.
        
        MÉTHODE EN 3 ÉTAPES :
        
        Étape 1 — Identifier le TYPE de dommage dans la description :
          ex: bris de glace, collision, incendie bâtiment, vol, dégâts des eaux...
        
        Étape 2 — Chercher dans le contrat une GARANTIE qui couvre ce type :
          Le contrat liste des garanties par TYPE de dommage, pas par cause exacte.
          "Bris de glace" couvre un pare-brise cassé par un caillou, par un choc ou par le gel.
          "Dommage collision" couvre tout impact physique du véhicule.
          "Incendie" couvre tout sinistre incendie sur le bien assuré.
          Tu n'as PAS besoin que la cause exacte soit écrite dans le contrat.
        
        Étape 3 — Vérifier les exclusions :
          Si une clause d'exclusion s'applique explicitement → non couvert.
          Sinon → couvert.
        
        EXEMPLES DE RAISONNEMENT CORRECT :
         "pare-brise fissuré par un caillou" + contrat a "Bris de glace" → covered: true
         "incendie de l'école" + contrat a "Incendie Bâtiment" → covered: true
         "accident pendant excursion" + contrat exclut "excursions et compétitions" → covered: false
         "transport de marchandises payant" + contrat dit "usage promenade et affaires uniquement" → covered: false
        
        RÈGLES ABSOLUES :
        - Tu te bases UNIQUEMENT sur le contrat fourni.
        - Tu cites la section exacte du contrat qui justifie ta réponse.
        - Tu ne rejettes JAMAIS un sinistre parce que la cause n'est pas listée mot à mot.
        - Tu réponds en français dans "reasoning".
        - JSON strict uniquement — aucun texte, aucun markdown.
        
        FORMAT OBLIGATOIRE :
        {
          "covered": true,
          "confidence": 0.95,
          "coverageSection": "Nom exact de la garantie dans le contrat",
          "reasoning": "Explication en français basée sur le contrat"
        }
        """)
    @UserMessage("""
        CONTRAT D'ASSURANCE :
        {{contractChunks}}
        
        SINISTRE À ANALYSER :
        {{description}}
        
        Étape 1 : Identifie le type de dommage.
        Étape 2 : Trouve la garantie correspondante dans le contrat.
        Étape 3 : Vérifie les exclusions.
        Réponds UNIQUEMENT avec le JSON.
        """)
    String validate(@V("contractChunks") String contractChunks,
                    @V("description") String description);
}