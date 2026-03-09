// SubmitClaimRequest.java
package com.insureflow.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public class SubmitClaimRequest {

    @NotNull(message = "Client ID is required")
    private UUID clientId;

    @NotNull(message = "Policy ID is required")
    private UUID policyId;

    @NotBlank(message = "Description is required")
    private String description;

    private List<String> photoUrls;

    public SubmitClaimRequest() {}

    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getPhotoUrls() { return photoUrls; }
    public void setPhotoUrls(List<String> photoUrls) { this.photoUrls = photoUrls; }
}