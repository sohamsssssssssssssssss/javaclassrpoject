package com.jade.services.files;

import com.jade.api.ServiceException;
import com.jade.api.StructuredError;
import com.jade.api.ErrorCode;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public final class FileSystemFileService implements FileService {

    private static final String ERROR_DIR_NOT_FOUND = "Directory not found: %s";
    private static final String ERROR_FILE_NOT_FOUND = "File not found: %s";
    private static final String ERROR_DEST_EXISTS = "Destination already exists: %s";
    private static final String ERROR_SOURCE_NOT_FOUND = "Source not found: %s";

    @Override
    public List<FileInfo> listFiles(Path directory) throws ServiceException {
        java.util.Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    String.format(ERROR_DIR_NOT_FOUND, directory),
                    Optional.empty()));
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(this::toFileInfo)
                    .toList();
        } catch (IOException e) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.IO_FAILURE,
                    "Failed to list files in " + directory,
                    Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())), e);
        }
    }

    @Override
    public List<FileInfo> findFilesByExtension(Path directory, String extension) throws ServiceException {
        java.util.Objects.requireNonNull(directory, "directory");
        java.util.Objects.requireNonNull(extension, "extension");
        // Accept both "txt" and ".txt"; FileInfo.extension() always includes the leading dot.
        String normalizedExtension = extension.startsWith(".") ? extension : "." + extension;
        if (!Files.isDirectory(directory)) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    String.format(ERROR_DIR_NOT_FOUND, directory),
                    Optional.empty()));
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String ext = p.getFileName().toString();
                        int dotIndex = ext.lastIndexOf('.');
                        if (dotIndex > 0) {
                            return ext.substring(dotIndex).equalsIgnoreCase(normalizedExtension);
                        }
                        return false;
                    })
                    .map(this::toFileInfo)
                    .toList();
        } catch (IOException e) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.IO_FAILURE,
                    "Failed to find files by extension in " + directory,
                    Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())), e);
        }
    }

    @Override
    public List<FileInfo> findFilesByName(Path directory, String searchText) throws ServiceException {
        java.util.Objects.requireNonNull(directory, "directory");
        java.util.Objects.requireNonNull(searchText, "searchText");
        if (!Files.isDirectory(directory)) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    String.format(ERROR_DIR_NOT_FOUND, directory),
                    Optional.empty()));
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().contains(searchText))
                    .map(this::toFileInfo)
                    .toList();
        } catch (IOException e) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.IO_FAILURE,
                    "Failed to find files by name in " + directory,
                    Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())), e);
        }
    }

    @Override
    public FileInfo getFileInfo(Path file) throws ServiceException {
        java.util.Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    String.format(ERROR_FILE_NOT_FOUND, file),
                    Optional.empty()));
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return new FileInfo(
                    file.getFileName().toString(),
                    file.toAbsolutePath(),
                    getExtension(file),
                    attrs.size(),
                    Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()),
                    attrs.isDirectory());
        } catch (IOException e) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.IO_FAILURE,
                    "Failed to get file info for " + file,
                    Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())), e);
        }
    }

    @Override
    public boolean createDirectory(Path directory) throws ServiceException {
        java.util.Objects.requireNonNull(directory, "directory");
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            if (Files.isDirectory(normalized)) {
                return true;
            }
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    String.format(ERROR_DEST_EXISTS, normalized) + " (a file with that name exists)",
                    Optional.empty()));
        }
        try {
            Files.createDirectories(normalized);
            return true;
        } catch (IOException e) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.IO_FAILURE,
                    "Failed to create directory " + normalized,
                    Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())), e);
        }
    }

    @Override
    public boolean copyFile(Path source, Path destination) throws ServiceException {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(destination, "destination");
        Path destNorm = destination.toAbsolutePath().normalize();
        if (Files.exists(destNorm)) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    String.format(ERROR_DEST_EXISTS, destNorm),
                    Optional.empty()));
        }
        if (!Files.exists(source)) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    String.format(ERROR_SOURCE_NOT_FOUND, source),
                    Optional.empty()));
        }
        if (Files.isDirectory(source)) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    "Source is a directory; copyFile only copies regular files: " + source,
                    Optional.empty()));
        }
        try {
            Path parent = destNorm.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, destNorm);
            return true;
        } catch (IOException e) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.IO_FAILURE,
                    "Failed to copy " + source + " to " + destNorm,
                    Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())), e);
        }
    }

    @Override
    public boolean moveFile(Path source, Path destination) throws ServiceException, IOException {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(destination, "destination");
        Path sourceNorm = source.toAbsolutePath().normalize();
        Path destNorm = destination.toAbsolutePath().normalize();
        if (!Files.exists(sourceNorm)) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    String.format(ERROR_SOURCE_NOT_FOUND, sourceNorm),
                    Optional.empty()));
        }
        if (Files.exists(destNorm) && !Files.isSameFile(sourceNorm, destNorm)) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    String.format(ERROR_DEST_EXISTS, destNorm),
                    Optional.empty()));
        }
        Path parent = destNorm.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.move(sourceNorm, destNorm);
            return true;
        } catch (IOException e) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.IO_FAILURE,
                    "Failed to move " + sourceNorm + " to " + destNorm,
                    Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())), e);
        }
    }

    @Override
    public boolean renameFile(Path source, Path newName) throws ServiceException, IOException {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(newName, "newName");
        Path sourceNorm = source.toAbsolutePath().normalize();
        if (!Files.exists(sourceNorm)) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    String.format(ERROR_SOURCE_NOT_FOUND, sourceNorm),
                    Optional.empty()));
        }
        Path parent = sourceNorm.getParent();
        if (newName.isAbsolute() || newName.getNameCount() != 1) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    "New name must be a simple file name, not a path: " + newName,
                    Optional.empty()));
        }
        String simpleName = newName.getFileName().toString();
        if (simpleName.equals(".") || simpleName.equals("..")) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    "New name must not be a relative path element: " + newName,
                    Optional.empty()));
        }
        Path target = parent.resolve(simpleName);
        if (Files.exists(target) && !Files.isSameFile(sourceNorm, target)) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.INVALID_COMMAND,
                    "Target already exists: " + target,
                    Optional.empty()));
        }
        try {
            Files.move(sourceNorm, target);
            return true;
        } catch (IOException e) {
            throw new ServiceException(new StructuredError(
                    ErrorCode.IO_FAILURE,
                    "Failed to rename " + sourceNorm + " to " + target,
                    Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())), e);
        }
    }

    private static String getExtension(Path file) {
        String name = file.getFileName().toString();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < name.length() - 1) {
            return name.substring(dotIndex);
        }
        return "";
    }

    private FileInfo toFileInfo(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return new FileInfo(
                    file.getFileName().toString(),
                    file.toAbsolutePath(),
                    getExtension(file),
                    attrs.size(),
                    Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()),
                    attrs.isDirectory());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
