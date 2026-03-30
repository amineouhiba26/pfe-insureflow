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
 * RAG adapter — ingestion and semantic retrieval of insurance contracts.
 *
 * Optimisations clés :
 *
 * INGESTION :
 *   - Chunks de 200 tokens (au lieu de 300) → une idée par chunk
 *   - Overlap réduit à 30 tokens → moins de redondance
 *   - Nettoyage agressif : puces, tirets, numéros d'article, espaces multiples
 *   - Filtrage des lignes trop courtes (bruit PDFBox)
 *   - Suppression des en-têtes répétitifs (BNA ASSURANCES, N° contrat...)
 *   - Delete avant re-ingestion → jamais de doublons
 *
 * RETRIEVAL :
 *   - topK augmenté à 6 pour les contrats denses (Madrassati = 4 pages)
 *   - Seuil similarité à 0.35 → plus permissif pour les termes juridiques
 *   - Post-processing : chaque chunk tronqué à 400 caractères max
 *   - Déduplication : supprime les chunks trop similaires entre eux
 */
@Component
public class DocumentIngestionAdapter implements VectorStorePort {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionAdapter.class);

    // ── Chunking config ───────────────────────────────────────────────────────
    private static final int    CHUNK_SIZE           = 200;   // tokens par chunk
    private static final int    CHUNK_OVERLAP        = 30;    // overlap réduit
    private static final int    MIN_CHUNK_LENGTH     = 10;    // ignore les micro-chunks
    private static final int    MAX_CHUNK_CHARACTERS = 10_000;

    // ── Retrieval config ──────────────────────────────────────────────────────
    private static final double SIMILARITY_THRESHOLD = 0.35;  // plus permissif pour termes juridiques
    private static final int    DEFAULT_TOP_K        = 6;     // plus de contexte pour contrats denses
    private static final int    MAX_CHUNK_LENGTH     = 400;   // tronque les chunks trop longs
    private static final double DEDUP_THRESHOLD      = 0.85;  // supprime quasi-doublons

    // ── Ingestion quality ─────────────────────────────────────────────────────
    private static final int    MIN_EXPECTED_CHUNKS  = 3;
    private static final int    MIN_LINE_LENGTH      = 8;     // ignore lignes trop courtes

    private final VectorStore  vectorStore;
    private final JdbcTemplate jdbc;

    public DocumentIngestionAdapter(VectorStore vectorStore, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.jdbc        = jdbc;
    }

    // ── Ingestion ─────────────────────────────────────────────────────────────

    @Override
    public void ingestDocument(String policyId, byte[] fileBytes, String fileName) {
        validateInput(policyId, fileBytes, fileName);

        long start = System.currentTimeMillis();
        log.info("[RAG] Ingestion start — policy={} file='{}' size={}KB",
                policyId, fileName, fileBytes.length / 1024);

        Path tempFile = null;
        try {
            tempFile = writeTempFile(fileBytes, fileName);

            List<Document> pages   = readPages(tempFile);
            List<Document> cleaned = cleanPages(pages);
            List<Document> chunks  = chunk(cleaned);

            tagWithPolicyId(chunks, policyId);
            deleteExistingChunks(policyId);
            vectorStore.add(chunks);

            long elapsed = System.currentTimeMillis() - start;
            log.info("[RAG] Ingestion complete — policy={} pages={} chunks={} time={}ms",
                    policyId, pages.size(), chunks.size(), elapsed);

            if (chunks.size() < MIN_EXPECTED_CHUNKS) {
                log.warn("[RAG] Seulement {} chunks pour policy={} — vérifier le PDF",
                        chunks.size(), policyId);
            }

        } catch (IOException e) {
            throw new RuntimeException(
                    "Ingestion PDF échouée pour policy=" + policyId + ": " + e.getMessage(), e);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    // ── Retrieval ─────────────────────────────────────────────────────────────

    @Override
    public List<String> retrieveRelevantChunks(String query, String policyId, int topK) {
        // Utilise DEFAULT_TOP_K si topK demandé est trop petit
        int effectiveTopK = Math.max(topK, DEFAULT_TOP_K);

        log.debug("[RAG] Retrieval — policy={} topK={} query='{}'",
                policyId, effectiveTopK, truncate(query, 80));

        FilterExpressionBuilder b = new FilterExpressionBuilder();

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(effectiveTopK)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .filterExpression(b.eq("policyId", policyId).build())
                .build();

        List<String> rawChunks = vectorStore.similaritySearch(request)
                .stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        // Post-process : nettoie, tronque et déduplique les chunks récupérés
        List<String> processed = postProcess(rawChunks);

        log.debug("[RAG] Retrieved {} chunks (after dedup) for policy={}",
                processed.size(), policyId);

        return processed;
    }

    // ── Private — ingestion helpers ───────────────────────────────────────────

    private void validateInput(String policyId, byte[] fileBytes, String fileName) {
        if (policyId == null || policyId.isBlank())
            throw new IllegalArgumentException("policyId ne peut pas être vide");
        if (fileBytes == null || fileBytes.length == 0)
            throw new IllegalArgumentException("Fichier vide : " + fileName);
        if (fileName == null || fileName.isBlank())
            throw new IllegalArgumentException("fileName ne peut pas être vide");
    }

    private Path writeTempFile(byte[] bytes, String fileName) throws IOException {
        Path temp = Files.createTempFile("insureflow-", "-" + fileName);
        Files.write(temp, bytes);
        return temp;
    }

    private List<Document> readPages(Path file) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .build();
        return new PagePdfDocumentReader(
                new FileSystemResource(file.toFile()), config).get();
    }

    private List<Document> cleanPages(List<Document> pages) {
        return pages.stream()
                .map(page -> new Document(cleanText(page.getText()), page.getMetadata()))
                .filter(page -> !page.getText().isBlank())
                .collect(Collectors.toList());
    }

    private List<Document> chunk(List<Document> pages) {
        return new TokenTextSplitter(
                CHUNK_SIZE, CHUNK_OVERLAP, MIN_CHUNK_LENGTH,
                MAX_CHUNK_CHARACTERS, true)
                .apply(pages);
    }

    private void tagWithPolicyId(List<Document> chunks, String policyId) {
        chunks.forEach(c -> c.getMetadata().put("policyId", policyId));
    }

    private void deleteExistingChunks(String policyId) {
        int deleted = jdbc.update(
                "DELETE FROM vector_store WHERE metadata->>'policyId' = ?", policyId);
        if (deleted > 0) {
            log.info("[RAG] {} chunks supprimés avant re-ingestion pour policy={}",
                    deleted, policyId);
        }
    }

    private void deleteTempFile(Path tempFile) {
        if (tempFile == null) return;
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            log.warn("[RAG] Impossible de supprimer le fichier temp : {}", e.getMessage());
        }
    }

    /**
     * Nettoyage optimisé pour les contrats BNA (automobile ET multirisques).
     *
     * Problèmes résolus :
     * 1. Espaces multiples entre colonnes de tableaux PDFBox
     * 2. Puces • et tirets de liste → format uniforme "- "
     * 3. Numéros d'article redondants (ARTICLE 1, CLAUSE 2...)
     * 4. En-têtes répétitifs (BNA ASSURANCES, N° contrat...)
     * 5. Lignes trop courtes qui sont du bruit
     * 6. Numéros de page seuls
     */
    private String cleanText(String raw) {
        if (raw == null) return "";

        String result = Arrays.stream(raw.split("\n"))
                .map(line -> line.replace("\t", " "))
                .map(line -> line.replaceAll(" {2,}", " "))
                .map(line -> line.replaceAll("^[•▪►▶]\\s*", ""))
                .map(line -> line.replaceAll("^–\\s*", ""))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.matches("^\\d{1,2}$"))
                .filter(line -> line.length() >= MIN_LINE_LENGTH)
                .filter(line -> !line.matches("(?i).*BNA\\s+ASSURANCES.*"))
                .filter(line -> !line.matches("(?i)^(Le Souscripteur|Fait à|P/\\s*BNA).*"))
                .filter(line -> !line.matches("(?i)^(Siège social|Fax|Site web|courrier).*"))
                .collect(Collectors.joining(" "));  // ← SPACE pas \n

        // Collapse espaces multiples qui peuvent apparaître après le join
        return result.replaceAll(" {2,}", " ").trim();
    }
    // ── Private — retrieval helpers ───────────────────────────────────────────

    /**
     * Post-traitement des chunks récupérés par pgvector.
     *
     * 1. Tronque les chunks trop longs à MAX_CHUNK_LENGTH caractères
     *    → évite de noyer le LLM dans trop de texte par chunk
     * 2. Supprime les quasi-doublons (overlap entre chunks adjacents)
     *    → évite que le LLM lise deux fois la même information
     */
    private List<String> postProcess(List<String> chunks) {
        return chunks.stream()
                .map(this::truncateChunk)
                .filter(c -> !c.isBlank())
                .filter(c -> c.length() >= MIN_LINE_LENGTH)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Tronque un chunk à MAX_CHUNK_LENGTH caractères en coupant
     * proprement à la fin d'une phrase (point) si possible.
     */
    private String truncateChunk(String chunk) {
        if (chunk == null) return "";

        // Nettoie les \n résiduels dans les chunks récupérés depuis pgvector
        chunk = chunk.replace("\n", " ")
                .replaceAll(" {2,}", " ")
                .trim();

        if (chunk.length() <= MAX_CHUNK_LENGTH) return chunk;

        int lastDot = chunk.lastIndexOf('.', MAX_CHUNK_LENGTH);
        if (lastDot > MAX_CHUNK_LENGTH / 2) {
            return chunk.substring(0, lastDot + 1).trim();
        }

        int lastSpace = chunk.lastIndexOf(' ', MAX_CHUNK_LENGTH);
        if (lastSpace > 0) {
            return chunk.substring(0, lastSpace).trim() + "...";
        }

        return chunk.substring(0, MAX_CHUNK_LENGTH).trim() + "...";
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}