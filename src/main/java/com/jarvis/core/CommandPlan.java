package com.jarvis.core;

import com.jarvis.api.FileSearchQuery;

import java.util.Objects;

/** Typed plans produced by {@link CommandParser}; never raw shell text. */
public sealed interface CommandPlan {
    record OpenApp(String requestedName, String lookupName) implements CommandPlan {
        public OpenApp {
            Objects.requireNonNull(requestedName, "requestedName");
            Objects.requireNonNull(lookupName, "lookupName");
        }
    }

    record FindFiles(FileSearchQuery query) implements CommandPlan {
        public FindFiles {
            Objects.requireNonNull(query, "query");
        }
    }

    record SystemStatus() implements CommandPlan {
    }

    record ShowHistory() implements CommandPlan {
    }

    /**
     * A planned file mutation. The plan references files only through typed
     * {@link Selection} descriptors and a validated destination name; the
     * gateway resolves concrete paths against the configured scope before
     * anything is confirmed or executed.
     */
    record FileMutation(Kind kind, Selection selection, String name, java.util.List<String> extraNames)
            implements CommandPlan {
        public FileMutation {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(name, "name");
            extraNames = java.util.List.copyOf(extraNames);
        }

        public enum Kind {
            MOVE,
            COPY,
            RENAME,
            CREATE_FOLDER
        }

        /**
         * How the affected files are chosen. {@code ALL_IN_SCOPE} is used by
         * folder creation (no input files); {@code LAST_RESULT} reuses the
         * most recent successful search of this session; {@code NEWEST}
         * picks the most recently modified file of that result.
         */
        public enum Selection {
            ALL_IN_SCOPE,
            LAST_RESULT,
            NEWEST
        }
    }

    /** Reverses the most recent undoable request of this session. */
    record Undo() implements CommandPlan {
    }

    /** Lists regular files of the configured scope roots (top level only). */
    record ListFiles() implements CommandPlan {
    }

    /** Metadata request for one scope file, by name. */
    record FileInfo(String fileName) implements CommandPlan {
        public FileInfo {
            Objects.requireNonNull(fileName, "fileName");
        }
    }

    /**
     * Document content search: extracts text (Tika) from the files of the
     * last successful search of this session and matches the quoted query
     * inside it. The service-level 100-document limit is preserved by the
     * gateway; the parser never widens it.
     */
    record ContentSearch(String query) implements CommandPlan {
        public ContentSearch {
            Objects.requireNonNull(query, "query");
        }
    }
}
