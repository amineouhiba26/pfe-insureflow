# Python Scripts — InsureFlow

InsureFlow is a Spring Boot / Java app, but it ships with **6 Python scripts** that handle three things Java is bad at (or where ad-hoc scripts are easier): bootstrapping Keycloak, generating vector embeddings, and running an end-to-end evaluation pipeline against the live backend.

Here's what each one does and how they fit together.

```
pfe-insureflow/
├── setup_keycloak.py          # 1. Bootstrap auth (realm + clients + users + roles)
├── populate_embeddings.py     # 2. Generate embeddings for past_claims rows
└── evaluation/
    ├── prepare_evaluation.py  # 3. Build test dataset from DB
    ├── run_evaluation.py      # 4. Submit claims to live API + poll results
    ├── calculate_metrics.py   # 5. Compute precision/recall/MAPE/etc.
    └── generate_charts.py     # 6. Produce PNG charts from results
```

---

## 1. [setup_keycloak.py](setup_keycloak.py)

**Purpose:** One-shot bootstrap of the Keycloak instance used for auth. Idempotent — it deletes the existing realm first, then re-creates everything from scratch.

**What it does, step by step:**

1. **Get admin token** — hits `master` realm's `openid-connect/token` endpoint with `admin / REDACTED` (password grant) to get an access token for the Keycloak Admin REST API.
2. **Delete + recreate realm** `insureflow` so the script can be re-run safely.
3. **Create two OIDC clients:**
   - `insureflow-backend` — public client, direct access grants enabled.
   - `insureflow-frontend` — public client, redirect URIs / web origins point at `http://localhost:4200` (the Angular app).
4. **Create two realm roles:** `CLIENT` and `ADMIN`.
5. **Create two seed users:**
   - `ali.almansouri` — role `CLIENT`, has `cin=05739884` custom attribute.
   - `admin.insureflow` — role `ADMIN`.
6. **Attach protocol mappers** to both clients so the issued JWT contains:
   - `cin` claim (from the user attribute) — used by the backend to match users to clients.
   - `realm_access.roles` — multivalued claim with the user's realm roles.

**Why it exists:** Keycloak's UI is fine for exploration but useless for reproducible setup. This script is the single source of truth for the dev realm configuration.

**Notable detail:** It uses `urllib` from the stdlib (no `requests`) — zero deps, runs anywhere with just Python 3.

**Run it:** `python3 setup_keycloak.py` (Keycloak must be up at `http://localhost:8180`).

---

## 2. [populate_embeddings.py](populate_embeddings.py)

**Purpose:** Backfills `nomic-embed-text` vector embeddings for every row in the `past_claims` table where `embedding IS NULL`. These embeddings are what the RAG-style retrieval in the backend uses to find similar past claims.

**What it does:**

1. Connects to PostgreSQL on port `5433` (the dockerised DB).
2. Ensures the `pgvector` extension is enabled (`CREATE EXTENSION IF NOT EXISTS vector`).
3. Selects all `past_claims` rows with `embedding IS NULL`.
4. Calls Ollama at `http://localhost:11434/api/pull` to pull `nomic-embed-text` if not already present.
5. For each row, POSTs the `description` to Ollama's `/api/embeddings` endpoint and writes the resulting vector back into the `embedding` column (cast to `vector` via `%s::vector`).

**Why it exists:** Embeddings are expensive to compute and don't change once written. Doing this in Python via Ollama avoids pulling a Python ML dep into the Java backend.

**Run it:** `python3 populate_embeddings.py` (DB + Ollama must be running).

---

## 3. [evaluation/prepare_evaluation.py](evaluation/prepare_evaluation.py) — **Step 1 of the eval pipeline**

**Purpose:** Builds the ground-truth test dataset for the end-to-end evaluation. Reads every row in `past_claims` and converts it into a JSON test case with the expected (ground truth) classification, decision, and cost.

**What it does:**

1. Connects to PostgreSQL.
2. Pulls 100 rows from `past_claims`, ordered by `claim_date`.
3. For `VEHICLE_DAMAGE` claims, **enriches** the description by prepending a random car brand + year (e.g. `"Toyota 2021 - "`). This is because the original synthetic data didn't include brand info, but the EstimatorAgent needs it to do realistic cost estimation.
4. Builds a test case object for each row containing:
   - `id`, `description` (possibly enriched), `parts_damaged`, `contract_type`
   - `ground_truth`: `{claim_type, decision, estimated_amount, fraud_score}`
5. Writes the list to [evaluation/test_cases.json](evaluation/test_cases.json).
6. **Resets [evaluation/results.json](evaluation/results.json) to `[]`** so a fresh evaluation starts from zero.
7. Prints distribution stats (how many of each `claim_type`, how many of each `decision`).

**Output:** `test_cases.json` (100 entries).

**Run it:** `python3 evaluation/prepare_evaluation.py`

---

## 4. [evaluation/run_evaluation.py](evaluation/run_evaluation.py) — **Step 2 (the heavy one)**

**Purpose:** Takes the test cases from step 1, submits each one as a real claim against the **live InsureFlow backend** (via the same REST endpoint a real frontend would use), polls until the agentic pipeline returns a final decision, and records the result.

**The auth dance:** Spring Boot doesn't handle credentials — Keycloak does. So the script:

- Uses **3 seed users** (one per policy type — `VEHICLE_DAMAGE`, `THEFT`, `PROPERTY_DAMAGE`) so the `ValidatorAgent` doesn't reject claims for coverage mismatch.
- For each user, gets an access token via Keycloak's password grant.
- Tokens are cached and refreshed every 240s (just under Keycloak's 5-min expiry) by the `TokenManager` class.

**The flow for each test case:**

1. Get a token for the matching policy type's user.
2. Call `GET /api/v1/policies/my` to resolve the user's real policy UUID (falls back to a hardcoded seed UUID if the call fails).
3. `POST /api/v1/claims` with `{clientId, policyId, description, clientEstimatedCost, photoUrls}`. The controller actually ignores `clientId` (it pulls it from the JWT) but Bean Validation still requires `@NotNull`, so it must be present.
4. **Poll** `GET /api/v1/claims/{id}` every 3s for up to 180s until the claim reaches a terminal status (`APPROVED`, `REJECTED`, or `PENDING_REVIEW`).
5. Build a result record containing the system's predicted `claim_type`, `decision`, `estimated_cost`, `confidence_score`, and `processing_time_ms` (computed from `updatedAt - submittedAt`).
6. **Append to [evaluation/results.json](evaluation/results.json) immediately** (incremental save) so a Ctrl+C doesn't lose progress.

**CLI flags:**

| Flag | What it does |
|---|---|
| `--test` | Submit ONE claim only, full request/response printed, results NOT saved (dry-run to verify request format) |
| `--all` | Process every remaining test case |
| `--batch N` | Process up to N (default 30) |
| *(no flag)* | First 30 unprocessed cases |

**Why batched runs:** A full 100-claim run takes a while (each claim runs through Router → Classifier → Estimator → Validator agents and that can take 10-60s per claim). Incremental saving + skip-already-processed means you can chip away at it.

**Run it:** `python3 evaluation/run_evaluation.py --test` first, then `--all` once you're confident.

---

## 5. [evaluation/calculate_metrics.py](evaluation/calculate_metrics.py) — **Step 3**

**Purpose:** Reads `results.json` and computes the standard ML-style metrics you'd expect for a classifier + regressor + decision-maker. Prints a fancy box-drawn report to stdout and writes a JSON report.

**What it computes:**

- **Classification** (`claim_type`):
  - Overall accuracy.
  - Per-class precision, recall, F1.
  - Full 3×3 confusion matrix.
- **Estimation** (`estimated_cost` vs `estimated_amount`):
  - MAE (mean absolute error in TND).
  - MAPE (mean absolute percentage error).
  - Median relative error.
  - Bucket counts: within 10% / 25% / 50% / over 50%.
- **Decision** (`APPROVED` / `REJECTED` / `PENDING_REVIEW`):
  - Overall accuracy + per-status accuracy.
- **Processing time:**
  - avg, min, max, p95 in seconds.
- **Speed comparison:** computes how many times faster InsureFlow is than manual processing (assumes 3 business days as the manual baseline).

**Output:** `metrics_report.json` + a pretty terminal report.

**Run it:** `python3 evaluation/calculate_metrics.py`

---

## 6. [evaluation/generate_charts.py](evaluation/generate_charts.py) — **Step 4**

**Purpose:** Same input as step 3, but produces 4 PNG charts (using matplotlib + seaborn). These are what you embed in the thesis/presentation.

**The 4 charts:**

1. **`confusion_matrix.png`** — Heatmap of the 3×3 classifier confusion matrix. Cells show both raw count and percentage.
2. **`estimation_accuracy.png`** — Bar chart of how many estimations fall into the ≤10% / 10-25% / 25-50% / >50% relative-error buckets.
3. **`processing_time_distribution.png`** — Histogram of per-claim processing time with vertical lines for the mean and P95.
4. **`decision_accuracy.png`** — Grouped bars: ground truth count vs correctly predicted count, per decision status.

**Technical notes:**

- Uses `matplotlib.use("Agg")` (non-interactive backend) — no display required, perfect for SSH/headless.
- All chart titles and labels are in **French** (the thesis is in French).
- Output goes to [evaluation/charts/](evaluation/charts/).

**Run it:** `python3 evaluation/generate_charts.py`

---

## End-to-end evaluation pipeline (TL;DR)

```bash
# Initial bootstrap (one-time)
python3 setup_keycloak.py
python3 populate_embeddings.py

# Run the eval
python3 evaluation/prepare_evaluation.py        # build test_cases.json
python3 evaluation/run_evaluation.py --test     # dry-run 1 claim
python3 evaluation/run_evaluation.py --all      # process all 100
python3 evaluation/calculate_metrics.py         # print report
python3 evaluation/generate_charts.py           # produce PNGs
```

## Dependencies summary

| Script | Deps |
|---|---|
| `setup_keycloak.py` | stdlib only |
| `populate_embeddings.py` | `psycopg2-binary`, `requests` |
| `prepare_evaluation.py` | `psycopg2-binary` |
| `run_evaluation.py` | `requests` |
| `calculate_metrics.py` | `numpy` |
| `generate_charts.py` | `numpy`, `matplotlib`, `seaborn`, `pandas` |

All install with: `pip3 install --break-system-packages psycopg2-binary requests numpy matplotlib seaborn pandas`

## Required services

| Service | Port | Used by |
|---|---|---|
| PostgreSQL | `5433` | `populate_embeddings.py`, `prepare_evaluation.py` |
| Keycloak | `8180` | `setup_keycloak.py`, `run_evaluation.py` |
| Ollama | `11434` | `populate_embeddings.py` |
| InsureFlow backend | `8080` | `run_evaluation.py` |
