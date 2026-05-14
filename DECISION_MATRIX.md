# DecisionMatrix & Agent Confidence Scoring

## Overview

Every submitted claim passes through 4 autonomous LLM agents. Each agent produces a **confidence score** (0.0–1.0). When all agents finish, the `ConfidenceCalculator` computes a **composite confidence score**, and the `DecisionMatrix` applies rule-based logic to decide the outcome. **The system never auto-approves or auto-rejects** — all claims end up `PENDING_REVIEW` for a human adjuster.

```
Claim submitted
    │
    ▼
┌──────────────┐
│ RouterAgent  │  ← classifies claim type
└──────┬───────┘
       │
       ├──────────────────────┐
       ▼                      ▼
┌──────────────┐   ┌──────────────────┐
│ValidatorAgent│   │ EstimatorAgent    │
│ (RAG + LLM)  │   │ (vision + pricing)│
└──────┬───────┘   └────────┬─────────┘
       │                    │
       └──────────┬─────────┘
                  ▼
         ┌──────────────┐
         │ FraudAgent    │  ← last agent; publishes to Q_DECISION
         └──────┬───────┘
                ▼
       ┌────────────────┐
       │ DecisionService │
       │  ├─ ConfidenceCalculator
       │  └─ DecisionMatrix
       └────────┬───────┘
                ▼
         PENDING_REVIEW
```

---

## How Each Agent Produces Its Confidence

### 1. RouterAgent — `RouterAgent.java`

**Role:** Classifies the claim into a type (`VEHICLE_DAMAGE`, `PROPERTY_DAMAGE`, `HEALTH`, `THEFT`, `NATURAL_DISASTER`, `OTHER`).

**Confidence source:** The LLM self-assesses how sure it is about the classification.

```json
{
  "claimType": "VEHICLE_DAMAGE",
  "confidence": 0.95,
  "reasoning": "La description mentionne une collision avec dégâts au pare-choc."
}
```

- The LLM's raw output is parsed by `ResponseParser.extractJson()` and `ResponseParser.getDouble(json, "confidence", 0.5)`.
- Default fallback: `0.5` if the field is missing.
- The agent runs with **temperature 0.1** for deterministic classification.

### 2. ValidatorAgent — `ValidatorAgent.java`

**Role:** Determines if the claim is covered by the client's insurance policy, using RAG (retrieves up to 5 relevant contract chunks from pgvector).

**Confidence source:** The LLM self-assesses based on how clearly the contract covers the claim.

```json
{
  "covered": true,
  "confidence": 0.95,
  "coverageSection": "Garantie Bris de Glace",
  "reasoning": "Le contrat couvre le bris de glace, le pare-brise fissuré est inclus."
}
```

- **No contract found** → hardcoded fallback: `{"covered": false, "confidence": 0.3}`.
- The confidence is used both:
  - In `ConfidenceCalculator`: averaged with other agents' confidences (LLM score component).
  - In `ConfidenceCalculator.computeBusinessScore()`: if `validatorConfidence < 0.7`, a penalty of `-0.2` is applied to the business rules score.

### 3. EstimatorAgent — `EstimatorAgentService.java`

**Role:** Assesses damage (vision analysis via `llama3.2-vision` + text fallback via `llama3.1`), determines severity, and looks up real-world pricing via SerpAPI.

**Confidence source:** The LLM self-assesses how confident it is in the damage assessment:

```json
{
  "damagedElements": [
    {"element": "pare-choc avant", "severity": "SEVERE"},
    {"element": "capot", "severity": "MODERATE"}
  ],
  "overallSeverity": "SEVERE",
  "confidence": 0.88,
  "reasoning": "Le pare-choc avant est arraché, le capot présente des déformations."
}
```

- Additionally, the estimator produces an **`imageQualityScore`** (0.0–1.0) based solely on **photo count**:

  | Photos | Score |
  |--------|-------|
  | 0      | 0.0   |
  | 1      | 0.6   |
  | 2–3    | 0.8   |
  | 4+     | 1.0   |

- The `imageQualityScore` feeds into the composite confidence (weight 0.2).

### 4. FraudAgent — `FraudAgentService.java`

**Role:** Detects inconsistencies between the client's description, the estimator's findings, and the price comparison.

**Confidence source:** The agent outputs an **`anomalyScore`** (0.0 = clean, 1.0 = certain fraud). This is **inverted** to produce the agent's confidence:

```
fraudConfidence = 1.0 - anomalyScore
```

```json
{
  "anomalyDetected": false,
  "anomalyScore": 0.05,
  "anomalyType": "NONE",
  "priceAnalysis": "Prix client 3000 TND vs système 3200 TND — client déclare moins, aucune inflation",
  "reasoning": "La description correspond aux dommages constatés.",
  "details": "Aucune incohérence détectée."
}
```

- **Hard safety override:** If the LLM incorrectly flags `PRICE_INFLATION` when the client's cost is *less* than the system's estimate, the result is overridden to `NONE` with `anomalyScore = 0.05`.
- On exception → fallback: `{"anomalyScore": 0.0, "anomalyType": "NONE"}` → confidence = 1.0.

---

## Composite Confidence Score

**File:** `ConfidenceCalculator.java`

The `ConfidenceCalculator.compute(Claim)` function combines three weighted components:

```
composite = (llmAvgConfidence × 0.4)
          + (businessRulesScore   × 0.4)
          + (imageQualityScore    × 0.2)
```

### Component 1 — LLM Average Confidence (weight 0.4)

The average of all 4 agents' self-assessed confidences:

```
llmAvgConfidence = (routerConf + validatorConf + estimatorConf + fraudConf) / 4.0
```

Each defaults to `0.5` if missing from the JSON.

### Component 2 — Business Rules Score (weight 0.4)

Starts at `1.0` and applies penalties:

| Condition | Penalty |
|-----------|---------|
| Validator confidence < 0.7 | `-0.2` |
| Fraud anomalyScore | `- anomalyScore × 0.4` |

Result is clamped to `≥ 0.0`.

### Component 3 — Image Quality Score (weight 0.2)

Directly from the estimator's `imageQualityScore` field (default `0.5`).

### Final Computation

The composite is clamped to `[0.0, 1.0]` and stored on the Claim as `confidenceScore`.

---

## DecisionMatrix Rules

**File:** `DecisionMatrix.java`

The matrix applies rules in order — **first match wins**. All result in `PENDING_REVIEW`:

| # | Rule | Flag | Condition |
|---|------|------|-----------|
| 1 | Not covered | `NON_COUVERT` | `validatorResult.covered == false` |
| 2 | Fraud suspected | `FRAUDE_SUSPECTEE` | `anomalyScore > 0.6` |
| 2b | Price inflated | `PRIX_GONFLE` | `clientEstimatedCost / estimatedCost > 1.30` (deterministic) |
| 3 | Total loss | `PERTE_TOTALE` | `overallSeverity == "TOTAL_LOSS"` |
| 4 | Low confidence | `CONFIANCE_FAIBLE` | composite confidence < 0.75 |
| 5 | High cost | `MONTANT_ELEVE` | estimated cost > 15,000 TND |
| 6 | All clear | `VERIFICATION_OK` | None of the above triggered |

---

## Orchestration Flow

1. **Claim submitted** → `OrchestratorConsumer.onClaimIntake()` publishes to `Q_ROUTED`.
2. **RouterAgent** processes, persists `routerResult`, then fans out to `Q_VALIDATED` + `Q_ESTIMATED`.
3. **ValidatorAgent** and **EstimatorAgent** process in parallel.
4. **EstimatorAgent**, when done, publishes to `Q_FRAUD`.
5. **FraudAgent** processes (last agent), persists `fraudResult`, then publishes to `Q_DECISION`.
6. **`DecisionService.decide(claim)`** is called:
   - `ConfidenceCalculator.compute(claim)` → composite score
   - `claim.setConfidenceScore(score)`
   - `DecisionMatrix.evaluate(claim, score)` → `DecisionResult`
   - Claim transitions to `PENDING_REVIEW`
   - A `HumanReviewTask` is created with the reason and flag

---

## Key Design Decisions

- **No auto-approve, no auto-reject:** Every claim requires human confirmation. The flags guide the adjuster on what to look for.
- **Deterministic price inflation (Rule 2b):** This is a hard numerical check (`clientCost / sysCost > 1.30`) that runs *in addition to* the LLM fraud check, serving as a safety net.
- **Anomaly score inversion:** The FraudAgent's `anomalyScore` is inverted (`1.0 - score`) to become a "confidence" for uniform averaging with the other agents.
- **Business rules penalty:** Even if the LLM is confident, a low validator confidence or high anomaly score reduces the composite via the business rules component.
- **Image quality weighting:** Poor photos (few or none) reduce the composite score, potentially triggering human review, since the estimator's assessment is less reliable.
