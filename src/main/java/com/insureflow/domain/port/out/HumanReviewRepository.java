package com.insureflow.domain.port.out;

import com.insureflow.domain.model.HumanReviewTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HumanReviewRepository {
    HumanReviewTask save(HumanReviewTask task);
    Optional<HumanReviewTask> findById(UUID id);
    Optional<HumanReviewTask> findByClaimId(UUID claimId);
    List<HumanReviewTask> findAllPending();
}