package com.jarvis.core;

import com.jarvis.api.FileSearchQuery;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CommandParserTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = LocalDate.of(2026, 9, 18).atTime(15, 0)
            .atZone(ZONE).toInstant();

    private final CommandParser parser = new CommandParser(Clock.fixed(NOW, ZONE));

    private CommandPlan.FindFiles find(String input) throws Exception {
        return assertInstanceOf(CommandPlan.FindFiles.class, parser.parse(input));
    }

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
        FileSearchQuery query = find("find PDFs larger than 20 MB").query();
        assertEquals(20L * 1_048_576L, query.minimumSizeBytes().orElseThrow());
        assertEquals(50, query.maxResults());
        assertEquals(10_000, query.scanLimit());
    }

    @Test
    void parsesGeneralizedExtensionsWithPluralForms() throws Exception {
        assertEquals(java.util.Set.of("txt"), find("find txt files").query().extensions());
        assertEquals(java.util.Set.of("jpg"), find("find jpgs").query().extensions());
        assertEquals(java.util.Set.of("docx"), find("find docx files").query().extensions());
    }

    @Test
    void parsesSizeOperatorsAndUnits() throws Exception {
        FileSearchQuery query = find("find zip files larger than 1 GB smaller than 4 GB").query();
        assertEquals(java.util.OptionalLong.of(1L * CommandParser.BYTES_PER_GB), query.minimumSizeBytes());
        assertEquals(java.util.OptionalLong.of(4L * CommandParser.BYTES_PER_GB), query.maximumSizeBytes());

        FileSearchQuery kib = find("find txt files smaller than 512 kb").query();
        assertEquals(java.util.OptionalLong.of(512L * CommandParser.BYTES_PER_KB), kib.maximumSizeBytes());
    }

    @Test
    void parsesTodayAsLowerBoundFromLocalMidnight() throws Exception {
        FileSearchQuery query = find("find txt files from today").query();
        Instant expectedMidnight = LocalDate.of(2026, 9, 18).atStartOfDay(ZONE).toInstant();
        assertEquals(Optional.of(expectedMidnight), query.modifiedAfter());
        assertEquals(Optional.empty(), query.modifiedBefore());
    }

    @Test
    void parsesYesterdayAsExactlyThePreviousCalendarDay() throws Exception {
        FileSearchQuery query = find("find pdfs from yesterday").query();
        Instant yesterdayStart = LocalDate.of(2026, 9, 17).atStartOfDay(ZONE).toInstant();
        Instant todayStart = LocalDate.of(2026, 9, 18).atStartOfDay(ZONE).toInstant();
        assertEquals(Optional.of(yesterdayStart), query.modifiedAfter());
        assertEquals(Optional.of(todayStart), query.modifiedBefore());
    }

    @Test
    void parsesThisWeekAsMondayMidnight() throws Exception {
        // 2026-09-18 is a Friday; the week started Monday 2026-09-14.
        FileSearchQuery query = find("find pdfs from this week").query();
        Instant monday = LocalDate.of(2026, 9, 14).atStartOfDay(ZONE).toInstant();
        assertEquals(Optional.of(monday), query.modifiedAfter());
    }

    @Test
    void parsesWeekdayAsThatDayOnly() throws Exception {
        FileSearchQuery query = find("find pdfs from saturday").query();
        // Most recent Saturday before Friday 2026-09-18 is 2026-09-12.
        Instant saturday = LocalDate.of(2026, 9, 12).atStartOfDay(ZONE).toInstant();
        Instant sunday = LocalDate.of(2026, 9, 13).atStartOfDay(ZONE).toInstant();
        assertEquals(Optional.of(saturday), query.modifiedAfter());
        assertEquals(Optional.of(sunday), query.modifiedBefore());
    }

    @Test
    void parsesBeforeAsUpperBound() throws Exception {
        FileSearchQuery query = find("find txt files before this month").query();
        Instant september = LocalDate.of(2026, 9, 1).atStartOfDay(ZONE).toInstant();
        assertEquals(Optional.of(september), query.modifiedBefore());
    }

    @Test
    void parsesCreateFolderMoveCopyRenameAndUndo() throws Exception {
        CommandPlan.FileMutation create = assertInstanceOf(
                CommandPlan.FileMutation.class, parser.parse("create folder called College"));
        assertEquals(CommandPlan.FileMutation.Kind.CREATE_FOLDER, create.kind());
        assertEquals("College", create.name());

        CommandPlan.FileMutation move = assertInstanceOf(
                CommandPlan.FileMutation.class, parser.parse("move these files to Review"));
        assertEquals(CommandPlan.FileMutation.Kind.MOVE, move.kind());
        assertEquals(CommandPlan.FileMutation.Selection.LAST_RESULT, move.selection());
        assertEquals("Review", move.name());

        CommandPlan.FileMutation copy = assertInstanceOf(
                CommandPlan.FileMutation.class, parser.parse("copy these files to Review"));
        assertEquals(CommandPlan.FileMutation.Kind.COPY, copy.kind());

        CommandPlan.FileMutation rename = assertInstanceOf(
                CommandPlan.FileMutation.class, parser.parse("rename the newest to summary"));
        assertEquals(CommandPlan.FileMutation.Kind.RENAME, rename.kind());
        assertEquals(CommandPlan.FileMutation.Selection.NEWEST, rename.selection());

        assertInstanceOf(CommandPlan.Undo.class, parser.parse("undo"));
    }

    @Test
    void rejectsUnsafeNamesAndUnknownInputs() {
        assertThrows(CommandParseException.class, () -> parser.parse("create folder called ../escape"));
        assertThrows(CommandParseException.class, () -> parser.parse("move these files to a/b"));
        assertThrows(CommandParseException.class, () -> parser.parse("move these to"));
        assertThrows(CommandParseException.class, () -> parser.parse("find xyz files"));
        assertThrows(CommandParseException.class, () -> parser.parse("delete everything"));
        assertThrows(CommandParseException.class, () -> parser.parse("find PDFs larger than 2.5 MB"));
        assertThrows(CommandParseException.class, () -> parser.parse("find PDFs larger than -1 MB"));
        assertThrows(CommandParseException.class, () -> parser.parse("open \"Text Editor"));
    }

    @Test
    void keepsSprintOneSizeValidationMessages() {
        CommandParseException exception = assertThrows(
                CommandParseException.class, () -> parser.parse("find PDFs larger than 0 MB"));
        assertTrue(exception.getMessage().contains("positive"));
    }

    @Test
    void dateBoundariesUseTheInjectedClock() throws Exception {
        // Same phrase at a different pinned time resolves differently.
        Instant later = LocalDate.of(2026, 10, 1).atTime(23, 30).atZone(ZONE).toInstant();
        CommandParser octoberParser = new CommandParser(Clock.fixed(later, ZONE));
        FileSearchQuery query = octoberParser
                .parse("find pdfs from this month") instanceof CommandPlan.FindFiles f
                ? f.query()
                : null;
        assertNotNull(query);
        assertEquals(Optional.of(LocalDate.of(2026, 10, 1).atStartOfDay(ZONE).toInstant()),
                query.modifiedAfter());
    }
}
