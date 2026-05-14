package com.insureflow.estimator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "car_parts_prices")
public class CarPartsPriceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String car;

    private String model;

    private Integer year;

    @Column(name = "body_part", nullable = false)
    private String bodyPart;

    @Column(name = "shop1_price")
    private BigDecimal shop1Price;

    @Column(name = "shop2_price", nullable = false)
    private BigDecimal shop2Price;

    @Column(name = "shop3_price", nullable = false)
    private BigDecimal shop3Price;

    private String source;

    @Column(name = "created_at")
    private Instant createdAt;

    public CarPartsPriceEntity() {}

    public Long getId()              { return id; }
    public String getCar()           { return car; }
    public String getModel()         { return model; }
    public Integer getYear()         { return year; }
    public String getBodyPart()      { return bodyPart; }
    public BigDecimal getShop1Price(){ return shop1Price; }
    public BigDecimal getShop2Price(){ return shop2Price; }
    public BigDecimal getShop3Price(){ return shop3Price; }
    public String getSource()        { return source; }
    public Instant getCreatedAt()    { return createdAt; }

    public void setId(Long id)                  { this.id = id; }
    public void setCar(String car)              { this.car = car; }
    public void setModel(String model)          { this.model = model; }
    public void setYear(Integer year)           { this.year = year; }
    public void setBodyPart(String bodyPart)    { this.bodyPart = bodyPart; }
    public void setShop1Price(BigDecimal v)     { this.shop1Price = v; }
    public void setShop2Price(BigDecimal v)     { this.shop2Price = v; }
    public void setShop3Price(BigDecimal v)     { this.shop3Price = v; }
    public void setSource(String source)        { this.source = source; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
