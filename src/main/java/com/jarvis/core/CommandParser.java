package com.jarvis.core;

import com.jarvis.api.ErrorCode;
import com.jarvis.api.FileSearchQuery;
import com.jarvis.api.StructuredError;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

public final class CommandParser {
    public static final int MAX_RESULTS = 50;
    public static final int SCAN_LIMIT = 10_000;
    public static final long BYTES_PER_MB = 1_048_576L;

    private final CommandTokenizer tokenizer = new CommandTokenizer();

    public CommandPlan parse(String input) throws CommandParseException {
        if (input == null || input.isBlank()) {
            throw invalid("Enter a command");
        }
        List<CommandTokenizer.Token> tokens = tokenizer.tokenize(input);
        if (tokens.isEmpty()) {
            throw invalid("Enter a command");
        }
        String first = keyword(tokens.getFirst());
        if (first.equals("open")) {
            return parseOpen(tokens);
        }
        if (first.equals("find")) {
            return parseFind(tokens);
        }
        if (matches(tokens, "system", "status") || matches(tokens, "status")) {
            return new CommandPlan.SystemStatus();
        }
        if (matches(tokens, "show", "history") || matches(tokens, "history")) {
            return new CommandPlan.ShowHistory();
        }
        throw invalid("Unsupported command. Try open, find PDFs, system status, or show history");
    }

    private CommandPlan parseOpen(List<CommandTokenizer.Token> tokens) throws CommandParseException {
        if (tokens.size() < 2) {
            throw invalid("Usage: open <configured app>");
        }
        String requested = tokens.subList(1, tokens.size()).stream()
                .map(CommandTokenizer.Token::value)
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();
        if (requested.isBlank()) {
            throw invalid("Application name must not be empty");
        }
        String lookup = requested.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return new CommandPlan.OpenApp(requested, lookup);
    }

    private CommandPlan parseFind(List<CommandTokenizer.Token> tokens) throws CommandParseException {
        boolean subjectMatches = tokens.size() >= 2
                && (keyword(tokens.get(1)).equals("pdfs")
                || (tokens.size() >= 3
                && keyword(tokens.get(1)).equals("pdf")
                && keyword(tokens.get(2)).equals("files")));
        if (!subjectMatches) {
            throw invalid("Only bounded PDF filename search is supported");
        }
        int suffix = keyword(tokens.get(1)).equals("pdfs") ? 2 : 3;
        OptionalLong minimum = OptionalLong.empty();
        if (tokens.size() != suffix) {
            if (tokens.size() != suffix + 4
                    || !keyword(tokens.get(suffix)).equals("larger")
                    || !keyword(tokens.get(suffix + 1)).equals("than")
                    || !keyword(tokens.get(suffix + 3)).equals("mb")) {
                throw invalid("Usage: find PDFs [larger than <positive integer> MB]");
            }
            long megabytes;
            try {
                megabytes = Long.parseLong(tokens.get(suffix + 2).value());
                if (megabytes <= 0) {
                    throw new NumberFormatException();
                }
                minimum = OptionalLong.of(Math.multiplyExact(megabytes, BYTES_PER_MB));
            } catch (ArithmeticException | NumberFormatException e) {
                throw invalid("PDF size must be a positive whole number of MB");
            }
        }
        return new CommandPlan.FindFiles(
                new FileSearchQuery(Set.of("pdf"), minimum, MAX_RESULTS, SCAN_LIMIT));
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
