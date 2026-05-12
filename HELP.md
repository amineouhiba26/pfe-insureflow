# InsureFlow — Système Multi-Agent de Traitement de Sinistres Assurance

*Java 21 · Spring Boot 3.3.5 · LangChain4j · Ollama · RabbitMQ · Keycloak · PostgreSQL/pgvector*

InsureFlow est une plateforme backend *intelligente et événementielle* de traitement de sinistres assurance, basée sur une architecture *hexagonale (Ports & Adapters)* et un *pipeline de 4 agents IA* qui collaborent de manière asynchrone via RabbitMQ pour classer, valider, estimer et détecter les fraudes sur chaque sinistre, avant de le soumettre à un *expert humain* pour décision finale.

---

## Table des Matières

1. [Aperçu Général](#aperçu-général)
2. [Architecture](#architecture)
    - [Architecture Hexagonale](#architecture-hexagonale)
    - [Pipeline Événementiel](#pipeline-événementiel)
    - [Diagramme de Flux](#diagramme-de-flux)
3. [Pipeline Multi-Agents IA](#pipeline-multi-agents-ia)
    - [1. RouterAgent — Classification du Sinistre](#1-routeragent--classification-du-sinistre)
    - [2. ValidatorAgent — Validation de Couverture (RAG)](#2-validatoragent--validation-de-couverture-rag)
    - [3. EstimatorAgent — Analyse des Dégâts & Estimation](#3-estimatoragent--analyse-des-dégâts--estimation)
    - [4. FraudAgent — Détection de Fraude](#4-fraudagent--détection-de-fraude)
    - [Orchestrator — Décision & Score de Confiance](#orchestrator--décision--score-de-confiance)
4. [Stack Technologique](#stack-technologique)
5. [Prérequis](#prérequis)
6. [Démarrage Rapide](#démarrage-rapide)
    - [1. Infrastructure (Docker)](#1-infrastructure-docker)
    - [2. Modèles Ollama](#2-modèles-ollama)
    - [3. Configurer Keycloak](#3-configurer-keycloak)
    - [4. Lancer l'Application](#4-lancer-lapplication)
    - [5. Utilisateurs par Défaut](#5-utilisateurs-par-défaut)
7. [Référence API REST](#référence-api-rest)
    - [Authentification & Auth](#authentification--auth)
    - [Clients](#clients)
    - [Sinistres (Claims)](#sinistres-claims)
    - [Polices (Policies)](#polices-policies)
    - [Revue Humaine](#revue-humaine)
    - [Administration](#administration)
    - [Photos](#photos)
    - [Contrats (RAG)](#contrats-rag)
    - [Utilitaire](#utilitaire)
8. [Schéma de la Base de Données](#schéma-de-la-base-de-données)
    - [clients](#clients)
    - [policies](#policies)
    - [claims](#claims-1)
    - [human_review_tasks](#human_review_tasks)
    - [repair_costs](#repair_costs)
    - [vector_store](#vector_store)
9. [Sécurité & Authentification](#sécurité--authentification)
    - [Flux d'Authentification](#flux-dauthentification)
    - [Rôles](#rôles)
    - [Extraction CIN](#extraction-cin)
10. [Structure du Projet](#structure-du-projet)
11. [Configuration de Référence](#configuration-de-référence)
    - [application.yml](#applicationyml)
    - [docker-compose.yml](#docker-composeyml)
12. [Pricing & Détermination des Coûts](#pricing--détermination-des-coûts)
    - [Stratégie de Pricing](#stratégie-de-pricing)
    - [Table repair_costs](#table-repair_costs)
    - [SerpAPI — Prix Marché Réel](#serpapi--prix-marché-réel)
    - [Fallback LLM](#fallback-llm)
13. [RAG — Retrieval-Augmented Generation](#rag--retrieval-augmented-generation)
    - [Ingestion](#ingestion)
    - [Retrieval](#retrieval)
    - [Optimisations](#optimisations)
14. [RabbitMQ — File d'Attente & Routage](#rabbitmq--file-dattente--routage)
    - [Exchange Direct](#exchange-direct)
    - [7 Queues + 5 DLQs](#7-queues--5-dlqs)
    - [Dead Letter Queues](#dead-letter-queues)
15. [Tests](#tests)
16. [Dépannage](#dépannage)
17. [Développement](#développement)

---

## Aperçu Général

*InsureFlow* automatise le cycle de vie complet d'un sinistre assurance :

1. *Soumission* — Un client déclare un sinistre via l'API REST (description, photos, coût estimé)
2. *Pipeline IA asynchrone* — 4 agents spécialisés analysent le sinistre en parallèle via RabbitMQ :
    - *RouterAgent* classe le type de sinistre (véhicule, habitation, santé, vol, catastrophe naturelle)
    - *ValidatorAgent* vérifie la couverture contrat par RAG (recherche sémantique dans les PDF de polices)
    - *EstimatorAgent* analyse les photos via vision IA (llama3.2-vision) et estime les coûts de réparation via SerpAPI
    - *FraudAgent* croise la déclaration client vs les constats photo pour détecter les anomalies
3. *Moteur de Décision* — Calcule un score de confiance composite et applique des règles métier
4. *Revue Humaine* — Tout sinistre passe par un expert humain (jamais de rejet automatique)
5. *Décision Finale* — L'expert approuve ou rejette le sinistre avec notes

### Principes Fondamentaux

- *Aucun rejet automatique* — Seul un humain peut rejeter un sinistre
- *Pricing déterministe* — Les LLM ne produisent jamais de prix directement ; les coûts viennent de SerpAPI (prix marché réel) ou de la table repair_costs
- *RAG sur contrats* — Les PDF de polices sont vectorisés dans pgvector pour une recherche sémantique
- *Asynchrone & Résilient* — Chaque agent a sa propre file RabbitMQ avec DLQ (Dead Letter Queue)
- *Sécurité OAuth2* — Authentification via Keycloak avec JWT, rôles CLIENT et ADMIN

---

## Architecture

### Architecture Hexagonale

Le projet suit une architecture *hexagonale (Ports & Adapters)* avec 4 couches strictes :

┌──────────────────────────────────────────────────────┐
│                      WEB                              │
│     Controllers REST (REST API endpoints)             │
│     AdminController, ClaimController, AuthController  │
├──────────────────────────────────────────────────────┤
│                  APPLICATION                           │
│     Use Cases (SubmitClaimUseCaseImpl)                │
│     DTOs (ClaimResponse, SubmitClaimRequest)          │
├──────────────────────────────────────────────────────┤
│                    AGENT                               │
│     RouterAgent · ValidatorAgent · EstimatorAgent      │
│     FraudAgent · Orchestrator                         │
│     ConfidenceCalculator · DecisionMatrix              │
├──────────────────────────────────────────────────────┤
│                    DOMAIN                              │
│     Modèles (Claim, Client, Policy, HumanReviewTask)  │
│     Enums (ClaimStatus, ClaimType, Severity, etc.)    │
│     Ports (interfaces) — in/out                       │
├──────────────────────────────────────────────────────┤
│                 INFRASTRUCTURE                         │
│     Persistence (JPA Adapters, Entities, Repos)       │
│     Messaging (RabbitMQ Config, Producer, Consumer)   │
│     AI (LangChain4jConfig, DocumentIngestionAdapter)  │
│     Security (Keycloak, JWT, CORS)                    │
│     Pricing (SerpAPI, Cloudinary)                     │
└──────────────────────────────────────────────────────┘

*Règle de dépendance* : Chaque couche ne dépend que de la couche directement en dessous. Le Domain ne connaît ni Spring, ni JPA, ni RabbitMQ.

### Pipeline Événementiel

                     ┌─────────────┐
                     │  Client     │
                     │  soumet     │
                     │  sinistre   │
                     └──────┬──────┘
                            │ POST /api/v1/claims
                            ▼
                   ┌─────────────────┐
                   │  SubmitClaim    │
                   │  UseCaseImpl    │
                   │  (save DB +     │
                   │   publish event)│
                   └────────┬────────┘
                            │ RabbitMQ: claim.intake
                            ▼
                   ┌─────────────────┐
                   │  Orchestrator   │
                   │  Consumer       │
                   │  (intake →      │
                   │   forward)      │
                   └────────┬────────┘
                            │ claim.routed
                  ┌─────────┴──────────┐
                  ▼                     ▼
        ┌─────────────────┐   ┌─────────────────┐
        │  RouterAgent    │   │  (parallèle)     │
        │  classifie type │   │  ValidatorAgent  │
        │  (llama3.1:8b)  │   │  + EstimatorAgent│
        └────────┬────────┘   └────────┬─────────┘
                 │                     │
                 │ claim.routed        │ claim.validated / claim.estimated
                 │ (déclenche          │
                 │  validator+estimat.)│
                 ▼                     ▼
        ┌─────────────────┐   ┌─────────────────┐
        │  ValidatorAgent │   │  EstimatorAgent │
        │  (RAG + LLM)    │   │  (Vision +      │
        │  vérifie couv.  │   │   SerpAPI)       │
        └────────┬────────┘   └────────┬─────────┘
                 │                     │
                 └─────────┬───────────┘
                           │ claim.fraud.checked
                           ▼
                  ┌─────────────────┐
                  │   FraudAgent    │
                  │  croise         │
                  │  déclaration vs │
                  │  constats       │
                  └────────┬────────┘
                           │ claim.decision
                           ▼
                  ┌─────────────────┐
                  │  Orchestrator   │
                  │  → DecisionSvc  │
                  │  → ConfidenceCalc
                  │  → DecisionMatrix
                  └────────┬────────┘
                           │ PENDING_REVIEW
                           ▼
                  ┌─────────────────┐
                  │  HumanReview    │
                  │  Task créée     │
                  │  (attends expert)│
                  └─────────────────┘
                           │ PUT /api/v1/reviews/{id}/resolve
                           ▼
                  ┌─────────────────┐
                  │   APPROVED /    │
                  │   REJECTED      │
                  └─────────────────┘

### Diagramme de Flux

Soumission
│
▼
[SUBMITTED] ──► RouterAgent ──► [ROUTING]
│                                │
│                    ┌───────────┴───────────┐
│                    ▼                       ▼
│            [VALIDATING]              [ESTIMATING]
│            ValidatorAgent            EstimatorAgent
│            (RAG + LLM)               (Vision + SerpAPI)
│                    │                       │
│                    └───────────┬───────────┘
│                                ▼
│                        [FRAUD_CHECK]
│                        FraudAgent
│                                │
│                                ▼
│                        [PENDING_REVIEW]
│                        Décision humaine
│                                │
│                    ┌───────────┴───────────┐
│                    ▼                       ▼
│              [APPROVED]              [REJECTED]
└─────────────────────────────────────────────

---

## Pipeline Multi-Agents IA

### 1. RouterAgent — Classification du Sinistre

*Interface LangChain4j* : RouterAgent.java
*Service* : RouterAgentService.java
*Modèle* : llama3.1:8b (Ollama)
*File* : claim.routed

Le RouterAgent lit la description textuelle du sinistre et le classe dans l'une des catégories suivantes :

| Type | Description |
|------|-------------|
| VEHICLE_DAMAGE | Accidents de voiture, collisions, rayures |
| PROPERTY_DAMAGE | Dégâts maison, bâtiment, mobilier |
| HEALTH | Frais médicaux, hospitalisation |
| THEFT | Vol de véhicule, cambriolage |
| NATURAL_DISASTER | Inondation, tremblement de terre, tempête |
| OTHER | Tout ce qui ne correspond pas aux catégories ci-dessus |

*Prompt* (extrait) :
Tu es un assistant spécialisé dans la classification des sinistres d'assurance.
Classe le sinistre suivant dans l'une des catégories...
Réponds UNIQUEMENT avec un objet JSON...

*Sortie JSON* :
{
"claimType": "VEHICLE_DAMAGE",
"confidence": 0.92,
"reasoning": "La description mentionne un choc avant avec dégâts au pare-choc et au capot"
}

Une fois la classification faite, le RouterAgent déclenche *simultanément* le ValidatorAgent et l'EstimatorAgent.

---

### 2. ValidatorAgent — Validation de Couverture (RAG)

*Interface LangChain4j* : ValidatorAgent.java
*Service* : ValidatorAgentService.java
*Modèle* : llama3.1:8b (Ollama)
*File* : claim.validated

Le ValidatorAgent utilise *Retrieval-Augmented Generation (RAG)* pour vérifier si le sinistre est couvert par la police d'assurance.

*Processus* :
1. Reçoit le policyId du sinistre
2. Interroge pgvector pour trouver les 6 chunks les plus pertinents du contrat PDF
3. Passe ces chunks + la description du sinistre au LLM
4. Le LLM détermine si le sinistre est couvert selon les termes du contrat

*Sortie JSON* :
{
"covered": true,
"confidence": 0.85,
"coverageSection": "Article 3.2 — Garantie Dommages Véhicule",
"reasoning": "Le contrat couvre les collisions et chocs. La franchise de 200 TND s'applique."
}

Si aucun contrat n'a été uploadé pour la police, le ValidatorAgent retourne covered: false avec une confiance de 0.3, ce qui déclenche une revue humaine.

---

### 3. EstimatorAgent — Analyse des Dégâts & Estimation

*Interfaces* : EstimatorAgent.java
*Service* : EstimatorAgentService.java
*Vision* : VisionAnalysisService.java (llama3.2-vision)
*Pricing* : PricingResearchService.java (SerpAPI)
*Qualité Image* : ImageQualityService.java
*File* : claim.estimated

L'EstimatorAgent est l'agent le plus complexe. Il :

1. *Analyse les photos* via llama3.2-vision (téléchargement des images depuis Cloudinary)
2. *Identifie les éléments endommagés* et leur sévérité (pare-choc SEVERE, capot MODERATE, etc.)
3. *Corrige la sévérité* avec des règles métier post-traitement (évite les faux TOTAL_LOSS)
4. *Estime les coûts* via SerpAPI (prix marché réel) pour chaque élément
5. *Calcule un score de qualité d'image* basé sur le nombre de photos

*Stratégie de Pricing* (détaillée section dédiée) :
- SerpAPI → requêtes multilingues avec détection de devise et conversion en TND
- Fallback LLM si SerpAPI échoue complètement
- Dernier recours : plages minimales par sévérité

*Exemple de sortie JSON* :
{
"claimType": "VEHICLE_DAMAGE",
"overallSeverity": "SEVERE",
"estimatedCostMin": "2450.00",
"estimatedCostMax": "5200.00",
"estimatedCost": "3825.00",
"currency": "TND",
"pricingMethod": "serp",
"pricingConfidence": "high",
"imageQualityScore": 0.85,
"analysisMethod": "llama3.2-vision",
"confidence": 0.88,
"reasoning": "Pare-choc avant arraché, capot plié, radiateur endommagé",
"costBreakdown": [
"pare-choc avant (SEVERE): 600-1200 TND [SerpAPI]",
"capot (MODERATE): 800-1500 TND [SerpAPI]",
"radiateur (SEVERE): 400-800 TND [SerpAPI]",
"main d'oeuvre (SEVERE): 500-1200 TND [SerpAPI]"
]
}

#### Post-traitement de Sévérité

L'EstimatorAgent applique des règles métier pour corriger les erreurs du modèle vision :

| Règle | Action |
|-------|--------|
| 3+ éléments TOTAL_LOSS | Sévérité → TOTAL_LOSS |
| Mots-clés structuraux (châssis tordu, épave, etc.) | Sévérité → TOTAL_LOSS |
| TOTAL_LOSS avec < 2 éléments et pas de mots-clés | Downgrade → SEVERE |
| 3+ éléments SEVERE et sévérité actuelle MINOR/MODERATE | Upgrade → SEVERE |

#### Analyse Vision

**VisionAnalysisService.java** utilise llama3.2-vision pour analyser les photos :
- Télécharge les images depuis les URLs Cloudinary
- Les convertit en Base64
- Les envoie au modèle vision avec un prompt structuré
- Le prompt force le format JSON, les noms d'éléments en français
- Si aucune photo n'est accessible, fallback vers l'analyse textuelle

---

### 4. FraudAgent — Détection de Fraude

*Interface LangChain4j* : FraudAgent.java
*Service* : FraudAgentService.java
*Modèle* : llama3.1:8b (Ollama)
*File* : claim.fraud.checked

Le FraudAgent croise la *déclaration écrite du client* avec les *constats de l'EstimatorAgent* pour détecter des anomalies.

*Anomalies détectées* :

| Type | Description |
|------|-------------|
| NONE | Aucune anomalie, tout est cohérent |
| EXAGGERATION | Le client décrit des dégâts pires que ce que montrent les photos |
| INCONSISTENCY | La description mentionne des dommages différents de ceux visibles |
| UNDERREPORTING | Le client décrit moins de dégâts que visible (rare) |
| SUSPICIOUS_MEDIA | Les métadonnées photo, éclairage ou qualité suggèrent une manipulation |

*Protection intégrée* : Si le client demande MOINS que le système, un *safety override* désactive PRICE_INFLATION même si le LLM le détecte.

*Sortie JSON* :
{
"anomalyDetected": false,
"anomalyScore": 0.12,
"anomalyType": "NONE",
"reasoning": "La description du client correspond aux dommages observés sur les photos",
"priceAnalysis": "Le client demande 3500 TND, le système estime 3825 TND — écart normal de -8.5%",
"details": "Examen croisé cohérent. Aucune divergence significative."
}

---

### Orchestrator — Décision & Score de Confiance

**DecisionService.java** — Point d'entrée de la décision finale.

**ConfidenceCalculator.java** — Calcule le score de confiance composite :

composite = (Moyenne confiance LLM × 0.4) + (Score règles métier × 0.4) + (Score qualité image × 0.2)

Composantes :
- *Confiance LLM* : moyenne des confidences des 4 agents
- *Score règles métier* : pénalisé si validateur < 0.7 ou si score de fraude > 0
- *Qualité image* : basé sur le nombre de photos fournies

**DecisionMatrix.java** — Moteur de règles métier. *Première règle gagnante :*

| Règle | Condition | Flag | Action |
|-------|-----------|------|--------|
| 1 | Non couvert par la police | NON_COUVERT | PENDING_REVIEW |
| 2 | Score fraude > 0.6 | FRAUDE_SUSPECTEE | PENDING_REVIEW |
| 2b | Inflation prix > 30% | PRIX_GONFLE | PENDING_REVIEW |
| 3 | Sévérité = TOTAL_LOSS | PERTE_TOTALE | PENDING_REVIEW |
| 4 | Confiance composite < 0.75 | CONFIANCE_FAIBLE | PENDING_REVIEW |
| 5 | Coût estimé > 15 000 TND | MONTANT_ELEVE | PENDING_REVIEW |
| 6 | Toutes les règles passées | VERIFICATION_OK | PENDING_REVIEW (confirmation humaine) |

*Note importante* : La DecisionMatrix retourne TOUJOURS PENDING_REVIEW. Aucun sinistre n'est approuvé ou rejeté automatiquement — un expert humain prend la décision finale.

---

## Stack Technologique

| Catégorie | Technologie | Version |
|-----------|-------------|---------|
| *Langage* | Java | 21 |
| *Framework* | Spring Boot | 3.3.5 |
| *Build* | Maven (wrapper) | 3.9.12 |
| *Base de données* | PostgreSQL + pgvector | 16 |
| *Migrations* | Flyway | - |
| *IA - LLM* | Ollama (llama3.1:8b) | via LangChain4j + Spring AI |
| *IA - Vision* | Ollama (llama3.2-vision) | via LangChain4j |
| *IA - Embeddings* | mxbai-embed-large | 1024 dimensions |
| *Vector Store* | pgvector (HNSW, COSINE_DISTANCE) | Spring AI |
| *Framework IA* | LangChain4j | 0.35.0 |
| *Framework IA* | Spring AI | 1.0.0-M6 |
| *Messaging* | RabbitMQ | 3.13-management |
| *Authentification* | Keycloak | 24.0 |
| *OAuth2* | Spring Security + JWT | - |
| *Stockage photos* | Cloudinary | HTTP5 |
| *Pricing* | SerpAPI | REST |
| *Persistence* | Spring Data JPA + Hibernate | - |
| *Tests* | JUnit 5 + Mockito | - |

---

## Prérequis

- *Java 21* (JDK)
- *Docker* & *Docker Compose* (PostgreSQL, RabbitMQ, Keycloak)
- *Ollama* (serveur local pour les modèles LLM)
- *Maven* (ou utiliser le wrapper ./mvnw)

---

## Démarrage Rapide

### 1. Infrastructure (Docker)

docker compose up -d

Cela démarre 3 conteneurs :
| Service | Image | Ports |
|---------|-------|-------|
| *PostgreSQL + pgvector* | pgvector/pgvector:pg16 | 5433:5432 |
| *RabbitMQ* | rabbitmq:3.13-management | 5672 (AMQP), 15672 (Management UI) |
| *Keycloak* | quay.io/keycloak/keycloak:24.0 | 8180:8080 |

### 2. Modèles Ollama

# Modèle de chat principal
ollama pull llama3.1:8b

# Modèle d'embeddings pour le RAG
ollama pull mxbai-embed-large

# Modèle vision pour l'analyse des photos
ollama pull llama3.2-vision

### 3. Configurer Keycloak

Le script setup_keycloak.py crée automatiquement :
- Le realm insureflow
- Les clients OAuth2 (insureflow-backend, insureflow-frontend)
- Les rôles (CLIENT, ADMIN)
- Les utilisateurs de démo
- Les protocol mappers (CIN, rôles)

python setup_keycloak.py

### 4. Lancer l'Application

./mvnw spring-boot:run

L'application démarre sur http://localhost:8080.

### 5. Utilisateurs par Défaut

| Utilisateur | Rôle | Mot de passe | CIN |
|-------------|------|-------------|-----|
| ali.almansouri | CLIENT | 123456 | 05739884 |
| admin.insureflow | ADMIN | Admin2026! | - |

---

## Référence API REST

### Authentification & Auth

| Méthode | Endpoint | Auth | Description |
|---------|----------|------|-------------|
| GET | /api/v1/auth/config | Public | Configuration Keycloak pour le frontend (issuer, clientId) |
| GET | /api/v1/auth/me | Authentifié | Informations de l'utilisateur courant depuis le JWT |

### Clients

| Méthode | Endpoint | Auth | Description |
|---------|----------|------|-------------|
| POST | /api/v1/clients | Authentifié | Créer un nouveau client |
| GET | /api/v1/clients/{id} | Authentifié | Récupérer un client par ID |

### Sinistres (Claims)

| Méthode | Endpoint | Auth | Description |
|---------|----------|------|-------------|
| POST | /api/v1/claims | Authentifié | Soumettre un sinistre (JSON avec URLs photos) |
| POST | /api/v1/claims/with-photos | Authentifié | Soumettre un sinistre avec fichiers photos (multipart) |
| GET | /api/v1/claims | Authentifié | Liste des sinistres de l'utilisateur courant |
| GET | /api/v1/claims/{id} | Authentifié | Détail d'un sinistre (inclut les résultats des agents) |

*Exemple de soumission JSON* :
curl -X POST http://localhost:8080/api/v1/claims \
-H "Authorization: Bearer $TOKEN" \
-H "Content-Type: application/json" \
-d '{
"policyId": "uuid-de-la-police",
"description": "Accident de voiture — choc avant contre un mur. Pare-choc arraché, capot plié.",
"photoUrls": ["https://res.cloudinary.com/.../photo1.jpg"],
"clientEstimatedCost": 3500.00
}'

*Exemple de soumission multipart* :
curl -X POST http://localhost:8080/api/v1/claims/with-photos \
-H "Authorization: Bearer $TOKEN" \
-F "policyId=uuid-de-la-police" \
-F "description=Accident choc avant" \
-F "clientEstimatedCost=3500" \
-F "photos=@/chemin/photo1.jpg" \
-F "photos=@/chemin/photo2.jpg"

### Polices (Policies)

| Méthode | Endpoint | Auth | Description |
|---------|----------|------|-------------|
| GET | /api/v1/policies/my | Authentifié | Polices de l'utilisateur courant |

### Revue Humaine

| Méthode | Endpoint | Auth | Description |
|---------|----------|------|-------------|
| GET | /api/v1/reviews/pending | Authentifié | Liste des tâches de revue en attente |
| PUT | /api/v1/reviews/{id}/resolve | Authentifié | Résoudre une tâche de revue (APPROVED/REJECTED) |

*Exemple de résolution* :
curl -X PUT http://localhost:8080/api/v1/reviews/{id}/resolve \
-H "Authorization: Bearer $TOKEN" \
-H "Content-Type: application/json" \
-d '{
"decision": "APPROVED",
"assignedTo": "adjuster@insureflow.com",
"adjusterNotes": "Dégâts confirmés, couverture valide, coût raisonnable"
}'

### Administration

| Méthode | Endpoint | Rôle | Description |
|---------|----------|------|-------------|
| GET | /api/v1/admin/stats | ADMIN | Statistiques des sinistres (total, approuvés, rejetés, en attente) |
| GET | /api/v1/admin/claims | ADMIN | Tous les sinistres |
| GET | /api/v1/admin/claims/pending | ADMIN | Sinistres en attente de revue |
| POST | /api/v1/admin/claims/{id}/approve | ADMIN | Approuver un sinistre |
| POST | /api/v1/admin/claims/{id}/reject | ADMIN | Rejeter un sinistre (motif obligatoire) |
| POST | /api/v1/admin/clients | ADMIN | Créer un client + utilisateur Keycloak |
| GET | /api/v1/admin/clients | ADMIN | Tous les clients |
| POST | /api/v1/admin/policies | ADMIN | Créer une police |
| GET | /api/v1/admin/policies | ADMIN | Toutes les polices |
| DELETE | /api/v1/admin/reset/claims | ADMIN | Réinitialiser les données de sinistres |
| DELETE | /api/v1/admin/reset/all | ADMIN | Réinitialiser toutes les données |

### Photos

| Méthode | Endpoint | Rôle | Description |
|---------|----------|------|-------------|
| POST | /api/v1/photos/upload | ADMIN | Uploader plusieurs photos vers Cloudinary |
| POST | /api/v1/photos/upload/single | ADMIN | Uploader une seule photo |

### Contrats (RAG)

| Méthode | Endpoint | Rôle | Description |
|---------|----------|------|-------------|
| POST | /api/v1/admin/contracts/{policyId}/ingest | ADMIN | Uploader un PDF de contrat et l'indexer dans pgvector |
| GET | /api/v1/admin/contracts/{policyId}/search | ADMIN | Tester la recherche RAG sur un contrat |

### Utilitaire

| Méthode | Endpoint | Auth | Description |
|---------|----------|------|-------------|
| GET | /api/test/remote-llm | - | Test de connectivité Ollama |
| GET | /api/test/ping | - | Ping santé |
| GET | /actuator/health | Public | Health check Spring Boot Actuator |
| GET | /actuator/info | Public | Informations application |

---

## Schéma de la Base de Données

### clients

| Colonne | Type | Contraintes | Description |
|---------|------|-------------|-------------|
| id | UUID | PK, défaut gen_random_uuid() | Identifiant unique |
| full_name | VARCHAR(255) | NOT NULL | Nom complet |
| email | VARCHAR(255) | NOT NULL, UNIQUE | Email |
| phone | VARCHAR(50) | - | Téléphone |
| national_id | VARCHAR(50) | UNIQUE | CIN (carte d'identité nationale) |
| created_at | TIMESTAMPTZ | défaut now() | Date de création |

### policies

| Colonne | Type | Contraintes | Description |
|---------|------|-------------|-------------|
| id | UUID | PK | Identifiant unique |
| client_id | UUID | FK → clients(id) | Référence au client |
| policy_number | VARCHAR(100) | NOT NULL, UNIQUE | Numéro de police |
| type | VARCHAR(50) | - | Type (VEHICLE_DAMAGE, PROPERTY_DAMAGE, etc.) |
| coverage_limit | NUMERIC(15,2) | - | Plafond de couverture |
| deductible | NUMERIC(15,2) | - | Franchise |
| start_date | DATE | - | Date d'effet |
| end_date | DATE | - | Date d'échéance |
| contract_document_path | TEXT | - | Chemin/nom du PDF de contrat |
| created_at | TIMESTAMPTZ | défaut now() | Date de création |

### claims

| Colonne | Type | Contraintes | Description |
|---------|------|-------------|-------------|
| id | UUID | PK | Identifiant unique |
| client_id | UUID | FK → clients(id) | Référence au client |
| policy_id | UUID | FK → policies(id) | Référence à la police |
| type | VARCHAR(50) | - | Type déterminé par RouterAgent |
| status | VARCHAR(50) | NOT NULL, défaut 'SUBMITTED' | Statut dans le cycle de vie |
| description | TEXT | - | Description du sinistre par le client |
| photo_urls | TEXT | - | URLs Cloudinary des photos (séparées par virgule) |
| router_result | TEXT | - | JSON résultat du RouterAgent |
| validator_result | TEXT | - | JSON résultat du ValidatorAgent |
| estimator_result | TEXT | - | JSON résultat de l'EstimatorAgent |
| fraud_result | TEXT | - | JSON résultat du FraudAgent |
| estimated_cost | NUMERIC(15,2) | - | Coût estimé par le système |
| final_cost | NUMERIC(15,2) | - | Coût final (après décision) |
| client_estimated_cost | NUMERIC(15,2) | - | Coût estimé par le client (V2) |
| rejection_reason | TEXT | - | Motif de rejet |
| confidence_score | DOUBLE PRECISION | - | Score de confiance composite (0.0 - 1.0) |
| submitted_at | TIMESTAMPTZ | défaut now() | Date de soumission |
| updated_at | TIMESTAMPTZ | défaut now() | Date de dernière modification |

### human_review_tasks

| Colonne | Type | Contraintes | Description |
|---------|------|-------------|-------------|
| id | UUID | PK | Identifiant unique |
| claim_id | UUID | FK → claims(id) | Référence au sinistre |
| reason | TEXT | - | Raison de la revue humaine (sortie de DecisionMatrix) |
| status | VARCHAR(50) | défaut 'PENDING' | PENDING / RESOLVED |
| assigned_to | VARCHAR(255) | - | Email de l'expert assigné |
| adjuster_notes | TEXT | - | Notes de l'expert (déprécié, utiliser resolution_note) |
| resolution_note | TEXT | - | Note de résolution (V3) |
| created_at | TIMESTAMPTZ | défaut now() | Date de création |
| resolved_at | TIMESTAMPTZ | - | Date de résolution |

### repair_costs

| Colonne | Type | Contraintes | Description |
|---------|------|-------------|-------------|
| id | UUID | PK | Identifiant unique |
| part_name | VARCHAR(100) | NOT NULL | Nom de la pièce |
| severity | VARCHAR(50) | NOT NULL | MINOR / MODERATE / SEVERE |
| min_cost | NUMERIC(15,2) | NOT NULL | Coût minimum |
| max_cost | NUMERIC(15,2) | NOT NULL | Coût maximum |
| region | VARCHAR(100) | défaut 'TN', UNIQUE(part_name, severity, region) | Région |

*Données initiales (15 lignes)* :

| Pièce | MINOR | MODERATE | SEVERE |
|-------|-------|----------|--------|
| Pare-choc avant | 200-500 | 600-1200 | 1500-2500 |
| Capot | 300-700 | 800-1500 | 2000-4000 |
| Pare-brise | 150-400 | 500-900 | 1000-1800 |
| Portière | 200-600 | 700-1500 | 1800-3500 |
| Airbags | - | - | 1200-2500 |
| Moteur | - | - | 4000-9000 |
| Châssis | - | - | 5000-15000 |

### vector_store

Table gérée par Spring AI pgvector. Stocke les embeddings des chunks de contrats.

| Colonne | Type | Description |
|---------|------|-------------|
| id | UUID | Identifiant unique |
| content | TEXT | Texte du chunk |
| metadata | JSON | Métadonnées (inclut policyId pour filtrage) |
| embedding | vector(1024) | Embedding vectoriel (1024 dimensions, COSINE_DISTANCE, index HNSW) |

---

## Sécurité & Authentification

### Flux d'Authentification

┌──────────┐         ┌──────────┐         ┌──────────┐
│  Frontend│         │ Keycloak│         │  Backend │
│ (Angular)│         │ (SSO)   │         │(Spring)  │
└────┬─────┘         └────┬─────┘         └────┬─────┘
│  Login (user/pass)  │                   │
│────────────────────►│                   │
│                     │                   │
│    JWT Token        │                   │
│◄────────────────────│                   │
│                     │                   │
│  API Call + JWT     │                   │
│─────────────────────────────────────────►│
│                     │                   │
│                     │  Validate JWT     │
│                     │  (issuer JWKS)    │
│                     │◄──────────────────│
│                     │                   │
│                     │   OK              │
│                     │──────────────────►│
│                     │                   │
│    Response         │                   │
│◄─────────────────────────────────────────│

### Rôles

| Rôle | Accès |
|------|-------|
| CLIENT | Accès à ses propres sinistres, polices, et soumission |
| ADMIN | Accès complet CRUD, statistiques, gestion clients/polices, ingestion contrats, upload photos |

### Extraction CIN

Le JwtUtils extrait le CIN (carte d'identité nationale) du JWT Keycloak via le protocol mapper cin. Ce CIN est utilisé pour identifier de manière unique le client dans le système InsureFlow.

*Mapper Keycloak* : oidc-usermodel-attribute-mapper qui mappe l'attribut utilisateur cin vers le claim JWT cin.

---

## Structure du Projet

insureflow/
├── pom.xml                          # Dépendances Maven (Spring Boot, AI, Messaging)
├── docker-compose.yml               # PostgreSQL + RabbitMQ + Keycloak
├── proxy.conf.json                  # Proxy Angular → localhost:8080
├── setup_keycloak.py                # Script de configuration Keycloak
├── mvnw / mvnw.cmd                  # Maven Wrapper
│
└── src/
├── main/
│   ├── java/com/insureflow/
│   │   ├── InsureflowApplication.java
│   │   │
│   │   ├── adapter/in/web/
│   │   │   └── TestRemoteOllamaController.java
│   │   │
│   │   ├── agent/                               # Couche Agent IA
│   │   │   ├── estimator/                       # Agent estimation
│   │   │   │   ├── EstimatorAgent.java          # @AiService LangChain4j
│   │   │   │   ├── EstimatorAgentService.java   # Logique + pricing
│   │   │   │   ├── ImageQualityService.java     # Score qualité photo
│   │   │   │   └── VisionAnalysisService.java   # llama3.2-vision
│   │   │   ├── fraud/                           # Agent détection fraude
│   │   │   │   ├── FraudAgent.java              # @AiService LangChain4j
│   │   │   │   └── FraudAgentService.java       # Logique + safety overrides
│   │   │   ├── orchestrator/                    # Orchestrateur
│   │   │   │   ├── ClaimDossier.java            # Suivi agent en mémoire
│   │   │   │   ├── ConfidenceCalculator.java    # Score composite
│   │   │   │   └── DecisionMatrix.java          # Règles métier
│   │   │   ├── router/                          # Agent classification
│   │   │   │   ├── RouterAgent.java             # @AiService LangChain4j
│   │   │   │   └── RouterAgentService.java      # Logique
│   │   │   ├── shared/                          # Utilitaires agents
│   │   │   │   ├── AgentResult.java             # Wrapper générique
│   │   │   │   └── ResponseParser.java          # Extraction JSON
│   │   │   └── validator/                       # Agent validation RAG
│   │   │       ├── ValidatorAgent.java          # @AiService LangChain4j
│   │   │       └── ValidatorAgentService.java   # Logique + RAG
│   │   │
│   │   ├── application/                         # Couche Application
│   │   │   ├── dto/                             # DTOs requête/réponse
│   │   │   │   ├── ClaimResponse.java
│   │   │   │   ├── CreateClientRequest.java
│   │   │   │   ├── CreatePolicyRequest.java
│   │   │   │   ├── ReviewDecisionRequest.java
│   │   │   │   └── SubmitClaimRequest.java
│   │   │   └── usecase/                         # Cas d'utilisation
│   │   │       ├── ResolveReviewUseCaseImpl.java
│   │   │       └── SubmitClaimUseCaseImpl.java
│   │   │
│   │   ├── domain/                              # Couche Domaine (pure Java)
│   │   │   ├── model/                           # Modèles métier
│   │   │   │   ├── Claim.java                   # Agrégat central
│   │   │   │   ├── Client.java
│   │   │   │   ├── HumanReviewTask.java
│   │   │   │   ├── Policy.java
│   │   │   │   └── RepairCost.java
│   │   │   ├── model/enums/                     # Énumérations
│   │   │   │   ├── AnomalyType.java
│   │   │   │   ├── ClaimStatus.java
│   │   │   │   ├── ClaimType.java
│   │   │   │   └── Severity.java
│   │   │   └── port/                            # Interfaces (ports)
│   │   │       ├── in/                          # Ports d'entrée
│   │   │       │   ├── IngestContractUseCase.java
│   │   │       │   ├── ResolveReviewUseCase.java
│   │   │       │   └── SubmitClaimUseCase.java
│   │   │       └── out/                         # Ports de sortie
│   │   │           ├── ClaimEventPublisher.java
│   │   │           ├── ClaimRepository.java
│   │   │           ├── ClientRepository.java
│   │   │           ├── HumanReviewRepository.java
│   │   │           ├── PolicyRepository.java
│   │   │           └── VectorStorePort.java
│   │   │
│   │   ├── infrastructure/                      # Couche Infrastructure
│   │   │   ├── ai/
│   │   │   │   ├── LangChain4jConfig.java       # Configuration modèles Ollama
│   │   │   │   └── rag/
│   │   │   │       └── DocumentIngestionAdapter.java  # RAG ingestion/retrieval
│   │   │   ├── decision/
│   │   │   │   └── DecisionService.java         # Orchestre DecisionMatrix + ConfidenceCalc
│   │   │   ├── messaging/
│   │   │   │   ├── ClaimEvent.java              # Payload RabbitMQ
│   │   │   │   ├── ClaimEventPublisherAdapter.java
│   │   │   │   ├── OrchestratorConsumer.java    # Consumer intake + decision
│   │   │   │   └── RabbitMQConfig.java          # 7 queues + 5 DLQs + exchange
│   │   │   ├── persistence/
│   │   │   │   ├── adapter/                     # Implémentations ports
│   │   │   │   │   ├── ClaimRepositoryAdapter.java
│   │   │   │   │   ├── ClientRepositoryAdapter.java
│   │   │   │   │   ├── HumanReviewRepositoryAdapter.java
│   │   │   │   │   └── PolicyRepositoryAdapter.java
│   │   │   │   ├── entity/                      # Entités JPA
│   │   │   │   │   ├── ClaimJpaEntity.java
│   │   │   │   │   ├── ClientJpaEntity.java
│   │   │   │   │   ├── HumanReviewJpaEntity.java
│   │   │   │   │   ├── PolicyJpaEntity.java
│   │   │   │   │   ├── RepairCostJpaEntity.java
│   │   │   │   │   └── StringListConverter.java
│   │   │   │   └── repository/                  # Repos Spring Data JPA
│   │   │   │       ├── ClaimJpaRepository.java
│   │   │   │       ├── ClientJpaRepository.java
│   │   │   │       ├── HumanReviewJpaRepository.java
│   │   │   │       ├── PolicyJpaRepository.java
│   │   │   │       └── RepairCostJpaRepository.java
│   │   │   ├── pricing/
│   │   │   │   └── PricingResearchService.java  # SerpAPI pricing engine
│   │   │   ├── security/
│   │   │   │   ├── CorsConfig.java              # CORS pour Angular
│   │   │   │   ├── JwtUtils.java                # Extraction CIN du JWT
│   │   │   │   ├── KeycloakAdminService.java    # Création utilisateurs Keycloak
│   │   │   │   ├── KeycloakSyncService.java     # Sync DB → Keycloak au démarrage
│   │   │   │   └── SecurityConfig.java          # Spring Security + OAuth2
│   │   │   ├── CloudinaryConfig.java            # Config Cloudinary
│   │   │   └── PhotoUploadService.java          # Upload photos Cloudinary
│   │   │
│   │   └── web/                                 # Contrôleurs REST
│   │       ├── admin/
│   │       │   ├── AdminController.java         # CRUD clients/polices, approve/reject
│   │       │   ├── ContractAdminController.java # Upload PDF contrats
│   │       │   ├── PhotoUploadController.java   # Upload photos
│   │       │   └── ResetController.java         # Réinitialisation test
│   │       ├── advice/
│   │       │   └── GlobalExceptionHandler.java  # Gestion erreurs centralisée
│   │       ├── AuthController.java              # Config Keycloak + /me
│   │       ├── ClaimController.java             # CRUD sinistres
│   │       ├── ClientController.java            # CRUD clients
│   │       ├── HumanReviewController.java       # Revue humaine
│   │       └── PolicyController.java            # Consultation polices
│   │
│   └── resources/
│       ├── application.yml                      # Configuration Spring Boot
│       └── db/migration/
│           ├── V1__init.sql                     # Schéma initial
│           ├── V2__add_client_estimated_cost.sql
│           └── V3__add_review_resolution.sql
│
└── test/
└── java/org/example/pfeinsureflow/
└── PfeInsureflowApplicationTests.java   # Test chargement contexte

---

## Configuration de Référence

### application.yml

# Base de données PostgreSQL + pgvector
spring:
datasource:
url: jdbc:postgresql://localhost:5433/insureflow_db
username: postgres
password: postgres
jpa:
hibernate.ddl-auto: validate        # Flyway gère le schéma
open-in-view: false
flyway:
enabled: true
locations: classpath:db/migration

# RabbitMQ
spring.rabbitmq:
host: localhost
port: 5672
username: insureflow
password: insureflow

# Ollama AI
spring.ai.ollama:
base-url: http://localhost:11434
chat.model: llama3.1:8b
embedding.model: mxbai-embed-large

spring.ai.vectorstore.pgvector:
initialize-schema: true
dimensions: 1024
distance-type: COSINE_DISTANCE
index-type: HNSW

# Keycloak / OAuth2
spring.security.oauth2.resourceserver.jwt:
issuer-uri: http://localhost:8180/realms/insureflow

# Cloudinary
cloudinary:
cloud-name: dmqevyefl
api-key: ...
api-secret: ...

# SerpAPI
serpapi.api-key: ...

# Ollama extended config
ollama:
base-url: http://localhost:11434
chat:
model: llama3.1:8b
timeout: 120
vision:
model: llama3.2-vision
timeout: 300

# Queues RabbitMQ
insureflow.rabbitmq:
exchange: insureflow.claims
queues:
intake:    claim.intake
routed:    claim.routed
validated: claim.validated
estimated: claim.estimated
fraud:     claim.fraud.checked
decision:  claim.decision
review:    claim.human.review

### docker-compose.yml

services:
db:
image: pgvector/pgvector:pg16          # PostgreSQL 16 avec extension vector
ports: ["5433:5432"]
environment:
POSTGRES_DB: insureflow_db
POSTGRES_USER: postgres
POSTGRES_PASSWORD: postgres

rabbitmq:
image: rabbitmq:3.13-management         # Avec interface web :15672
ports: ["5672:5672", "15672:15672"]
environment:
RABBITMQ_DEFAULT_USER: insureflow
RABBITMQ_DEFAULT_PASS: insureflow

keycloak:
image: quay.io/keycloak/keycloak:24.0
command: start-dev
ports: ["8180:8080"]
environment:
KEYCLOAK_ADMIN: admin
KEYCLOAK_ADMIN_PASSWORD: REDACTED

---

## Pricing & Détermination des Coûts

### Stratégie de Pricing

La détermination des coûts suit une stratégie en 3 niveaux :

1. SerpAPI (recherche web) ──────► PRIX MARCHÉ RÉEL (haute confiance)
2. LLM Fallback (llama3.1) ──────► ESTIMATION (basse confiance)
3. Dernier recours (sévérité) ───► PLAGE MINIMALE (très approximatif)

*Principe fondamental* : Les LLM ne produisent JAMAIS de prix directement. Tous les prix viennent soit de SerpAPI (données marché réelles), soit de la table repair_costs, soit de plages par sévérité.

### Table repair_costs

La table contient 15 lignes préremplies pour les pièces automobiles courantes en Tunisie. L'EstimatorAgent utilise d'abord SerpAPI, pas cette table (celle-ci sert de référence statique complémentaire).

### SerpAPI — Prix Marché Réel

PricingResearchService.java implémente un moteur de recherche de prix sophistiqué :

1. *Construction de requêtes multilingues* : français, anglais, arabe selon la sévérité et le type de pièce

   Ex: "prix remplacement pare-choc avant Ford Ranger Tunisie TND"

2. *Scoring des sources* : poids plus élevé pour les sites de pièces auto, carrosserie, assurances
3. *Extraction de prix* : patterns regex pour les fourchettes, les prix uniques, avec détection de devise
4. *Conversion devise → TND* : taux de change pour EUR, USD, MAD, GBP, DZD, SAR, AED
5. *Filtrage par sévérité* : validation que le prix correspond à la plage attendue pour la sévérité

### Fallback LLM

Si SerpAPI échoue pour tous les éléments (pas de résultat), l'EstimatorAgent appelle le LLM en fallback :
- Prompt spécifique pour forcer une fourchette de prix
- Parsing de la réponse pour extraire des paires min-max
- Si même le LLM échoue, utilisation des *plages minimales par sévérité* (lastResortRange)

---

## RAG — Retrieval-Augmented Generation

Implémenté dans DocumentIngestionAdapter.java via Spring AI + pgvector.

### Ingestion

1. Réception du fichier PDF du contrat de police
2. Écriture en fichier temporaire
3. Lecture page par page via PagePdfDocumentReader (Spring AI)
4. Nettoyage agressif du texte :
    - Suppression des espaces multiples
    - Uniformisation des puces et tirets
    - Filtrage des lignes trop courtes (< 8 caractères)
    - Suppression des en-têtes répétitifs (BNA ASSURANCES, N° contrat...)
    - Suppression des numéros de page seuls
5. Chunking : 200 tokens par chunk, overlap de 30 tokens
6. Marquage avec policyId dans les métadonnées
7. Suppression des anciens chunks pour cette police (évite les doublons)
8. Insertion dans pgvector

### Retrieval

1. Réception de la description du sinistre + policyId
2. Recherche des 6 chunks les plus similaires (similarity threshold : 0.35)
3. Filtrage par policyId pour ne chercher que dans le bon contrat
4. Post-traitement : troncature à 400 caractères, déduplication
5. Les chunks sont passés au ValidatorAgent comme contexte

### Optimisations

- *Chunks de 200 tokens* : une idée par chunk (vs défaut Long)
- *Seuil de similarité à 0.35* : plus permissif pour les termes juridiques (vs défaut 0.5)
- *Déduplication* à 85% de similarité : supprime les quasi-doublons
- *Troncature à 400 caractères* : évite de noyer le LLM dans trop de texte

---

## RabbitMQ — File d'Attente & Routage

### Exchange Direct

L'application utilise un *Direct Exchange* (insureflow.claims) où chaque message est routé vers la file dont le routingKey correspond exactement au nom de la file.

### 7 Queues + 5 DLQs

| Queue | Routing Key | Consommateur | DLQ | Rôle |
|-------|-------------|-------------|-----|------|
| claim.intake | claim.intake | OrchestratorConsumer | claim.intake.dlq | Point d'entrée |
| claim.routed | claim.routed | RouterAgentService | claim.routed.dlq | Classification |
| claim.validated | claim.validated | ValidatorAgentService | claim.validated.dlq | Validation couverture |
| claim.estimated | claim.estimated | EstimatorAgentService | claim.estimated.dlq | Estimation dégâts |
| claim.fraud.checked | claim.fraud.checked | FraudAgentService | claim.fraud.checked.dlq | Détection fraude |
| claim.decision | claim.decision | OrchestratorConsumer | - | Décision finale |
| claim.human.review | claim.human.review | - (lecture directe DB) | - | Notification revue |

### Dead Letter Queues

Chaque file d'agent (sauf decision et review) a une *DLQ associée*. Si un consommateur jette une exception et que le message est rejeté après retries, RabbitMQ déplace le message vers la DLQ plutôt que de le perdre. Les DLQ permettent le débogage des échecs de traitement.

---

## Tests

Actuellement, un seul test de chargement de contexte Spring Boot :

./mvnw test

*Fichier* : src/test/java/org/example/pfeinsureflow/PfeInsureflowApplicationTests.java
*Framework* : JUnit 5 + Spring Boot Test

---

## Dépannage

### Keycloak ne démarre pas

# Vérifier que le port 8180 est libre
docker compose logs keycloak

# Redémarrer Keycloak seul
docker compose restart keycloak

### Connexion Ollama refusée

# Vérifier qu'Ollama tourne
ollama list

# Vérifier que les modèles sont installés
ollama pull llama3.1:8b
ollama pull mxbai-embed-large
ollama pull llama3.2-vision

### RabbitMQ — Messages dans les DLQ

# Accéder à l'interface RabbitMQ Management
# http://localhost:15672 (insureflow/insureflow)

# Voir les messages morts dans les DLQ
# Queues → claim.intake.dlq → Get Messages

### Flyway — Erreur de migration

# Vérifier l'état des migrations
# Supprimer la base et recréer
docker compose down -v && docker compose up -d

### Erreur "Client non trouvé pour CIN"

Vérifier que le protocol mapper cin est bien configuré dans Keycloak (le script setup_keycloak.py le fait automatiquement).

---

## Développement

### Commandes Utiles

# Démarrer l'application
./mvnw spring-boot:run

# Build sans tests
./mvnw clean package -DskipTests

# Build avec tests
./mvnw clean verify

# Lancer les tests
./mvnw test

# Application locale (profile dev)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

### Conventions de Code

- *Architecture* : Hexagonale (Domain → Application → Infrastructure → Web)
- *Nommage* : camelCase pour Java, kebab-case pour les URLs, UPPER_SNAKE pour les enums
- *Agents IA* : Interface @AiService (LangChain4j) + Service avec @RabbitListener
- *Ports* : Interfaces Java dans domain/port/, implémentations dans infrastructure/
- *Migrations* : Flyway avec format V{numero}__{description}.sql
- *Résultats agents* : Stockés en JSON brut dans la colonne correspondante du claim

### Principes d'Architecture

1. *Le Domain ne dépend de rien* — Pas d'annotations Spring, pas de JPA, pas de frameworks
2. *Inversion de dépendance* — Les ports (interfaces) sont dans le Domain, les implémentations dans l'Infrastructure
3. *Un agent = une file RabbitMQ* — Chaque agent est indépendant avec sa propre file + DLQ
4. *Pricing déterministe* — Les LLM ne produisent jamais de prix ; SerpAPI et la base de données sont les seules sources
5. *Tout sinistre → revue humaine* — Aucun rejet automatique, un expert humain prend toujours la décision finale
6. *JSON brut pour les résultats agents* — Chaque agent écrit son JSON complet dans la base, préservant toute l'information