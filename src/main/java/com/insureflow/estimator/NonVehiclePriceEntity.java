package com.insureflow.estimator;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "non_vehicle_prices")
public class NonVehiclePriceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_type", nullable = false)
    private String claimType;

    @Column(nullable = false)
    private String category;

    @Column(name = "item_description")
    private String itemDescription;

    @Column(name = "min_price_tnd", nullable = false)
    private BigDecimal minPriceTnd;

    @Column(name = "avg_price_tnd", nullable = false)
    private BigDecimal avgPriceTnd;

    @Column(name = "max_price_tnd", nullable = false)
    private BigDecimal maxPriceTnd;

    private String source;

    public Long getId()                  { return id; }
    public String getClaimType()         { return claimType; }
    public String getCategory()          { return category; }
    public String getItemDescription()   { return itemDescription; }
    public BigDecimal getMinPriceTnd()   { return minPriceTnd; }
    public BigDecimal getAvgPriceTnd()   { return avgPriceTnd; }
    public BigDecimal getMaxPriceTnd()   { return maxPriceTnd; }
    public String getSource()            { return source; }
}
