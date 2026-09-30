package com.jarvis.services.file;

import com.jarvis.api.ErrorCode;
import com.jarvis.api.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemFileServiceTest {

    private final FileSystemFileService service = new FileSystemFileService();

    // --- listing and search -------------------------------------------------

    @Test
    void listFilesListsOnlyTopLevelRegularFiles(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("a.txt"), "x");
        Files.writeString(temp.resolve("b.pdf"), "y");
        Files.createDirectories(temp.resolve("subdir"));
        Files.writeString(temp.resolve("subdir/nested.txt"), "z");

        List<FileInfo> files = service.listFiles(temp);

        List<String> names = files.stream().map(FileInfo::name).sorted().toList();
        assertEquals(List.of("a.txt", "b.pdf"), names);
        assertTrue(files.stream().noneMatch(FileInfo::directory));
    }

    @Test
    void listOperationsRejectMissingDirectory(@TempDir Path temp) {
        Path missing = temp.resolve("nope");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.listFiles(missing));
        assertEquals(ErrorCode.INVALID_COMMAND, ex.error().code());
        assertTrue(ex.error().message().contains("Directory not found"));

        assertThrows(ServiceException.class, () -> service.findFilesByExtension(missing, ".txt"));
        assertThrows(ServiceException.class, () -> service.findFilesByName(missing, "x"));
    }

    @Test
    void findFilesByExtensionMatchesCaseInsensitivelyWithOrWithoutDot(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("a.txt"), "x");
        Files.writeString(temp.resolve("b.TXT"), "y");
        Files.writeString(temp.resolve("c.md"), "z");
        Files.writeString(temp.resolve("noext"), "w");

        for (String query : new String[]{".txt", "txt"}) {
            List<String> names = service.findFilesByExtension(temp, query).stream()
                    .map(FileInfo::name)
                    .sorted()
                    .toList();
            assertEquals(List.of("a.txt", "b.TXT"), names, "extension query: " + query);
        }

        assertTrue(service.findFilesByExtension(temp, ".xyz").isEmpty());
    }

    @Test
    void findFilesByNameMatchesSubstringsCaseSensitively(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("report-2026.pdf"), "x");
        Files.writeString(temp.resolve("report-final.pdf"), "y");
        Files.writeString(temp.resolve("notes.txt"), "z");

        assertEquals(2, service.findFilesByName(temp, "report").size());
        // Pinned contract: name search is case-sensitive substring matching.
        assertEquals(0, service.findFilesByName(temp, "Report").size());
        assertEquals(0, service.findFilesByName(temp, "zzz").size());
    }

    // --- metadata -----------------------------------------------------------

    @Test
    void getFileInfoReturnsFileMetadata(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("meta.txt");
        Files.writeString(file, "hello"); // 5 bytes

        FileInfo info = service.getFileInfo(file);

        assertEquals("meta.txt", info.name());
        assertEquals(".txt", info.extension());
        assertEquals(5, info.sizeBytes());
        assertFalse(info.directory());
        assertTrue(info.absolutePath().isAbsolute());
        assertEquals("meta.txt", info.absolutePath().getFileName().toString());
        assertNotNull(info.lastModified());
    }

    @Test
    void getFileInfoDescribesDirectories(@TempDir Path temp) throws Exception {
        Path dir = temp.resolve("folder");
        Files.createDirectories(dir);

        FileInfo info = service.getFileInfo(dir);

        assertTrue(info.directory());
        assertEquals("folder", info.name());
        assertEquals("", info.extension());
    }

    @Test
    void getFileInfoRejectsMissingFile(@TempDir Path temp) {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.getFileInfo(temp.resolve("missing.txt")));

        assertEquals(ErrorCode.INVALID_COMMAND, ex.error().code());
        assertTrue(ex.error().message().contains("File not found"));
    }

    // --- mutations ----------------------------------------------------------

    @Test
    void createDirectoryCreatesParentsAndIsIdempotent(@TempDir Path temp) throws Exception {
        assertTrue(service.createDirectory(temp.resolve("a/b/c")));
        assertTrue(Files.isDirectory(temp.resolve("a/b/c")));
        assertTrue(service.createDirectory(temp.resolve("a/b/c"))); // already exists
    }

    @Test
    void createDirectoryRefusesPathTakenByFile(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("occupied");
        Files.writeString(file, "x");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.createDirectory(file));

        assertEquals(ErrorCode.INVALID_COMMAND, ex.error().code());
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void copyFileCopiesContentAndRefusesOverwrite(@TempDir Path temp) throws Exception {
        Path src = temp.resolve("src.txt");
        Files.writeString(src, "abc");
        Path dst = temp.resolve("dst.txt");

        assertTrue(service.copyFile(src, dst));
        assertEquals("abc", Files.readString(dst));

        ServiceException overwrite = assertThrows(ServiceException.class, () -> service.copyFile(src, dst));
        assertEquals(ErrorCode.INVALID_COMMAND, overwrite.error().code());
        assertEquals("abc", Files.readString(dst)); // target untouched

        ServiceException missing = assertThrows(ServiceException.class,
                () -> service.copyFile(temp.resolve("nope.txt"), temp.resolve("out.txt")));
        assertEquals(ErrorCode.INVALID_COMMAND, missing.error().code());
    }

    @Test
    void copyFileCreatesMissingDestinationParents(@TempDir Path temp) throws Exception {
        Path src = temp.resolve("src.txt");
        Files.writeString(src, "abc");

        assertTrue(service.copyFile(src, temp.resolve("brand/new/deep/copy.txt")));
        assertEquals("abc", Files.readString(temp.resolve("brand/new/deep/copy.txt")));
    }

    @Test
    void copyFileRefusesDirectorySourceAndExistingDirectoryDestination(@TempDir Path temp) throws Exception {
        Path dir = temp.resolve("folder");
        Files.createDirectories(dir);
        Path src = temp.resolve("src.txt");
        Files.writeString(src, "abc");

        ServiceException dirSource = assertThrows(ServiceException.class,
                () -> service.copyFile(dir, temp.resolve("copy-of-folder")));
        assertEquals(ErrorCode.INVALID_COMMAND, dirSource.error().code());
        assertTrue(Files.notExists(temp.resolve("copy-of-folder")));

        ServiceException dirDestination = assertThrows(ServiceException.class,
                () -> service.copyFile(src, dir));
        assertEquals(ErrorCode.INVALID_COMMAND, dirDestination.error().code());
        assertTrue(Files.notExists(dir.resolve("src.txt")));
    }

    @Test
    void moveFileMovesAndRefusesOverwrite(@TempDir Path temp) throws Exception {
        Path src = temp.resolve("m.txt");
        Files.writeString(src, "abc");
        Path dst = temp.resolve("moved.txt");

        assertTrue(service.moveFile(src, dst));
        assertTrue(Files.notExists(src));
        assertEquals("abc", Files.readString(dst));

        Path other = temp.resolve("other.txt");
        Files.writeString(other, "zzz");
        ServiceException overwrite = assertThrows(ServiceException.class, () -> service.moveFile(dst, other));
        assertEquals(ErrorCode.INVALID_COMMAND, overwrite.error().code());
        assertTrue(Files.exists(dst));
        assertEquals("zzz", Files.readString(other));
    }

    @Test
    void moveFileOntoItselfIsAccepted(@TempDir Path temp) throws Exception {
        Path src = temp.resolve("same.txt");
        Files.writeString(src, "keep");

        assertTrue(service.moveFile(src, src));

        assertEquals("keep", Files.readString(src));
    }

    @Test
    void moveFileMovesDirectoriesWithContents(@TempDir Path temp) throws Exception {
        Path dir = temp.resolve("project");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("inside.txt"), "data");

        assertTrue(service.moveFile(dir, temp.resolve("renamed-project")));

        assertTrue(Files.notExists(dir));
        assertEquals("data", Files.readString(temp.resolve("renamed-project/inside.txt")));
    }

    @Test
    void renameFileRenamesWithinSameDirectory(@TempDir Path temp) throws Exception {
        Path src = temp.resolve("draft.txt");
        Files.writeString(src, "content");

        assertTrue(service.renameFile(src, Path.of("final.txt")));

        assertTrue(Files.notExists(src));
        assertEquals("content", Files.readString(temp.resolve("final.txt")));
    }

    @Test
    void renameFileRefusesExistingTarget(@TempDir Path temp) throws Exception {
        Path src = temp.resolve("draft.txt");
        Files.writeString(src, "a");
        Path taken = temp.resolve("taken.txt");
        Files.writeString(taken, "b");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.renameFile(src, Path.of("taken.txt")));

        assertEquals(ErrorCode.INVALID_COMMAND, ex.error().code());
        assertTrue(Files.exists(src));
        assertEquals("b", Files.readString(taken));
    }

    @Test
    void renameFileRejectsPathLikeNewNames(@TempDir Path temp) throws Exception {
        Path src = temp.resolve("a.txt");
        Files.writeString(src, "x");

        assertThrows(ServiceException.class, () -> service.renameFile(src, Path.of("sub/b.txt")));
        assertThrows(ServiceException.class, () -> service.renameFile(src, temp.resolve("elsewhere.txt")));
        assertThrows(ServiceException.class, () -> service.renameFile(src, Path.of("..")));

        // Source stays untouched after every rejection.
        assertTrue(Files.exists(src));
    }

    // --- input validation ---------------------------------------------------

    @Test
    void nullArgumentsAreRejected(@TempDir Path temp) {
        assertThrows(NullPointerException.class, () -> service.listFiles(null));
        assertThrows(NullPointerException.class, () -> service.findFilesByExtension(null, ".txt"));
        assertThrows(NullPointerException.class, () -> service.findFilesByName(null, "x"));
        assertThrows(NullPointerException.class, () -> service.getFileInfo(null));
        assertThrows(NullPointerException.class, () -> service.createDirectory(null));
        assertThrows(NullPointerException.class, () -> service.copyFile(null, temp));
        assertThrows(NullPointerException.class, () -> service.copyFile(temp, null));
        assertThrows(NullPointerException.class, () -> service.moveFile(null, temp));
        assertThrows(NullPointerException.class, () -> service.moveFile(temp, null));
        assertThrows(NullPointerException.class, () -> service.renameFile(null, temp));
        assertThrows(NullPointerException.class, () -> service.renameFile(temp, null));
    }
}
