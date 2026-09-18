package com.jarvis.core;

import com.jarvis.api.FileSearchQuery;

import java.util.Objects;

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
}
