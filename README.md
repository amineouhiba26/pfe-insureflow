# InsureFlow — Système de Traitement Automatisé des Sinistres par IA

InsureFlow est une application Spring Boot qui automatise le traitement des déclarations de sinistres d'assurance à l'aide d'un pipeline multi-agents alimenté par des LLMs locaux (Ollama).

---

## Stack Technique

| Couche | Technologie |
|---|---|
| Backend | Spring Boot 3.3.5, Java 21 |
| Agents IA | LangChain4j 0.35.0 |
| RAG / Embeddings | Spring AI 1.0.0 |
| LLMs | Ollama — `llama3.1:8b` (texte), `llama3.2-vision` (photos) |
| Embeddings | `mxbai-embed-large` (1024 dimensions) |
| Base de données | PostgreSQL 15 + extension pgvector |
| Messaging | RabbitMQ (pipeline asynchrone) |
| Authentification | Keycloak (OAuth2 / JWT) |
| Photos | Cloudinary (stockage CDN) |
| Recherche de prix | SerpAPI (fallback web) |

---

## Spring AI vs LangChain4j — Qui fait quoi ?

Ces deux librairies coexistent dans le projet mais ont des rôles distincts.

### Spring AI → RAG (Retrieval-Augmented Generation)

Spring AI gère exclusivement la partie **vectorielle et documentaire** : ingestion de contrats PDF, découpage en chunks, stockage et recherche dans PostgreSQL via pgvector.

```
Contrat PDF
    ↓ PagePdfDocumentReader      (Spring AI — lit le PDF page par page)
    ↓ TokenTextSplitter          (Spring AI — découpe en morceaux de 200 tokens)
    ↓ VectorStore.add()          (Spring AI — stocke dans pgvector avec embeddings)

Requête sinistre
    ↓ SearchRequest              (Spring AI — recherche cosinus dans pgvector)
    → top-6 chunks de contrat les plus pertinents
```

**Classes concernées :**
- `DocumentIngestionAdapter` — ingestion et récupération des chunks
- `LangChain4jConfig` — configuration des beans Ollama et pgvector

### LangChain4j → Agents LLM

LangChain4j gère **tous les appels LLM** via des interfaces annotées `@AiService`. LangChain4j génère automatiquement l'implémentation : il ne faut écrire que l'interface avec des prompts en annotations.

```java
@AiService(wiringMode = EXPLICIT, chatModel = "chatLanguageModel")
public interface RouterAgent {
    @SystemMessage("Tu es un expert en assurance...")
    @UserMessage("Classifie ce sinistre : {{description}}")
    String classify(@V("description") String description);
}
```

**Les 4 agents sont tous des interfaces `@AiService` LangChain4j.**

---

## Architecture Globale

```
Utilisateur (REST)
    ↓
ClaimController
    ↓  Upload photos → Cloudinary
    ↓  Sauvegarde sinistre → PostgreSQL (status: SUBMITTED)
    ↓  Publie événement → RabbitMQ
    ↓
┌─────────────────────────────────────────┐
│           PIPELINE RABBITQ              │
│                                         │
│  Q_INTAKE → Q_ROUTED → Q_VALIDATED     │
│          → Q_ESTIMATED → Q_FRAUD       │
│          → Q_DECISION                  │
└─────────────────────────────────────────┘
    ↓
OrchestratorConsumer (décision finale)
    ↓
GET /api/v1/claims/{id}  ← Utilisateur interroge le résultat
```

Chaque queue est consommée par un agent différent. Le pipeline est **séquentiel et asynchrone** : chaque agent enrichit le sinistre puis le passe au suivant.

---

## Les 4 Agents — Fonctionnement Détaillé

### Agent 1 — Router (`RouterAgentService`)

**Rôle :** Classifier le type de sinistre.

**Queue :** écoute `claim.routed` → publie sur `claim.validated`

**Fonctionnement :**
1. Reçoit la description textuelle du sinistre
2. Appelle `RouterAgent.classify(description)` via LangChain4j
3. Le LLM retourne un JSON structuré

**Modèle :** `llama3.1:8b` (température 0.1 — réponses stables)

**Sortie JSON :**
```json
{
  "claimType": "VEHICLE_DAMAGE",
  "confidence": 0.95,
  "reasoning": "La description mentionne une collision frontale sur un véhicule..."
}
```

**Types possibles :** `VEHICLE_DAMAGE`, `PROPERTY_DAMAGE`, `HEALTH`, `THEFT`, `NATURAL_DISASTER`, `OTHER`

---

### Agent 2 — Validator (`ValidatorAgentService`)

**Rôle :** Vérifier si le sinistre est couvert par le contrat d'assurance.

**Queue :** écoute `claim.validated` → publie sur `claim.estimated` (ou `claim.decision` si rejet)

**Fonctionnement en 3 étapes :**

**Étape 1 — Garde déterministe (`ClaimScopeChecker`) :**
- Vérification par mots-clés, sans LLM
- Exemple : police VEHICLE_DAMAGE + description mentionnant "maison" → rejet immédiat (confidence 0.99)
- Rapide, ne peut pas halluciner

**Étape 2 — RAG (Spring AI) :**
- Recherche dans pgvector les 6 chunks de contrat les plus similaires à la description
- Filtre par `policyId` pour n'utiliser que le bon contrat

**Étape 3 — Validation LLM (LangChain4j) :**
- Passe les chunks + la description au LLM
- Le LLM identifie la garantie applicable et vérifie les exclusions

**Modèle :** `llama3.1:8b`

**Sortie JSON :**
```json
{
  "covered": true,
  "confidence": 0.91,
  "coverageSection": "Article 3.2 — Garantie Bris de Glace",
  "reasoning": "Le sinistre correspond à la garantie bris de glace, aucune exclusion applicable."
}
```

**Court-circuit :** Si `covered = false`, les agents Estimator et Fraud sont sautés (SKIPPED) et le pipeline va directement à la décision finale.

---

### Agent 3 — Estimator (`EstimatorAgentService`)

**Rôle :** Estimer le coût de réparation à partir du texte et des photos.

**Queue :** écoute `claim.estimated` → publie sur `claim.fraud.checked`

**Fonctionnement :**

**Analyse texte (LangChain4j — `llama3.1:8b`) :**
- Identifie les éléments endommagés et leur sévérité
- Sortie : liste de `{element, severity}` (ex: `{pare-choc avant, SEVERE}`)

**Analyse vision (LangChain4j — `llama3.2-vision`) :**
- Télécharge les photos depuis Cloudinary
- Les encode en base64 et les envoie au modèle vision
- Fusionne avec les résultats texte (dédoublonnage par nom)

**Estimation des prix (3 niveaux de fallback) :**
```
1. Base de données pièces auto  → prix min/max/avg en TND
        ↓ si non trouvé
2. SerpAPI (recherche web)      → fourchette de prix
        ↓ si non trouvé
3. LLM (llama3.1:8b)           → génère une fourchette MIN–MAX
```

**Post-traitement :**
- Correction de sévérité par mots-clés (ex: "feu" → `TOTAL_LOSS`)
- Score de qualité des photos (0.0–1.0) via `ImageQualityService`
- Recherche de sinistres similaires dans l'historique (pgvector)

**Sortie JSON (extrait) :**
```json
{
  "damagedElements": [
    {"element": "pare-choc avant", "severity": "SEVERE", "estimatedCost": 1200},
    {"element": "capot", "severity": "MODERATE", "estimatedCost": 800}
  ],
  "totalEstimatedCost": 2000,
  "overallSeverity": "SEVERE",
  "confidence": 0.88
}
```

---

### Agent 4 — Fraud Detector (`FraudAgentService`)

**Rôle :** Détecter d'éventuelles anomalies ou tentatives de fraude.

**Queue :** écoute `claim.fraud.checked` → publie sur `claim.decision`

**Fonctionnement :**

**Étape 1 — Calcul déterministe de la direction de prix :**
Avant d'appeler le LLM, le service calcule lui-même la direction :
- Client déclare **moins** que le système → `CLIENT_INFERIEUR` (pas de fraude probable)
- Client déclare **plus** que le système → `CLIENT_SUPERIEUR` (risque inflation de prix)

Cette information est passée en contexte au LLM pour éviter toute mauvaise interprétation.

**Étape 2 — Analyse LLM (LangChain4j — `llama3.1:8b`) :**
- Compare la description, les résultats d'estimation, les coûts déclarés vs calculés
- Reçoit la direction pré-calculée pour guider l'analyse

**Garde de sécurité :**
Si le LLM retourne `PRICE_INFLATION` alors que le client a déclaré **moins** que le système → correction forcée à `NONE` (override déterministe).

**Modèle :** `llama3.1:8b`

**Sortie JSON :**
```json
{
  "anomalyDetected": false,
  "anomalyScore": 0.05,
  "anomalyType": "NONE",
  "priceAnalysis": "Client déclare 3000 TND, système estime 3200 TND — pas d'inflation.",
  "reasoning": "Aucune incohérence détectée entre la description et les photos."
}
```

**Types d'anomalie :** `NONE`, `EXAGGERATION`, `PRICE_INFLATION`, `INCONSISTENCY`, `SUSPICIOUS_MEDIA`, `UNDERREPORTING`

---

## Décision Finale (Orchestrateur)

Après les 4 agents, `OrchestratorConsumer.onDecision()` appelle `DecisionService` qui applique :

### Calcul de confiance globale

```
confiance_globale = (moyenne_LLM × 0.4) + (règles_métier × 0.4) + (qualité_photos × 0.2)
```

### Matrice de décision (6 règles dans l'ordre)

| Règle | Condition | Résultat |
|---|---|---|
| 0 — Scope Guard | Rejet déterministe (confidence ≥ 0.99) | **REJECTED** |
| 1 — Non couvert | LLM dit `covered = false` | PENDING_REVIEW |
| 2 — Fraude | `anomalyScore > 0.6` | PENDING_REVIEW |
| 2b — Inflation | Écart de prix > 30 % | PENDING_REVIEW |
| 3 — Perte totale | Sévérité = `TOTAL_LOSS` | PENDING_REVIEW |
| 4 — Confiance faible | `confiance_globale < 0.75` | PENDING_REVIEW |
| 5 — Montant élevé | Coût estimé > 15 000 TND | PENDING_REVIEW |

> **Important :** Le système ne valide jamais automatiquement un sinistre. Seul le rejet de périmètre est automatique. Tout le reste attend une validation humaine (`PENDING_REVIEW`).

---

## Pipeline Complet (Résumé Visuel)

```
POST /claims
    │
    ├─ Upload photos → Cloudinary
    ├─ Sauvegarde DB (SUBMITTED)
    └─ Publie → RabbitMQ
                  │
          ┌───────▼───────┐
          │  ROUTER        │  llama3.1:8b
          │  Classifie     │  → claimType, confidence
          └───────┬───────┘
                  │
          ┌───────▼───────┐
          │  VALIDATOR     │  ClaimScopeChecker (déterministe)
          │  Vérifie       │  + RAG pgvector (Spring AI)
          │  la couverture │  + llama3.1:8b (LangChain4j)
          └───────┬───────┘
                  │
         couvert? │
        ┌────NO───┘───YES────┐
        │                    │
   → Q_DECISION      ┌───────▼───────┐
   (skip estimator)  │  ESTIMATOR    │  llama3.1:8b (texte)
                     │  Chiffre      │  llama3.2-vision (photos)
                     │  les dommages │  DB / SerpAPI / LLM (prix)
                     └───────┬───────┘
                              │
                     ┌───────▼───────┐
                     │  FRAUD        │  Direction prix (déterministe)
                     │  Détecte      │  + llama3.1:8b (LangChain4j)
                     │  les anomalies│
                     └───────┬───────┘
                              │
                     ┌───────▼───────┐
                     │  DÉCISION     │  ConfidenceCalculator
                     │  FINALE       │  + DecisionMatrix (6 règles)
                     └───────┬───────┘
                              │
                    REJECTED / PENDING_REVIEW
                              │
                     GET /claims/{id}  ← polling utilisateur
```

---

## Lancer le Projet

### Prérequis

```bash
# Ollama + modèles requis
ollama pull llama3.1:8b
ollama pull llama3.2-vision
ollama pull mxbai-embed-large

# PostgreSQL avec pgvector
CREATE EXTENSION vector;

# RabbitMQ
docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

### Démarrage

```bash
mvn spring-boot:run
```

L'API est disponible sur `http://localhost:8080`.

---

## Variables d'environnement clés

| Variable | Description |
|---|---|
| `SPRING_DATASOURCE_URL` | URL PostgreSQL |
| `SPRING_RABBITMQ_HOST` | Hôte RabbitMQ |
| `CLOUDINARY_CLOUD_NAME` | Nom du cloud Cloudinary |
| `CLOUDINARY_API_KEY` | Clé API Cloudinary |
| `SERPAPI_KEY` | Clé SerpAPI (recherche de prix) |
| `KEYCLOAK_AUTH_SERVER_URL` | URL serveur Keycloak |
