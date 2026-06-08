# InsureFlow — Agents Workflow & Authentication

End-to-end walkthrough of what happens between *"a client submits a claim"* and *"a human adjuster sees it in their review queue"*, plus how Keycloak handles auth for both clients and admins.

Companion docs:
- [DECISION_MATRIX.md](DECISION_MATRIX.md) — how confidence scoring and the final decision rules work.
- [PYTHON_SCRIPTS.md](PYTHON_SCRIPTS.md) — the bootstrap and evaluation scripts that live alongside the Java app.

---

## 1. The big picture

InsureFlow is a Spring Boot app that processes insurance claims **fully asynchronously** through a chain of LangChain4j `@AiService` agents backed by Ollama. The HTTP layer only accepts the claim and returns immediately — everything after that is RabbitMQ messages flowing between agent consumers.

```
       HTTP POST                    RabbitMQ                        RabbitMQ
                            ┌────────────────────┐         ┌─────────────────┐
 Client ──► ClaimController ─► claim.intake ──► RouterAgent ─► claim.validated ─► ValidatorAgent (RAG)
            (returns 202)                            │
                                                    └──► claim.estimated ─► EstimatorAgent
                                                                                  │
                                                                                  ▼
                                                                          claim.fraud.checked
                                                                                  │
                                                                                  ▼
                                                                            FraudAgent
                                                                                  │
                                                                                  ▼
                                                                          claim.decision
                                                                                  │
                                                                                  ▼
                                                                   OrchestratorConsumer.onDecision()
                                                                                  │
                                                                                  ▼
                                                                  ConfidenceCalculator + DecisionMatrix
                                                                                  │
                                                                                  ▼
                                                                        status = PENDING_REVIEW
                                                                       (HumanReviewTask created in DB)
```

Key things to understand before reading the rest:

- **Validator and Estimator run in parallel.** RouterAgent publishes the same event to *both* `claim.validated` and `claim.estimated`, and they execute independently.
- **FraudAgent runs after Estimator**, not in parallel — it needs the estimator's cost figure to compute price-inflation signals.
- **No agent auto-approves anything.** The pipeline always ends in `PENDING_REVIEW`. `APPROVED` / `REJECTED` are *only* set by a human admin via the admin REST endpoints.
- **No notifications are wired.** Email is stored on the client record (for Keycloak sync) but the system does not send anything when a claim's status changes.

---

## 2. The pipeline, step by step

### Step 1 — Claim submission (synchronous, returns 202)

**Entry point:** [src/main/java/com/insureflow/web/ClaimController.java](src/main/java/com/insureflow/web/ClaimController.java)

Two POST endpoints accept claims:

| Endpoint | Body | Use case |
|---|---|---|
| `POST /api/v1/claims` | JSON `SubmitClaimRequest` (policyId, description, photoUrls, clientEstimatedCost) | Frontend / evaluation scripts |
| `POST /api/v1/claims/with-photos` | `multipart/form-data` with photo files | Direct upload from a UI |

Auth: every request must carry a Keycloak JWT in the `Authorization: Bearer …` header. The controller pulls the client's UUID from the `sub` claim (the `clientId` field in the request body is **ignored** — Bean Validation requires it but the controller overwrites it from the JWT).

The handler delegates to `SubmitClaimUseCaseImpl` ([src/main/java/com/insureflow/application/usecase/SubmitClaimUseCaseImpl.java](src/main/java/com/insureflow/application/usecase/SubmitClaimUseCaseImpl.java)), which does three things in this exact order:

1. Build a new `Claim` via `Claim.newSubmission()` (status = `SUBMITTED`).
2. **Persist it** via `claimRepository.save(...)`.
3. Publish a `ClaimEvent` to RabbitMQ via `eventPublisher.publishSubmitted(...)`.

Order matters: save before publish, otherwise an agent could consume the event before the row exists in Postgres. The HTTP response (`202 Accepted` with a `ClaimResponse`) is returned immediately after publish.

---

### Step 2 — RabbitMQ topology

Defined in [src/main/java/com/insureflow/infrastructure/messaging/RabbitMQConfig.java](src/main/java/com/insureflow/infrastructure/messaging/RabbitMQConfig.java).

One direct exchange — `insureflow.claims` — and seven queues. Five of them have dead-letter queues so failed messages aren't silently dropped.

| Queue | Routing key | Producer | Consumer | DLQ |
|---|---|---|---|---|
| `claim.intake`        | `claim.intake`        | `ClaimEventPublisherAdapter` (after save) | `OrchestratorConsumer.onClaimIntake()` | `claim.intake.dlq` |
| `claim.routed`        | `claim.routed`        | `OrchestratorConsumer` (fan-out from intake) | `RouterAgentService.onRouted()` | `claim.routed.dlq` |
| `claim.validated`     | `claim.validated`     | `RouterAgentService` | `ValidatorAgentService.onValidated()` | `claim.validated.dlq` |
| `claim.estimated`     | `claim.estimated`     | `RouterAgentService` (same message, in parallel with `claim.validated`) | `EstimatorAgentService.onEstimated()` | `claim.estimated.dlq` |
| `claim.fraud.checked` | `claim.fraud.checked` | `EstimatorAgentService` | `FraudAgentService.onFraudCheck()` | `claim.fraud.checked.dlq` |
| `claim.decision`      | `claim.decision`      | `FraudAgentService` (last agent) | `OrchestratorConsumer.onDecision()` | — |
| `claim.human.review`  | `claim.human.review`  | (currently unused) | (no consumer) | — |

`claim.human.review` was provisioned for a future notification worker but nothing publishes to it yet.

---

### Step 3 — Router classifies the claim

**Files:** [src/main/java/com/insureflow/agent/router/RouterAgent.java](src/main/java/com/insureflow/agent/router/RouterAgent.java), [src/main/java/com/insureflow/agent/router/RouterAgentService.java](src/main/java/com/insureflow/agent/router/RouterAgentService.java)

`RouterAgent` is a LangChain4j `@AiService` with a single method `classify(String description)`. Its system prompt forces the model to return strict JSON:

```json
{ "claimType": "VEHICLE_DAMAGE", "confidence": 0.95, "reasoning": "..." }
```

Allowed types: `VEHICLE_DAMAGE`, `PROPERTY_DAMAGE`, `HEALTH`, `THEFT`, `NATURAL_DISASTER`, `OTHER`. There is **no separate agent per type** — every claim follows the same pipeline regardless of `claimType`. The classification only affects how the estimator looks up pricing.

`RouterAgentService` (the consumer):

1. Sets `claim.status = ROUTING`.
2. Calls the LLM, parses the JSON, writes `claim.type` and `claim.routerResult`.
3. Sets `claim.status = VALIDATING`.
4. **Publishes the same `ClaimEvent` to both `claim.validated` and `claim.estimated`** ([src/main/java/com/insureflow/agent/router/RouterAgentService.java](src/main/java/com/insureflow/agent/router/RouterAgentService.java) lines 51-52). This fan-out is what makes validator and estimator run in parallel.

---

### Step 4a — Validator (RAG over the policy contract)

**Files:** [src/main/java/com/insureflow/agent/validator/ValidatorAgent.java](src/main/java/com/insureflow/agent/validator/ValidatorAgent.java), [src/main/java/com/insureflow/agent/validator/ValidatorAgentService.java](src/main/java/com/insureflow/agent/validator/ValidatorAgentService.java)

The validator's job is to decide whether the claim is **covered by the policy's contract**, not whether it's valid in any general sense.

It does this with RAG over the policy document:

1. `vectorStorePort.retrieveRelevantChunks(description, policyId, topK=5)` runs a pgvector similarity search over the contract chunks that were ingested for this specific policy ([src/main/java/com/insureflow/infrastructure/ai/rag/DocumentIngestionAdapter.java](src/main/java/com/insureflow/infrastructure/ai/rag/DocumentIngestionAdapter.java)). Similarity threshold is 0.35 (deliberately permissive for legal French).
2. The top chunks (truncated to 400 chars, deduplicated) are stitched into the prompt alongside the claim description.
3. `ValidatorAgent.validate(contractChunks, description)` returns:

```json
{ "covered": true, "confidence": 0.95, "coverageSection": "Article 4.2", "reasoning": "..." }
```

If no contract chunks come back (no document ingested for this policy), it falls back to `{ covered: false, confidence: 0.3 }` rather than hallucinating coverage.

The result is written to `claim.validatorResult`. The validator does **not** advance the status — it just waits for the estimator/fraud chain to finish before the orchestrator runs.

---

### Step 4b — Estimator (the heavy one)

**Files:** [src/main/java/com/insureflow/agent/estimator/EstimatorAgent.java](src/main/java/com/insureflow/agent/estimator/EstimatorAgent.java), [src/main/java/com/insureflow/agent/estimator/EstimatorAgentService.java](src/main/java/com/insureflow/agent/estimator/EstimatorAgentService.java)

This is the most complex step. The estimator's job is to produce a credible cost figure. It runs in parallel with the validator but does six sub-stages internally:

1. **Image quality scoring** — [ImageQualityService](src/main/java/com/insureflow/agent/estimator/ImageQualityService.java) returns 0.0 / 0.6 / 0.8 / 1.0 based on how many photos came in (0 / 1 / 2-3 / 4+). This score later feeds into the composite confidence.

2. **Text analysis** — `EstimatorAgent.analyse(...)` identifies damaged elements and assigns severity (`MINOR` / `MODERATE` / `SEVERE` / `TOTAL_LOSS`) **without** pricing them. Pricing is deliberately separated from the LLM call.

3. **Vision analysis** — if photos are present, [VisionAnalysisService](src/main/java/com/insureflow/agent/estimator/VisionAnalysisService.java) downloads them, base64-encodes them, and calls Ollama's `llama3.2-vision` model. The vision result is *merged* into the text result: text-detected parts are never removed, vision-detected parts are only appended if new.

4. **Severity correction** — keyword-based post-processing on the description (`"incendie"`, `"destruction totale"`, `"inondation"`, etc.) can upgrade or downgrade the LLM's severity verdict. This catches cases where the model under-reports a clearly total loss.

5. **Pricing — three-tier fallback:**
   - **Tier 1 (DB):** [CarPartsPricingService](src/main/java/com/insureflow/agent/estimator/CarPartsPricingService.java) for `VEHICLE_DAMAGE` (brand + year are extracted from the description), [NonVehiclePricingService](src/main/java/com/insureflow/agent/estimator/NonVehiclePricingService.java) for everything else.
   - **Tier 2 (web):** [PricingResearchService](src/main/java/com/insureflow/agent/estimator/PricingResearchService.java) hits SerpAPI for live pricing on parts not in the DB.
   - **Tier 3 (LLM fallback):** if both fail, prompts the chat model for a `MIN–MAX` range, with the DB floor as a hard minimum constraint.

6. **Similar claims context** — [SimilarClaimsService](src/main/java/com/insureflow/agent/estimator/SimilarClaimsService.java) does a pgvector similarity search over the `past_claims` table (these are the embeddings backfilled by [populate_embeddings.py](populate_embeddings.py)) and returns the 3 most similar historical claims with their decisions and amounts. This is context only — it does not directly affect the cost calculation.

Final JSON written to `claim.estimatorResult` contains `claimType`, `overallSeverity`, `estimatedCost` (midpoint of the min/max range), `costBreakdown`, `pricingMethod`, `pricingConfidence`, `similarClaimsContext`. The numeric `estimatedCost` is also written to the dedicated column on the claim row.

When done, `EstimatorAgentService` publishes a new event to `claim.fraud.checked`.

---

### Step 5 — Fraud agent

**Files:** [src/main/java/com/insureflow/agent/fraud/FraudAgent.java](src/main/java/com/insureflow/agent/fraud/FraudAgent.java), [src/main/java/com/insureflow/agent/fraud/FraudAgentService.java](src/main/java/com/insureflow/agent/fraud/FraudAgentService.java)

The fraud agent runs **after** the estimator (not in parallel) because it needs the system's cost estimate to compare against the client's declared cost.

Three input signals:
1. The original description and the estimator's structured findings.
2. The client's declared cost vs the system's estimated cost.
3. The internal consistency of the claim (does the description match the damaged parts?).

The service does one important pre-computation before calling the LLM: `computePriceDirection()` ([FraudAgentService.java](src/main/java/com/insureflow/agent/fraud/FraudAgentService.java) lines 132-174) deterministically tells the model whether the client cost is higher / lower / equal to the system cost. This prevents a recurring failure mode where the LLM would flag `PRICE_INFLATION` even when the client *under-declared*. There's also a safety override after the LLM call that strips `PRICE_INFLATION` flags when the client price was actually lower.

Output written to `claim.fraudResult`:

```json
{
  "anomalyDetected": true,
  "anomalyScore": 0.72,
  "anomalyType": "PRICE_INFLATION",
  "priceAnalysis": "...",
  "reasoning": "...",
  "details": "..."
}
```

Allowed `anomalyType` values: `NONE`, `EXAGGERATION`, `PRICE_INFLATION`, `INCONSISTENCY`, `SUSPICIOUS_MEDIA`, `UNDERREPORTING`.

FraudAgent then publishes to `claim.decision` — this is the signal to the orchestrator that the agent chain is done.

---

### Step 6 — Orchestration and decision

**Files:** [src/main/java/com/insureflow/infrastructure/messaging/OrchestratorConsumer.java](src/main/java/com/insureflow/infrastructure/messaging/OrchestratorConsumer.java), [src/main/java/com/insureflow/agent/orchestrator/DecisionMatrix.java](src/main/java/com/insureflow/agent/orchestrator/DecisionMatrix.java)

Note that there is **no explicit "wait for all agents" coordinator**. Synchronisation is implicit: by the time `claim.decision` fires, the validator is also guaranteed to have finished (it sits on a single-consumer queue, was triggered earlier in parallel with the estimator, and an LLM round trip is much shorter than the estimator's pricing + vision pipeline). The orchestrator simply reads the claim row from Postgres — all four agent results (`routerResult`, `validatorResult`, `estimatorResult`, `fraudResult`) are already there.

`OrchestratorConsumer.onDecision()` does two things:

1. Calls `ConfidenceCalculator` to compute a composite confidence score (40% LLM average + 40% business rules + 20% image quality — full breakdown in [DECISION_MATRIX.md](DECISION_MATRIX.md)).
2. Calls `DecisionMatrix.decide(claim)` to apply the rules. Rules are evaluated in order, first match wins; every match produces `PENDING_REVIEW` with a different reason flag:

| # | Trigger | Flag |
|---|---|---|
| 1 | `validatorResult.covered == false` | `NON_COUVERT` |
| 2 | `fraudResult.anomalyScore > 0.6` | `FRAUDE_SUSPECTEE` |
| 3 | client cost / system cost > 1.30 | `PRIX_GONFLE` |
| 4 | `overallSeverity == "TOTAL_LOSS"` | `PERTE_TOTALE` |
| 5 | composite confidence < 0.75 | `CONFIANCE_FAIBLE` |
| 6 | `estimatedCost > 15 000 TND` | `MONTANT_ELEVE` |
| 7 | none of the above | `VERIFICATION_OK` |

A `HumanReviewTask` row is inserted into the DB with the reason flag, the claim status is set to `PENDING_REVIEW`, and the composite confidence is persisted on the claim. The auto-pipeline ends here.

---

### Step 7 — Human review (out of band)

An admin (a Keycloak user with the `ADMIN` realm role) picks up the `HumanReviewTask` via the admin endpoints and either approves or rejects the claim. Those endpoints set `claim.status` to `APPROVED` or `REJECTED` respectively. The client can poll `GET /api/v1/claims/{id}` to see the final state.

There is no notification on status change — the client must poll, or a future notification worker would need to consume from `claim.human.review` (currently provisioned but unused).

---

## 3. Claim status machine

Defined in [src/main/java/com/insureflow/domain/model/enums/ClaimStatus.java](src/main/java/com/insureflow/domain/model/enums/ClaimStatus.java).

```
SUBMITTED ──► ROUTING ──► VALIDATING ──► (parallel with ESTIMATING) ──► FRAUD_CHECK ──► PENDING_REVIEW
                                                                                              │
                                                                              human admin ────┤
                                                                                              ├──► APPROVED
                                                                                              └──► REJECTED
```

The intermediate statuses (`ROUTING`, `VALIDATING`, `ESTIMATING`, `FRAUD_CHECK`) are useful for the polling UI and for the evaluation script to detect that processing is still in flight.

---

## 4. Keycloak authentication

The entire bootstrap is automated by [setup_keycloak.py](setup_keycloak.py) — see [PYTHON_SCRIPTS.md](PYTHON_SCRIPTS.md) for the script-level breakdown. This section covers the *runtime* model.

### Realm and clients

- **Realm:** `insureflow` (recreated from scratch each time the bootstrap runs — idempotent reset).
- **Clients:**
  - `insureflow-backend` — direct access grants enabled (used for service-to-service and the evaluation script's password-grant flow).
  - `insureflow-frontend` — public client. Redirect URIs and web origins point at `http://localhost:4200` (the Angular app).

### Realm roles

- `CLIENT` — regular policyholders. Required by `@PreAuthorize("hasRole('CLIENT')")` on the claim-submission endpoints.
- `ADMIN` — system administrators. Required by the admin endpoints that approve / reject claims.

### Seed users (created by the bootstrap)

| Username | Role | Notes |
|---|---|---|
| `ali.almansouri` | `CLIENT` | Has `cin=05739884` custom attribute. |
| `admin.insureflow` | `ADMIN` | Used to exercise the admin endpoints. |

The evaluation script ([evaluation/run_evaluation.py](evaluation/run_evaluation.py)) also expects **three** policy-type-specific CLIENT users so the validator doesn't reject claims for coverage mismatch — see PYTHON_SCRIPTS.md.

### Protocol mappers

Both clients have two extra protocol mappers attached so the issued JWT carries fields the backend actually uses:

1. **`cin` mapper** — copies the `cin` user attribute into the `cin` claim on access token, ID token, and userinfo. Used to match Keycloak users to internal `Client` rows.
2. **Roles mapper** — copies realm roles into `realm_access.roles` (multivalued). Used by Spring Security for `hasRole('CLIENT')` / `hasRole('ADMIN')` checks.

### Login flow

```
POST http://localhost:8180/realms/insureflow/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=password
&username=ali.almansouri
&password=123456
&client_id=insureflow-frontend
```

Response is the standard OIDC bundle — `access_token` (5-minute lifetime), `refresh_token`, `id_token`, `expires_in`, etc.

### Per-request validation

For every API call the client sends:

```
Authorization: Bearer <access_token>
```

On the backend, Spring Security's resource-server filter:

1. Fetches the realm's public key (cached) and verifies the token signature + `iss` + `exp`.
2. `JwtUtils.extractClientId(jwt)` reads the `sub` claim as a UUID — this is the internal client identifier.
3. `JwtUtils.extractRoles(jwt)` reads `realm_access.roles` and maps each to a Spring `SimpleGrantedAuthority` with the `ROLE_` prefix, so `@PreAuthorize("hasRole('CLIENT')")` works as expected.

### Example decoded token

```json
{
  "sub": "9c8b...uuid-of-client",
  "cin": "05739884",
  "realm_access": { "roles": ["CLIENT"] },
  "preferred_username": "ali.almansouri",
  "iss": "http://localhost:8180/realms/insureflow",
  "exp": 1234567890,
  "iat": 1234567890
}
```

### Token refresh

Access tokens are short-lived (5 minutes). Clients use the refresh token to silently renew. The evaluation script's `TokenManager` rotates tokens every 240 seconds (just under the 5-minute expiry) to avoid mid-batch 401s on long runs.

---

## 5. Required services (runtime)

| Service | Port | Purpose |
|---|---|---|
| PostgreSQL (+ pgvector) | 5433 | Claims, policies, contract chunks, past claim embeddings |
| Keycloak | 8180 | Auth — realm `insureflow` |
| RabbitMQ | 5672 / 15672 | Agent pipeline message bus |
| Ollama | 11434 | Chat models (router/validator/estimator/fraud), vision (`llama3.2-vision`), embeddings (`nomic-embed-text`) |
| InsureFlow backend | 8080 | The Spring Boot app itself |

All of these are wired up in [docker-compose.yml](docker-compose.yml) except the backend, which is run from `./mvnw spring-boot:run`.

---

## 6. Things that look like features but aren't (yet)

These are easy to misread from the codebase — clarifying them so nobody chases a ghost:

- **No type-specific agents.** There is no medical-codes agent for HEALTH, no police-report check for THEFT, etc. The `claimType` only changes how the estimator looks up pricing.
- **No auto-approval or auto-rejection.** `DecisionMatrix` always returns `PENDING_REVIEW`. The `APPROVED` / `REJECTED` statuses only come from the admin endpoints.
- **No email / SMS / push notifications.** Email is stored on the `Client` row purely for Keycloak sync. `claim.human.review` is provisioned but has no consumer.
- **`ClaimDossier` is dead code.** It was an early attempt at agent-count-based synchronisation; nothing currently uses it. Coordination is implicit via the message chain.
- **The `clientId` field in `SubmitClaimRequest` is ignored.** Bean Validation requires it to be non-null, but the controller overwrites it with the JWT `sub`.
