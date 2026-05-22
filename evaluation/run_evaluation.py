#!/usr/bin/env python3
"""
Step 2 — Submit test cases to the live InsureFlow API and record results.

Prerequisites:
    - InsureFlow backend running at http://localhost:8080
    - Keycloak running at http://localhost:8180
    - test_cases.json produced by prepare_evaluation.py

    pip3 install --break-system-packages requests

Run:
    python3 evaluation/run_evaluation.py --test     # 1 claim dry-run (verify before bulk)
    python3 evaluation/run_evaluation.py            # first 30 claims
    python3 evaluation/run_evaluation.py --all      # all 100 claims
    python3 evaluation/run_evaluation.py --batch 10

Auth strategy
─────────────
InsureFlow uses Keycloak.  Spring Boot never handles credentials — tokens
are obtained via Keycloak's password grant and forwarded as Bearer tokens.

Three seed users (one per policy type) are used so the ValidatorAgent does
not reject claims due to a coverage mismatch.

    VEHICLE_DAMAGE  → sonia.gharbi   / SYN00000004   policy b…004  client a…004
    THEFT           → fatma.trabelsi / SYN00000002   policy b…002  client a…002
    PROPERTY_DAMAGE → karim.jendoubi / SYN00000003   policy b…003  client a…003

Username  = firstName.lastName  (computed by KeycloakAdminService.createUser)
Password  = national_id         (set as default credential by KeycloakAdminService)
client_id = InsureFlow DB UUID  (seeded by V9__seed_synthetic_claims.sql)
policy_id = InsureFlow DB UUID  (seeded by V9__seed_synthetic_claims.sql)

These users are synced to Keycloak automatically on backend startup via
KeycloakSyncService.  If login fails, open http://localhost:8180 and check
that the users exist in the insureflow realm with the CIN attribute set.

Request body note
─────────────────
SubmitClaimRequest has four fields validated with @NotNull / @NotBlank:
    clientId            UUID     @NotNull   — required (ignored by controller, used by validator)
    policyId            UUID     @NotNull   — required
    description         String   @NotBlank  — required
    clientEstimatedCost BigDecimal          — optional
    photoUrls           List<String>        — optional

ClaimController.submit() extracts clientId from the JWT (via JwtUtils) and
ignores request.getClientId(), but Bean Validation still enforces @NotNull,
so clientId MUST be present and non-null in every request body.
"""

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

try:
    import requests
except ImportError:
    print("[ERROR] requests not installed.")
    print("        Run: pip3 install --break-system-packages requests")
    sys.exit(1)

# ── Configuration ──────────────────────────────────────────────────────────────
API_BASE     = "http://localhost:8080/api/v1"
KC_TOKEN_URL = "http://localhost:8180/realms/insureflow/protocol/openid-connect/token"
KC_CLIENT_ID = "insureflow-frontend"

# Seed data UUIDs from V9__seed_synthetic_claims.sql and clients/policies tables.
# client_id: InsureFlow DB UUID  (a0000001-…)
# policy_id: InsureFlow DB UUID  (b0000001-…), matched by type so ValidatorAgent passes.
AUTH_PROFILES: dict[str, dict] = {
    "VEHICLE_DAMAGE": {
        "username":  "sonia.gharbi",
        "password":  "SYN00000004",
        "client_id": "a0000001-0000-0000-0000-000000000004",
        "policy_id": "b0000001-0000-0000-0000-000000000004",
    },
    "THEFT": {
        "username":  "fatma.trabelsi",
        "password":  "SYN00000002",
        "client_id": "a0000001-0000-0000-0000-000000000002",
        "policy_id": "b0000001-0000-0000-0000-000000000002",
    },
    "PROPERTY_DAMAGE": {
        "username":  "karim.jendoubi",
        "password":  "SYN00000003",
        "client_id": "a0000001-0000-0000-0000-000000000003",
        "policy_id": "b0000001-0000-0000-0000-000000000003",
    },
}

POLL_INTERVAL_S = 3     # seconds between status polls
MAX_WAIT_S      = 180   # timeout per claim
SUBMIT_DELAY_S  = 2     # pause between submissions (avoid overloading RabbitMQ)
TOKEN_REFRESH_S = 240   # refresh before Keycloak's 5-min expiry

TERMINAL_STATUSES = {"APPROVED", "REJECTED", "PENDING_REVIEW"}

INPUT_PATH  = Path(__file__).parent / "test_cases.json"
OUTPUT_PATH = Path(__file__).parent / "results.json"
# ────────────────────────────────────────────────────────────────────────────────


# ── Token management ───────────────────────────────────────────────────────────

class TokenManager:
    """Fetches and lazily refreshes Keycloak access tokens per claim type."""

    def __init__(self):
        self._cache: dict[str, dict] = {}

    def get(self, claim_type: str) -> str:
        entry = self._cache.get(claim_type)
        if entry is None or time.time() > entry["expires_at"]:
            self._cache[claim_type] = self._fetch(claim_type)
        return self._cache[claim_type]["token"]

    def _fetch(self, claim_type: str) -> dict:
        profile = AUTH_PROFILES.get(claim_type) or AUTH_PROFILES["VEHICLE_DAMAGE"]
        try:
            resp = requests.post(
                KC_TOKEN_URL,
                # Keycloak password grant — form-encoded, NOT JSON
                data={
                    "grant_type": "password",
                    "client_id":  KC_CLIENT_ID,
                    "username":   profile["username"],
                    "password":   profile["password"],
                },
                timeout=15,
            )
        except requests.ConnectionError:
            raise RuntimeError(
                f"Cannot reach Keycloak at {KC_TOKEN_URL}\n"
                "Is Keycloak running on port 8180?"
            )

        if resp.status_code != 200:
            raise RuntimeError(
                f"Keycloak token request failed for {claim_type} "
                f"(username={profile['username']})\n"
                f"Status: {resp.status_code}\n"
                f"Body:   {resp.text[:500]}\n\n"
                "Troubleshooting:\n"
                "  1. Open http://localhost:8180 → realm insureflow → Users\n"
                "  2. Verify the user exists and has attribute cin=<national_id>\n"
                "  3. Start the InsureFlow backend once — KeycloakSyncService creates users automatically\n"
                "  4. If the user exists but login fails, reset the password in Keycloak admin console"
            )

        data = resp.json()
        print(f"  [AUTH] Token OK  user={profile['username']}  claim_type={claim_type}")
        return {
            "token":      data["access_token"],
            "expires_at": time.time() + TOKEN_REFRESH_S,
        }


# ── Policy resolution ──────────────────────────────────────────────────────────

def resolve_policy_id(token: str, claim_type: str, fallback_id: str) -> str:
    """
    Calls GET /api/v1/policies/my and returns the first policy whose type
    matches claim_type.  Falls back to the hardcoded seed UUID if the API
    call fails or no matching policy is found.
    """
    try:
        resp = requests.get(
            f"{API_BASE}/policies/my",
            headers={"Authorization": f"Bearer {token}"},
            timeout=10,
        )
        if resp.status_code != 200:
            print(f"  [POLICY] GET /policies/my returned {resp.status_code} — using seed UUID")
            return fallback_id

        policies: list[dict] = resp.json()
        if not policies:
            print("  [POLICY] No policies found for this user — using seed UUID")
            return fallback_id

        # Prefer a policy whose type exactly matches the claim type
        for p in policies:
            if p.get("type", "").upper() == claim_type.upper():
                print(f"  [POLICY] Matched policy {p['id']}  type={p['type']}")
                return p["id"]

        # Otherwise use the first available policy
        first_id = policies[0]["id"]
        print(f"  [POLICY] No type match; using first policy {first_id}  type={policies[0].get('type')}")
        return first_id

    except Exception as e:
        print(f"  [POLICY] Error fetching policies: {e} — using seed UUID")
        return fallback_id


# ── Claim submission ───────────────────────────────────────────────────────────

def submit_claim(token: str, client_id: str, policy_id: str,
                 description: str, estimated_amount: float) -> dict:
    """
    POST /api/v1/claims

    SubmitClaimRequest required fields (Bean Validation enforced by @Valid):
        clientId            UUID    @NotNull  — must be present even though the
                                               controller overwrites it from JWT
        policyId            UUID    @NotNull
        description         String  @NotBlank
        clientEstimatedCost         optional
        photoUrls                   optional
    """
    body = {
        "clientId":            client_id,       # @NotNull — must be present
        "policyId":            policy_id,       # @NotNull — must be present
        "description":         description,     # @NotBlank — must be non-empty
        "clientEstimatedCost": estimated_amount,
        "photoUrls":           [],
    }

    resp = requests.post(
        f"{API_BASE}/claims",
        json=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type":  "application/json",
        },
        timeout=15,
    )

    if resp.status_code == 400:
        print(f"  [400 ERROR] Request body was: {json.dumps(body, indent=4)}")
        print(f"  [400 ERROR] Response body:    {resp.text}")

    resp.raise_for_status()
    return resp.json()


# ── Claim polling ──────────────────────────────────────────────────────────────

def poll_claim(claim_id: str, token: str) -> dict | None:
    """Polls GET /claims/{id} every POLL_INTERVAL_S seconds until terminal status."""
    deadline = time.time() + MAX_WAIT_S
    dots = 0
    while time.time() < deadline:
        try:
            resp = requests.get(
                f"{API_BASE}/claims/{claim_id}",
                headers={"Authorization": f"Bearer {token}"},
                timeout=15,
            )
        except requests.RequestException as e:
            print(f"\n  [WARN] Poll error: {e}")
            time.sleep(POLL_INTERVAL_S)
            continue

        if resp.status_code != 200:
            print(f"\n  [WARN] Poll returned {resp.status_code}")
            time.sleep(POLL_INTERVAL_S)
            continue

        claim  = resp.json()
        status = claim.get("status", "")

        if status in TERMINAL_STATUSES:
            print()   # newline after dots
            return claim

        print(".", end="", flush=True)
        dots += 1
        time.sleep(POLL_INTERVAL_S)

    print(f"\n  [TIMEOUT] Claim {claim_id} did not reach terminal status in {MAX_WAIT_S}s")
    return None


# ── Result helpers ─────────────────────────────────────────────────────────────

def _parse_processing_ms(claim: dict) -> int:
    try:
        s = datetime.fromisoformat((claim.get("submittedAt") or "").replace("Z", "+00:00"))
        u = datetime.fromisoformat((claim.get("updatedAt")   or "").replace("Z", "+00:00"))
        return max(0, int((u - s).total_seconds() * 1000))
    except Exception:
        return 0


def _extract_router_type(router_result: dict | None) -> str | None:
    if not router_result:
        return None
    for key in ("claimType", "claim_type", "type", "category", "predicted_type"):
        if key in router_result:
            return str(router_result[key]).upper()
    return None


def build_result(test_case: dict, claim: dict | None, error: str | None) -> dict:
    if claim is None:
        return {
            "test_id":       test_case["id"],
            "ground_truth":  test_case["ground_truth"],
            "system_result": None,
            "error":         error or "timeout",
        }

    detected_type = (
        claim.get("type")
        or _extract_router_type(claim.get("routerResult"))
    )

    return {
        "test_id":      test_case["id"],
        "ground_truth": test_case["ground_truth"],
        "system_result": {
            "claim_id":           claim.get("id"),
            "claim_type":         detected_type,
            "decision":           claim.get("status"),
            "estimated_cost":     float(claim["estimatedCost"])   if claim.get("estimatedCost")   else 0.0,
            "confidence_score":   float(claim["confidenceScore"]) if claim.get("confidenceScore") else 0.0,
            "processing_time_ms": _parse_processing_ms(claim),
        },
        "error": None,
    }


def load_results() -> list:
    if OUTPUT_PATH.exists():
        try:
            return json.loads(OUTPUT_PATH.read_text(encoding="utf-8"))
        except Exception:
            pass
    return []


def save_results(results: list):
    OUTPUT_PATH.write_text(json.dumps(results, indent=2, default=str), encoding="utf-8")


# ── Startup validation ─────────────────────────────────────────────────────────

def validate_profiles(tokens: TokenManager) -> dict[str, str]:
    """
    Authenticates all three profiles and fetches real policy IDs from
    GET /api/v1/policies/my.  Returns {claim_type: resolved_policy_id}.
    """
    print("[INIT] Authenticating and resolving policy IDs…")
    resolved: dict[str, str] = {}
    for claim_type, profile in AUTH_PROFILES.items():
        try:
            token = tokens.get(claim_type)
            resolved[claim_type] = resolve_policy_id(token, claim_type, profile["policy_id"])
        except Exception as e:
            print(f"  [WARN] {claim_type}: {e}")
            print(f"  [WARN] Falling back to seed policy_id for {claim_type}")
            resolved[claim_type] = profile["policy_id"]
    print()
    return resolved


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Run InsureFlow claim evaluation")
    parser.add_argument("--test",  action="store_true",
                        help="Submit ONE claim only (dry-run to verify request format)")
    parser.add_argument("--all",   action="store_true",
                        help="Process all remaining test cases")
    parser.add_argument("--batch", type=int, default=30,
                        help="Max claims to process in this run (default: 30)")
    args = parser.parse_args()

    if not INPUT_PATH.exists():
        print(f"[ERROR] {INPUT_PATH} not found.")
        print("        Run: python3 evaluation/prepare_evaluation.py")
        sys.exit(1)

    test_cases: list = json.loads(INPUT_PATH.read_text(encoding="utf-8"))
    print(f"[INFO] Loaded {len(test_cases)} test cases from {INPUT_PATH}")
    print(f"[INFO] API: {API_BASE}  |  Keycloak: {KC_TOKEN_URL}\n")

    tokens = TokenManager()

    # Authenticate all profiles upfront and resolve real policy IDs
    try:
        resolved_policies = validate_profiles(tokens)
    except Exception as e:
        print(f"[FATAL] Auth validation failed: {e}")
        sys.exit(1)

    existing  = load_results()
    done_ids  = {r["test_id"] for r in existing}
    remaining = [tc for tc in test_cases if tc["id"] not in done_ids]
    print(f"[INFO] Already processed: {len(done_ids)} | Remaining: {len(remaining)}")

    if args.test:
        # Pick the first unprocessed test case for a single dry-run
        to_run = remaining[:1] if remaining else test_cases[:1]
        print(f"[TEST MODE] Running 1 claim only — results will NOT be saved\n")
    elif args.all:
        to_run = remaining
    else:
        to_run = remaining[:args.batch]

    limit = len(to_run)

    if not to_run:
        print("[INFO] Nothing to process — all test cases are already done.")
        print("[NEXT] Run: python3 evaluation/calculate_metrics.py")
        sys.exit(0)

    print(f"[INFO] Processing {limit} claim(s)\n")

    results = list(existing)
    ok = errors = timeouts = 0

    for i, tc in enumerate(to_run, 1):
        gt         = tc["ground_truth"]
        claim_type = gt["claim_type"]
        profile    = AUTH_PROFILES.get(claim_type, AUTH_PROFILES["VEHICLE_DAMAGE"])
        policy_id  = resolved_policies.get(claim_type, profile["policy_id"])

        print(f"[{i:3d}/{limit}] {claim_type}")
        print(f"         id={tc['id']}")
        print(f"         {tc['description'][:90]}")

        try:
            token = tokens.get(claim_type)

            submitted = submit_claim(
                token=token,
                client_id=profile["client_id"],
                policy_id=policy_id,
                description=tc["description"],
                estimated_amount=gt["estimated_amount"],
            )

            claim_id = submitted["id"]
            print(f"         → submitted  id={claim_id}  status={submitted.get('status')}")

            if args.test:
                print(f"\n[TEST] Full submission response:")
                print(json.dumps(submitted, indent=2, default=str))
                print("\n[TEST] Now polling for final status…")

            final = poll_claim(claim_id, token)

            if final:
                status = final.get("status")
                cost   = final.get("estimatedCost", 0)
                ctype  = final.get("type")
                ms     = _parse_processing_ms(final)
                print(f"         → done  status={status}  type={ctype}  estimatedCost={cost}  ({ms//1000}s)")

                if args.test:
                    print(f"\n[TEST] Full final response:")
                    print(json.dumps(final, indent=2, default=str))

                result = build_result(tc, final, None)
                ok += 1
            else:
                result = build_result(tc, None, "timeout")
                timeouts += 1

        except KeyboardInterrupt:
            print("\n[INTERRUPTED] Saving progress…")
            save_results(results)
            sys.exit(0)
        except Exception as e:
            print(f"         → ERROR: {e}")
            result = build_result(tc, None, str(e))
            errors += 1

        if not args.test:
            results.append(result)
            save_results(results)   # incremental save after each claim

        if i < limit:
            time.sleep(SUBMIT_DELAY_S)

    print(f"\n{'═' * 60}")
    print(f"  Done: {ok}  |  Timeouts: {timeouts}  |  Errors: {errors}")

    if args.test:
        print("\n[TEST MODE] Results were NOT saved.")
        if ok > 0:
            print("[TEST] SUCCESS — request format is correct.")
            print("[NEXT] Run without --test to process claims:")
            print("       python3 evaluation/run_evaluation.py")
        else:
            print("[TEST] FAILED — fix the error above before running the full batch.")
    else:
        print(f"  Results saved → {OUTPUT_PATH}")
        left = len(remaining) - limit
        if not args.all and left > 0:
            print(f"\n  {left} claims still remaining.")
            print("  Run with --all to process everything:")
            print("  python3 evaluation/run_evaluation.py --all")
        else:
            print("\n[NEXT] Run: python3 evaluation/calculate_metrics.py")


if __name__ == "__main__":
    main()
