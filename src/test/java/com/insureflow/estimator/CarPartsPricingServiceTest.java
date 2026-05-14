package com.insureflow.estimator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CarPartsPricingServiceTest {

    @Mock
    private CarPartsPriceRepository repo;

    @InjectMocks
    private CarPartsPricingService service;

    // ── Fallback level 1 — exact match (HIGH confidence) ─────────────────────

    @Test
    void exactMatch_returnsHighConfidence() {
        CarPartsPriceEntity entity = entity("toyota", 2020, "front bumper", 275, 350, 1368);
        when(repo.findByCarAndYearAndBodyPart("toyota", 2020, "front bumper"))
                .thenReturn(List.of(entity));

        Optional<PriceEstimate> result = service.estimatePrice("toyota", 2020, "front bumper");

        assertThat(result).isPresent();
        PriceEstimate pe = result.get();
        assertThat(pe.confidenceLevel()).isEqualTo("HIGH");
        assertThat(pe.fallbackUsed()).isFalse();
        assertThat(pe.matchedCar()).isEqualTo("toyota");
        assertThat(pe.matchedYear()).isEqualTo(2020);
        // shop2 average still accessible
        assertThat(pe.avgPrice()).isEqualByComparingTo(BigDecimal.valueOf(350));
        assertThat(pe.maxPrice()).isEqualByComparingTo(BigDecimal.valueOf(1368));
        // all-shops average: (275+350+1368)/3 = 1993/3 = 664.33
        assertThat(pe.allShopsAvgPrice()).isEqualByComparingTo(new BigDecimal("664.33"));
    }

    @Test
    void exactMatch_withMultipleRows_averagesPrices() {
        CarPartsPriceEntity e1 = entity("renault", 2021, "car hood", 400, 900, 2000);
        CarPartsPriceEntity e2 = entity("renault", 2021, "car hood", 600, 1100, 2400);
        when(repo.findByCarAndYearAndBodyPart("renault", 2021, "car hood"))
                .thenReturn(List.of(e1, e2));

        Optional<PriceEstimate> result = service.estimatePrice("renault", 2021, "car hood");

        assertThat(result).isPresent();
        PriceEstimate pe = result.get();
        assertThat(pe.confidenceLevel()).isEqualTo("HIGH");
        // avg shop2 = (900+1100)/2 = 1000
        assertThat(pe.avgPrice()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        // all-shops avg: row1=(400+900+2000)/3=1100, row2=(600+1100+2400)/3=1366.67 → avg=1233.33
        assertThat(pe.allShopsAvgPrice()).isEqualByComparingTo(new BigDecimal("1233.33"));
    }

    // ── Fallback level 2 — car + part, year ignored (MEDIUM confidence) ───────

    @Test
    void yearFallback_returnsMediumConfidence() {
        when(repo.findByCarAndYearAndBodyPart("toyota", 2020, "front bumper"))
                .thenReturn(List.of());
        CarPartsPriceEntity entity = entity("toyota", 2019, "front bumper", 250, 340, 1300);
        when(repo.findByCarAndBodyPart("toyota", "front bumper"))
                .thenReturn(List.of(entity));

        Optional<PriceEstimate> result = service.estimatePrice("toyota", 2020, "front bumper");

        assertThat(result).isPresent();
        PriceEstimate pe = result.get();
        assertThat(pe.confidenceLevel()).isEqualTo("MEDIUM");
        assertThat(pe.fallbackUsed()).isTrue();
        assertThat(pe.matchedCar()).isEqualTo("toyota");
        assertThat(pe.matchedYear()).isNull();
        // all-shops avg: (250+340+1300)/3 = 630.00
        assertThat(pe.allShopsAvgPrice()).isEqualByComparingTo(new BigDecimal("630.00"));
    }

    @Test
    void yearFallback_usedWhenNullYearPassed() {
        CarPartsPriceEntity entity = entity("dacia", null, "rear bumper", 190, 590, 980);
        when(repo.findByCarAndBodyPart("dacia", "rear bumper"))
                .thenReturn(List.of(entity));

        Optional<PriceEstimate> result = service.estimatePrice("dacia", null, "rear bumper");

        assertThat(result).isPresent();
        assertThat(result.get().confidenceLevel()).isEqualTo("MEDIUM");
    }

    // ── Fallback level 3 — part only, all cars (LOW confidence) ──────────────

    @Test
    void partOnlyFallback_returnsLowConfidence() {
        when(repo.findByCarAndYearAndBodyPart(anyString(), any(), anyString()))
                .thenReturn(List.of());
        when(repo.findByCarAndBodyPart(anyString(), anyString()))
                .thenReturn(List.of());
        CarPartsPriceEntity entity = entity("honda", 2019, "front bumper", 450, 750, 1100);
        when(repo.findByBodyPart("front bumper"))
                .thenReturn(List.of(entity));

        Optional<PriceEstimate> result = service.estimatePrice("toyota", 2020, "front bumper");

        assertThat(result).isPresent();
        PriceEstimate pe = result.get();
        assertThat(pe.confidenceLevel()).isEqualTo("LOW");
        assertThat(pe.fallbackUsed()).isTrue();
        assertThat(pe.matchedCar()).isNull();
        assertThat(pe.matchedYear()).isNull();
        // all-shops avg: (450+750+1100)/3 = 766.67
        assertThat(pe.allShopsAvgPrice()).isEqualByComparingTo(new BigDecimal("766.67"));
    }

    @Test
    void noDataAnywhere_returnsEmpty() {
        when(repo.findByCarAndYearAndBodyPart(anyString(), any(), anyString()))
                .thenReturn(List.of());
        when(repo.findByCarAndBodyPart(anyString(), anyString()))
                .thenReturn(List.of());
        when(repo.findByBodyPart(anyString()))
                .thenReturn(List.of());

        Optional<PriceEstimate> result = service.estimatePrice("toyota", 2020, "unknown_part_xyz");

        assertThat(result).isEmpty();
    }

    // ── normalizeBodyPart ─────────────────────────────────────────────────────

    @Test
    void frenchElementName_normalizesToDbPart() {
        assertThat(service.normalizeBodyPart("pare-choc avant")).isEqualTo("front bumper");
        assertThat(service.normalizeBodyPart("bouclier avant")).isEqualTo("front bumper");
        assertThat(service.normalizeBodyPart("bouclier arrière")).isEqualTo("rear bumper");
        assertThat(service.normalizeBodyPart("capot")).isEqualTo("car hood");
        assertThat(service.normalizeBodyPart("phare avant gauche")).isEqualTo("headlight (l)");
        assertThat(service.normalizeBodyPart("coffre")).isEqualTo("car boot");
    }

    @Test
    void fenderDirection_resolvedCorrectly() {
        // Exact aliases
        assertThat(service.normalizeBodyPart("fender right")).isEqualTo("fender (f/r)");
        assertThat(service.normalizeBodyPart("fender left")).isEqualTo("fender (f/l)");
        assertThat(service.normalizeBodyPart("right fender")).isEqualTo("fender (f/r)");
        assertThat(service.normalizeBodyPart("left fender")).isEqualTo("fender (f/l)");
        // French keyword fallback — right fender must NOT return (f/l)
        assertThat(service.normalizeBodyPart("aile avant droite")).isEqualTo("fender (f/r)");
        assertThat(service.normalizeBodyPart("aile avant gauche")).isEqualTo("fender (f/l)");
        // flanc aliases
        assertThat(service.normalizeBodyPart("flanc droit")).isEqualTo("fender (f/r)");
        assertThat(service.normalizeBodyPart("flanc gauche")).isEqualTo("fender (f/l)");
        assertThat(service.normalizeBodyPart("flanc avant droit")).isEqualTo("fender (f/r)");
        assertThat(service.normalizeBodyPart("flanc avant gauche")).isEqualTo("fender (f/l)");
    }

    @Test
    void headlightDirection_resolvedCorrectly() {
        assertThat(service.normalizeBodyPart("left headlight")).isEqualTo("headlight (l)");
        assertThat(service.normalizeBodyPart("right headlight")).isEqualTo("headlight (r)");
        assertThat(service.normalizeBodyPart("headlight right")).isEqualTo("headlight (r)");
        assertThat(service.normalizeBodyPart("phare avant droit")).isEqualTo("headlight (r)");
    }

    // ── shop1 nullable handling ───────────────────────────────────────────────

    @Test
    void shop1Null_minPriceIsNull_allShopsAvgUsesShop2And3Only() {
        CarPartsPriceEntity entity = entityNoShop1("toyota", 2021, "windshield", 800, 4000);
        when(repo.findByCarAndYearAndBodyPart("toyota", 2021, "windshield"))
                .thenReturn(List.of(entity));

        Optional<PriceEstimate> result = service.estimatePrice("toyota", 2021, "windshield");

        assertThat(result).isPresent();
        PriceEstimate pe = result.get();
        assertThat(pe.minPrice()).isNull();
        assertThat(pe.avgPrice()).isEqualByComparingTo(BigDecimal.valueOf(800));
        // null shop1 → row uses only shop2+shop3: (800+4000)/2 = 2400
        assertThat(pe.allShopsAvgPrice()).isEqualByComparingTo(new BigDecimal("2400.00"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CarPartsPriceEntity entity(String car, Integer year, String bodyPart,
                                       double shop1, double shop2, double shop3) {
        CarPartsPriceEntity e = new CarPartsPriceEntity();
        e.setCar(car);
        e.setYear(year);
        e.setBodyPart(bodyPart);
        e.setShop1Price(BigDecimal.valueOf(shop1));
        e.setShop2Price(BigDecimal.valueOf(shop2));
        e.setShop3Price(BigDecimal.valueOf(shop3));
        return e;
    }

    private CarPartsPriceEntity entityNoShop1(String car, Integer year, String bodyPart,
                                              double shop2, double shop3) {
        CarPartsPriceEntity e = new CarPartsPriceEntity();
        e.setCar(car);
        e.setYear(year);
        e.setBodyPart(bodyPart);
        e.setShop1Price(null);
        e.setShop2Price(BigDecimal.valueOf(shop2));
        e.setShop3Price(BigDecimal.valueOf(shop3));
        return e;
    }
}
