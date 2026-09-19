package com.jarvis.services.project;

import com.jarvis.api.BuildSystem;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.ProjectContext;
import com.jarvis.api.ProjectService;
import com.jarvis.api.ServiceException;
import com.jarvis.api.StructuredError;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Maven-only project detection. A candidate is a project when the directory
 * exists and carries a {@code pom.xml}; anything else is a structured,
 * honest rejection. Detection never infers from names and never searches
 * personal paths.
 */
public final class FileSystemProjectService implements ProjectService {

    public static final String MAVEN_DESCRIPTOR = "pom.xml";

    @Override
    public ProjectContext activate(Path candidate) throws ServiceException {
        if (candidate == null) {
            throw rejection(ErrorCode.INVALID_COMMAND, "No project path was given");
        }
        Path root = candidate.toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw rejection(ErrorCode.INVALID_COMMAND,
                    "Project folder does not exist: " + root);
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw rejection(ErrorCode.INVALID_COMMAND,
                    "Project path is not a directory: " + root);
        }
        Path descriptor = root.resolve(MAVEN_DESCRIPTOR);
        if (!Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS)) {
            throw rejection(ErrorCode.INVALID_COMMAND,
                    "No supported project descriptor (" + MAVEN_DESCRIPTOR + ") in " + root);
        }
        String name = Optional.ofNullable(root.getFileName())
                .map(Path::toString)
                .orElse(root.toString())
                .toLowerCase(Locale.ROOT);
        return new ProjectContext(root, name, BuildSystem.MAVEN, descriptor);
    }

    private static ServiceException rejection(ErrorCode code, String message) {
        return new ServiceException(new StructuredError(code, message, Optional.empty()));
    }
}
