package com.jade.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Bounded parser grammar for build/test diagnostics phrases. */
class CommandParserDiagnosticsTest {

    private final CommandParser parser = new CommandParser();

    @Test
    void diagnosticsPhrasesMapToTypedPlans() throws Exception {
        assertInstanceOf(CommandPlan.ProjectDiagnostics.class, parser.parse("what failed"));
        assertInstanceOf(CommandPlan.ProjectDiagnostics.class, parser.parse("what broke now"));
        assertInstanceOf(CommandPlan.ProjectDiagnostics.class, parser.parse("what went wrong"));
        assertInstanceOf(CommandPlan.ProjectDiagnostics.class, parser.parse("what errors were there"));
        assertInstanceOf(CommandPlan.ProjectDiagnostics.class, parser.parse("what failed?"));
        assertInstanceOf(CommandPlan.ProjectDiagnosticsCount.class, parser.parse("how many tests failed"));
        assertInstanceOf(CommandPlan.ProjectDiagnosticsCount.class, parser.parse("how many tests are failing"));
    }

    @Test
    void showMeTheErrorsMapsToTheTypedDiagnosticsPlan() throws Exception {
        assertInstanceOf(CommandPlan.ProjectDiagnostics.class, parser.parse("show me the errors"));
        assertInstanceOf(CommandPlan.ProjectDiagnostics.class, parser.parse("show the errors"));
        assertInstanceOf(CommandPlan.ProjectDiagnostics.class, parser.parse("show me the failures"));
    }

    @Test
    void nearMissPhrasesStayRejected() {
        assertThrows(CommandParseException.class, () -> parser.parse("what"));
        assertThrows(CommandParseException.class, () -> parser.parse("why did it fail"));
        assertThrows(CommandParseException.class, () -> parser.parse("fix it"));
        assertThrows(CommandParseException.class, () -> parser.parse("what broke yesterday"));
    }
}
