package com.insureflow.infrastructure.ai.rag;

import com.insureflow.domain.port.out.VectorStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implements VectorStorePort using Spring AI + pgvector.
 *
 * INGESTION flow (when admin uploads a PDF contract):
 * 1. ByteArrayResource wraps the raw PDF bytes so Spring AI can read them
 * 2. PagePdfDocumentReader reads each page as a Document
 * 3. TokenTextSplitter splits pages into 500-token chunks with 50-token overlap
 *    Why overlap? So sentences at chunk boundaries aren't cut in half —
 *    the end of chunk N and start of chunk N+1 share 50 tokens.
 * 4. Each chunk gets metadata: {"policyId": "uuid-here"}
 * 5. VectorStore embeds each chunk using mxbai-embed-large (1024 dimensions)
 *    and stores the vector + metadata in the vector_store table in Postgres
 *
 * RETRIEVAL flow (when ValidatorAgent needs contract sections):
 * 1. Query = claim description
 * 2. FilterExpressionBuilder adds: WHERE metadata->>'policyId' = 'uuid-here'
 *    This ensures we ONLY retrieve chunks from THIS client's contract
 * 3. pgvector finds the top-K chunks whose embeddings are most similar
 *    to the query embedding (cosine distance)
 * 4. Returns the raw text of those chunks
 *
 * Why 500 tokens / 50 overlap?
 * 500 tokens ≈ 375 words ≈ one or two contract articles.
 * Small enough to fit many chunks in the LLM context.
 * Large enough to contain meaningful coverage information.
 */
@Component
public class DocumentIngestionAdapter implements VectorStorePort {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionAdapter.class);

    private final VectorStore vectorStore;

    public DocumentIngestionAdapter(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void ingestDocument(String policyId, byte[] fileBytes, String fileName) {
        log.info("[RAG] Ingesting document '{}' for policyId={}", fileName, policyId);

        // Wrap bytes as a Spring resource so PDF reader can process it
        ByteArrayResource resource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() { return fileName; }
        };

        // Read PDF page by page
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .build();
        PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
        List<Document> pages = reader.get();

        // Split into 500-token chunks with 50-token overlap
        TokenTextSplitter splitter = new TokenTextSplitter(500, 50, 5, 10000, true);
        List<Document> chunks = splitter.apply(pages);

        // Tag each chunk with policyId so we can filter by it at query time
        chunks.forEach(chunk ->
                chunk.getMetadata().put("policyId", policyId));

        // Embed and store in pgvector
        vectorStore.add(chunks);

        log.info("[RAG] Stored {} chunks for policyId={}", chunks.size(), policyId);
    }

    @Override
    public List<String> retrieveRelevantChunks(String query, String policyId, int topK) {
        log.debug("[RAG] Retrieving top-{} chunks for policyId={}", topK, policyId);

        // Build a metadata filter: only return chunks from this policy's contract
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var filter = b.eq("policyId", policyId).build();

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filter)
                .build();

        List<Document> results = vectorStore.similaritySearch(request);

        return results.stream()
                .map(Document::getText)
                .collect(Collectors.toList());
    }
}