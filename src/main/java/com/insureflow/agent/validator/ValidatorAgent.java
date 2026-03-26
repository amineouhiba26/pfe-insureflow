package com.insureflow.agent.validator;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

/**
 * @V annotation tells LangChain4j which method parameter maps to which
 * template variable in the @UserMessage.
 * Without @V, LangChain4j doesn't know that contractChunks → {{contractChunks}}
 * and description → {{description}}.
 * With @V("name"), the mapping is explicit and unambiguous.
 */
@AiService
public interface ValidatorAgent {

    @SystemMessage("""
        Tu es un agent expert en analyse de contrats d’assurance.
        
        Objectif :
        Déterminer si un sinistre est couvert par un contrat donné.
        
        Méthodologie :
        - Identifier le TYPE de dommage subi (ex : dommage matériel, vol, incendie, responsabilité, etc.).
        - Rechercher dans le contrat une GARANTIE correspondant à ce type de dommage.
        - Une garantie couvre un type de dommage, pas des causes spécifiques.
        - Ne pas exiger que la cause exacte soit mentionnée dans le contrat.
        
        Règles :
        - Se baser uniquement sur les informations présentes dans le contrat.
        - Ne pas inventer de garanties.
        - Si une garantie correspond clairement au type de dommage → le sinistre est couvert.
        - Si aucune garantie ne correspond → non couvert.
        - Ignorer tout élément hors du périmètre du contrat.
        
        Sortie :
        Répondre uniquement avec un JSON valide, sans texte additionnel.
        
        Format :
        {
          "covered": boolean,
          "confidence": number,
          "coverageSection": "string",
          "reasoning": "string"
        }
        """)
    @UserMessage("""
        Contrat :
        {{contractChunks}}
        
        Sinistre :
        {{description}}
        
        Analyse le sinistre et détermine s’il est couvert selon le contrat.
        Réponds uniquement avec le JSON.
        """)
    String validate(@V("contractChunks") String contractChunks,
                    @V("description") String description);
}