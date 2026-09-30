package com.jade.brain.training;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointFallbackTest {
    @TempDir Path directory;

    @Test void nonAtomicFallbackReplacesCompleteFileAndRestoresOnFailure() throws IOException {
        Path target = directory.resolve("model.jade"), temp = directory.resolve("complete.tmp");
        Files.writeString(target, "last valid checkpoint");
        assertThrows(IOException.class,
                () -> BrainCheckpoint.replaceWithBackup(directory.resolve("missing.tmp"), target));
        assertEquals("last valid checkpoint", Files.readString(target));
        Files.writeString(temp, "complete new checkpoint");
        BrainCheckpoint.replaceWithBackup(temp, target);
        assertEquals("complete new checkpoint", Files.readString(target));
        assertFalse(Files.exists(temp));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
}
