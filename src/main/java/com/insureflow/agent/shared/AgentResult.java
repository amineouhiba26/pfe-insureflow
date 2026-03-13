// AgentResult.java
package com.insureflow.agent.shared;

/**
 * A generic wrapper for what any agent returns.
 *
 * Every agent produces:
 *   - resultJson: the full structured JSON output stored in the claim row
 *   - confidence: 0.0 to 1.0, how confident this agent is in its answer
 *   - reasoning:  a human-readable explanation of the decision
 *
 * The Orchestrator reads confidence from all 4 agents to compute
 * the composite confidence score using ConfidenceCalculator (Sprint 6).
 */
public class AgentResult {

    private String  resultJson;
    private double  confidence;
    private String  reasoning;
    private boolean success;
    private String  errorMessage;

    public AgentResult() {}

    /** Factory — successful result */
    public static AgentResult success(String resultJson, double confidence, String reasoning) {
        AgentResult r = new AgentResult();
        r.resultJson = resultJson;
        r.confidence = confidence;
        r.reasoning  = reasoning;
        r.success    = true;
        return r;
    }

    /** Factory — failed result (agent threw exception, circuit breaker in Sprint 7) */
    public static AgentResult failure(String errorMessage) {
        AgentResult r = new AgentResult();
        r.success      = false;
        r.confidence   = 0.0;
        r.errorMessage = errorMessage;
        r.resultJson   = "{\"error\":\"" + errorMessage + "\"}";
        return r;
    }

    public String  getResultJson()   { return resultJson;   }
    public double  getConfidence()   { return confidence;   }
    public String  getReasoning()    { return reasoning;    }
    public boolean isSuccess()       { return success;      }
    public String  getErrorMessage() { return errorMessage; }
}