package com.jarvis.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandParserTest {
    private final CommandParser parser = new CommandParser();

    @Test
    void parsesSupportedCommandsAndPreservesQuotedAppName() throws Exception {
        CommandPlan.OpenApp open = assertInstanceOf(
                CommandPlan.OpenApp.class, parser.parse("OPEN \"Text Editor\""));
        assertEquals("Text Editor", open.requestedName());
        assertEquals("text editor", open.lookupName());
        assertInstanceOf(CommandPlan.FindFiles.class, parser.parse("find pdf files"));
        assertInstanceOf(CommandPlan.SystemStatus.class, parser.parse("status"));
        assertInstanceOf(CommandPlan.ShowHistory.class, parser.parse("show history"));
    }

    @Test
    void convertsWholeMegabytesToBinaryBytes() throws Exception {
        CommandPlan.FindFiles find = assertInstanceOf(
                CommandPlan.FindFiles.class, parser.parse("find PDFs larger than 20 MB"));
        assertEquals(20L * 1_048_576L, find.query().minimumSizeBytes().orElseThrow());
        assertEquals(50, find.query().maxResults());
        assertEquals(10_000, find.query().scanLimit());
    }

    @Test
    void rejectsMalformedSizesAndUnknownCommands() {
        assertThrows(CommandParseException.class, () -> parser.parse("find PDFs larger than 2.5 MB"));
        assertThrows(CommandParseException.class, () -> parser.parse("find PDFs larger than -1 MB"));
        assertThrows(CommandParseException.class, () -> parser.parse("delete everything"));
        assertThrows(CommandParseException.class, () -> parser.parse("open \"Text Editor"));
    }
}
