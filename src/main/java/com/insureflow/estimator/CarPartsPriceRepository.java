package com.insureflow.estimator;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CarPartsPriceRepository extends JpaRepository<CarPartsPriceEntity, Long> {

    // Level 1 — exact match
    List<CarPartsPriceEntity> findByCarAndYearAndBodyPart(String car, Integer year, String bodyPart);

    // Level 2 — car + part, ignore year
    List<CarPartsPriceEntity> findByCarAndBodyPart(String car, String bodyPart);

    // Level 3 — part only, all cars
    List<CarPartsPriceEntity> findByBodyPart(String bodyPart);

    // Sum of shop2 prices across all parts for a given car/year (used for total-loss car valuation)
    @Query("SELECT SUM(e.shop2Price) FROM CarPartsPriceEntity e WHERE e.car = :car AND e.year = :year")
    Optional<BigDecimal> sumShop2ByCarAndYear(@Param("car") String car, @Param("year") Integer year);

    // Sum of shop2 prices across all parts for a car (any year) — fallback for estimateCarValue
    @Query("SELECT SUM(e.shop2Price) FROM CarPartsPriceEntity e WHERE e.car = :car")
    Optional<BigDecimal> sumShop2ByCar(@Param("car") String car);
}
