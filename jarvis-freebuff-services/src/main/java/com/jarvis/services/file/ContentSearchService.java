package com.jarvis.services.file;

import com.jarvis.api.ServiceException;
import com.jarvis.api.StructuredError;
import com.jarvis.api.ErrorCode;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public final class ContentSearchService {

    public static class DocumentResult {
        private final Path path;
        private final int matchCount;
        private final Optional<String> snippet;
        private final String extractedText;

        public DocumentResult(Path path, int matchCount, Optional<String> snippet, String extractedText) {
            this.path = path;
            this.matchCount = matchCount;
            this.snippet = snippet;
            this.extractedText = extractedText;
        }

        public Path path() {
            return path;
        }

        public int matchCount() {
            return matchCount;
        }

        public Optional<String> snippet() {
            return snippet;
        }

        public String extractedText() {
            return extractedText;
        }
    }

    private static final int MAX_DOCUMENTS = 100;
    private static final int MAX_MATCHES_PER_DOC = 50;
    private static final int MAX_SNIPPET_LENGTH = 200;
    private static final int MAX_EXTRACTED_CHARS = 500_000;

    private final DocumentExtractionService extractionService;

    public ContentSearchService() {
        this(new DocumentExtractionService());
    }

    public ContentSearchService(DocumentExtractionService extractionService) {
        this.extractionService = java.util.Objects.requireNonNull(extractionService, "extractionService");
    }

    public SearchResult searchContent(Collection<Path> files, String query) throws ServiceException {
        java.util.Objects.requireNonNull(files, "files");
        java.util.Objects.requireNonNull(query, "query");

        if (query.isBlank()) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    "Search query must not be blank",
                    Optional.empty()));
        }

        List<DocumentResult> results = files.stream()
                .limit(MAX_DOCUMENTS)
                .map(path -> searchSingleDocument(path, query))
                .collect(Collectors.toList());

        if (files.size() > MAX_DOCUMENTS) {
            return new SearchResult(results, "Document limit of " + MAX_DOCUMENTS + " applied");
        }

        return new SearchResult(results);
    }

    private DocumentResult searchSingleDocument(Path file, String query) {
        try {
            DocumentText doc = extractionService.extract(file);
            if (doc.status() != ExtractionStatus.SUCCESS) {
                return new DocumentResult(file, 0, Optional.empty(), doc.text());
            }

            String lowerText = doc.text().toLowerCase(Locale.ROOT);
            String lowerQuery = query.toLowerCase(Locale.ROOT);

            int matchCount = countMatches(lowerText, lowerQuery);

            if (matchCount == 0) {
                return new DocumentResult(file, 0, Optional.empty(), doc.text());
            }

            String snippet = extractSnippet(doc.text(), lowerText, lowerQuery);

            return new DocumentResult(file, matchCount, Optional.of(snippet), doc.text());

        } catch (Exception e) {
            return new DocumentResult(file, 0, Optional.empty(), "Extraction error: " + e.getMessage());
        }
    }

    private int countMatches(String text, String query) {
        int count = 0;
        int index = 0;
        while (index >= 0 && count < MAX_MATCHES_PER_DOC) {
            index = text.indexOf(query, index);
            if (index >= 0) {
                count++;
                index++; // move past this match to find next
            }
        }
        return count;
    }

    private String extractSnippet(String text, String lowerText, String lowerQuery) {
        int index = lowerText.indexOf(lowerQuery);
        if (index < 0) {
            return "";
        }

        int start = Math.max(0, index - MAX_SNIPPET_LENGTH / 2);
        int end = Math.min(text.length(), index + lowerQuery.length() + MAX_SNIPPET_LENGTH / 2);

        // Don't split in the middle of a word if possible
        if (start > 0) {
            int spaceBefore = text.lastIndexOf(' ', end);
            if (spaceBefore > start) {
                start = spaceBefore + 1;
            }
        }

        String snippet = text.substring(start, end);
        if (snippet.length() > MAX_SNIPPET_LENGTH) {
            snippet = snippet.substring(0, MAX_SNIPPET_LENGTH) + "...";
        }

        return snippet;
    }

    public static class SearchResult {
        private final List<DocumentResult> documentResults;
        private final String appliedLimits;

        public SearchResult(List<DocumentResult> documentResults) {
            this.documentResults = documentResults;
            this.appliedLimits = "";
        }

        public SearchResult(List<DocumentResult> documentResults, String appliedLimits) {
            this.documentResults = documentResults;
            this.appliedLimits = appliedLimits;
        }

        public List<DocumentResult> getDocumentResults() {
            return documentResults;
        }

        public String appliedLimits() {
            return appliedLimits;
        }

        public int totalMatches() {
            return documentResults.stream()
                    .mapToInt(DocumentResult::matchCount)
                    .sum();
        }
    }
}