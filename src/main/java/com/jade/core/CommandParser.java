package com.jade.core;

import com.jade.api.ErrorCode;
import com.jade.api.FileSearchQuery;
import com.jade.api.StructuredError;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Typed command parser.
 *
 * <p>Sprint 1 shape is preserved (open, find PDFs, system status, history);
 * sprint 2 adds generalized filename search (any known extension, size
 * operators and units, date phrases), folder creation, move/copy/rename with
 * selection phrases, and undo. Natural language is never turned into shell
 * text: the parser only produces typed plans whose string fields are lookup
 * keys resolved against configured apps, known extensions and configured
 * scope roots.</p>
 *
 * <p>Date phrases ("today", "yesterday", "this week", "this month", "on
 * Saturday") resolve to calendar windows against an injected {@link Clock}
 * so tests can pin the boundary semantics deterministically. "from X" applies
 * the phrase's own window ("from yesterday" = modified yesterday only; "from
 * today" = today onward); "before X" is an upper bound at the start of X.</p>
 */
public final class CommandParser {
    public static final int MAX_RESULTS = 50;
    public static final int SCAN_LIMIT = 10_000;
    public static final long BYTES_PER_KB = 1_024L;
    public static final long BYTES_PER_MB = 1_048_576L;
    public static final long BYTES_PER_GB = 1_073_741_824L;

    private static final Set<String> KNOWN_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "txt", "md", "ppt", "pptx", "xls", "xlsx",
            "png", "jpg", "jpeg", "gif", "svg",
            "mp3", "wav", "mp4", "mov", "avi", "mkv",
            "zip", "tar", "gz", "rar", "7z",
            "java", "py", "ts", "js", "html", "css", "json", "xml", "csv", "jar");

    private static final Set<String> WEEKDAYS = Set.of(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

    private static final Set<String> FULL_DATE_PHRASES = prefixed(
            Set.of("today", "yesterday", "this week", "this month"), WEEKDAYS);



    private final CommandTokenizer tokenizer = new CommandTokenizer();
    private final Clock clock;

    public CommandParser() {
        this(Clock.systemUTC());
    }

    public CommandParser(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public CommandPlan parse(String input) throws CommandParseException {
        if (input == null || input.isBlank()) {
            throw invalid("Enter a command");
        }
        List<CommandTokenizer.Token> tokens = tokenizer.tokenize(input);
        if (tokens.isEmpty()) {
            throw invalid("Enter a command");
        }
        String first = keyword(tokens.getFirst());
        switch (first) {
            case "open" -> {
                if (tokens.size() >= 3 && keyword(tokens.get(1)).equals("project")) {
                    return new CommandPlan.OpenProject(join(tokens.subList(2, tokens.size())).strip());
                }
                return parseOpen(tokens);
            }
            case "run" -> {
                // "run [the] tests" — the only project verb besides build.
                int subject = 1;
                if (tokens.size() > 1 && keyword(tokens.get(1)).equals("the")) {
                    subject = 2;
                }
                if (tokens.size() == subject + 1 && keyword(tokens.get(subject)).equals("tests")) {
                    return new CommandPlan.ProjectOperationPlan(com.jade.api.ProjectOperation.TEST);
                }
                throw invalid("Usage: run [the] tests");
            }
            case "build" -> {
                // The project-build verb: "build it" refers to the active
                // project, never to the contextual file selection.
                if (tokens.size() == 2 && keyword(tokens.get(1)).equals("it")) {
                    return new CommandPlan.ProjectOperationPlan(com.jade.api.ProjectOperation.BUILD);
                }
                throw invalid("Usage: build it");
            }
            case "only" -> {
                return parseRefinement(tokens);
            }
            case "confirm" -> {
                if (tokens.size() == 1) {
                    return new CommandPlan.ConfirmPending();
                }
                throw invalid("Usage: confirm");
            }
            case "cancel" -> {
                if (tokens.size() == 1) {
                    return new CommandPlan.CancelPending();
                }
                throw invalid("Usage: cancel");
            }
            case "find" -> {
                for (int index = 2; index < tokens.size(); index++) {
                    if (keyword(tokens.get(index)).equals("containing")) {
                        return parseContaining(tokens);
                        }
                }
                return parseFind(tokens);
            }
            case "create" -> {
                return parseCreate(tokens);
            }
            case "move" -> {
                return parseTransfer(tokens, CommandPlan.FileMutation.Kind.MOVE);
            }
            case "copy" -> {
                return parseTransfer(tokens, CommandPlan.FileMutation.Kind.COPY);
            }
            case "rename" -> {
                return parseRename(tokens);
            }
            case "undo" -> {
                if (tokens.size() == 1) {
                    return new CommandPlan.Undo();
                }
                if (tokens.size() == 2 && keyword(tokens.get(1)).equals("that")) {
                    // "undo that" is the conversational form of "undo".
                    return new CommandPlan.Undo();
                }
                throw invalid("Usage: undo [that]");
            }
            case "list" -> {
                if (matches(tokens, "list", "files")) {
                    return new CommandPlan.ListFiles();
                }
                throw invalid("Usage: list files");
            }
            case "file" -> {
                if (tokens.size() == 3 && keyword(tokens.get(1)).equals("info")) {
                    return new CommandPlan.FileInfo(tokens.get(2).value().strip());
                }
                throw invalid("Usage: file info <name>");
            }
            case "what" -> {
                if (tokens.size() == 2 && keyword(tokens.get(1)).equals("happened")) {
                    return new CommandPlan.LastProjectOutcome();
                }
                if (tokens.size() == 6
                        && keyword(tokens.get(1)).equals("project")
                        && keyword(tokens.get(2)).equals("am")
                        && keyword(tokens.get(3)).equals("i")
                        && keyword(tokens.get(4)).equals("working")
                        && keyword(tokens.get(5)).equals("on")) {
                    return new CommandPlan.CurrentProject();
                }
                if (tokens.size() == 3 && keyword(tokens.get(1)).equals("is")) {
                    return new CommandPlan.FileInfo(tokens.get(2).value().strip());
                }
                CommandPlan projectQuestion = parseProjectQuestion(tokens);
                if (projectQuestion != null) {
                    return projectQuestion;
                }
                throw invalid("Unsupported command. Try 'what is <file name>', "
                        + "'what project am I working on', 'what happened', "
                        + "or 'what dependencies does it use'");
            }
            default -> {
                CommandPlan projectQuestion = parseProjectPhrase(tokens);
                if (projectQuestion != null) {
                    return projectQuestion;
                }
                if (matches(tokens, "system", "status") || matches(tokens, "status")) {
                    return new CommandPlan.SystemStatus();
                }
                if (matches(tokens, "show", "history") || matches(tokens, "history")) {
                    return new CommandPlan.ShowHistory();
                }
                throw invalid("Unsupported command. Try open, find PDFs, system status, or show history");
            }
        }
    }

    /**
     * Non-"what" bounded project-intelligence phrases. Each maps to exactly
     * one typed inspection plan; anything else falls through to rejection.
     */
    private CommandPlan parseProjectPhrase(List<CommandTokenizer.Token> tokens) {
        List<String> words = phraseWords(tokens);
        // "show me the project structure" / "show the project structure" / "show project structure"
        if ((words.size() == 5 && words.get(0).equals("show") && words.get(1).equals("me")
                && words.get(2).equals("the") && words.get(3).equals("project")
                && words.get(4).equals("structure"))
                || (words.size() == 4 && words.get(0).equals("show") && words.get(1).equals("the")
                        && words.get(2).equals("project") && words.get(3).equals("structure"))
                || (words.size() == 3 && words.get(0).equals("show") && words.get(1).equals("project")
                        && words.get(2).equals("structure"))) {
            return new CommandPlan.ProjectStructure();
        }
        // "how many java files are there" / "how many java files"
        if ((words.size() == 6 && words.get(0).equals("how") && words.get(1).equals("many")
                && words.get(2).equals("java") && words.get(3).equals("files")
                && words.get(4).equals("are") && words.get(5).equals("there"))
                || (words.size() == 4 && words.get(0).equals("how") && words.get(1).equals("many")
                        && words.get(2).equals("java") && words.get(3).equals("files"))) {
            return new CommandPlan.ProjectSourceCounts();
        }
        // "give me a project summary" / "project summary"
        if ((words.size() == 5 && words.get(0).equals("give") && words.get(1).equals("me")
                && words.get(2).equals("a") && words.get(3).equals("project")
                && words.get(4).equals("summary"))
                || (words.size() == 2 && words.get(0).equals("project")
                        && words.get(1).equals("summary"))) {
            return new CommandPlan.InspectProject();
        }
        // "where are the tests"
        if (words.size() == 4 && words.get(0).equals("where") && words.get(1).equals("are")
                && words.get(2).equals("the") && words.get(3).equals("tests")) {
            return new CommandPlan.ProjectSourceCounts();
        }
        // "are there any todos" / "are there any todos?"
        if (words.size() == 4 && words.get(0).equals("are") && words.get(1).equals("there")
                && words.get(2).equals("any") && words.get(3).equals("todos")) {
            return new CommandPlan.ProjectTodos();
        }
        if (words.size() == 5 && words.get(0).equals("are") && words.get(1).equals("there")
                && words.get(2).equals("any") && words.get(3).equals("todos")
                && words.get(4).equals("?")) {
            return new CommandPlan.ProjectTodos();
        }
        // "inspect this project" / "inspect the project" / "inspect project"
        if ((words.size() == 3 && words.get(0).equals("inspect")
                && (words.get(1).equals("this") || words.get(1).equals("the"))
                && words.get(2).equals("project"))
                || (words.size() == 2 && words.get(0).equals("inspect")
                        && words.get(1).equals("project"))) {
            return new CommandPlan.InspectProject();
        }
        return null;
    }

    /**
     * Bounded project-intelligence question grammar under "what":
     * kind of project is this / give me a project summary (also bare
     * "project summary"), dependencies does it use, main class, todos.
     * Nothing here is a generic question answerer — each phrase maps to
     * exactly one typed inspection plan.
     */
    /** Lower-cased keywords with one trailing question mark stripped. */
    private static List<String> phraseWords(List<CommandTokenizer.Token> tokens) {
        return tokens.stream().map(CommandParser::keyword)
                .map(word -> word.endsWith("?") ? word.substring(0, word.length() - 1) : word)
                .filter(word -> !word.isEmpty())
                .toList();
    }

    private CommandPlan parseProjectQuestion(List<CommandTokenizer.Token> tokens) {
        List<String> words = phraseWords(tokens);
        // "what kind of project is this"
        if (words.size() == 6 && words.get(1).equals("kind") && words.get(2).equals("of")
                && words.get(3).equals("project") && words.get(4).equals("is")
                && words.get(5).equals("this")) {
            return new CommandPlan.InspectProject();
        }
        // "what are the main classes" (plural acceptance of the main-class intent)
        if (words.size() == 5 && words.get(1).equals("are") && words.get(2).equals("the")
                && words.get(3).equals("main") && words.get(4).equals("classes")) {
            return new CommandPlan.ProjectMainCandidates();
        }
        // "what dependencies does it use" / "... does this project use"
        if (words.size() == 5 && words.get(1).equals("dependencies") && words.get(2).equals("does")
                && words.get(3).equals("it") && words.get(4).equals("use")) {
            return new CommandPlan.ProjectDependencies();
        }
        if (words.size() == 7 && words.get(1).equals("dependencies") && words.get(2).equals("does")
                && words.get(3).equals("this") && words.get(4).equals("project")
                && words.get(5).equals("use") && words.get(6).equals("?")) {
            return new CommandPlan.ProjectDependencies();
        }
        if (words.size() == 6 && words.get(1).equals("dependencies") && words.get(2).equals("does")
                && words.get(3).equals("this") && words.get(4).equals("project")
                && words.get(5).equals("use")) {
            return new CommandPlan.ProjectDependencies();
        }
        // "what is the main class" — "is" handled above for files, so this
        // longer form must be checked before the 3-token file-info form.
        if (words.size() == 5 && words.get(1).equals("is") && words.get(2).equals("the")
                && words.get(3).equals("main") && words.get(4).equals("class")) {
            return new CommandPlan.ProjectMainCandidates();
        }
        // "what are the todos" / "what are the todos in this project"
        if (words.size() == 4 && words.get(1).equals("are") && words.get(2).equals("the")
                && words.get(3).equals("todos")) {
            return new CommandPlan.ProjectTodos();
        }
        if (words.size() == 8 && words.get(1).equals("are") && words.get(2).equals("the")
                && words.get(3).equals("todos") && words.get(4).equals("in")
                && words.get(5).equals("this") && words.get(6).equals("project")
                && words.get(7).equals("?")) {
            return new CommandPlan.ProjectTodos();
        }
        if (words.size() == 7 && words.get(1).equals("are") && words.get(2).equals("the")
                && words.get(3).equals("todos") && words.get(4).equals("in")
                && words.get(5).equals("this") && words.get(6).equals("project")) {
            return new CommandPlan.ProjectTodos();
        }
        return null;
    }

    private CommandPlan parseOpen(List<CommandTokenizer.Token> tokens) throws CommandParseException {
        if (tokens.size() < 2) {
            throw invalid("Usage: open <configured app>");
        }
        // Contextual file selectors share the "open" verb. They are matched
        // first so a scope file named like an app can never shadow them and
        // an app named "it" can never hijack the pronoun.
        if (tokens.size() == 2 && keyword(tokens.get(1)).equals("it")) {
            return new CommandPlan.OpenSelected();
        }
        if (tokens.size() == 3 && keyword(tokens.get(1)).equals("the")) {
            switch (keyword(tokens.get(2))) {
                case "newest", "oldest" -> {
                    return new CommandPlan.SelectFile(keyword(tokens.get(2)).equals("newest")
                            ? CommandPlan.SelectFile.Target.NEWEST
                            : CommandPlan.SelectFile.Target.OLDEST);
                }
                case "file" -> {
                    return new CommandPlan.OpenSelected();
                }
                default -> {
                    // Fall through to application opening below.
                }
            }
        }
        String requested = join(tokens.subList(1, tokens.size()));
        if (requested.isBlank()) {
            throw invalid("Application name must not be empty");
        }
        return new CommandPlan.OpenApp(requested, normalizeLookup(requested));
    }

    private CommandPlan parseFind(List<CommandTokenizer.Token> tokens) throws CommandParseException {
        // The subject is an extension word, optionally plural ("pdfs", "jpgs")
        // and optionally followed by "files" ("find pdf files", "find txt files").
        if (tokens.size() < 2) {
            throw invalid("Usage: find <extension> files [filters]");
        }
        String word = keyword(tokens.get(1));
        String extension = singularExtension(word);
        if (extension.isEmpty()) {
            throw invalid("Unknown file type: " + word
                    + ". Try: find pdfs, find txt files, find jpgs");
        }
        int remainderStart = tokens.size() > 2 && keyword(tokens.get(2)).equals("files") ? 3 : 2;
        return new CommandPlan.FindFiles(
                parseFilters(tokens.subList(remainderStart, tokens.size()), Set.of(extension)));
    }

    /**
     * Parses the filter tail of a find command. Grammar (clauses optional,
     * any order, all bounded by the sprint scan limits):
     * {@code [larger|smaller than N unit] [from|after DATE] [before DATE]}.
     */
    private FileSearchQuery parseFilters(List<CommandTokenizer.Token> tokens, Set<String> extensions)
            throws CommandParseException {
        OptionalLong minimum = OptionalLong.empty();
        OptionalLong maximum = OptionalLong.empty();
        Optional<Instant> after = Optional.empty();
        Optional<Instant> before = Optional.empty();
        int index = 0;
        while (index < tokens.size()) {
            String word = keyword(tokens.get(index));
            switch (word) {
                case "larger", "bigger", "smaller" -> {
                    boolean larger = !word.equals("smaller");
                    if (index + 4 > tokens.size() || !keyword(tokens.get(index + 1)).equals("than")) {
                        throw invalid("Usage: find " + extensions.iterator().next() + " files "
                                + word + " than <number> <unit>");
                    }
                    long amount = parseAmount(tokens.get(index + 2).value());
                    long unit = parseUnit(keyword(tokens.get(index + 3)));
                    long bytes = multiply(amount, unit);
                    if (larger) {
                        minimum = OptionalLong.of(bytes);
                    } else {
                        maximum = OptionalLong.of(bytes);
                    }
                    index += 4;
                }
                case "from", "after", "since" -> {
                    DatePhrase phrase = consumeDatePhrase(tokens, index + 1);
                    DateWindow window = resolveWindow(phrase.phrase());
                    after = Optional.of(window.start());
                    if (window.end().isPresent() && before.isEmpty()) {
                        before = window.end();
                    }
                    index = phrase.endIndex();
                }
                case "before" -> {
                    DatePhrase phrase = consumeDatePhrase(tokens, index + 1);
                    before = Optional.of(resolveWindow(phrase.phrase()).start());
                    index = phrase.endIndex();
                }
                default -> throw invalid("Unsupported search filter: " + tokens.get(index).value());
            }
        }
        try {
            return new FileSearchQuery(extensions, minimum, maximum, after, before, MAX_RESULTS, SCAN_LIMIT);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    /**
     * Consumes a multi-word date phrase token by token. "this week" arrives
     * as two tokens because the tokenizer splits on whitespace; "on Saturday"
     * arrives as two tokens as well. Returns the full phrase and the index of
     * the first token after it.
     */
    private DatePhrase consumeDatePhrase(List<CommandTokenizer.Token> tokens, int start)
            throws CommandParseException {
        StringBuilder accumulated = new StringBuilder();
        int index = start;
        while (index < tokens.size()) {
            String candidate = accumulated.isEmpty()
                    ? keyword(tokens.get(index))
                    : accumulated + " " + keyword(tokens.get(index));
            if (FULL_DATE_PHRASES.contains(candidate)) {
                return new DatePhrase(candidate, index + 1);
            }
            if (candidate.equals("this") || candidate.equals("on")) {
                accumulated = new StringBuilder(candidate);
                index++;
                continue;
            }
            break;
        }
        throw invalid("Unsupported date phrase: "
                + (accumulated.isEmpty() ? "<missing>" : accumulated.toString()));
    }

    private record DatePhrase(String phrase, int endIndex) {
    }

    /**
     * Calendar window of a date phrase, pinned by {@link Clock}. "today" is
     * [today 00:00, ∞), "yesterday" the full previous calendar day, "this
     * week" starts Monday 00:00, "this month" the 1st 00:00, and "on
     * <weekday>" the most recent occurrence of that weekday, that day only.
     */
    private DateWindow resolveWindow(String phrase) throws CommandParseException {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), clock.getZone());
        ZonedDateTime midnight = now.toLocalDate().atStartOfDay(now.getZone());
        return switch (phrase) {
            case "today" -> new DateWindow(midnight.toInstant(), Optional.empty());
            case "yesterday" -> new DateWindow(
                    midnight.minusDays(1).toInstant(), Optional.of(midnight.toInstant()));
            case "this week" -> new DateWindow(
                    midnight.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toInstant(),
                    Optional.empty());
            case "this month" -> new DateWindow(
                    midnight.withDayOfMonth(1).toInstant(), Optional.empty());
            default -> weekdayWindow(midnight, phrase);
        };
    }

    private static DateWindow weekdayWindow(ZonedDateTime midnight, String phrase) throws CommandParseException {
        String day = phrase.startsWith("on ") ? phrase.substring(3) : phrase;
        DayOfWeek weekday = switch (day) {
            case "monday" -> DayOfWeek.MONDAY;
            case "tuesday" -> DayOfWeek.TUESDAY;
            case "wednesday" -> DayOfWeek.WEDNESDAY;
            case "thursday" -> DayOfWeek.THURSDAY;
            case "friday" -> DayOfWeek.FRIDAY;
            case "saturday" -> DayOfWeek.SATURDAY;
            case "sunday" -> DayOfWeek.SUNDAY;
            default -> null;
        };
        if (weekday == null) {
            throw invalid("Unsupported date phrase: " + phrase);
        }
        ZonedDateTime start = midnight.with(TemporalAdjusters.previousOrSame(weekday));
        return new DateWindow(start.toInstant(), Optional.of(start.plusDays(1).toInstant()));
    }

    private record DateWindow(Instant start, Optional<Instant> end) {
    }

    /**
     * Parses a context refinement ({@code "only …"}) of the most recent
     * successful search. Grammar: {@code [larger|smaller than N unit]
     * [from|after|since|before DATE] [<extension>[s] [files]]} — each
     * recognized clause replaces that constraint dimension, while every
     * constraint not mentioned is inherited from the previous structured
     * query by the gateway. The previous sentence is never re-parsed.
     */
    private CommandPlan parseRefinement(List<CommandTokenizer.Token> tokens) throws CommandParseException {
        if (tokens.size() < 2) {
            throw invalid("Usage: only larger|smaller than <size>, only from <date>, or only <type> [files]");
        }
        OptionalLong minimum = OptionalLong.empty();
        OptionalLong maximum = OptionalLong.empty();
        Optional<Instant> after = Optional.empty();
        Optional<Instant> before = Optional.empty();
        Set<String> extensions = null;
        int index = 1;
        while (index < tokens.size()) {
            String word = keyword(tokens.get(index));
            switch (word) {
                case "larger", "bigger", "smaller" -> {
                    boolean larger = !word.equals("smaller");
                    if (index + 4 > tokens.size() || !keyword(tokens.get(index + 1)).equals("than")) {
                        throw invalid("Usage: only " + word + " than <number> <unit>");
                    }
                    long amount = parseAmount(tokens.get(index + 2).value());
                    long unit = parseUnit(keyword(tokens.get(index + 3)));
                    long bytes = multiply(amount, unit);
                    if (larger) {
                        minimum = OptionalLong.of(bytes);
                    } else {
                        maximum = OptionalLong.of(bytes);
                    }
                    index += 4;
                }
                case "from", "after", "since" -> {
                    DatePhrase phrase = consumeDatePhrase(tokens, index + 1);
                    DateWindow window = resolveWindow(phrase.phrase());
                    after = Optional.of(window.start());
                    if (window.end().isPresent() && before.isEmpty()) {
                        before = window.end();
                    }
                    index = phrase.endIndex();
                }
                case "before" -> {
                    DatePhrase phrase = consumeDatePhrase(tokens, index + 1);
                    before = Optional.of(resolveWindow(phrase.phrase()).start());
                    index = phrase.endIndex();
                }
                default -> {
                    String extension = singularExtension(word);
                    if (extension.isEmpty()) {
                        throw invalid("Unsupported refinement: " + tokens.get(index).value());
                    }
                    extensions = Set.of(extension);
                    index++;
                    if (index < tokens.size() && keyword(tokens.get(index)).equals("files")) {
                        index++;
                    }
                }
            }
        }
        if (extensions == null && minimum.isEmpty() && maximum.isEmpty()
                && after.isEmpty() && before.isEmpty()) {
            throw invalid("Nothing to refine; try: only larger than 20 MB, only from today, or only PDFs");
        }
        return new CommandPlan.RefineSearch(new FileSearchQuery.Refinement(
                extensions == null ? Optional.empty() : Optional.of(extensions),
                minimum, maximum, after, before));
    }

    private CommandPlan parseCreate(List<CommandTokenizer.Token> tokens) throws CommandParseException {
        if (tokens.size() != 4 || !keyword(tokens.get(1)).equals("folder")
                || !keyword(tokens.get(2)).equals("called")) {
            throw invalid("Usage: create folder called <name>");
        }
        String name = tokens.get(3).value().strip();
        if (name.isEmpty()) {
            throw invalid("Folder name must not be empty");
        }
        requireSafeName(name);
        return new CommandPlan.FileMutation(
                CommandPlan.FileMutation.Kind.CREATE_FOLDER,
                CommandPlan.FileMutation.Selection.ALL_IN_SCOPE,
                name,
                List.of());
    }

    private CommandPlan parseTransfer(List<CommandTokenizer.Token> tokens, CommandPlan.FileMutation.Kind kind)
            throws CommandParseException {
        String verb = kind.name().toLowerCase(Locale.ROOT);
        if (tokens.size() < 3) {
            throw invalid("Usage: " + verb + " these [files] to <folder>");
        }
        int index;
        CommandPlan.FileMutation.Selection selection;
        switch (keyword(tokens.get(1))) {
            case "these" -> {
                index = 2;
                if (keyword(tokens.get(index)).equals("files")) {
                    index++;
                }
                selection = CommandPlan.FileMutation.Selection.LAST_RESULT;
            }
            case "the" -> {
                if (tokens.size() < 3) {
                    throw invalid("Usage: " + verb + " the newest|oldest|file to <folder>");
                }
                switch (keyword(tokens.get(2))) {
                    case "newest" -> {
                        selection = CommandPlan.FileMutation.Selection.NEWEST;
                        index = 3;
                    }
                    case "oldest" -> {
                        selection = CommandPlan.FileMutation.Selection.OLDEST;
                        index = 3;
                    }
                    case "file" -> {
                        selection = CommandPlan.FileMutation.Selection.SELECTED;
                        index = 3;
                    }
                    default -> throw invalid("Usage: " + verb + " the newest|oldest|file to <folder>");
                }
            }
            case "it" -> {
                selection = CommandPlan.FileMutation.Selection.SELECTED;
                index = 2;
            }
            case "that" -> {
                selection = CommandPlan.FileMutation.Selection.SELECTED;
                index = tokens.size() >= 3 && keyword(tokens.get(2)).equals("file") ? 3 : 2;
            }
            default -> throw invalid("Usage: " + verb + " these [files]|the newest|the oldest|it to <folder>");
        }
        if (index >= tokens.size() || !keyword(tokens.get(index)).equals("to") || index + 1 >= tokens.size()) {
            throw invalid("Usage: " + verb + " these [files]|the newest|the oldest|it to <folder>");
        }
        String destination = join(tokens.subList(index + 1, tokens.size())).strip();
        if (destination.isEmpty()) {
            throw invalid("Destination folder must not be empty");
        }
        requireSafeName(destination);
        return new CommandPlan.FileMutation(kind, selection, destination, List.of());
    }

    /**
     * Parses "find <extensions> [files] containing \"query\"": the search
     * grammar as above, with the result list consumed as the document set
     * for a quoted phrase search inside the documents' extracted text.
     */
    private CommandPlan parseContaining(List<CommandTokenizer.Token> tokens) throws CommandParseException {
        if (tokens.size() < 2) {
            throw invalid("Usage: find <extension> files containing \"text\"");
        }
        String word = keyword(tokens.get(1));
        String extension = singularExtension(word);
        if (extension.isEmpty()) {
            throw invalid("Unknown file type: " + word
                    + ". Try: find pdfs containing \"invoice\"");
        }
        int index = 2;
        if (index < tokens.size() && keyword(tokens.get(index)).equals("files")) {
            index++;
        }
        if (index + 1 >= tokens.size() || !keyword(tokens.get(index)).equals("containing")) {
            throw invalid("Usage: find " + extension + " files containing \"text\"");
        }
        String query = join(tokens.subList(index + 1, tokens.size())).strip();
        if (query.isEmpty()) {
            throw invalid("Search text must not be empty");
        }
        return new CommandPlan.ContentSearch(query);
    }

    private CommandPlan parseRename(List<CommandTokenizer.Token> tokens) throws CommandParseException {
        if (tokens.size() != 5 || !keyword(tokens.get(1)).equals("the")
                || !keyword(tokens.get(3)).equals("to")) {
            throw invalid("Usage: rename the newest|oldest to <name>");
        }
        CommandPlan.FileMutation.Selection selection = switch (keyword(tokens.get(2))) {
            case "newest" -> CommandPlan.FileMutation.Selection.NEWEST;
            case "oldest" -> CommandPlan.FileMutation.Selection.OLDEST;
            default -> throw invalid("Usage: rename the newest|oldest to <name>");
        };
        String newName = tokens.get(4).value().strip();
        if (newName.isEmpty()) {
            throw invalid("New name must not be empty");
        }
        requireSafeName(newName);
        return new CommandPlan.FileMutation(
                CommandPlan.FileMutation.Kind.RENAME,
                selection,
                newName,
                List.of());
    }

    /**
     * Accepts "pdfs"/"pdf" style plurals ("jpgs", "txts") plus extensions
     * that do not pluralize with "s" ("docx"); returns the canonical
     * extension or "" when the word is not a known file type.
     */
    private static String singularExtension(String word) {
        if (KNOWN_EXTENSIONS.contains(word)) {
            return word;
        }
        if (word.endsWith("s") && KNOWN_EXTENSIONS.contains(word.substring(0, word.length() - 1))) {
            return word.substring(0, word.length() - 1);
        }
        return "";
    }

    private static long parseAmount(String value) throws CommandParseException {
        try {
            long amount = Long.parseLong(value);
            if (amount <= 0) {
                throw new NumberFormatException();
            }
            return amount;
        } catch (NumberFormatException e) {
            throw invalid("Size must be a positive whole number");
        }
    }

    private static long parseUnit(String unit) throws CommandParseException {
        return switch (unit) {
            case "kb", "kib" -> BYTES_PER_KB;
            case "mb", "mib" -> BYTES_PER_MB;
            case "gb", "gib" -> BYTES_PER_GB;
            default -> throw invalid("Unsupported size unit: " + unit);
        };
    }

    private static long multiply(long amount, long unitBytes) throws CommandParseException {
        try {
            return Math.multiplyExact(amount, unitBytes);
        } catch (ArithmeticException e) {
            throw invalid("Size is too large");
        }
    }

    private static void requireSafeName(String name) throws CommandParseException {
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || name.equals(".") || name.equals("..")) {
            throw invalid("Names must not contain path separators or traversal segments");
        }
    }

    private static Set<String> prefixed(Set<String> phrases, Set<String> weekdays) {
        Set<String> all = new java.util.HashSet<>(phrases);
        for (String day : weekdays) {
            all.add(day);
            all.add("on " + day);
        }
        return Set.copyOf(all);
    }

    private static String join(List<CommandTokenizer.Token> tokens) {
        return tokens.stream()
                .map(CommandTokenizer.Token::value)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private static String normalizeLookup(String requested) {
        return requested.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean matches(List<CommandTokenizer.Token> tokens, String... words) {
        if (tokens.size() != words.length) {
            return false;
        }
        for (int index = 0; index < words.length; index++) {
            if (!keyword(tokens.get(index)).equals(words[index])) {
                return false;
            }
        }
        return true;
    }

    private static String keyword(CommandTokenizer.Token token) {
        return token.value().toLowerCase(Locale.ROOT);
    }

    private static CommandParseException invalid(String message) {
        return new CommandParseException(new StructuredError(
                ErrorCode.INVALID_COMMAND, message, Optional.empty()));
    }
}
