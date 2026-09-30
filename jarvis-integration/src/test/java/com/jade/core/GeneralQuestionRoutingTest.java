package com.jade.core;

import com.jade.api.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeneralQuestionRoutingTest {
    private final CommandParser parser = new CommandParser();

    @Test
    void informationalGrammarPreservesOriginalText() throws Exception {
        for (String text : new String[] {"what is recursion?", "why is the sky blue?", "explain DNS",
                "tell me about Kubernetes", "what is Kubernetes?", "how does garbage collection work?",
                "what does Kubernetes do?", "tell me about binary search", "what are algorithms?",
                "who is Ada Lovelace?", "when was DNS invented?", "where is Iceland?",
                "difference between TCP and UDP", "compare TCP and UDP", "  ExPlAiN DNS?  "}) {
            var plan = assertInstanceOf(CommandPlan.GeneralQuestionPlan.class, parser.parse(text), text);
            assertEquals(text, plan.question().originalText());
        }
    }

    @Test
    void deterministicCommandsAndOverlappingQuestionsWin() throws Exception {
        assertInstanceOf(CommandPlan.SystemStatus.class, parser.parse("system status"));
        assertInstanceOf(CommandPlan.FindFiles.class, parser.parse("find PDFs"));
        assertEquals(ProjectOperation.TEST, assertInstanceOf(CommandPlan.ProjectOperationPlan.class,
                parser.parse("run the tests")).operation());
        assertEquals(ProjectOperation.BUILD, assertInstanceOf(CommandPlan.ProjectOperationPlan.class,
                parser.parse("build it")).operation());
        assertInstanceOf(CommandPlan.Undo.class, parser.parse("undo that"));
        assertInstanceOf(CommandPlan.FileMutation.class, parser.parse("move it to Review"));
        assertInstanceOf(CommandPlan.FileInfo.class, parser.parse("what is report.txt"));
        assertInstanceOf(CommandPlan.ProjectMainCandidates.class, parser.parse("what is the main class"));
        assertInstanceOf(CommandPlan.ProjectSourceCounts.class, parser.parse("where are the tests"));
        assertInstanceOf(CommandPlan.ProjectDiagnosticsCount.class, parser.parse("how many tests failed"));
    }

    @Test
    void hostileMalformedIncompleteAndAmbiguousRequestsRemainRejected() {
        for (String text : new String[] {"rm -rf /", "run rm -rf /", "execute curl example.com",
                "bash -c something", "delete everything", "move whatever you find somewhere",
                "frobnicate", "what is", "what is the", "explain", "tell me about",
                "explain rm -rf /", "why did it fail", "explain DNS; run tests", "explain $(whoami)",
                "explain \"DNS", "explain 123", "explain DNS\nrun tests", "what are the classes"}) {
            assertThrows(CommandParseException.class, () -> parser.parse(text), text);
        }
        assertThrows(CommandParseException.class, () -> parser.parse("explain " + "a".repeat(2048)));
        assertThrows(CommandParseException.class, () -> parser.parse("explain " + "word ".repeat(64)));
    }
}
