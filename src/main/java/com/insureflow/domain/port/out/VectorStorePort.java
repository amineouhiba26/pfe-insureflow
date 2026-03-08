package com.insureflow.domain.port.out;

import java.util.List;

/**
 * Outbound port — the domain says "store this document" and "find relevant chunks".
 * The Spring AI pgvector adapter implements this.
 * Domain code never imports spring-ai.
 */
public interface VectorStorePort {
    void ingestDocument(String policyId, byte[] fileBytes, String fileName);
    List<String> retrieveRelevantChunks(String query, String policyId, int topK);
}