package com.jade.services.search;

import com.jade.api.CancellationToken;
import com.jade.api.ErrorCode;
import com.jade.api.FileMatch;
import com.jade.api.FileSearchQuery;
import com.jade.api.FileSearchResult;
import com.jade.api.FileSearchService;
import com.jade.api.ServiceException;
import com.jade.api.StructuredError;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Bounded filename search inside explicitly configured roots.
 *
 * <p>Contract obligations (docs/CONTRACTS.md, docs/PROJECT_DECISIONS.md):
 * only constructor roots are scanned, at most 50 matches are returned, the
 * walk stops after 10,000 visited regular files, symbolic links are never
 * followed, and the result reports which bound stopped the scan.
 * Inaccessible entries are skipped and reported as warnings; a complete
 * empty search stays distinct from a partial or cancelled one (cancellation
 * surfaces as {@link ErrorCode#CANCELLED}, a fully inaccessible root set as
 * {@link ErrorCode#ACCESS_DENIED}, and the flags
 * {@link FileSearchResult#scanLimitReached()} /
 * {@link FileSearchResult#resultLimitReached()} mark partial scans).</p>
 */
public final class FileSystemFileSearchService implements FileSearchService {

    /** Sprint 1 fixed limits; mirror docs/PROJECT_DECISIONS.md. */
    public static final int SPRINT_MAX_RESULTS = 50;
    public static final int SPRINT_SCAN_LIMIT = 10_000;

    /** Keeps warning memory bounded on pathological trees. */
    static final int MAX_WARNINGS = 200;

    private static final Comparator<Path> ENTRY_ORDER = Comparator
            .comparing(Path::toString, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Comparator.naturalOrder());

    private static final Comparator<FileMatch> MATCH_ORDER = Comparator
            .comparing(FileMatch::fileName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(match -> match.path().toString(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(FileMatch::path, Comparator.naturalOrder());

    private static final int PROGRESS_EVERY = 250;

    private final List<Path> roots;
    private final List<String> warnings = new ArrayList<>();

    public FileSystemFileSearchService(List<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("at least one search root is required");
        }
        List<Path> normalized = new ArrayList<>();
        for (Path root : roots) {
            if (root == null) {
                throw new IllegalArgumentException("search roots must not be null");
            }
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException(
                        "search root is not an existing directory: " + root);
            }
            normalized.add(root.toAbsolutePath().normalize());
        }
        this.roots = List.copyOf(normalized);
    }

    public List<Path> roots() {
        return roots;
    }

    /**
     * Warnings collected during the most recent {@link #search} call.
     * The frozen {@link FileSearchResult} record has no warning field, so
     * skipped-entry details are surfaced here for the gateway/UI.
     */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    @Override
    public FileSearchResult search(
            FileSearchQuery query,
            CancellationToken cancellation,
            LongConsumer visitedFileProgress) throws ServiceException {
        java.util.Objects.requireNonNull(query, "query");
        java.util.Objects.requireNonNull(cancellation, "cancellation");
        FileSearchQuery effective = clampQuery(query);
        Set<String> extensions = normalizeExtensions(effective.extensions());

        warnings.clear();

        List<FileMatch> matches = new ArrayList<>();
        Set<Path> visited = new LinkedHashSet<>();
        boolean resultLimitReached = false;
        boolean scanLimitReached = false;
        boolean cancelled = false;
        int scannedRoots = 0;

        outer:
        for (Path root : roots) {
            if (cancellation.isCancellationRequested()) {
                cancelled = true;
                break;
            }
            Deque<Path> queue = new ArrayDeque<>();
            try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(root)) {
                scannedRoots++;
                List<Path> sortedEntries = new ArrayList<>();
                for (Path entry : rootStream) {
                    if (entry.normalize().startsWith(root)) {
                        sortedEntries.add(entry);
                    } else {
                        recordWarning("Skipped entry outside root: " + entry);
                    }
                }
                sortedEntries.sort(ENTRY_ORDER);
                queue.addAll(sortedEntries);
            } catch (DirectoryIteratorException | IOException | SecurityException e) {
                recordWarning("Could not open root " + root + ": " + rootFailureMessage(e));
                continue;
            }
            while (!queue.isEmpty()) {
                if (cancellation.isCancellationRequested()) {
                    cancelled = true;
                    break outer;
                }
                if (visited.size() >= effective.scanLimit()) {
                    scanLimitReached = true;
                    break outer;
                }
                Path current = queue.poll();
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(
                            current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException | SecurityException e) {
                    recordWarning("Could not read attributes for " + current + ": " + rootFailureMessage(e));
                    continue;
                }
                if (attributes.isSymbolicLink()) {
                    recordWarning("Skipped symbolic link: " + current);
                    continue;
                }
                if (attributes.isDirectory()) {
                    for (Path child : listChildren(current)) {
                        if (child.normalize().startsWith(root)) {
                            queue.add(child);
                        } else {
                            recordWarning("Skipped entry outside root: " + child);
                        }
                    }
                    continue;
                }
                if (!attributes.isRegularFile()) {
                    continue;
                }
                visited.add(current);
                if (visitedFileProgress != null && visited.size() % PROGRESS_EVERY == 0) {
                    visitedFileProgress.accept(visited.size());
                }
                if (!matchesQuery(current.getFileName().toString(), attributes, extensions, effective)) {
                    continue;
                }
                matches.add(toMatch(current, attributes));
                if (matches.size() >= effective.maxResults()) {
                    resultLimitReached = true;
                    break outer;
                }
            }
        }

        if (visitedFileProgress != null && !visited.isEmpty() && visited.size() % PROGRESS_EVERY != 0) {
            visitedFileProgress.accept(visited.size());
        }
        if (cancelled) {
            throw new ServiceException(error(
                    ErrorCode.CANCELLED,
                    "File search cancelled",
                    "Cancelled after visiting " + visited.size() + " files"));
        }
        if (scannedRoots == 0) {
            throw new ServiceException(error(
                    ErrorCode.ACCESS_DENIED,
                    "No configured search root could be scanned",
                    warnings.isEmpty() ? null : String.join("; ", warnings)));
        }
        if (scanLimitReached) {
            recordWarning("Scan limit of " + effective.scanLimit()
                    + " files reached; results are partial.");
        }
        matches.sort(MATCH_ORDER);
        return new FileSearchResult(
                List.copyOf(matches),
                visited.size(),
                resultLimitReached,
                scanLimitReached);
    }

    private FileSearchQuery clampQuery(FileSearchQuery query) {
        int maxResults = Math.min(query.maxResults(), SPRINT_MAX_RESULTS);
        int scanLimit = Math.min(query.scanLimit(), SPRINT_SCAN_LIMIT);
        if (maxResults == query.maxResults() && scanLimit == query.scanLimit()) {
            return query;
        }
        return new FileSearchQuery(query.extensions(), query.minimumSizeBytes(), query.maximumSizeBytes(),
                query.modifiedAfter(), query.modifiedBefore(), maxResults, scanLimit);
    }

    private Set<String> normalizeExtensions(Set<String> extensions) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String extension : extensions) {
            String cleaned = extension.strip();
            if (cleaned.isEmpty()) {
                throw new IllegalArgumentException("extensions must not be blank");
            }
            String withoutDot = cleaned.startsWith(".") ? cleaned.substring(1) : cleaned;
            normalized.add(asciiLower(withoutDot));
        }
        return Set.copyOf(normalized);
    }

    /**
     * Extension matching is ASCII case-insensitive per docs/PROJECT_DECISIONS.md
     * (Locale.ROOT toLowerCase would fold non-ASCII letters too).
     */
    private static String asciiLower(String value) {
        StringBuilder builder = null;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c >= 'A' && c <= 'Z') {
                if (builder == null) {
                    builder = new StringBuilder(value);
                }
                builder.setCharAt(index, (char) (c + ('a' - 'A')));
            }
        }
        return builder == null ? value : builder.toString();
    }

    /**
     * Sprint 2 filtering: extension match as in sprint 1, plus optional size
     * bounds (minimum inclusive, maximum exclusive) and an optional
     * last-modified window (modifiedAfter inclusive, modifiedBefore
     * exclusive), so "from yesterday" is exactly the previous calendar day.
     */
    private boolean matchesQuery(
            String fileName,
            BasicFileAttributes attributes,
            Set<String> extensions,
            FileSearchQuery query) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        if (!extensions.contains(asciiLower(fileName.substring(dot + 1)))) {
            return false;
        }
        if (query.minimumSizeBytes().isPresent() && attributes.size() < query.minimumSizeBytes().getAsLong()) {
            return false;
        }
        if (query.maximumSizeBytes().isPresent() && attributes.size() >= query.maximumSizeBytes().getAsLong()) {
            return false;
        }
        Instant modified = attributes.lastModifiedTime().toInstant();
        if (query.modifiedAfter().isPresent() && modified.isBefore(query.modifiedAfter().get())) {
            return false;
        }
        if (query.modifiedBefore().isPresent() && !modified.isBefore(query.modifiedBefore().get())) {
            return false;
        }
        return true;
    }

    private FileMatch toMatch(Path file, BasicFileAttributes attributes) {
        FileTime modified = attributes.lastModifiedTime();
        return new FileMatch(
                file,
                file.getFileName().toString(),
                attributes.size(),
                modified == null ? Instant.EPOCH : modified.toInstant());
    }

    private List<Path> listChildren(Path directory) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (DirectoryIteratorException | IOException | SecurityException e) {
            recordWarning("Could not list directory " + directory + ": " + rootFailureMessage(e));
        }
        children.sort(ENTRY_ORDER);
        return children;
    }

    private void recordWarning(String message) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(message);
        } else if (warnings.size() == MAX_WARNINGS) {
            warnings.add("Further warnings omitted.");
        }
    }

    private static String rootFailureMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private StructuredError error(ErrorCode code, String message, String detail) {
        return new StructuredError(code, message, Optional.ofNullable(detail));
    }
}
