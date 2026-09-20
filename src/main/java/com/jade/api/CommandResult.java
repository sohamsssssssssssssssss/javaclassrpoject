package com.jade.api;

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
        CancellationReceipt,
        ProjectContext,
        ProjectOperationResult,
        ProjectOutcomeReport,
        ProjectInspectionResult,
        ProjectInspectionResult.SourceInventory,
        ProjectTree,
        DependencyList,
        TodoFindings,
        MainClassCandidates,
        DiagnosticsReport,
        DiagnosticCount {
}
