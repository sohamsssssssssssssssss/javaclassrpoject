package com.jade.core;

import com.jade.api.FileSearchQuery;

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
            NEWEST,
            /** The deterministically chosen least-recently-modified file. */
            OLDEST,
            /** The current explicit session selection ("it" / "the file"). */
            SELECTED
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

    /**
     * Refines the most recent successful search of this session by replacing
     * only the dimensions carried in {@code refinement} (restated extensions
     * and/or resolved size bounds and/or date window); all other constraints
     * of the previous structured query are retained. Structured state, never
     * re-parsed prose.
     */
    record RefineSearch(FileSearchQuery.Refinement refinement) implements CommandPlan {
        public RefineSearch {
            Objects.requireNonNull(refinement, "refinement");
        }
    }

    /**
     * Contextual selection from the current session result set.
     * {@code NEWEST} / {@code OLDEST} resolve deterministically by last
     * modified time with an absolute-path tie-break.
     */
    record SelectFile(Target target) implements CommandPlan {
        public enum Target {
            NEWEST,
            OLDEST
        }
    }

    /**
     * Selects one file by exact name — resolved strictly against the current
     * session result set, never against an arbitrary filesystem path. Used
     * by the search-result card's Open action; the subsequent "open it"
     * still goes through the normal scope-guarded opener seam.
     */
    record SelectByName(String fileName) implements CommandPlan {
        public SelectByName {
            Objects.requireNonNull(fileName, "fileName");
        }
    }

    /**
     * Opens the single contextual file referent: the current explicit
     * selection when present, otherwise the sole file of the current result
     * set. Never a guess — ambiguous or missing referents are rejections.
     */
    record OpenSelected() implements CommandPlan {
    }

    /**
     * Executes the exact pending confirmed operation stored for this
     * session — the typed operations previewed when the mutation command
     * ran. No text is re-parsed and no paths are re-resolved.
     */
    record ConfirmPending() implements CommandPlan {
    }

    /** Cancels the pending confirmation without any filesystem effect. */
    record CancelPending() implements CommandPlan {
    }

    /**
     * Activates a project by name or path. The target is resolved and
     * validated by the project service (directory + supported descriptor);
     * the parser never invents machine-specific locations.
     */
    record OpenProject(String target) implements CommandPlan {
        public OpenProject {
            Objects.requireNonNull(target, "target");
        }
    }

    /** Reports the currently active project of this session. */
    record CurrentProject() implements CommandPlan {
    }

    /**
     * Runs a typed project operation against the active project. The verb
     * itself carries the project intent — "build it" never refers to a file.
     */
    record ProjectOperationPlan(com.jade.api.ProjectOperation operation) implements CommandPlan {
        public ProjectOperationPlan {
            Objects.requireNonNull(operation, "operation");
        }
    }

    /** Reports the most recent project operation of this session, if any. */
    record LastProjectOutcome() implements CommandPlan {
    }

    /**
     * Full static inspection of the active project (summary, structure,
     * dependencies, main candidates, TODO/FIXME) — read-only, bounded.
     */
    record InspectProject() implements CommandPlan {
    }

    /** The bounded rendered structure tree of the active project. */
    record ProjectStructure() implements CommandPlan {
    }

    /** Counts of the active project's Java sources, tests and resources. */
    record ProjectSourceCounts() implements CommandPlan {
    }

    /** Declared Maven dependencies of the active project. */
    record ProjectDependencies() implements CommandPlan {
    }

    /** Static main-class candidates of the active project. */
    record ProjectMainCandidates() implements CommandPlan {
    }

    /** Bounded TODO/FIXME findings of the active project. */
    record ProjectTodos() implements CommandPlan {
    }

    /**
     * Structured diagnostics of the most recent project operation of this
     * session: what failed, with bounded evidence. Not a code-fixing flow.
     */
    record ProjectDiagnostics() implements CommandPlan {
    }

    /** Failure count of the most recent project operation, as detected. */
    record ProjectDiagnosticsCount() implements CommandPlan {
    }
}
