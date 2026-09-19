package com.jarvis.ui;

import com.jarvis.api.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.OptionalLong;

public final class CommandUI extends BorderPane implements AutoCloseable {
    private final CommandGateway gateway;
    private final boolean cancellable;
    private final TextField commandField = new TextField();
    private final Button submitButton = new Button("Submit");
    private final Button cancelButton = new Button("Cancel");
    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar();
    private final VBox results = new VBox(8);
    private VBox topBox;
    private VoicePanel voicePanel;
    private final Label searchScopeLabel = new Label();
    private CommandSubscription currentSubscription;

    public CommandUI(CommandGateway gateway) {
        this(gateway, true);
    }

    public CommandUI(CommandGateway gateway, boolean cancellable) {
        this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        this.cancellable = cancellable;
        buildView();
    }

    private void buildView() {
        getStyleClass().add("root-pane");
        setPadding(new Insets(16));
        setPrefSize(820, 620);

        commandField.setPromptText("Try: find txt files, find PDFs larger than 20 MB, create folder called College, undo");
        commandField.setAccessibleText("JARVIS command");
        commandField.setOnAction(ignored -> onSubmit());
        HBox.setHgrow(commandField, Priority.ALWAYS);

        submitButton.getStyleClass().add("button-primary");
        submitButton.setDefaultButton(true);
        submitButton.setDisable(true);
        submitButton.setOnAction(ignored -> onSubmit());

        cancelButton.getStyleClass().add("button-secondary");
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        cancelButton.setOnAction(ignored -> onCancel());

        commandField.textProperty().addListener((ignored, oldValue, newValue) ->
                submitButton.setDisable(currentSubscription != null || newValue == null || newValue.isBlank()));

        HBox commandRow = new HBox(8, commandField, submitButton, cancelButton);
        VBox top = new VBox(8, commandRow, statusLabel, progressBar);
        topBox = top;
        top.setPadding(new Insets(0, 0, 12, 0));
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        setTop(top);

        results.setPadding(new Insets(12));
        showMessage("Enter a command to get started", "label-subtle");
        ScrollPane scroll = new ScrollPane(results);
        scroll.setFitToWidth(true);
        scroll.setPannable(true);
        setCenter(scroll);

        searchScopeLabel.getStyleClass().add("label-subtle");
        setBottom(searchScopeLabel);
        BorderPane.setMargin(searchScopeLabel, new Insets(12, 0, 0, 0));
    }

    public void onSubmit() {
        String text = commandField.getText();
        if (currentSubscription != null || text == null || text.isBlank()) {
            return;
        }
        setRunning(true);
        currentSubscription = gateway.submit(
                CommandRequest.create(text), this::onProgress, this::onComplete);
    }

    public void onCancel() {
        if (cancellable && currentSubscription != null) {
            currentSubscription.cancel();
            statusLabel.setText("Cancelling…");
        }
    }

    private void onProgress(ProgressEvent event) {
        onFxThread(() -> {
            statusLabel.setText(event.message());
            OptionalLong total = event.totalUnits();
            boolean measurable = total.isPresent() && total.getAsLong() > 0;
            progressBar.setProgress(measurable
                    ? Math.min(1.0, (double) event.completedUnits() / total.getAsLong())
                    : ProgressIndicator.INDETERMINATE_PROGRESS);
            progressBar.setVisible(true);
            progressBar.setManaged(true);
        });
    }

    private void onComplete(CommandOutcome outcome) {
        onFxThread(() -> {
            currentSubscription = null;
            setRunning(false);
            results.getChildren().clear();
            switch (outcome.status()) {
                case CANCELLED -> showMessage("Command cancelled", "label-subtle");
                case REJECTED -> showMessage("Rejected: " + errorMessage(outcome), "label-warning");
                case FAILED -> showMessage("Failed: " + errorMessage(outcome), "label-warning");
                case SUCCEEDED -> outcome.result().ifPresentOrElse(
                        this::showResult,
                        () -> showMessage(outcome.summary(), "label-subtle"));
            }
        });
    }

    private void showResult(CommandResult result) {
        switch (result) {
            case AppLaunchReceipt receipt -> {
                showMessage("Launch request accepted: " + receipt.displayName(), "text-accent");
                showMessage("Application ID: " + receipt.appId(), "label-subtle");
            }
            case FileSearchResult files -> showFiles(files);
            case SystemSnapshot snapshot -> showSystem(snapshot);
            case HistoryResult history -> showHistory(history);
            case MutationReceipt mutation -> showMutation(mutation);
            case UndoResult undo -> showUndo(undo);
            case ContentSearchResult content -> showContentSearch(content);
            case SelectedFileResult selected -> {
                showMessage("✓ " + selected.fileName() + "  (" + selected.sizeBytes() + " bytes)", "text-accent");
                showMessage(selected.path().toString(), "label-subtle");
            }
            case FileMutationPreview preview -> {
                showMessage("⏸ " + preview.pending().originalCommand(), "text-accent");
                showMessage("Type 'confirm' to apply, or 'cancel' to discard.", "label-subtle");
            }
            case CancellationReceipt cancelled -> showMessage(cancelled.message(), "label-subtle");
            case ProjectContext project -> {
                showMessage("✓ Active project: " + project.name(), "text-accent");
                showMessage(project.root() + "  (" + project.buildSystem() + ")", "label-subtle");
            }
            case ProjectOperationResult run -> {
                String icon = switch (run.status()) {
                    case SUCCEEDED -> "✓";
                    case BUILD_FAILED -> "✗";
                    case TIMED_OUT -> "⏱";
                };
                showMessage(icon + " " + run.operation() + " on " + run.projectName()
                        + " — " + run.status()
                        + (run.exitCode() == null ? "" : " (exit " + run.exitCode() + ")")
                        + ", " + (run.durationMillis() / 1000.0) + " s", "text-accent");
                if (!run.outputSummary().isBlank()) {
                    showMessage(run.outputSummary(), "label-subtle");
                }
                if (run.outputTruncated()) {
                    showMessage("(older output was truncated)", "label-warning");
                }
            }
            case ProjectOutcomeReport report -> report.operation().ifPresentOrElse(
                    run -> showMessage("Last project operation: " + run.operation()
                            + " on " + run.projectName() + " — " + run.status()
                            + (run.exitCode() == null ? "" : " (exit " + run.exitCode() + ")")
                            + ", " + (run.durationMillis() / 1000.0) + " s", "text-accent"),
                    () -> showMessage("No project operation has run in this session yet.", "label-subtle"));
        }
    }

    private void showMutation(MutationReceipt receipt) {
        for (MutationReceipt.Entry entry : receipt.entries()) {
            String line = capitalize(receipt.kind().name().toLowerCase(java.util.Locale.ROOT)) + "  "
                    + entry.source().getFileName() + "  →  " + entry.target().getFileName();
            if (entry.status() == OperationStatus.APPLIED) {
                showMessage("✓ " + line, "text-accent");
            } else {
                showMessage("✗ " + line + " — "
                        + entry.error().map(StructuredError::message).orElse("failed"), "label-warning");
            }
        }
        if (receipt.kind() != MutationKind.CREATE_FOLDER) {
            showMessage("Undo is available for moves and renames: type 'undo'.", "label-subtle");
        }
    }

    private void showUndo(UndoResult undo) {
        for (UndoEntry.JournalRow row : undo.rows()) {
            String line = row.entry().source().getFileName() + "  →  " + row.entry().target().getFileName();
            switch (row.status()) {
                case UNDONE -> showMessage("✓ Restored " + line, "text-accent");
                case FAILED -> showMessage("✗ Could not restore " + line + " — "
                        + row.error().map(StructuredError::message).orElse("failed"), "label-warning");
                case ACTIVE -> showMessage("• " + line, "label-subtle");
            }
        }
    }

    /** Renders document content search: matches with snippets, plus every unreadable document. */
    private void showContentSearch(ContentSearchResult content) {
        if (content.documents().isEmpty()) {
            showMessage("No documents were searched", "label-subtle");
        }
        for (ContentSearchResult.DocumentHit hit : content.documents()) {
            String name = hit.path().getFileName().toString();
            if (hit.extractionFailed()) {
                showMessage("✗ " + name + " — could not extract text"
                        + hit.failureReason().map(reason -> ": " + reason).orElse(""), "label-warning");
            } else if (hit.matchCount() > 0) {
                Label line = new Label("✓ " + name + " — " + hit.matchCount()
                        + (hit.matchCount() >= 50 ? "+ match(es)" : " match(es)"));
                line.getStyleClass().add("text-primary");
                line.setWrapText(true);
                results.getChildren().add(line);
                hit.snippet().ifPresent(snippet -> showMessage("    …" + snippet + "…", "label-subtle"));
            } else {
                showMessage("• " + name + " — no match", "label-subtle");
            }
        }
        showMessage("Total: " + content.totalMatches() + " match(es)", "text-accent");
        if (!content.appliedLimits().isEmpty()) {
            showMessage(content.appliedLimits(), "label-warning");
        }
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void showFiles(FileSearchResult files) {
        if (files.matches().isEmpty()) {
            showMessage("No matching PDF files found", "label-subtle");
        } else {
            for (FileMatch match : files.matches()) {
                Label path = new Label(match.path() + "  (" + formatBytes(match.sizeBytes()) + ")");
                path.setWrapText(true);
                path.getStyleClass().add("text-primary");
                results.getChildren().add(path);
            }
        }
        showMessage("Visited " + files.visitedFiles() + " file(s)", "label-subtle");
        if (files.resultLimitReached()) {
            showMessage("Result limit reached (50)", "label-warning");
        }
        if (files.scanLimitReached()) {
            showMessage("Scan limit reached (10,000 files); results are partial", "label-warning");
        }
    }

    private void showSystem(SystemSnapshot snapshot) {
        showMessage("OS: " + snapshot.osName() + " " + snapshot.osVersion(), "text-primary");
        showMessage("Architecture: " + snapshot.architecture(), "text-primary");
        showMessage("CPU load: " + (snapshot.cpuLoadPercent().isPresent()
                ? String.format("%.1f%%", snapshot.cpuLoadPercent().getAsDouble()) : "Unavailable"), "text-primary");
        showMessage("Memory: " + optionalBytes(snapshot.availableMemoryBytes()) + " available / "
                + optionalBytes(snapshot.totalMemoryBytes()) + " total", "text-primary");
    }

    private void showHistory(HistoryResult history) {
        if (history.entries().isEmpty()) {
            showMessage("No command history available", "label-subtle");
            return;
        }
        for (HistoryEntry entry : history.entries()) {
            showMessage("[" + entry.status() + "] " + entry.originalText(), "text-primary");
        }
    }

    private void setRunning(boolean running) {
        submitButton.setDisable(running || commandField.getText().isBlank());
        cancelButton.setVisible(running && cancellable);
        cancelButton.setManaged(running && cancellable);
        if (!running) {
            statusLabel.setText("");
            progressBar.setVisible(false);
            progressBar.setManaged(false);
        }
    }

    private void showMessage(String text, String styleClass) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(styleClass);
        results.getChildren().add(label);
    }

    private static String errorMessage(CommandOutcome outcome) {
        return outcome.error().map(StructuredError::message).orElse(outcome.summary());
    }

    private static String optionalBytes(OptionalLong value) {
        return value.isPresent() ? formatBytes(value.getAsLong()) : "Unavailable";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_024) return bytes + " B";
        if (bytes < 1_048_576) return String.format("%.1f KiB", bytes / 1_024.0);
        if (bytes < 1_073_741_824L) return String.format("%.1f MiB", bytes / 1_048_576.0);
        return String.format("%.1f GiB", bytes / 1_073_741_824.0);
    }

    private static void onFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    public void setSearchScope(String scope) {
        searchScopeLabel.setText("Search scope: " + scope);
    }

    /**
     * Adds the optional voice status strip above the results area without
     * changing the existing layout. No-op when already installed.
     */
    public void setVoicePanel(VoicePanel panel) {
        java.util.Objects.requireNonNull(panel, "panel");
        if (voicePanel != null) {
            return;
        }
        this.voicePanel = panel;
        if (topBox != null) {
            topBox.getChildren().add(panel);
        }
    }

    @Override
    public void close() {
        if (currentSubscription != null) {
            currentSubscription.close();
            currentSubscription = null;
        }
    }
}
