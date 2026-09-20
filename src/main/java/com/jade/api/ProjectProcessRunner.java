package com.jade.api;

/**
 * Seam that runs one typed {@link ProjectOperation} inside a validated
 * {@link ProjectContext}. Implementations derive every process argument from
 * the operation enum — never from user text — and must bound both the wait
 * time and the captured output. A tool that runs but exits non-zero is a
 * normal {@link ProjectOperationResult}; only a process that cannot be
 * launched surfaces as a {@link ServiceException}.
 */
@FunctionalInterface
public interface ProjectProcessRunner {

    ProjectOperationResult execute(ProjectContext project, ProjectOperation operation)
            throws ServiceException;
}
