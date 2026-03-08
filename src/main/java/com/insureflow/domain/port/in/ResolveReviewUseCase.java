package com.insureflow.domain.port.in;

import com.insureflow.domain.model.HumanReviewTask.ReviewStatus;
import java.util.UUID;

public interface ResolveReviewUseCase {
    void resolve(UUID reviewTaskId, ReviewStatus decision,
                 String assignedTo, String adjusterNotes);
}