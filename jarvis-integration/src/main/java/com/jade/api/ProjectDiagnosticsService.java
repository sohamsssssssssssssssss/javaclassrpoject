package com.jade.api;

/**
 * Seam that extracts structured diagnostics for the active project from
 * Maven/Surefire evidence (surefire XML reports and the bounded captured
 * output of the last operation). Implementations are conservative: unknown
 * patterns stay UNKNOWN and bounds are reported truthfully.
 */
@FunctionalInterface
public interface ProjectDiagnosticsService {

    /**
     * Extracts diagnostics for the given project and the outcome of its most
     * recent operation. Implementations only read files inside the project
     * root (never outside it) and never execute Maven.
     */
    DiagnosticsReport analyze(ProjectContext project, ProjectOperationResult lastOperation)
            throws ServiceException;
}
