#!/usr/bin/env python3
"""
Step 1 — Prepare evaluation dataset.

Reads 100 rows from past_claims and writes evaluation/test_cases.json.

Prerequisites:
    pip3 install --break-system-packages psycopg2-binary

Run:
    python3 evaluation/prepare_evaluation.py
"""

import json
import random
import sys
from collections import Counter
from pathlib import Path

try:
    import psycopg2
except ImportError:
    print("[ERROR] psycopg2 not installed.")
    print("        Run: pip3 install --break-system-packages psycopg2-binary")
    sys.exit(1)

# ── Configuration ──────────────────────────────────────────────────────────────
DB_CONFIG = {
    "host":     "localhost",
    "port":     5433,          # application.yml: datasource.url uses 5433
    "dbname":   "insureflow_db",
    "user":     "postgres",
    "password": "postgres",
}

OUTPUT_PATH  = Path(__file__).parent / "test_cases.json"
RESULTS_PATH = Path(__file__).parent / "results.json"

VEHICLE_PREFIXES = [
    "Toyota 2021", "Honda 2020", "Volkswagen 2019",
    "Hyundai 2022", "Renault 2020", "Peugeot 2021",
    "Kia 2022", "Ford 2019", "Nissan 2020", "Dacia 2021",
]
# ────────────────────────────────────────────────────────────────────────────────


def connect():
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        print(f"[OK] Connected to {DB_CONFIG['dbname']} on localhost:{DB_CONFIG['port']}")
        return conn
    except Exception as e:
        print(f"[ERROR] DB connection failed: {e}")
        print("        Make sure PostgreSQL is running and InsureFlow backend has started at least once.")
        sys.exit(1)


def fetch_past_claims(conn) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute("""
            SELECT id, client_name, description, claim_type,
                   estimated_amount, decision, fraud_score,
                   parts_damaged, contract_type
            FROM past_claims
            ORDER BY claim_date NULLS LAST
        """)
        rows = cur.fetchall()
        cols = [desc[0] for desc in cur.description]
        return [dict(zip(cols, row)) for row in rows]


def enrich_description(description: str, claim_type: str) -> tuple[str, bool]:
    """Prepend a random car brand + year for VEHICLE_DAMAGE claims."""
    if claim_type != "VEHICLE_DAMAGE":
        return description, False
    prefix = random.choice(VEHICLE_PREFIXES)
    return f"{prefix} - {description}", True


def build_test_case(row: dict) -> dict:
    raw_desc   = (row["description"] or "").strip()
    claim_type = row["claim_type"]
    description, enriched = enrich_description(raw_desc, claim_type)
    return {
        "id":            str(row["id"]),
        "description":   description,
        "parts_damaged": row["parts_damaged"],
        "contract_type": row["contract_type"],
        "enriched":      enriched,   # flag so callers can verify enrichment
        "ground_truth": {
            "claim_type":       claim_type,
            "decision":         row["decision"],
            "estimated_amount": float(row["estimated_amount"]) if row["estimated_amount"] is not None else 0.0,
            "fraud_score":      float(row["fraud_score"])      if row["fraud_score"]      is not None else 0.0,
        },
    }


def main():
    conn = connect()
    try:
        rows = fetch_past_claims(conn)
        print(f"[OK] Fetched {len(rows)} rows from past_claims")

        if not rows:
            print("[WARN] past_claims table is empty.")
            print("       Start the InsureFlow backend once so Flyway runs V7/V9 migrations.")
            sys.exit(1)

        test_cases = [build_test_case(r) for r in rows]

        enriched_count = sum(1 for tc in test_cases if tc["enriched"])

        OUTPUT_PATH.write_text(
            json.dumps(test_cases, indent=2, default=str),
            encoding="utf-8",
        )
        print(f"[OK] Saved {len(test_cases)} test cases → {OUTPUT_PATH}")
        print(f"[OK] Enriched {enriched_count} VEHICLE_DAMAGE descriptions with car brand + year")

        # Reset results.json so all 100 claims are re-evaluated with fixed descriptions
        RESULTS_PATH.write_text("[]", encoding="utf-8")
        print(f"[OK] Reset {RESULTS_PATH} → []")

        types     = Counter(tc["ground_truth"]["claim_type"] for tc in test_cases)
        decisions = Counter(tc["ground_truth"]["decision"]   for tc in test_cases)
        print(f"\n[INFO] Claim-type distribution: {dict(types)}")
        print(f"[INFO] Decision distribution:    {dict(decisions)}")

        # Show a sample enriched description
        sample = next((tc for tc in test_cases if tc["enriched"]), None)
        if sample:
            print(f"\n[SAMPLE] {sample['description'][:100]}")

        print(f"\n[NEXT] Run: python3 evaluation/run_evaluation.py --test")

    finally:
        conn.close()


if __name__ == "__main__":
    main()
