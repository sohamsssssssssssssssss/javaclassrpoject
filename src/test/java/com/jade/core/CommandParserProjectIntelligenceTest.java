package com.jade.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded parser grammar for project-intelligence phrases. Every supported
 * phrase maps to exactly one typed inspection plan; near-misses must stay
 * rejections so the parser never becomes a generic question answerer.
 */
class CommandParserProjectIntelligenceTest {

    private final CommandParser parser = new CommandParser();

    private void assertPlan(String input, Class<? extends CommandPlan> type) throws Exception {
        assertInstanceOf(type, parser.parse(input), input);
    }

    private void assertRejected(String input) {
        assertThrows(CommandParseException.class, () -> parser.parse(input), input);
    }

    @Test
    void inspectionPhrasesMapToTypedPlans() throws Exception {
        assertPlan("what kind of project is this", CommandPlan.InspectProject.class);
        assertPlan("give me a project summary", CommandPlan.InspectProject.class);
        assertPlan("project summary", CommandPlan.InspectProject.class);
        assertPlan("inspect this project", CommandPlan.InspectProject.class);
        assertPlan("inspect the project", CommandPlan.InspectProject.class);
        assertPlan("inspect project", CommandPlan.InspectProject.class);
    }

    @Test
    void structureAndCountPhrasesMapToTypedPlans() throws Exception {
        assertPlan("show me the project structure", CommandPlan.ProjectStructure.class);
        assertPlan("show the project structure", CommandPlan.ProjectStructure.class);
        assertPlan("show project structure", CommandPlan.ProjectStructure.class);
        assertPlan("how many java files are there", CommandPlan.ProjectSourceCounts.class);
        assertPlan("how many java files", CommandPlan.ProjectSourceCounts.class);
        assertPlan("where are the tests", CommandPlan.ProjectSourceCounts.class);
    }

    @Test
    void dependencyMainAndTodoPhrasesMapToTypedPlans() throws Exception {
        assertPlan("what dependencies does it use", CommandPlan.ProjectDependencies.class);
        assertPlan("what dependencies does this project use", CommandPlan.ProjectDependencies.class);
        assertPlan("what is the main class", CommandPlan.ProjectMainCandidates.class);
        assertPlan("what are the main classes", CommandPlan.ProjectMainCandidates.class);
        assertPlan("are there any todos", CommandPlan.ProjectTodos.class);
        assertPlan("what are the todos", CommandPlan.ProjectTodos.class);
        assertPlan("what are the todos in this project", CommandPlan.ProjectTodos.class);
    }

    @Test
    void trailingQuestionMarksAreTolerated() throws Exception {
        assertPlan("are there any todos?", CommandPlan.ProjectTodos.class);
        assertPlan("what dependencies does it use?", CommandPlan.ProjectDependencies.class);
    }

    @Test
    void nearMissPhrasesStayRejected() {
        assertRejected("what kind of project");
        assertRejected("what dependencies do they use");
        assertRejected("how many files are there");
        assertRejected("show me everything");
        assertRejected("give me a summary of everything");
        assertRejected("tell me about the project");
        assertRejected("explain the code");
        assertRejected("what are the classes");
    }

    @Test
    void fileQuestionGrammarIsUnchanged() throws Exception {
        assertPlan("what is report.txt", CommandPlan.FileInfo.class);
        // The 3-token "what is <name>" form predates project intelligence
        // and keeps its file-info meaning.
        assertPlan("what is the", CommandPlan.FileInfo.class);
    }

    @Test
    void legacyCommandsStillParse() throws Exception {
        assertPlan("find pdfs", CommandPlan.FindFiles.class);
        assertPlan("system status", CommandPlan.SystemStatus.class);
        assertPlan("run the tests", CommandPlan.ProjectOperationPlan.class);
        assertPlan("build it", CommandPlan.ProjectOperationPlan.class);
        assertPlan("what happened", CommandPlan.LastProjectOutcome.class);
        assertPlan("what project am I working on", CommandPlan.CurrentProject.class);
    }
}
