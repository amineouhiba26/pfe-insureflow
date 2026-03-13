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
        You are an insurance policy validation agent.
        Your job is to determine whether a claim is covered based on the policy contract.
        
        You will receive:
        1. Relevant sections from the client's insurance contract
        2. The claim description
        
        Analyse the contract sections carefully and decide if the claim is covered.
        
        You MUST respond with ONLY a JSON object. No explanation. No markdown. No extra text.
        
        Required JSON format:
        {
          "covered": true,
          "confidence": 0.88,
          "coverageSection": "Article 3.2 - Vehicle Collision Coverage",
          "reasoning": "The contract explicitly covers collision damage to the insured vehicle"
        }
        
        If the contract sections do not clearly address the claim type, set covered to false
        and confidence to 0.4.
        """)
    @UserMessage("""
        Contract sections:
        {{contractChunks}}
        
        Claim description:
        {{description}}
        
        Is this claim covered?
        """)
    String validate(@V("contractChunks") String contractChunks,
                    @V("description") String description);
}