package com.jade.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigurationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsUserSelectedRootDataDirectoryAndAlias() {
        String oldRoots = System.getProperty("jade.search.roots");
        String oldData = System.getProperty("jade.data.dir");
        String oldAlias = System.getProperty("jade.app.alias");
        try {
            System.setProperty("jade.search.roots", temporaryDirectory.toString());
            System.setProperty("jade.data.dir", temporaryDirectory.resolve("data").toString());
            System.setProperty("jade.app.alias", "  My   Calc  ");

            AppConfiguration configuration = AppConfiguration.load();

            assertEquals(List.of(temporaryDirectory.toAbsolutePath()), configuration.searchRoots());
            assertEquals(temporaryDirectory.resolve("data").toAbsolutePath(), configuration.dataDirectory());
            assertTrue(configuration.configuredApps().getFirst().aliases().contains("my calc"));
        } finally {
            restore("jade.search.roots", oldRoots);
            restore("jade.data.dir", oldData);
            restore("jade.app.alias", oldAlias);
        }
    }

    @Test
    void canonicalJadeKeysWinOverLegacyJarvisKeys() {
        String oldRoots = System.getProperty("jade.search.roots");
        String oldData = System.getProperty("jade.data.dir");
        String oldAlias = System.getProperty("jade.app.alias");
        String oldLegacyRoots = System.getProperty("jarvis.search.roots");
        String oldLegacyData = System.getProperty("jarvis.data.dir");
        String oldLegacyAlias = System.getProperty("jarvis.app.alias");
        try {
            System.setProperty("jade.search.roots", temporaryDirectory.toString());
            System.setProperty("jade.data.dir", temporaryDirectory.resolve("jade-data").toString());
            System.setProperty("jade.app.alias", "jade alias");
            System.setProperty("jarvis.search.roots", temporaryDirectory.resolve("legacy-roots").toString());
            System.setProperty("jarvis.data.dir", temporaryDirectory.resolve("legacy-data").toString());
            System.setProperty("jarvis.app.alias", "legacy alias");

            AppConfiguration configuration = AppConfiguration.load();

            assertEquals(List.of(temporaryDirectory.toAbsolutePath()), configuration.searchRoots());
            assertEquals(temporaryDirectory.resolve("jade-data").toAbsolutePath(), configuration.dataDirectory());
            assertTrue(configuration.configuredApps().getFirst().aliases().contains("jade alias"));
            assertFalse(configuration.configuredApps().getFirst().aliases().contains("legacy alias"));
        } finally {
            restore("jade.search.roots", oldRoots);
            restore("jade.data.dir", oldData);
            restore("jade.app.alias", oldAlias);
            restore("jarvis.search.roots", oldLegacyRoots);
            restore("jarvis.data.dir", oldLegacyData);
            restore("jarvis.app.alias", oldLegacyAlias);
        }
    }

    @Test
    void legacyJarvisKeysStillWorkAsFallback() {
        String oldRoots = System.getProperty("jade.search.roots");
        String oldData = System.getProperty("jade.data.dir");
        String oldAlias = System.getProperty("jade.app.alias");
        String oldLegacyRoots = System.getProperty("jarvis.search.roots");
        String oldLegacyData = System.getProperty("jarvis.data.dir");
        String oldLegacyAlias = System.getProperty("jarvis.app.alias");
        try {
            System.setProperty("jarvis.search.roots", temporaryDirectory.toString());
            System.setProperty("jarvis.data.dir", temporaryDirectory.resolve("legacy-data").toString());
            System.setProperty("jarvis.app.alias", "legacy calc");

            AppConfiguration configuration = AppConfiguration.load();

            assertEquals(List.of(temporaryDirectory.toAbsolutePath()), configuration.searchRoots());
            assertEquals(temporaryDirectory.resolve("legacy-data").toAbsolutePath(), configuration.dataDirectory());
            assertTrue(configuration.configuredApps().getFirst().aliases().contains("legacy calc"));
        } finally {
            restore("jade.search.roots", oldRoots);
            restore("jade.data.dir", oldData);
            restore("jade.app.alias", oldAlias);
            restore("jarvis.search.roots", oldLegacyRoots);
            restore("jarvis.data.dir", oldLegacyData);
            restore("jarvis.app.alias", oldLegacyAlias);
        }
    }

    /** Scenario A: no legacy data and no JADE data → canonical JADE location used. */
    @Test
    void freshInstallUsesCanonicalJadeLocation() {
        List<String> warnings = new ArrayList<>();

        Path resolved = AppConfiguration.platformDataDirectory("Mac OS X", temporaryDirectory, null, warnings);

        assertEquals(temporaryDirectory.resolve("Library").resolve("Application Support").resolve("JADE"),
                resolved);
        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        // Linux naming differs by case by design.
        Path linux = AppConfiguration.platformDataDirectory("Linux", temporaryDirectory, null, new ArrayList<>());
        assertEquals(temporaryDirectory.resolve(".local").resolve("share").resolve("jade"), linux);
        // Windows uses %APPDATA% (or the home directory as fallback).
        Path windows = AppConfiguration.platformDataDirectory("Windows 11", temporaryDirectory,
                temporaryDirectory.resolve("AppData").toString(), new ArrayList<>());
        assertEquals(temporaryDirectory.resolve("AppData").resolve("JADE"), windows);
    }

    /** Scenario B: legacy exists, JADE absent → safe migration/reuse as designed. */
    @Test
    void legacyJarvisDataIsMigratedToJade() throws IOException {
        Path appSupport = temporaryDirectory.resolve("Library").resolve("Application Support");
        Path legacy = appSupport.resolve("JARVIS");
        Files.createDirectories(legacy.resolve("speakers"));
        Files.writeString(legacy.resolve("history.db"), "legacy-history-bytes");
        Files.writeString(legacy.resolve("speakers").resolve("SOHAM.spk"), "1:0.5");
        List<String> warnings = new ArrayList<>();

        Path resolved = AppConfiguration.platformDataDirectory("Mac OS X", temporaryDirectory, null, warnings);

        Path canonical = appSupport.resolve("JADE");
        assertEquals(canonical, resolved);
        assertTrue(Files.isDirectory(canonical));
        assertEquals("legacy-history-bytes", Files.readString(canonical.resolve("history.db")));
        assertEquals("1:0.5", Files.readString(canonical.resolve("speakers").resolve("SOHAM.spk")));
        // Legacy directory must be kept, not deleted.
        assertTrue(Files.isDirectory(legacy));
        assertEquals("legacy-history-bytes", Files.readString(legacy.resolve("history.db")));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("Migrated existing JARVIS data")));
    }

    /** Scenario C: JADE already exists → legacy data does NOT overwrite it. */
    @Test
    void existingJadeDataIsNeverOverwrittenByLegacy() throws IOException {
        Path appSupport = temporaryDirectory.resolve("Library").resolve("Application Support");
        Path canonical = appSupport.resolve("JADE");
        Files.createDirectories(canonical);
        Files.writeString(canonical.resolve("history.db"), "current-jade-history");
        Path legacy = appSupport.resolve("JARVIS");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("history.db"), "old-jarvis-history");
        List<String> warnings = new ArrayList<>();

        Path resolved = AppConfiguration.platformDataDirectory("Mac OS X", temporaryDirectory, null, warnings);

        assertEquals(canonical, resolved);
        assertEquals("current-jade-history", Files.readString(canonical.resolve("history.db")));
        assertEquals("old-jarvis-history", Files.readString(legacy.resolve("history.db")));
        assertTrue(warnings.isEmpty());
    }

    /** Scenario D: the legacy directory is never destructively deleted. */
    @Test
    void legacyDirectoryIsNeverDeletedByMigration() throws IOException {
        Path appSupport = temporaryDirectory.resolve("Library").resolve("Application Support");
        Path legacy = appSupport.resolve("JARVIS");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("history.db"), "keep-me");
        List<String> warnings = new ArrayList<>();

        AppConfiguration.platformDataDirectory("Mac OS X", temporaryDirectory, null, warnings);

        assertTrue(Files.isDirectory(legacy));
        assertEquals("keep-me", Files.readString(legacy.resolve("history.db")));
    }

    /** Scenario E: migrated SQLite history/schema remains usable. */
    @Test
    void migratedSqliteHistoryRemainsReadable() throws Exception {
        Path appSupport = temporaryDirectory.resolve("Library").resolve("Application Support");
        Path legacy = appSupport.resolve("JARVIS");
        Files.createDirectories(legacy);
        // Build a real legacy database through the repository itself.
        java.util.UUID id = java.util.UUID.randomUUID();
        java.time.Instant completed = java.time.Instant.parse("2026-09-19T10:00:00Z");
        com.jade.api.HistoryEntry written = new com.jade.api.HistoryEntry(
                id, "find pdfs", com.jade.api.CommandStatus.SUCCEEDED, "summary for find pdfs",
                java.util.Optional.empty(), completed.minusSeconds(10), completed.minusSeconds(5), completed);
        try (com.jade.services.history.SqliteHistoryRepository legacyRepository =
                     new com.jade.services.history.SqliteHistoryRepository(legacy.resolve("history.db"))) {
            legacyRepository.save(written);
        }
        List<String> warnings = new ArrayList<>();

        Path resolved = AppConfiguration.platformDataDirectory("Mac OS X", temporaryDirectory, null, warnings);

        Path canonical = appSupport.resolve("JADE");
        assertEquals(canonical, resolved);
        // The migrated canonical database opens with the unchanged schema and keeps the row.
        try (com.jade.services.history.SqliteHistoryRepository migratedRepository =
                     new com.jade.services.history.SqliteHistoryRepository(canonical.resolve("history.db"))) {
            List<com.jade.api.HistoryEntry> entries = migratedRepository.recent(10, com.jade.api.CancellationToken.NONE);
            assertEquals(1, entries.size());
            assertEquals(id, entries.getFirst().requestId());
            assertEquals("find pdfs", entries.getFirst().originalText());
        }
        // The legacy database was copied, not moved or deleted.
        try (com.jade.services.history.SqliteHistoryRepository legacyRepository =
                     new com.jade.services.history.SqliteHistoryRepository(legacy.resolve("history.db"))) {
            assertEquals(1, legacyRepository.recent(10, com.jade.api.CancellationToken.NONE).size());
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
