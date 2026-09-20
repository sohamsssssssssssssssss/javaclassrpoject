package com.jade.services.project;

import com.jade.api.BuildSystem;
import com.jade.api.Diagnostic;
import com.jade.api.DiagnosticsReport;
import com.jade.api.ProjectContext;
import com.jade.api.ProjectOperation;
import com.jade.api.ProjectOperationResult;
import com.jade.api.ProjectOperationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostics extraction over generated temp fixtures: Surefire XML
 * (failures, errors, malformed reports, missing reports), conservative
 * output scanning (compilation errors, unknown lines), succeeded runs, and
 * timeouts. No Maven is executed in this class (see the pipeline test).
 */
class MavenDiagnosticsServiceTest {

    @TempDir
    Path temporaryDirectory;

    private final MavenDiagnosticsService service = new MavenDiagnosticsService();

    private Path project() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("proj"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        return root;
    }

    private ProjectContext context(Path root) {
        return new ProjectContext(root, "proj", BuildSystem.MAVEN, root.resolve("pom.xml"));
    }

    private ProjectOperationResult operation(ProjectOperation op, ProjectOperationStatus status, String output) {
        return new ProjectOperationResult(op, "proj", java.nio.file.Paths.get("nowhere"),
                status, status == ProjectOperationStatus.TIMED_OUT ? null : 1,
                100, output, false, status == ProjectOperationStatus.TIMED_OUT);
    }

    private void writeReport(Path root, String name, String xml) throws Exception {
        Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
        Files.writeString(reports.resolve(name), xml);
    }

    private static final String FAILING_REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.CalculatorTest" tests="2" failures="1" errors="0" skipped="0">
                <testcase name="adds" classname="com.example.CalculatorTest" time="0.01"/>
                <testcase name="subtracts" classname="com.example.CalculatorTest" time="0.02">
                    <failure message="expected:&lt;2&gt; but was:&lt;3&gt;" type="java.lang.AssertionError">stack line one
                    stack line two</failure>
                </testcase>
            </testsuite>
            """;

    private static final String ERROR_REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.BrokenTest" tests="1" failures="0" errors="1" skipped="0">
                <testcase name="explodes" classname="com.example.BrokenTest" time="0.01">
                    <error message="boom" type="java.lang.IllegalStateException">at com.example.BrokenTest.explodes</error>
                </testcase>
            </testsuite>
            """;

    @Test
    void testFailureIsExtractedWithClassMethodAndBoundedDetail() throws Exception {
        Path root = project();
        writeReport(root, "TEST-com.example.CalculatorTest.xml", FAILING_REPORT);
        DiagnosticsReport report = service.analyze(context(root),
                operation(ProjectOperation.TEST, ProjectOperationStatus.BUILD_FAILED, "[ERROR] Tests run: 2"));
        assertEquals(1, report.diagnostics().size());
        Diagnostic diagnostic = report.diagnostics().getFirst();
        assertEquals(Diagnostic.Kind.TEST_FAILURE, diagnostic.kind(), diagnostic.toString());
        assertEquals(Diagnostic.Source.SUREFIRE_REPORT, diagnostic.source());
        assertEquals("com.example.CalculatorTest", diagnostic.testClass().orElseThrow());
        assertEquals("subtracts", diagnostic.testMethod().orElseThrow());
        assertTrue(diagnostic.message().orElseThrow().contains("expected"));
        assertTrue(diagnostic.detail().orElseThrow().length() <= Diagnostic.DETAIL_LIMIT);
        assertFalse(report.truncated());
    }

    @Test
    void testErrorIsExtractedAsErrorKind() throws Exception {
        Path root = project();
        writeReport(root, "TEST-com.example.BrokenTest.xml", ERROR_REPORT);
        DiagnosticsReport report = service.analyze(context(root),
                operation(ProjectOperation.TEST, ProjectOperationStatus.BUILD_FAILED, ""));
        assertEquals(Diagnostic.Kind.TEST_ERROR, report.diagnostics().getFirst().kind());
        assertEquals("explodes", report.diagnostics().getFirst().testMethod().orElseThrow());
    }

    @Test
    void succeededRunReportsZeroFailuresWithoutInventingProblems() throws Exception {
        DiagnosticsReport report = service.analyze(context(project()),
                operation(ProjectOperation.TEST, ProjectOperationStatus.SUCCEEDED, "[ERROR] stray noise"));
        assertTrue(report.diagnostics().isEmpty());
        assertEquals(ProjectOperationStatus.SUCCEEDED, report.lastStatus());
    }

    @Test
    void timeoutIsReportedHonestlyAsABuildError() throws Exception {
        DiagnosticsReport report = service.analyze(context(project()),
                operation(ProjectOperation.TEST, ProjectOperationStatus.TIMED_OUT, "partial"));
        assertEquals(1, report.diagnostics().size());
        assertEquals(Diagnostic.Kind.BUILD_ERROR, report.diagnostics().getFirst().kind());
        assertTrue(report.diagnostics().getFirst().message().orElseThrow().contains("timed out"));
    }

    @Test
    void missingReportsFallBackToConservativeOutputScanning() throws Exception {
        Path root = project();
        String output = """
                [INFO] Scanning for projects...
                [ERROR] /tmp/demo/src/main/java/Bad.java:[17,9] ';' expected
                [ERROR] BUILD FAILURE
                """;
        DiagnosticsReport report = service.analyze(context(root),
                operation(ProjectOperation.BUILD, ProjectOperationStatus.BUILD_FAILED, output));
        assertEquals(2, report.diagnostics().size());
        Diagnostic compilation = report.diagnostics().getFirst();
        assertEquals(Diagnostic.Kind.COMPILATION_ERROR, compilation.kind());
        assertEquals(Diagnostic.Source.MAVEN_OUTPUT, compilation.source());
        assertTrue(compilation.location().orElseThrow().endsWith("Bad.java:17"));
        assertEquals(Diagnostic.Kind.BUILD_ERROR, report.diagnostics().getLast().kind());
    }

    @Test
    void unknownErrorLinesStayUnknown() throws Exception {
        Path root = project();
        DiagnosticsReport report = service.analyze(context(root),
                operation(ProjectOperation.TEST, ProjectOperationStatus.BUILD_FAILED,
                        "[ERROR] some totally novel failure shape"));
        assertEquals(1, report.diagnostics().size());
        assertEquals(Diagnostic.Kind.UNKNOWN, report.diagnostics().getFirst().kind());
        assertEquals(Diagnostic.Source.MAVEN_OUTPUT, report.diagnostics().getFirst().source());
    }

    @Test
    void malformedReportBecomesOneHonestDiagnostic() throws Exception {
        Path root = project();
        writeReport(root, "TEST-broken.xml", "<testsuite><unclosed>");
        DiagnosticsReport report = service.analyze(context(root),
                operation(ProjectOperation.TEST, ProjectOperationStatus.BUILD_FAILED, ""));
        assertEquals(1, report.diagnostics().size());
        assertEquals(Diagnostic.Kind.UNKNOWN, report.diagnostics().getFirst().kind());
        assertTrue(report.diagnostics().getFirst().message().orElseThrow().contains("could not be parsed"));
    }

    @Test
    void truncatedOutputAndDiagnosticsAreBounded() throws Exception {
        Path root = project();
        StringBuilder flood = new StringBuilder();
        for (int index = 0; index < 40; index++) {
            flood.append("[ERROR] novel failure shape ").append(index).append("\n");
        }
        DiagnosticsReport report = service.analyze(context(root),
                operation(ProjectOperation.TEST, ProjectOperationStatus.BUILD_FAILED, flood.toString()));
        assertEquals(DiagnosticsReport.MAX_DIAGNOSTICS, report.diagnostics().size());
        assertTrue(report.truncated());
    }

    @Test
    void reportOutsideProjectRootIsRefused() throws Exception {
        Path root = project();
        Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path target = Files.writeString(outside.resolve("TEST-evil.xml"),
                FAILING_REPORT.replace("subtracts", "hijacked"));
        try {
            Files.createSymbolicLink(reports.resolve("TEST-evil.xml"), target);
            DiagnosticsReport report = service.analyze(context(root),
                    operation(ProjectOperation.TEST, ProjectOperationStatus.BUILD_FAILED, ""));
            // Either refused entirely, or surfaced as an unparsable/skip note —
            // never interpreted as real test evidence.
            assertTrue(report.diagnostics().isEmpty()
                    || report.diagnostics().stream().allMatch(d -> d.kind() == Diagnostic.Kind.UNKNOWN));
        } catch (UnsupportedOperationException | IOException e) {
            // Filesystem without symlink support: nothing to prove here.
        }
    }
}
