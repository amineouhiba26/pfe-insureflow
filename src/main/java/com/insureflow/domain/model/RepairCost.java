package com.insureflow.domain.model;

import com.insureflow.domain.model.enums.Severity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row in the repair_costs lookup table.
 *
 * This exists because LLMs should NEVER be trusted to produce prices.
 * They hallucinate numbers freely. Instead:
 *
 * 1. EstimatorAgent (vision model) identifies: which parts are damaged + severity
 * 2. For each damaged part, we query this table: "what does a SEVERE hood repair cost?"
 * 3. We sum the ranges across all damaged parts → total estimated cost range
 *
 * Example:
 *   hood     + SEVERE   → 2000 to 4000 TND
 *   bumper   + SEVERE   → 1500 to 2500 TND
 *   windows  + MODERATE → 500  to 900  TND
 *   Total              → 4000 to 7400 TND
 *
 * The LLM never touches the numbers. It only identifies parts and severity.
 * Prices come from this deterministic DB table.
 */
public class RepairCost {

    private UUID id;
    private String partName;   // e.g. "front bumper", "hood", "windshield"
    private Severity severity; // MINOR, MODERATE, SEVERE, TOTAL_LOSS
    private BigDecimal minCost;
    private BigDecimal maxCost;
    private String region;     // "TN" — costs differ by country

    public RepairCost() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPartName() { return partName; }
    public void setPartName(String partName) { this.partName = partName; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public BigDecimal getMinCost() { return minCost; }
    public void setMinCost(BigDecimal minCost) { this.minCost = minCost; }
    public BigDecimal getMaxCost() { return maxCost; }
    public void setMaxCost(BigDecimal maxCost) { this.maxCost = maxCost; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}