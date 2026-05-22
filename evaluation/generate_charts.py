#!/usr/bin/env python3
"""
Step 4 — Generate evaluation charts from evaluation/results.json.

Outputs four PNG files to evaluation/charts/:
    1. confusion_matrix.png
    2. estimation_accuracy.png
    3. processing_time_distribution.png
    4. decision_accuracy.png

Prerequisites:
    pip3 install --break-system-packages matplotlib seaborn numpy pandas

Run:
    python3 evaluation/generate_charts.py
"""

import json
import sys
from collections import defaultdict
from pathlib import Path

try:
    import numpy as np
    import matplotlib
    matplotlib.use("Agg")          # non-interactive backend (no display needed)
    import matplotlib.pyplot as plt
    import seaborn as sns
except ImportError as e:
    print(f"[ERROR] Missing dependency: {e}")
    print("        Run: pip3 install --break-system-packages matplotlib seaborn numpy pandas")
    sys.exit(1)

RESULTS_PATH = Path(__file__).parent / "results.json"
CHARTS_DIR   = Path(__file__).parent / "charts"

CLAIM_TYPES = ["VEHICLE_DAMAGE", "THEFT", "PROPERTY_DAMAGE"]
DECISIONS   = ["APPROVED", "REJECTED", "PENDING_REVIEW"]


def load_valid_results() -> list:
    if not RESULTS_PATH.exists():
        print(f"[ERROR] {RESULTS_PATH} not found.")
        print("        Run: python3 evaluation/run_evaluation.py")
        sys.exit(1)
    data = json.loads(RESULTS_PATH.read_text(encoding="utf-8"))
    valid = [r for r in data if r.get("system_result") is not None]
    print(f"[INFO] {len(valid)} valid results loaded (of {len(data)} total)")
    return valid


# ── 1. Confusion matrix ────────────────────────────────────────────────────────

def plot_confusion_matrix(results: list):
    idx    = {t: i for i, t in enumerate(CLAIM_TYPES)}
    matrix = np.zeros((3, 3), dtype=int)

    for r in results:
        gt   = r["ground_truth"]["claim_type"]
        pred = (r["system_result"]["claim_type"] or "UNKNOWN").upper()
        if gt in idx and pred in idx:
            matrix[idx[gt]][idx[pred]] += 1

    totals = matrix.sum(axis=1, keepdims=True)
    pct    = np.where(totals > 0, matrix / totals * 100, 0.0)

    labels = ["VEHICLE\nDAMAGE", "THEFT", "PROPERTY\nDAMAGE"]

    fig, ax = plt.subplots(figsize=(7, 5.5))
    sns.heatmap(
        pct, annot=False, cmap="Blues", ax=ax,
        xticklabels=labels, yticklabels=labels,
        vmin=0, vmax=100, linewidths=0.8, linecolor="white",
    )

    for i in range(3):
        for j in range(3):
            count = matrix[i][j]
            p     = pct[i][j]
            color = "white" if p > 55 else "black"
            ax.text(j + 0.5, i + 0.5, f"{count}\n({p:.0f}%)",
                    ha="center", va="center", fontsize=11,
                    color=color, fontweight="bold")

    ax.set_title("Matrice de Confusion — ClassifierAgent",
                 fontsize=13, fontweight="bold", pad=14)
    ax.set_xlabel("Prédiction du système", fontsize=11, labelpad=8)
    ax.set_ylabel("Vérité terrain",        fontsize=11, labelpad=8)
    plt.tight_layout()

    out = CHARTS_DIR / "confusion_matrix.png"
    plt.savefig(out, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"[OK] {out}")


# ── 2. Estimation accuracy ─────────────────────────────────────────────────────

def plot_estimation_accuracy(results: list):
    rel_errors = []
    for r in results:
        gt  = r["ground_truth"].get("estimated_amount", 0)
        est = r["system_result"].get("estimated_cost",  0)
        if gt and gt > 0 and est and est > 0:
            rel_errors.append(abs(est - gt) / gt * 100)

    if not rel_errors:
        print("[WARN] No valid estimation pairs — skipping estimation chart")
        return

    arr = np.array(rel_errors)
    n   = len(arr)

    bucket_defs = [
        ("≤ 10%\n(excellent)",     arr <= 10,                       "#27ae60"),
        ("10–25%\n(acceptable)",   (arr > 10)  & (arr <= 25),       "#82e0aa"),
        ("25–50%\n(poor)",         (arr > 25)  & (arr <= 50),       "#f39c12"),
        ("> 50%\n(très mauvais)",  arr > 50,                        "#e74c3c"),
    ]

    labels  = [b[0] for b in bucket_defs]
    counts  = [int(np.sum(b[1])) for b in bucket_defs]
    pcts    = [c / n * 100 for c in counts]
    colors  = [b[2] for b in bucket_defs]

    fig, ax = plt.subplots(figsize=(8, 5))
    bars = ax.bar(labels, pcts, color=colors, edgecolor="white", linewidth=1.5)

    for bar, count, pct in zip(bars, counts, pcts):
        ax.text(
            bar.get_x() + bar.get_width() / 2,
            bar.get_height() + 0.8,
            f"{count} ({pct:.0f}%)",
            ha="center", va="bottom", fontsize=10, fontweight="bold",
        )

    ax.set_title("Précision de l'EstimatorAgent",
                 fontsize=13, fontweight="bold", pad=14)
    ax.set_ylabel("% des sinistres évalués", fontsize=11)
    ax.set_ylim(0, max(pcts, default=0) * 1.3 + 5)
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", alpha=0.25)
    plt.tight_layout()

    out = CHARTS_DIR / "estimation_accuracy.png"
    plt.savefig(out, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"[OK] {out}")


# ── 3. Processing time distribution ───────────────────────────────────────────

def plot_processing_time(results: list):
    times_s = [
        r["system_result"]["processing_time_ms"] / 1000
        for r in results
        if r["system_result"].get("processing_time_ms", 0) > 0
    ]

    if not times_s:
        print("[WARN] No timing data — skipping processing-time chart")
        return

    arr = np.array(times_s)
    avg = np.mean(arr)
    p95 = np.percentile(arr, 95)

    fig, ax = plt.subplots(figsize=(8, 5))
    ax.hist(arr, bins=20, color="#3498db", edgecolor="white", linewidth=0.8, alpha=0.85)
    ax.axvline(avg, color="#e74c3c", linewidth=2, linestyle="--",
               label=f"Moyenne : {avg:.0f} s")
    ax.axvline(p95, color="#f39c12", linewidth=2, linestyle=":",
               label=f"P95 : {p95:.0f} s")

    ax.set_title("Distribution des Temps de Traitement",
                 fontsize=13, fontweight="bold", pad=14)
    ax.set_xlabel("Temps de traitement (secondes)", fontsize=11)
    ax.set_ylabel("Nombre de sinistres",            fontsize=11)
    ax.legend(fontsize=10)
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", alpha=0.25)
    plt.tight_layout()

    out = CHARTS_DIR / "processing_time_distribution.png"
    plt.savefig(out, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"[OK] {out}")


# ── 4. Decision accuracy ───────────────────────────────────────────────────────

def plot_decision_accuracy(results: list):
    gt_counts: dict[str, int]      = defaultdict(int)
    correct:   dict[str, int]      = defaultdict(int)

    for r in results:
        gt   = r["ground_truth"]["decision"]
        pred = (r["system_result"]["decision"] or "UNKNOWN").upper()
        gt_counts[gt] += 1
        if gt == pred:
            correct[gt] += 1

    x     = np.arange(len(DECISIONS))
    width = 0.38

    gt_vals   = [gt_counts.get(d, 0) for d in DECISIONS]
    corr_vals = [correct.get(d, 0)   for d in DECISIONS]

    fig, ax = plt.subplots(figsize=(8, 5))
    bars1 = ax.bar(x - width / 2, gt_vals,   width,
                   label="Vérité terrain",      color="#3498db", alpha=0.85)
    bars2 = ax.bar(x + width / 2, corr_vals, width,
                   label="Correctement prédit", color="#2ecc71", alpha=0.85)

    for bar in (*bars1, *bars2):
        h = bar.get_height()
        if h > 0:
            ax.text(bar.get_x() + bar.get_width() / 2, h + 0.2,
                    str(int(h)), ha="center", va="bottom", fontsize=9)

    ax.set_title("Précision des Décisions par Statut",
                 fontsize=13, fontweight="bold", pad=14)
    ax.set_xticks(x)
    ax.set_xticklabels([d.replace("_REVIEW", "") for d in DECISIONS], fontsize=10)
    ax.set_ylabel("Nombre de sinistres", fontsize=11)
    ax.legend(fontsize=10)
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", alpha=0.25)
    plt.tight_layout()

    out = CHARTS_DIR / "decision_accuracy.png"
    plt.savefig(out, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"[OK] {out}")


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    CHARTS_DIR.mkdir(exist_ok=True)

    sns.set_theme(style="whitegrid", context="notebook")
    plt.rcParams.update({"font.family": "DejaVu Sans"})

    results = load_valid_results()
    print()

    plot_confusion_matrix(results)
    plot_estimation_accuracy(results)
    plot_processing_time(results)
    plot_decision_accuracy(results)

    print(f"\n[OK] All charts saved to {CHARTS_DIR}/")


if __name__ == "__main__":
    main()
