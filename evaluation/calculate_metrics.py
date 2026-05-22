#!/usr/bin/env python3
"""
Step 3 — Calculate evaluation metrics from evaluation/results.json.

Prerequisites:
    pip3 install --break-system-packages numpy

Run:
    python3 evaluation/calculate_metrics.py
"""

import json
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

try:
    import numpy as np
except ImportError:
    print("[ERROR] numpy not installed.")
    print("        Run: pip3 install --break-system-packages numpy")
    sys.exit(1)

INPUT_PATH  = Path(__file__).parent / "results.json"
OUTPUT_PATH = Path(__file__).parent / "metrics_report.json"

CLAIM_TYPES = ["VEHICLE_DAMAGE", "THEFT", "PROPERTY_DAMAGE"]
DECISIONS   = ["APPROVED", "REJECTED", "PENDING_REVIEW"]

W = 56   # inner width of the report box (between ║ chars)


# ── Classification metrics ─────────────────────────────────────────────────────

def classification_metrics(results: list) -> dict:
    correct = 0
    matrix: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))

    for r in results:
        gt   = r["ground_truth"]["claim_type"]
        pred = (r["system_result"]["claim_type"] or "UNKNOWN").upper()
        matrix[gt][pred] += 1
        if gt == pred:
            correct += 1

    accuracy = correct / len(results) * 100 if results else 0.0

    per_class: dict[str, dict] = {}
    for cls in CLAIM_TYPES:
        tp = matrix[cls][cls]
        fp = sum(matrix[other][cls] for other in CLAIM_TYPES if other != cls)
        fn = sum(matrix[cls][other] for other in CLAIM_TYPES if other != cls)
        prec   = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1     = 2 * prec * recall / (prec + recall) if (prec + recall) > 0 else 0.0
        per_class[cls] = {"precision": prec, "recall": recall, "f1": f1,
                          "tp": tp, "fp": fp, "fn": fn}

    return {
        "accuracy":         accuracy,
        "correct":          correct,
        "total":            len(results),
        "per_class":        per_class,
        "confusion_matrix": {gt: dict(preds) for gt, preds in matrix.items()},
    }


# ── Estimation metrics ─────────────────────────────────────────────────────────

def estimation_metrics(results: list) -> dict:
    pairs: list[tuple[float, float]] = []
    for r in results:
        gt_amt  = r["ground_truth"].get("estimated_amount", 0)
        sys_est = r["system_result"].get("estimated_cost",  0)
        if gt_amt and gt_amt > 0 and sys_est and sys_est > 0:
            pairs.append((float(gt_amt), float(sys_est)))

    if not pairs:
        empty_bucket = {"count": 0, "pct": 0.0}
        return {
            "mae": 0, "mape": 0, "median_pct": 0, "count": 0,
            "buckets": {
                "within_10pct":  empty_bucket,
                "within_25pct":  empty_bucket,
                "within_50pct":  empty_bucket,
                "over_50pct":    empty_bucket,
            },
        }

    gt_arr  = np.array([p[0] for p in pairs])
    sys_arr = np.array([p[1] for p in pairs])
    abs_err = np.abs(sys_arr - gt_arr)
    rel_err = abs_err / gt_arr * 100

    n = len(pairs)

    def bucket(mask):
        c = int(np.sum(mask))
        return {"count": c, "pct": round(c / n * 100, 1)}

    return {
        "mae":        float(np.mean(abs_err)),
        "mape":       float(np.mean(rel_err)),
        "median_pct": float(np.median(rel_err)),
        "count":      n,
        "buckets": {
            "within_10pct": bucket(rel_err <= 10),
            "within_25pct": bucket(rel_err <= 25),
            "within_50pct": bucket(rel_err <= 50),
            "over_50pct":   bucket(rel_err >  50),
        },
    }


# ── Decision metrics ───────────────────────────────────────────────────────────

def decision_metrics(results: list) -> dict:
    correct = 0
    per_status: dict[str, dict] = {d: {"correct": 0, "total": 0} for d in DECISIONS}

    for r in results:
        gt   = r["ground_truth"]["decision"]
        pred = (r["system_result"]["decision"] or "UNKNOWN").upper()
        if gt in per_status:
            per_status[gt]["total"] += 1
        if gt == pred:
            correct += 1
            if gt in per_status:
                per_status[gt]["correct"] += 1

    accuracy = correct / len(results) * 100 if results else 0.0

    per_status_out: dict[str, dict] = {}
    for status, counts in per_status.items():
        t = counts["total"]
        c = counts["correct"]
        per_status_out[status] = {
            "correct":      c,
            "total":        t,
            "accuracy_pct": round(c / t * 100, 1) if t > 0 else 0.0,
        }

    return {
        "accuracy":    accuracy,
        "correct":     correct,
        "total":       len(results),
        "per_status":  per_status_out,
    }


# ── Processing time metrics ────────────────────────────────────────────────────

def timing_metrics(results: list) -> dict:
    times_ms = [
        r["system_result"]["processing_time_ms"]
        for r in results
        if r["system_result"].get("processing_time_ms", 0) > 0
    ]
    if not times_ms:
        return {"avg_s": 0.0, "min_s": 0.0, "max_s": 0.0, "p95_s": 0.0}

    arr = np.array(times_ms, dtype=float) / 1000.0
    return {
        "avg_s": float(np.mean(arr)),
        "min_s": float(np.min(arr)),
        "max_s": float(np.max(arr)),
        "p95_s": float(np.percentile(arr, 95)),
    }


# ── Report printer ─────────────────────────────────────────────────────────────

def _row(text: str) -> str:
    """Pad text to fit inside the box (W inner chars)."""
    return f"║ {text:<{W - 2}}║"


def print_report(cls_m: dict, est_m: dict, dec_m: dict, tim_m: dict):
    border = "═" * W
    sep    = f"╠{border}╣"

    print(f"╔{border}╗")
    print(_row(f"{'INSUREFLOW EVALUATION REPORT':^{W - 2}}"))
    print(_row(f"{'Generated: ' + str(date.today()):^{W - 2}}"))
    print(_row(f"{'Evaluated: ' + str(cls_m['total']) + ' claims':^{W - 2}}"))

    # ── Classification ──
    print(sep)
    print(_row("CLASSIFICATION ACCURACY"))
    print(_row(f"  Overall accuracy:  {cls_m['accuracy']:.1f}%"))
    label_map = {
        "VEHICLE_DAMAGE":  "VEHICLE_DAMAGE",
        "THEFT":           "THEFT         ",
        "PROPERTY_DAMAGE": "PROPERTY_DMG  ",
    }
    for cls in CLAIM_TYPES:
        m = cls_m["per_class"].get(cls, {})
        lbl = label_map.get(cls, cls[:14])
        val = f"P={m.get('precision',0):.2f}  R={m.get('recall',0):.2f}  F1={m.get('f1',0):.2f}"
        print(_row(f"  {lbl:16s}  {val}"))

    # ── Estimation ──
    print(sep)
    print(_row("ESTIMATION ACCURACY"))
    print(_row(f"  MAE:   {est_m['mae']:>10,.0f} TND"))
    print(_row(f"  MAPE:  {est_m['mape']:>10.1f}%"))
    buckets = est_m.get("buckets", {})
    for label, key in [
        ("Within 10% (excellent):", "within_10pct"),
        ("Within 25% (acceptable):", "within_25pct"),
        ("Within 50% (poor):     ", "within_50pct"),
        ("Over   50% (very poor):", "over_50pct"),
    ]:
        b = buckets.get(key, {})
        val = f"{b.get('count', 0):3d} claims ({b.get('pct', 0):.0f}%)"
        print(_row(f"  {label:<26}{val}"))

    # ── Decision ──
    print(sep)
    print(_row("DECISION ACCURACY"))
    print(_row(f"  Overall accuracy:  {dec_m['accuracy']:.1f}%"))
    dlabel_map = {
        "APPROVED":       "APPROVED      ",
        "REJECTED":       "REJECTED      ",
        "PENDING_REVIEW": "PENDING_REVIEW",
    }
    for status in DECISIONS:
        s   = dec_m["per_status"].get(status, {})
        lbl = dlabel_map.get(status, status[:14])
        val = f"{s.get('accuracy_pct', 0):.0f}%  ({s.get('correct', 0)}/{s.get('total', 0)} correct)"
        print(_row(f"  {lbl:16s}  {val}"))

    # ── Timing ──
    print(sep)
    print(_row("PROCESSING TIME"))
    print(_row(f"  Average: {tim_m['avg_s']:.0f}s"))
    timing_line = (
        f"  Min: {tim_m['min_s']:.0f}s   "
        f"Max: {tim_m['max_s']:.0f}s   "
        f"P95: {tim_m['p95_s']:.0f}s"
    )
    print(_row(timing_line))

    # ── Comparison ──
    print(sep)
    print(_row("COMPARISON: MANUAL vs INSUREFLOW"))
    print(_row("  Manual processing:  3-5 business days"))
    avg_s = tim_m["avg_s"] or 1
    speed = int((3 * 24 * 3600) / avg_s)
    print(_row(f"  InsureFlow avg:     {avg_s:.0f} seconds"))
    print(_row(f"  Speed improvement:  ~{speed:,}x faster"))

    print(f"╚{border}╝")


# ── Main ───────────────────────────────────────────────────────────────────────

def load_results() -> list:
    if not INPUT_PATH.exists():
        print(f"[ERROR] {INPUT_PATH} not found.")
        print("        Run: python3 evaluation/run_evaluation.py")
        sys.exit(1)
    data = json.loads(INPUT_PATH.read_text(encoding="utf-8"))
    valid   = [r for r in data if r.get("system_result") is not None]
    skipped = len(data) - len(valid)
    print(f"[INFO] Loaded {len(data)} results  ({len(valid)} valid, {skipped} timeout/error)")
    if not valid:
        print("[ERROR] No valid results to analyse.")
        sys.exit(1)
    return valid


def main():
    results = load_results()

    cls_m = classification_metrics(results)
    est_m = estimation_metrics(results)
    dec_m = decision_metrics(results)
    tim_m = timing_metrics(results)

    print()
    print_report(cls_m, est_m, dec_m, tim_m)

    avg_s = tim_m["avg_s"] or 1
    report = {
        "generatedAt":              str(date.today()),
        "totalEvaluated":           len(results),
        # Classification
        "classificationAccuracy":   round(cls_m["accuracy"], 2),
        "classificationCorrect":    cls_m["correct"],
        "perClassMetrics":          cls_m["per_class"],
        "confusionMatrix":          cls_m["confusion_matrix"],
        # Estimation
        "estimationMAE":            round(est_m["mae"], 2),
        "estimationMAPE":           round(est_m["mape"], 2),
        "estimationMedianPct":      round(est_m["median_pct"], 2),
        "withinTenPercent":         est_m["buckets"]["within_10pct"]["count"],
        "withinTwentyFivePercent":  est_m["buckets"]["within_25pct"]["count"],
        "withinFiftyPercent":       est_m["buckets"]["within_50pct"]["count"],
        # Decision
        "decisionAccuracy":         round(dec_m["accuracy"], 2),
        "decisionCorrect":          dec_m["correct"],
        "perStatusAccuracy":        dec_m["per_status"],
        # Timing
        "avgProcessingSeconds":     round(tim_m["avg_s"], 1),
        "minProcessingSeconds":     round(tim_m["min_s"], 1),
        "maxProcessingSeconds":     round(tim_m["max_s"], 1),
        "p95ProcessingSeconds":     round(tim_m["p95_s"], 1),
        "speedImprovementFactor":   int((3 * 24 * 3600) / avg_s),
    }

    OUTPUT_PATH.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\n[OK] Metrics report saved → {OUTPUT_PATH}")
    print("[NEXT] Run: python3 evaluation/generate_charts.py")


if __name__ == "__main__":
    main()
