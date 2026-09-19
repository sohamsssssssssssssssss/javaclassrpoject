package com.jarvis.api;

/** Result payload of a command, carried by {@link CommandOutcome}. */
public sealed interface CommandResult permits
        AppLaunchReceipt,
        FileSearchResult,
        SystemSnapshot,
        HistoryResult,
        MutationReceipt,
        UndoResult,
        ContentSearchResult,
        SelectedFileResult,
        FileMutationPreview,
        CancellationReceipt {
}
