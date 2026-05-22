package com.insureflow.estimator;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NonVehiclePriceRepository extends JpaRepository<NonVehiclePriceEntity, Long> {

    @Query(value = """
            SELECT * FROM non_vehicle_prices
            WHERE claim_type = :claimType
              AND (category ILIKE %:keyword% OR item_description ILIKE %:keyword%)
            ORDER BY avg_price_tnd ASC
            LIMIT 3
            """, nativeQuery = true)
    List<NonVehiclePriceEntity> findBestMatch(@Param("claimType") String claimType,
                                               @Param("keyword") String keyword);

    @Query(value = """
            SELECT * FROM non_vehicle_prices
            WHERE claim_type = :claimType
            ORDER BY avg_price_tnd ASC
            LIMIT 5
            """, nativeQuery = true)
    List<NonVehiclePriceEntity> findByClaimType(@Param("claimType") String claimType);
}
