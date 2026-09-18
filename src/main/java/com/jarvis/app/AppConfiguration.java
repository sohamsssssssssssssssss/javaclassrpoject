package com.jarvis.app;

import com.jarvis.api.ConfiguredApp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record AppConfiguration(List<Path> searchRoots, Path dataDirectory, String calculatorAlias, List<String> warnings) {
    private static final String ROOTS_PROPERTY = "jarvis.search.roots";
    private static final String ROOTS_ENV = "JARVIS_SEARCH_ROOTS";
    private static final String ALIAS_PROPERTY = "jarvis.app.alias";
    private static final String ALIAS_ENV = "JARVIS_APP_ALIAS";
    private static final String DATA_PROPERTY = "jarvis.data.dir";
    private static final String DATA_ENV = "JARVIS_DATA_DIR";

    AppConfiguration {
        searchRoots = List.copyOf(searchRoots);
        warnings = List.copyOf(warnings);
        calculatorAlias = normalizeAlias(calculatorAlias);
    }

    static AppConfiguration load() {
        List<String> warnings = new ArrayList<>();
        List<Path> roots = parseRoots(setting(ROOTS_PROPERTY, ROOTS_ENV), warnings);
        Path dataDirectory = dataDirectory(setting(DATA_PROPERTY, DATA_ENV));
        String alias = setting(ALIAS_PROPERTY, ALIAS_ENV);
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

    private static Path dataDirectory(String configured) {
        if (!configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "JARVIS");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData == null || appData.isBlank() ? home : appData, "JARVIS");
        }
        return Path.of(home, ".local", "share", "jarvis");
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null ? "" : value.strip();
    }

    private static String normalizeAlias(String alias) {
        return alias == null ? "" : alias.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
