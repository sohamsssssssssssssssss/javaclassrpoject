package com.jade.core;

import com.jade.api.ErrorCode;
import com.jade.api.StructuredError;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class CommandTokenizer {
    record Token(String value, boolean quoted) {
    }

    List<Token> tokenize(String input) throws CommandParseException {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < input.length()) {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
            if (index == input.length()) {
                break;
            }
            boolean quoted = input.charAt(index) == '"';
            int start = quoted ? ++index : index;
            if (quoted) {
                while (index < input.length() && input.charAt(index) != '"') {
                    index++;
                }
                if (index == input.length()) {
                    throw invalid("Unclosed quoted argument");
                }
                tokens.add(new Token(input.substring(start, index), true));
                index++;
                if (index < input.length() && !Character.isWhitespace(input.charAt(index))) {
                    throw invalid("Unexpected text after quoted argument");
                }
            } else {
                while (index < input.length() && !Character.isWhitespace(input.charAt(index))) {
                    if (input.charAt(index) == '"') {
                        throw invalid("Quotes must surround a complete argument");
                    }
                    index++;
                }
                tokens.add(new Token(input.substring(start, index), false));
            }
        }
        return List.copyOf(tokens);
    }

    private static CommandParseException invalid(String message) {
        return new CommandParseException(new StructuredError(
                ErrorCode.INVALID_COMMAND, message, Optional.empty()));
    }
}
