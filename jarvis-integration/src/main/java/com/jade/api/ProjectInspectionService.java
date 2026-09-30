package com.jade.api;

import java.util.List;

/**
 * Seam for read-only, static inspection of the validated active project.
 * Implementations collect facts (metadata, source inventory, bounded tree,
 * declared dependencies, main-class candidates, TODO/FIXME markers) without
 * modifying the project, executing builds, or resolving dependencies.
 */
@FunctionalInterface
public interface ProjectInspectionService {

    /** Hard bound on retained TODO/FIXME findings per inspection. */
    int MAX_TODO_FINDINGS = 50;
    /** Hard bound on scanned Java bytes per inspection (1 MiB per file). */
    int MAX_FILE_SCAN_BYTES = 1_048_576;

    /**
     * Inspects the given validated project and returns the typed result.
     *
     * @throws ServiceException when the project root or descriptor has
     *         disappeared since activation, or reading fails structurally.
     */
    ProjectInspectionResult inspect(ProjectContext project) throws ServiceException;
}
