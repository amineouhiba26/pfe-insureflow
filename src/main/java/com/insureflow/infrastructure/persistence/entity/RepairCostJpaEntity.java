package com.insureflow.infrastructure.persistence.entity;

import com.insureflow.domain.model.enums.Severity;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA entity for the repair_costs table.
 * Seeded in V1__init.sql with 15 rows covering common vehicle parts.
 */
@Entity
@Table(name = "repair_costs")
public class RepairCostJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "part_name", nullable = false)
    private String partName;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    @Column(name = "min_cost")
    private BigDecimal minCost;

    @Column(name = "max_cost")
    private BigDecimal maxCost;

    @Column(name = "region")
    private String region;

    public RepairCostJpaEntity() {}

    public UUID getId()           { return id; }
    public String getPartName()   { return partName; }
    public Severity getSeverity() { return severity; }
    public BigDecimal getMinCost(){ return minCost; }
    public BigDecimal getMaxCost(){ return maxCost; }
    public String getRegion()     { return region; }
}