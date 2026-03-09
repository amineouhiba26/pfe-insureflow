-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

-- Clients table
CREATE TABLE IF NOT EXISTS clients (
                                       id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name   VARCHAR(255) NOT NULL,
    email       VARCHAR(255) NOT NULL UNIQUE,
    phone       VARCHAR(50),
    national_id VARCHAR(50) UNIQUE,
    created_at  TIMESTAMPTZ DEFAULT now()
    );

-- Policies table
CREATE TABLE IF NOT EXISTS policies (
                                        id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id              UUID NOT NULL REFERENCES clients(id),
    policy_number          VARCHAR(100) NOT NULL UNIQUE,
    type                   VARCHAR(50),
    coverage_limit         NUMERIC(15,2),
    deductible             NUMERIC(15,2),
    start_date             DATE,
    end_date               DATE,
    contract_document_path TEXT,
    created_at             TIMESTAMPTZ DEFAULT now()
    );

-- Claims table
CREATE TABLE IF NOT EXISTS claims (
                                      id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id        UUID NOT NULL REFERENCES clients(id),
    policy_id        UUID NOT NULL REFERENCES policies(id),
    type             VARCHAR(50),
    status           VARCHAR(50) NOT NULL DEFAULT 'SUBMITTED',
    description      TEXT,
    photo_urls       TEXT,
    router_result    TEXT,
    validator_result TEXT,
    estimator_result TEXT,
    fraud_result     TEXT,
    estimated_cost   NUMERIC(15,2),
    final_cost       NUMERIC(15,2),
    rejection_reason TEXT,
    confidence_score DOUBLE PRECISION,
    submitted_at     TIMESTAMPTZ DEFAULT now(),
    updated_at       TIMESTAMPTZ DEFAULT now()
    );

-- Human review tasks
CREATE TABLE IF NOT EXISTS human_review_tasks (
                                                  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id       UUID NOT NULL REFERENCES claims(id),
    reason         TEXT,
    status         VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    assigned_to    VARCHAR(255),
    adjuster_notes TEXT,
    created_at     TIMESTAMPTZ DEFAULT now(),
    resolved_at    TIMESTAMPTZ
    );

-- Repair costs lookup table
CREATE TABLE IF NOT EXISTS repair_costs (
                                            id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    part_name VARCHAR(100) NOT NULL,
    severity  VARCHAR(50)  NOT NULL,
    min_cost  NUMERIC(15,2) NOT NULL,
    max_cost  NUMERIC(15,2) NOT NULL,
    region    VARCHAR(100) DEFAULT 'TN',
    UNIQUE(part_name, severity, region)
    );

INSERT INTO repair_costs (part_name, severity, min_cost, max_cost) VALUES
                                                                       ('front bumper', 'MINOR',    200,   500),
                                                                       ('front bumper', 'MODERATE', 600,  1200),
                                                                       ('front bumper', 'SEVERE',  1500,  2500),
                                                                       ('hood',         'MINOR',    300,   700),
                                                                       ('hood',         'MODERATE', 800,  1500),
                                                                       ('hood',         'SEVERE',  2000,  4000),
                                                                       ('windshield',   'MINOR',    150,   400),
                                                                       ('windshield',   'MODERATE', 500,   900),
                                                                       ('windshield',   'SEVERE',  1000,  1800),
                                                                       ('door',         'MINOR',    200,   600),
                                                                       ('door',         'MODERATE', 700,  1500),
                                                                       ('door',         'SEVERE',  1800,  3500),
                                                                       ('airbags',      'SEVERE',  1200,  2500),
                                                                       ('engine',       'SEVERE',  4000,  9000),
                                                                       ('frame',        'SEVERE',  5000, 15000)
    ON CONFLICT DO NOTHING;