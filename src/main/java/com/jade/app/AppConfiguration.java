package com.jade.app;

import com.jade.api.ConfiguredApp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

record AppConfiguration(List<Path> searchRoots, Path dataDirectory, String calculatorAlias, List<String> warnings) {
    private static final String ROOTS_PROPERTY = "jade.search.roots";
    private static final String ROOTS_ENV = "JADE_SEARCH_ROOTS";
    private static final String LEGACY_ROOTS_PROPERTY = "jarvis.search.roots";
    private static final String LEGACY_ROOTS_ENV = "JARVIS_SEARCH_ROOTS";
    private static final String ALIAS_PROPERTY = "jade.app.alias";
    private static final String ALIAS_ENV = "JADE_APP_ALIAS";
    private static final String LEGACY_ALIAS_PROPERTY = "jarvis.app.alias";
    private static final String LEGACY_ALIAS_ENV = "JARVIS_APP_ALIAS";
    private static final String DATA_PROPERTY = "jade.data.dir";
    private static final String DATA_ENV = "JADE_DATA_DIR";
    private static final String LEGACY_DATA_PROPERTY = "jarvis.data.dir";
    private static final String LEGACY_DATA_ENV = "JARVIS_DATA_DIR";

    AppConfiguration {
        searchRoots = List.copyOf(searchRoots);
        warnings = List.copyOf(warnings);
        calculatorAlias = normalizeAlias(calculatorAlias);
    }

    static AppConfiguration load() {
        List<String> warnings = new ArrayList<>();
        List<Path> roots = parseRoots(setting(ROOTS_PROPERTY, ROOTS_ENV, LEGACY_ROOTS_PROPERTY, LEGACY_ROOTS_ENV), warnings);
        Path dataDirectory = dataDirectory(setting(DATA_PROPERTY, DATA_ENV, LEGACY_DATA_PROPERTY, LEGACY_DATA_ENV), warnings);
        String alias = setting(ALIAS_PROPERTY, ALIAS_ENV, LEGACY_ALIAS_PROPERTY, LEGACY_ALIAS_ENV);
        return new AppConfiguration(roots, dataDirectory, alias, warnings);
    }

    AppConfiguration withSelection(Path root, String alias) {
        return new AppConfiguration(List.of(root.toAbsolutePath().normalize()), dataDirectory, alias, List.of());
    }

    List<ConfiguredApp> configuredApps() {
        Set<String> calculatorAliases = new LinkedHashSet<>(Set.of("calculator", "calc"));
        if (!calculatorAlias.isBlank()) {
            calculatorAliases.add(calculatorAlias);
        }
        return List.of(
                new ConfiguredApp("calculator", "Calculator", calculatorAliases),
                new ConfiguredApp("text-editor", "Text Editor", Set.of("text editor", "editor")),
                new ConfiguredApp("file-manager", "File Manager", Set.of("file manager", "files")));
    }

    String scopeDescription() {
        return searchRoots.stream().map(Path::toString).reduce((left, right) -> left + File.pathSeparator + right)
                .orElse("not configured");
    }

    private static List<Path> parseRoots(String configured, List<String> warnings) {
        if (configured.isBlank()) {
            return List.of();
        }
        LinkedHashSet<Path> valid = new LinkedHashSet<>();
        for (String value : configured.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (value.isBlank()) {
                continue;
            }
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (Files.isDirectory(path) && Files.isReadable(path)) {
                valid.add(path);
            } else {
                warnings.add("Not a readable directory: " + path);
            }
        }
        return List.copyOf(valid);
    }

    private static Path dataDirectory(String configured, List<String> warnings) {
        if (!configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String home = System.getProperty("user.home");
        return platformDataDirectory(System.getProperty("os.name", ""), Path.of(home),
                System.getenv("APPDATA"), warnings);
    }

    /**
     * Resolves the per-user data directory and performs the one-time, safe
     * JARVIS → JADE data migration. An existing legacy directory's contents
     * are copied into the canonical location; the legacy directory itself is
     * never modified or deleted, and an existing JADE directory is never
     * overwritten by legacy data. The SQLite history database, its WAL
     * sidecars and the speaker profiles all carry over untouched.
     */
    static Path platformDataDirectory(String os, Path home, String appData, List<String> warnings) {
        String system = os == null ? "" : os.toLowerCase(Locale.ROOT);
        Path base;
        String canonicalName;
        String legacyName;
        if (system.contains("mac")) {
            base = Path.of(home.toString(), "Library", "Application Support");
            canonicalName = "JADE";
            legacyName = "JARVIS";
        } else if (system.contains("win")) {
            base = Path.of(appData == null || appData.isBlank() ? home.toString() : appData);
            canonicalName = "JADE";
            legacyName = "JARVIS";
        } else {
            base = Path.of(home.toString(), ".local", "share");
            canonicalName = "jade";
            legacyName = "jarvis";
        }
        Path canonical = base.resolve(canonicalName);
        Path legacy = base.resolve(legacyName);
        if (Files.isDirectory(canonical)) {
            return canonical; // existing JADE data always wins
        }
        if (!Files.isDirectory(legacy)) {
            return canonical; // first run: canonical location, nothing to migrate
        }
        try {
            copyDirectory(legacy, canonical);
            warnings.add("Migrated existing JARVIS data from " + legacy + " to " + canonical
                    + "; the legacy folder was kept unchanged.");
            return canonical;
        } catch (IOException e) {
            warnings.add("Could not migrate legacy JARVIS data from " + legacy + " to " + canonical
                    + " (" + e.getMessage() + "); using the legacy location.");
            return legacy;
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path from : paths.sorted().toList()) {
                Path to = target.resolve(source.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /**
     * Canonical JADE settings win; the legacy JARVIS keys are only consulted
     * when no canonical value exists. Order: canonical system property,
     * canonical environment variable, legacy system property, legacy
     * environment variable.
     */
    private static String setting(String canonicalProperty, String canonicalEnvironment,
                                  String legacyProperty, String legacyEnvironment) {
        String value = System.getProperty(canonicalProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(canonicalEnvironment);
        }
        if (value == null || value.isBlank()) {
            value = System.getProperty(legacyProperty);
        }
        if (value == null || value.isBlank()) {
            value = System.getenv(legacyEnvironment);
        }
        return value == null ? "" : value.strip();
    }

    private static String normalizeAlias(String alias) {
        return alias == null ? "" : alias.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
