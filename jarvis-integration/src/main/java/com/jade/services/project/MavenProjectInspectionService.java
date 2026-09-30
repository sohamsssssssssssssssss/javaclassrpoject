package com.jade.services.project;

import com.jade.api.BuildSystem;
import com.jade.api.DependencyInfo;
import com.jade.api.ErrorCode;
import com.jade.api.MainClassCandidates;
import com.jade.api.ProjectContext;
import com.jade.api.ProjectInspectionResult;
import com.jade.api.ProjectInspectionService;
import com.jade.api.ProjectTree;
import com.jade.api.ServiceException;
import com.jade.api.StructuredError;
import com.jade.api.TodoFinding;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static, read-only Maven project inspection.
 *
 * <p>Safety and boundedness: traversal never follows symlinks, skips build
 * outputs and IDE metadata, stops at depth {@link ProjectTree#MAX_DEPTH} and
 * a hard entry cap, scans at most {@link #MAX_FILE_SCAN_BYTES} per Java file,
 * and retains at most {@link #MAX_TODO_FINDINGS} TODO/FIXME findings. Every
 * bound, when reached, is reported truthfully through the result flags. The
 * project is never modified and Maven is never executed or resolved.</p>
 */
public final class MavenProjectInspectionService implements ProjectInspectionService {

    /** Hard cap on visited directory entries during one inspection walk. */
    public static final int MAX_WALKED_ENTRIES = 20_000;
    /** Bounded one-line TODO snippet length. */
    public static final int SNIPPET_LIMIT = 120;

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "target", "build", "out", "bin", "node_modules", ".git", ".idea",
            ".vscode", ".gradle", ".settings", ".classpath", ".project");

    private static final Pattern MAIN_METHOD = Pattern.compile(
            "\\bpublic\\s+static\\s+void\\s+main\\s*\\(");
    private static final Pattern TODO_MARKER = Pattern.compile("\\b(TODO|FIXME)\\b");
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    @Override
    public ProjectInspectionResult inspect(ProjectContext project) throws ServiceException {
        Path root = project.root();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(ErrorCode.INVALID_COMMAND,
                    "The active project folder is no longer present: " + root);
        }
        Path descriptor = project.descriptorPath();
        if (!Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(ErrorCode.INVALID_COMMAND,
                    "The active project descriptor is no longer present: " + descriptor);
        }
        PomModel pom = readPom(descriptor);

        Walk walk = new Walk(root);
        walk.run();

        return new ProjectInspectionResult(
                pom.coordinates(),
                walk.inventory(),
                new ProjectTree(root, walk.treeLines, walk.treeTruncated || walk.walkLimitReached),
                pom.dependencies(),
                new MainClassCandidates(walk.mainCandidates),
                List.copyOf(walk.todoFindings),
                walk.todoFindings.size() >= MAX_TODO_FINDINGS,
                walk.walkLimitReached);
    }

    // ------------------------------------------------------------------
    // pom.xml: safe DOM parsing, static property resolution.
    // ------------------------------------------------------------------

    PomModel readPom(Path descriptor) throws ServiceException {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Hard XXE hardening: DTDs and external entities are refused.
            // The doctype-disallow feature is the primary guard; the
            // access-external attributes are best-effort because not every
            // JAXP implementation recognizes them.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            try {
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            } catch (IllegalArgumentException ignored) {
                // Feature above already refuses DTDs entirely.
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(descriptor.toFile());
        } catch (Exception e) {
            throw failure(ErrorCode.IO_FAILURE,
                    "Could not parse pom.xml: " + rootMessage(e));
        }
        Element projectElement = document.getDocumentElement();

        Map<String, String> properties = new HashMap<>();
        NodeList propertyNodes = projectElement.getElementsByTagName("properties");
        if (propertyNodes.getLength() > 0) {
            Element propertiesElement = (Element) propertyNodes.item(0);
            NodeList children = propertiesElement.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                if (children.item(index) instanceof Element property) {
                    properties.put(property.getTagName(), property.getTextContent().strip());
                }
            }
        }

        String artifactId = firstElementText(projectElement, "artifactId", DependencyInfo.UNKNOWN);
        String groupId = firstElementText(projectElement, "groupId", DependencyInfo.UNKNOWN);
        String version = firstElementText(projectElement, "version", DependencyInfo.UNKNOWN);
        // packaging has a spec-defined default of "jar"; not an invention.
        String packaging = firstElementText(projectElement, "packaging", "jar");
        String name = firstElementText(projectElement, "name", null);

        Optional<String> javaVersion = Optional.empty();
        for (String key : List.of("maven.compiler.release", "maven.compiler.target",
                "maven.compiler.source", "java.version")) {
            String value = properties.get(key);
            if (value != null && !value.isBlank()) {
                javaVersion = Optional.of(value);
                break;
            }
        }

        List<DependencyInfo> dependencies = new ArrayList<>();
        NodeList dependencyNodes = projectElement.getElementsByTagName("dependency");
        for (int index = 0; index < dependencyNodes.getLength(); index++) {
            Element dependency = (Element) dependencyNodes.item(index);
            // Only direct children of <dependencies> are declared dependencies;
            // this also skips dependencyManagement and plugin dependencies.
            if (!(dependency.getParentNode() instanceof Element parent)
                    || !parent.getTagName().equals("dependencies")) {
                continue;
            }
            String depGroup = resolve(firstElementText(dependency, "groupId", null), properties);
            String depArtifact = firstElementText(dependency, "artifactId", DependencyInfo.UNKNOWN);
            String depVersion = resolve(firstElementText(dependency, "version", null), properties);
            String depScope = firstElementText(dependency, "scope", null);
            dependencies.add(new DependencyInfo(
                    depGroup == null ? DependencyInfo.UNKNOWN : depGroup,
                    depArtifact,
                    Optional.ofNullable(depVersion),
                    Optional.ofNullable(depScope)));
        }

        return new PomModel(new ProjectInspectionResult.Coordinates(
                groupId, artifactId, version, packaging,
                Optional.ofNullable(name).filter(value -> !value.isBlank()),
                javaVersion), List.copyOf(dependencies));
    }

    /**
     * Resolves an in-pom property reference ({@code ${x}}) against the same
     * file's declared properties; anything unresolved stays UNKNOWN because
     * it must come from a parent POM or profile that inspection never reads.
     */
    private static String resolve(String raw, Map<String, String> properties) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = PROPERTY_REFERENCE.matcher(raw);
        if (!matcher.find()) {
            return raw;
        }
        String resolved = properties.get(matcher.group(1));
        return resolved == null ? null : resolved;
    }

    /**
     * Text of the first <em>direct</em> child element with the given tag.
     * Deep lookup is deliberately avoided: a POM's {@code <parent>} block
     * also carries {@code groupId}/{@code version}, and reading it as the
     * project's own coordinates would misreport inherited values as
     * explicit ones.
     */
    private static String firstElementText(Element parent, String tag, String fallback) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element element && element.getTagName().equals(tag)) {
                String text = element.getTextContent();
                if (text == null || text.isBlank()) {
                    return fallback;
                }
                return text.strip();
            }
        }
        return fallback;
    }

    record PomModel(ProjectInspectionResult.Coordinates coordinates, List<DependencyInfo> dependencies) {
    }

    // ------------------------------------------------------------------
    // One bounded, sorted, symlink-safe walk collects every fact.
    // ------------------------------------------------------------------

    private final class Walk {
        private final Path root;
        private final List<String> treeLines = new ArrayList<>();
        private final List<MainClassCandidates.Candidate> mainCandidates = new ArrayList<>();
        private final List<TodoFinding> todoFindings = new ArrayList<>();
        private final Set<String> packages = new TreeSet<>();
        private final List<String> sourceRoots = new ArrayList<>();
        private final List<String> testRoots = new ArrayList<>();

        private int javaSourceFiles;
        private int javaTestFiles;
        private int resourceFiles;
        private int visited;
        private boolean treeTruncated;
        private boolean walkLimitReached;

        Walk(Path root) {
            this.root = root;
        }

        ProjectInspectionResult.SourceInventory inventory() {
            return new ProjectInspectionResult.SourceInventory(
                    javaSourceFiles, javaTestFiles, resourceFiles, packages.size(),
                    List.copyOf(sourceRoots), List.copyOf(testRoots));
        }

        void run() throws ServiceException {
            try {
                walkDirectory(root, 0, true);
            } catch (IOException e) {
                throw failure(ErrorCode.IO_FAILURE,
                        "Could not walk the project tree: " + rootMessage(e));
            }
        }

        private void walkDirectory(Path directory, int depth, boolean isRoot) throws IOException {
            if (visited >= MAX_WALKED_ENTRIES) {
                walkLimitReached = true;
                return;
            }
            if (depth > ProjectTree.MAX_DEPTH) {
                treeTruncated = true;
                // Content below this level is neither drawn nor counted, so
                // the inspection must not claim complete inventory numbers.
                walkLimitReached = true;
                return;
            }
            try (var children = Files.list(directory)) {
                List<Path> sorted = children
                        .filter(child -> !Files.isSymbolicLink(child))
                        .filter(child -> !(Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                                && EXCLUDED_DIRECTORIES.contains(
                                        fileName(child).toLowerCase(java.util.Locale.ROOT))))
                        .sorted(Comparator.comparing(MavenProjectInspectionService::fileName))
                        .toList();
                for (Path child : sorted) {
                    visited++;
                    if (visited > MAX_WALKED_ENTRIES) {
                        walkLimitReached = true;
                        return;
                    }
                    boolean isDirectory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                    appendTreeLine(child, depth, isDirectory);
                    if (isDirectory) {
                        walkDirectory(child, depth + 1, false);
                    } else {
                        classifyFile(child);
                    }
                }
            }
        }

        private void appendTreeLine(Path path, int depth, boolean isDirectory) {
            if (treeLines.size() >= ProjectTree.MAX_LINES) {
                treeTruncated = true;
                return;
            }
            String name = fileName(path);
            String indent = "  ".repeat(Math.max(0, depth));
            treeLines.add(indent + name + (isDirectory ? "/" : ""));
        }

        private void classifyFile(Path file) throws IOException {
            String name = fileName(file);
            if (name.endsWith(".java")) {
                String relative = root.relativize(file).toString();
                String standardized = relative.replace('\\', '/');
                boolean isTest = standardized.startsWith("src/test/java/");
                boolean isMain = standardized.startsWith("src/main/java/");
                if (isTest) {
                    javaTestFiles++;
                    recordTestRoot(standardized);
                } else if (isMain) {
                    javaSourceFiles++;
                    recordSourceRoot(standardized);
                } else {
                    // Root-level or non-standard java files still count as sources.
                    javaSourceFiles++;
                }
                if (isMain || isTest) {
                    packages.add(packageOf(standardized));
                }
                scanJavaContent(file, standardized, isTest || isMain
                        ? packageName(standardized) : "");
            } else {
                resourceFiles++;
            }
        }

        private void recordSourceRoot(String standardized) {
            String rootPath = standardized.substring(0, "src/main/java".length());
            if (!sourceRoots.contains(rootPath)) {
                sourceRoots.add(rootPath);
            }
        }

        private void recordTestRoot(String standardized) {
            String rootPath = standardized.substring(0, "src/test/java".length());
            if (!testRoots.contains(rootPath)) {
                testRoots.add(rootPath);
            }
        }

        private void scanJavaContent(Path file, String standardized, String explicitPackage)
                throws IOException {
            if (!Files.isReadable(file)) {
                return;
            }
            long size = Files.size(file);
            if (size > MAX_FILE_SCAN_BYTES) {
                // Oversized source: counted above, but content not inspected.
                walkLimitReached = true;
                return;
            }
            String content;
            try (InputStream input = Files.newInputStream(file)) {
                content = new String(input.readNBytes((int) size), StandardCharsets.UTF_8);
            }
            String className = explicitPackage.isEmpty()
                    ? standardized
                    : explicitPackage + "." + nameWithoutExtension(standardized);

            if (MAIN_METHOD.matcher(content).find()) {
                String signatureLine = lines(content)
                        .filter(line -> MAIN_METHOD.matcher(line).find())
                        .findFirst()
                        .map(line -> line.strip())
                        .orElse("public static void main(...)");
                mainCandidates.add(new MainClassCandidates.Candidate(
                        className, signatureLine, standardized));
            }

            String[] contentLines = content.split("\n", -1);
            for (int index = 0; index < contentLines.length; index++) {
                Matcher matcher = TODO_MARKER.matcher(contentLines[index]);
                if (matcher.find() && todoFindings.size() < MAX_TODO_FINDINGS) {
                    todoFindings.add(new TodoFinding(
                            matcher.group(1),
                            standardized,
                            index + 1,
                            Optional.of(boundedSnippet(contentLines[index]))));
                }
            }
        }

        private String packageOf(String standardized) {
            String withoutFile = standardized.substring(0, standardized.lastIndexOf('/'));
            if (withoutFile.startsWith("src/main/java/")) {
                return withoutFile.substring("src/main/java/".length()).replace('/', '.');
            }
            return withoutFile.substring("src/test/java/".length()).replace('/', '.');
        }

        private String packageName(String standardized) {
            int lastSlash = standardized.lastIndexOf('/');
            if (lastSlash <= 0) {
                return "";
            }
            String directory = standardized.substring(0, lastSlash);
            if (directory.startsWith("src/main/java/")) {
                return directory.substring("src/main/java/".length()).replace('/', '.');
            }
            if (directory.startsWith("src/test/java/")) {
                return directory.substring("src/test/java/".length()).replace('/', '.');
            }
            return "";
        }
    }

    private static java.util.stream.Stream<String> lines(String content) {
        return java.util.Arrays.stream(content.split("\n", -1));
    }

    private static String boundedSnippet(String line) {
        String stripped = line.strip();
        return stripped.length() <= SNIPPET_LIMIT
                ? stripped
                : stripped.substring(0, SNIPPET_LIMIT) + "…";
    }

    private static String nameWithoutExtension(String standardized) {
        String name = standardized.substring(standardized.lastIndexOf('/') + 1);
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static String rootMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static ServiceException failure(ErrorCode code, String message) {
        return new ServiceException(new StructuredError(code, message, Optional.empty()));
    }
}
