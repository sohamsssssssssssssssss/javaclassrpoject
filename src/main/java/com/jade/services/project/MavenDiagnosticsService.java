package com.jade.services.project;

import com.jade.api.Diagnostic;
import com.jade.api.DiagnosticsReport;
import com.jade.api.ErrorCode;
import com.jade.api.ProjectContext;
import com.jade.api.ProjectDiagnosticsService;
import com.jade.api.ProjectOperation;
import com.jade.api.ProjectOperationResult;
import com.jade.api.ProjectOperationStatus;
import com.jade.api.ServiceException;
import com.jade.api.StructuredError;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured Maven build/test diagnostics without any AI reasoning.
 *
 * <p>Evidence sources, in order of trust: the generated
 * {@code target/surefire-reports/*.xml} of the active project (structured,
 * per-test results) and the bounded captured output of the last project
 * operation (conservative line patterns). Anything that matches no known
 * pattern is surfaced as one honest UNKNOWN diagnostic instead of being
 * interpreted. Only files inside the validated project root are read; Maven
 * is never executed by this service.</p>
 */
public final class MavenDiagnosticsService implements ProjectDiagnosticsService {

    /** Bounded number of report files inspected. */
    public static final int MAX_REPORT_FILES = 50;

    // Conservative Maven output patterns. Each captures the smallest
    // information that is unambiguous; everything else stays UNKNOWN.
    private static final Pattern COMPILATION_ERROR = Pattern.compile(
            "^\\[ERROR\\].*?(/[^:\\[]+\\.java):\\[(\\d+)[^\\]]*]\\s*(.*)$");
    private static final Pattern BUILD_FAILURE = Pattern.compile(
            "^\\[ERROR\\]\\s+(BUILD FAILURE.*|Failed to execute goal .*)$");
    private static final Pattern SUREFIRE_FAILURE_SUMMARY = Pattern.compile(
            "^\\[ERROR\\]\\s+Tests run:.*?Failures: (\\d+).*?Errors: (\\d+).*");
    private static final Pattern TEST_CASE_LINE = Pattern.compile(
            "^\\[ERROR\\]\\s+(\\S+)\\.(\\S+)[.:](?:\\d+:)?\\s*(.*)$");
    private static final Pattern DOCTYPE = Pattern.compile("<!DOCTYPE", Pattern.CASE_INSENSITIVE);

    @Override
    public DiagnosticsReport analyze(ProjectContext project, ProjectOperationResult lastOperation)
            throws ServiceException {
        if (lastOperation.timedOut() || lastOperation.status() == ProjectOperationStatus.TIMED_OUT) {
            // A timed-out run has no trustworthy report state.
            return new DiagnosticsReport(lastOperation.status(), List.of(
                    new Diagnostic(Diagnostic.Kind.BUILD_ERROR, Diagnostic.Source.MAVEN_OUTPUT,
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.of("The project operation timed out before it could finish."),
                            Optional.empty())), false);
        }
        if (lastOperation.status() == ProjectOperationStatus.SUCCEEDED) {
            // A green run is a green run: report zero detected failures
            // rather than inventing a problem from stray output lines.
            return DiagnosticsReport.noneDetected(lastOperation.status());
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        boolean truncated = false;

        // 1) Structured Surefire evidence, when a test run happened.
        if (lastOperation.operation() == ProjectOperation.TEST
                || lastOperation.operation() == ProjectOperation.BUILD) {
            List<Diagnostic> fromReports = readSurefireReports(project.root());
            int reportLimit = DiagnosticsReport.MAX_DIAGNOSTICS;
            truncated = fromReports.size() > reportLimit;
            diagnostics.addAll(fromReports.subList(0, Math.min(fromReports.size(), reportLimit)));
        }

        // 2) Conservative output patterns when reports said nothing.
        if (diagnostics.isEmpty()) {
            diagnostics.addAll(scanOutput(lastOperation.outputSummary()));
            truncated = diagnostics.size() > DiagnosticsReport.MAX_DIAGNOSTICS;
            if (truncated) {
                diagnostics = new ArrayList<>(
                        diagnostics.subList(0, DiagnosticsReport.MAX_DIAGNOSTICS));
            }
        }
        return new DiagnosticsReport(lastOperation.status(), diagnostics, truncated);
    }

    // ------------------------------------------------------------------
    // Surefire XML reports (structured evidence inside the project root).
    // ------------------------------------------------------------------

    private List<Diagnostic> readSurefireReports(Path projectRoot) throws ServiceException {
        Path reportsDirectory = projectRoot.resolve("target").resolve("surefire-reports");
        if (!Files.isDirectory(reportsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Path> reportFiles = new ArrayList<>();
        try (var stream = Files.list(reportsDirectory)) {
            stream.filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> fileName(path).startsWith("TEST-")
                            && fileName(path).endsWith(".xml"))
                    .sorted()
                    .limit(MAX_REPORT_FILES)
                    .forEach(reportFiles::add);
        } catch (IOException e) {
            throw failure(ErrorCode.IO_FAILURE,
                    "Could not read surefire reports: " + rootMessage(e));
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Path report : reportFiles) {
            if (diagnostics.size() >= DiagnosticsReport.MAX_DIAGNOSTICS) {
                break;
            }
            diagnostics.addAll(parseSurefireXml(report, projectRoot));
        }
        return diagnostics;
    }

    private List<Diagnostic> parseSurefireXml(Path report, Path projectRoot) throws ServiceException {
        // Stay inside the project root: reject reports that are symlinks or
        // resolve outside the validated root.
        try {
            Path realReport = report.toRealPath();
            if (!realReport.startsWith(projectRoot.toRealPath())) {
                return List.of();
            }
        } catch (IOException e) {
            return List.of();
        }
        if (containsDoctype(report)) {
            // XXE-hardened: a doctype in a report is refused, not parsed.
            return List.of(new Diagnostic(Diagnostic.Kind.UNKNOWN, Diagnostic.Source.SUREFIRE_REPORT,
                    Optional.of(fileName(report)), Optional.empty(), Optional.of(relativize(report, projectRoot)),
                    Optional.of("Surefire report was skipped: it declares a DOCTYPE."),
                    Optional.empty()));
        }
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            try {
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            } catch (IllegalArgumentException ignored) {
                // Not every JAXP implementation supports these attributes.
            }
            document = factory.newDocumentBuilder().parse(report.toFile());
        } catch (Exception e) {
            // A malformed report is one honest diagnostic, not a crash.
            return List.of(new Diagnostic(Diagnostic.Kind.UNKNOWN, Diagnostic.Source.SUREFIRE_REPORT,
                    Optional.of(fileName(report)), Optional.empty(), Optional.of(relativize(report, projectRoot)),
                    Optional.of("Surefire report could not be parsed: " + rootMessage(e)),
                    Optional.empty()));
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        Element root = document.getDocumentElement();
        String suiteClass = root.getAttribute("name");

        NodeList cases = root.getElementsByTagName("testcase");
        for (int index = 0; index < cases.getLength(); index++) {
            Element testCase = (Element) cases.item(index);
            Element failure = firstChild(testCase, "failure");
            Element error = firstChild(testCase, "error");
            Element problem = failure != null ? failure : error;
            if (problem == null) {
                continue;
            }
            String message = bounded(problem.getAttribute("message"), Diagnostic.MESSAGE_LIMIT);
            String detail = bounded(problem.getTextContent(), Diagnostic.DETAIL_LIMIT);
            String type = problem.getAttribute("type");
            diagnostics.add(new Diagnostic(
                    failure != null ? Diagnostic.Kind.TEST_FAILURE : Diagnostic.Kind.TEST_ERROR,
                    Diagnostic.Source.SUREFIRE_REPORT,
                    Optional.of(testCase.getAttribute("classname").isBlank()
                            ? suiteClass : testCase.getAttribute("classname")),
                    Optional.of(testCase.getAttribute("name")),
                    Optional.of(relativize(report, projectRoot)),
                    Optional.of(message.isBlank() ? type : message),
                    Optional.of(detail)));
        }
        return diagnostics;
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element element && element.getTagName().equals(tag)) {
                return element;
            }
        }
        return null;
    }

    private boolean containsDoctype(Path report) {
        try (var reader = Files.newBufferedReader(report)) {
            char[] buffer = new char[2048];
            int read = reader.read(buffer);
            return read > 0 && DOCTYPE.matcher(new String(buffer, 0, read)).find();
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Conservative bounded-output scanning (used when reports say nothing).
    // ------------------------------------------------------------------

    private List<Diagnostic> scanOutput(String outputSummary) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (String line : outputSummary.split("\n", -1)) {
            if (diagnostics.size() > DiagnosticsReport.MAX_DIAGNOSTICS) {
                break;
            }
            if (line.isBlank() || !line.startsWith("[ERROR]")) {
                continue;
            }
            Matcher compilation = COMPILATION_ERROR.matcher(line);
            if (compilation.matches()) {
                diagnostics.add(new Diagnostic(
                        Diagnostic.Kind.COMPILATION_ERROR, Diagnostic.Source.MAVEN_OUTPUT,
                        Optional.empty(), Optional.empty(),
                        Optional.of(compilation.group(1) + ":" + compilation.group(2)),
                        Optional.of(bounded(compilation.group(3), Diagnostic.MESSAGE_LIMIT)),
                        Optional.of(bounded(line, Diagnostic.DETAIL_LIMIT))));
                continue;
            }
            Matcher testCase = TEST_CASE_LINE.matcher(line);
            if (testCase.matches()) {
                boolean looksLikeTestResult = line.contains("expected") || line.contains("Exception")
                        || line.contains("assert") || line.contains("Assert");
                diagnostics.add(new Diagnostic(
                        looksLikeTestResult ? Diagnostic.Kind.TEST_FAILURE : Diagnostic.Kind.UNKNOWN,
                        Diagnostic.Source.MAVEN_OUTPUT,
                        Optional.of(testCase.group(1)), Optional.of(testCase.group(2)),
                        Optional.empty(),
                        Optional.of(bounded(testCase.group(3), Diagnostic.MESSAGE_LIMIT)),
                        Optional.of(bounded(line, Diagnostic.DETAIL_LIMIT))));
                continue;
            }
            Matcher buildFailure = BUILD_FAILURE.matcher(line);
            if (buildFailure.matches()) {
                diagnostics.add(new Diagnostic(
                        Diagnostic.Kind.BUILD_ERROR, Diagnostic.Source.MAVEN_OUTPUT,
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(bounded(buildFailure.group(1), Diagnostic.MESSAGE_LIMIT)),
                        Optional.of(bounded(line, Diagnostic.DETAIL_LIMIT))));
                continue;
            }
            if (SUREFIRE_FAILURE_SUMMARY.matcher(line).matches()) {
                // The summary is redundant with per-test evidence; skip it.
                continue;
            }
            // Unknown [ERROR] line: one honest UNKNOWN per line, bounded.
            diagnostics.add(new Diagnostic(
                    Diagnostic.Kind.UNKNOWN, Diagnostic.Source.MAVEN_OUTPUT,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of("Unrecognised Maven error output"),
                    Optional.of(bounded(line, Diagnostic.DETAIL_LIMIT))));
        }
        return diagnostics;
    }

    // ------------------------------------------------------------------

    private static String bounded(String value, int limit) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() <= limit ? stripped : stripped.substring(0, limit) + "…";
    }

    private static String relativize(Path report, Path root) {
        try {
            return root.relativize(report).toString();
        } catch (IllegalArgumentException e) {
            return report.toString();
        }
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
