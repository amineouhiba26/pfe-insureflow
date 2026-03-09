// ReviewDecisionRequest.java
package com.insureflow.application.dto;

import com.insureflow.domain.model.HumanReviewTask.ReviewStatus;
import jakarta.validation.constraints.NotNull;

public class ReviewDecisionRequest {

    @NotNull(message = "Decision is required")
    private ReviewStatus decision;

    private String assignedTo;
    private String adjusterNotes;

    public ReviewDecisionRequest() {}

    public ReviewStatus getDecision() { return decision; }
    public void setDecision(ReviewStatus decision) { this.decision = decision; }
    public String getAssignedTo() { return assignedTo; }
    public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
    public String getAdjusterNotes() { return adjusterNotes; }
    public void setAdjusterNotes(String adjusterNotes) { this.adjusterNotes = adjusterNotes; }
}