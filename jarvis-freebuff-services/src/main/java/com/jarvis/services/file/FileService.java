package com.jarvis.services.file;

import com.jarvis.api.ServiceException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface FileService {
    List<FileInfo> listFiles(Path directory) throws ServiceException;

    List<FileInfo> findFilesByExtension(Path directory, String extension) throws ServiceException;

    List<FileInfo> findFilesByName(Path directory, String searchText) throws ServiceException;

    FileInfo getFileInfo(Path file) throws ServiceException;

    boolean createDirectory(Path directory) throws ServiceException;

    boolean copyFile(Path source, Path destination) throws ServiceException;

    boolean moveFile(Path source, Path destination) throws ServiceException, IOException;

    boolean renameFile(Path source, Path newName) throws ServiceException, IOException;
}