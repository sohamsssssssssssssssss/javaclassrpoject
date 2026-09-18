package com.jarvis.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigurationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsUserSelectedRootDataDirectoryAndAlias() {
        String oldRoots = System.getProperty("jarvis.search.roots");
        String oldData = System.getProperty("jarvis.data.dir");
        String oldAlias = System.getProperty("jarvis.app.alias");
        try {
            System.setProperty("jarvis.search.roots", temporaryDirectory.toString());
            System.setProperty("jarvis.data.dir", temporaryDirectory.resolve("data").toString());
            System.setProperty("jarvis.app.alias", "  My   Calc  ");

            AppConfiguration configuration = AppConfiguration.load();

            assertEquals(List.of(temporaryDirectory.toAbsolutePath()), configuration.searchRoots());
            assertEquals(temporaryDirectory.resolve("data").toAbsolutePath(), configuration.dataDirectory());
            assertTrue(configuration.configuredApps().getFirst().aliases().contains("my calc"));
        } finally {
            restore("jarvis.search.roots", oldRoots);
            restore("jarvis.data.dir", oldData);
            restore("jarvis.app.alias", oldAlias);
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
