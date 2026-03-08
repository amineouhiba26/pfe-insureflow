package com.insureflow.domain.port.in;

import java.util.List;

public interface IngestContractUseCase {
    void ingest(String policyId, byte[] fileBytes, String fileName);
    List<String> search(String policyId, String query);
}