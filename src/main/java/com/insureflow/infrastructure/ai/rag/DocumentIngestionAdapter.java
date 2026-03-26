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
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG adapter for insurance contract ingestion and semantic retrieval.
 *
 * Ingestion pipeline:
 *   PDF bytes → temp file → PDFBox pages → text cleaning → token chunks
 *   → policyId metadata → mxbai-embed-large (1024D) → pgvector
 *
 * Retrieval pipeline:
 *   query text → embed → cosine search (filtered by policyId) → top-K chunks
 *
 * Design decisions:
 *   - 300-token chunks: BNA contracts are 2 pages. Smaller chunks = more
 *     precise retrieval. Each chunk maps to roughly one contract section.
 *   - 50-token overlap: prevents sentences from being severed at boundaries.
 *   - policyId filter: each client only sees their own contract chunks,
 *     never another client's.
 *   - Delete before re-ingest: uploading a new version of a contract
 *     replaces the old chunks cleanly instead of duplicating them.
 *   - 0.4 similarity threshold: filters out noise chunks that are
 *     semantically too far from the query.
 */
@Component
public class DocumentIngestionAdapter implements VectorStorePort {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionAdapter.class);

    private static final int    CHUNK_SIZE          = 300;
    private static final int    CHUNK_OVERLAP        = 50;
    private static final int    MIN_CHUNK_LENGTH     = 5;
    private static final int    MAX_CHUNK_CHARACTERS = 10_000;
    private static final double SIMILARITY_THRESHOLD = 0.4;
    private static final int    MIN_EXPECTED_CHUNKS  = 2;

    private final VectorStore  vectorStore;
    private final JdbcTemplate jdbc;

    public DocumentIngestionAdapter(VectorStore vectorStore, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.jdbc        = jdbc;
    }

    // ── Ingestion ────────────────────────────────────────────────────────────

    @Override
    public void ingestDocument(String policyId, byte[] fileBytes, String fileName) {
        validateInput(policyId, fileBytes, fileName);

        long start = System.currentTimeMillis();
        log.info("[RAG] Starting ingestion — policy={} file='{}' size={}KB",
                policyId, fileName, fileBytes.length / 1024);

        Path tempFile = null;
        try {
            tempFile = writeTempFile(fileBytes, fileName);

            List<Document> pages   = readPages(tempFile);
            List<Document> cleaned = cleanPages(pages);
            List<Document> chunks  = chunk(cleaned);

            tagWithPolicyId(chunks, policyId);

            // Delete any existing chunks for this policy before storing new ones
            // This ensures re-uploading a contract replaces rather than duplicates
            deleteExistingChunks(policyId);

            vectorStore.add(chunks);

            long elapsed = System.currentTimeMillis() - start;
            log.info("[RAG] Ingestion complete — policy={} pages={} chunks={} time={}ms",
                    policyId, pages.size(), chunks.size(), elapsed);

            if (chunks.size() < MIN_EXPECTED_CHUNKS) {
                log.warn("[RAG] Low chunk count ({}) for policy={} — PDF may have extraction issues",
                        chunks.size(), policyId);
            }

        } catch (IOException e) {
            throw new RuntimeException(
                    "PDF ingestion failed for policy " + policyId + ": " + e.getMessage(), e);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    // ── Retrieval ─────────────────────────────────────────────────────────────

    @Override
    public List<String> retrieveRelevantChunks(String query, String policyId, int topK) {
        log.debug("[RAG] Retrieving top-{} chunks — policy={} query='{}'",
                topK, policyId, truncate(query, 60));

        FilterExpressionBuilder b = new FilterExpressionBuilder();

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .filterExpression(b.eq("policyId", policyId).build())
                .build();

        List<String> chunks = vectorStore.similaritySearch(request)
                .stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        log.debug("[RAG] Retrieved {} chunks for policy={}", chunks.size(), policyId);
        return chunks;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void validateInput(String policyId, byte[] fileBytes, String fileName) {
        if (policyId == null || policyId.isBlank())
            throw new IllegalArgumentException("policyId must not be blank");
        if (fileBytes == null || fileBytes.length == 0)
            throw new IllegalArgumentException("File bytes are empty for: " + fileName);
        if (fileName == null || fileName.isBlank())
            throw new IllegalArgumentException("fileName must not be blank");
    }

    private Path writeTempFile(byte[] fileBytes, String fileName) throws IOException {
        Path temp = Files.createTempFile("insureflow-", "-" + fileName);
        Files.write(temp, fileBytes);
        return temp;
    }

    private List<Document> readPages(Path file) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .build();
        return new PagePdfDocumentReader(new FileSystemResource(file.toFile()), config).get();
    }

    private List<Document> cleanPages(List<Document> pages) {
        return pages.stream()
                .map(page -> new Document(cleanText(page.getText()), page.getMetadata()))
                .filter(page -> !page.getText().isBlank())
                .collect(Collectors.toList());
    }

    private List<Document> chunk(List<Document> pages) {
        return new TokenTextSplitter(
                CHUNK_SIZE, CHUNK_OVERLAP, MIN_CHUNK_LENGTH, MAX_CHUNK_CHARACTERS, true)
                .apply(pages);
    }

    private void tagWithPolicyId(List<Document> chunks, String policyId) {
        chunks.forEach(chunk -> chunk.getMetadata().put("policyId", policyId));
    }

    /**
     * Deletes all pgvector entries for a given policyId.
     * Called before re-ingestion so we never accumulate duplicate chunks.
     */
    private void deleteExistingChunks(String policyId) {
        int deleted = jdbc.update(
                "DELETE FROM vector_store WHERE metadata->>'policyId' = ?", policyId);
        if (deleted > 0) {
            log.info("[RAG] Deleted {} existing chunks for policy={} before re-ingestion",
                    deleted, policyId);
        }
    }

    private void deleteTempFile(Path tempFile) {
        if (tempFile == null) return;
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            log.warn("[RAG] Could not delete temp file {}: {}", tempFile, e.getMessage());
        }
    }

    /**
     * Cleans PDFBox text extracted from table-based BNA contracts.
     *
     * PDFBox reads table columns with large whitespace gaps:
     *   "Dommage     collision          8 477,528       5 %      629.033"
     *
     * After cleaning:
     *   "Dommage collision 8 477,528 5 % 629.033"
     */
    private String cleanText(String raw) {
        if (raw == null) return "";

        return Arrays.stream(raw.split("\n"))
                .map(line -> line.replace("\t", " "))
                .map(line -> line.replaceAll(" {2,}", " "))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.matches("^\\d{1,2}$"))  // remove lone page numbers
                .collect(Collectors.joining("\n"));
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}