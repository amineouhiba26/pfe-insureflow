package com.insureflow.web.admin;

import com.insureflow.application.dto.AnalyticsSummaryDTO;
import com.insureflow.application.dto.LabelValueDTO;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.infrastructure.persistence.repository.ClaimJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    @Mock
    private ClaimJpaRepository claimRepository;

    @InjectMocks
    private AnalyticsController analyticsController;

    @Test
    void claimsByType_returnsTwoHundred() {
        when(claimRepository.countGroupedByType())
                .thenReturn(Collections.singletonList(new Object[]{"VEHICLE_DAMAGE", 5L}));

        ResponseEntity<List<LabelValueDTO>> response = analyticsController.claimsByType();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).label()).isEqualTo("VEHICLE_DAMAGE");
        assertThat(response.getBody().get(0).value()).isEqualTo(5L);
    }

    @Test
    void claimsByStatus_returnsTwoHundred() {
        when(claimRepository.countGroupedByStatus())
                .thenReturn(List.of(
                        new Object[]{"APPROVED", 10L},
                        new Object[]{"REJECTED", 3L}
                ));

        ResponseEntity<List<LabelValueDTO>> response = analyticsController.claimsByStatus();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void summary_returnsCorrectTotals() {
        when(claimRepository.count()).thenReturn(100L);
        when(claimRepository.countByStatus(ClaimStatus.APPROVED)).thenReturn(60L);
        when(claimRepository.countByStatus(ClaimStatus.REJECTED)).thenReturn(20L);
        when(claimRepository.countByStatus(ClaimStatus.PENDING_REVIEW)).thenReturn(15L);
        when(claimRepository.findAvgEstimatedCost()).thenReturn(3500.0);
        when(claimRepository.countHighRiskClaims()).thenReturn(8L);

        ResponseEntity<AnalyticsSummaryDTO> response = analyticsController.summary();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        AnalyticsSummaryDTO dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.totalClaims()).isEqualTo(100L);
        assertThat(dto.approvedClaims()).isEqualTo(60L);
        assertThat(dto.rejectedClaims()).isEqualTo(20L);
        assertThat(dto.pendingClaims()).isEqualTo(15L);
        assertThat(dto.fraudFlaggedClaims()).isEqualTo(8L);
        assertThat(dto.avgEstimatedAmount()).isEqualTo(3500.0);
    }

    @Test
    void summary_nullAvgCost_defaultsToZero() {
        when(claimRepository.count()).thenReturn(0L);
        when(claimRepository.countByStatus(ClaimStatus.APPROVED)).thenReturn(0L);
        when(claimRepository.countByStatus(ClaimStatus.REJECTED)).thenReturn(0L);
        when(claimRepository.countByStatus(ClaimStatus.PENDING_REVIEW)).thenReturn(0L);
        when(claimRepository.findAvgEstimatedCost()).thenReturn(null);
        when(claimRepository.countHighRiskClaims()).thenReturn(0L);

        ResponseEntity<AnalyticsSummaryDTO> response = analyticsController.summary();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().avgEstimatedAmount()).isEqualTo(0.0);
    }

    @Test
    void agentPerformance_returnsFourAgents() {
        ResponseEntity<?> response = analyticsController.agentPerformance();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) response.getBody()).hasSize(4);
    }
}
