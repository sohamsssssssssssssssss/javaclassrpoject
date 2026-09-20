package com.jade.api;

import java.nio.file.Path;

/**
 * Seam that validates a candidate project directory and returns its typed
 * {@link ProjectContext}. Implementations check the directory exists, is a
 * directory, and carries a supported build descriptor — never guessing from
 * names or paths.
 */
@FunctionalInterface
public interface ProjectService {

    /**
     * Validates the candidate and returns its project context.
     *
     * @throws ServiceException with a structured {@link com.jade.api.ErrorCode}
     *         when the candidate is missing, is not a directory, or has no
     *         supported build descriptor.
     */
    ProjectContext activate(Path candidate) throws ServiceException;
}
