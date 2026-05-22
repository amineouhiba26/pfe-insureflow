#!/usr/bin/env python3
"""
populate_embeddings.py
Generates nomic-embed-text embeddings for all past_claims rows where
embedding IS NULL, then writes them back to PostgreSQL.

Usage:
    pip install psycopg2-binary requests
    python populate_embeddings.py
"""

import sys
import requests
import psycopg2

# ── Config ────────────────────────────────────────────────────────────────────
DB_HOST   = "localhost"
DB_PORT   = 5433          # matches application.yml datasource url
DB_NAME   = "insureflow_db"
DB_USER   = "postgres"
DB_PASS   = "postgres"

OLLAMA_URL  = "http://localhost:11434/api/embeddings"
EMBED_MODEL = "nomic-embed-text"
# ─────────────────────────────────────────────────────────────────────────────


def pull_model_if_needed():
    """Pull nomic-embed-text if not yet available in Ollama."""
    try:
        r = requests.post("http://localhost:11434/api/pull",
                          json={"name": EMBED_MODEL}, timeout=300, stream=True)
        for line in r.iter_lines():
            if line:
                print(f"[PULL] {line.decode()}", flush=True)
    except Exception as e:
        print(f"[WARN] Could not pull model (may already exist): {e}", flush=True)


def embed(text: str) -> list[float]:
    resp = requests.post(OLLAMA_URL,
                         json={"model": EMBED_MODEL, "prompt": text},
                         timeout=60)
    resp.raise_for_status()
    return resp.json()["embedding"]


def main():
    print("[INFO] Connecting to PostgreSQL …", flush=True)
    conn = psycopg2.connect(
        host=DB_HOST, port=DB_PORT, dbname=DB_NAME,
        user=DB_USER, password=DB_PASS
    )
    cur = conn.cursor()

    # Ensure pgvector extension is loaded for this session
    cur.execute("CREATE EXTENSION IF NOT EXISTS vector;")
    conn.commit()

    cur.execute("""
        SELECT id, description FROM past_claims
        WHERE embedding IS NULL
        ORDER BY claim_date
    """)
    rows = cur.fetchall()

    if not rows:
        print("[INFO] No rows with NULL embedding — nothing to do.", flush=True)
        conn.close()
        return

    total = len(rows)
    print(f"[INFO] {total} claim(s) to embed using '{EMBED_MODEL}' …", flush=True)

    pull_model_if_needed()

    for i, (claim_id, description) in enumerate(rows, start=1):
        try:
            vec = embed(description or "")
            vec_str = "[" + ",".join(str(v) for v in vec) + "]"
            cur.execute(
                "UPDATE past_claims SET embedding = %s::vector WHERE id = %s",
                (vec_str, str(claim_id))
            )
            conn.commit()
            print(f"[INFO] Embedded claim {i}/{total}: {claim_id}", flush=True)
        except Exception as e:
            conn.rollback()
            print(f"[ERROR] Failed claim {i}/{total} ({claim_id}): {e}", flush=True)

    cur.close()
    conn.close()
    print("[INFO] Done.", flush=True)


if __name__ == "__main__":
    main()
