package com.jade.ui;

import com.jade.api.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.OptionalLong;
import java.util.function.Consumer;
import com.jade.services.audio.VoiceState;
import com.jade.ui.shell.JadeShellModel;

public final class CommandUI extends BorderPane implements AutoCloseable {
    private final CommandGateway gateway;
    private final boolean cancellable;
    private final TextField commandField = new TextField();
    private final Button submitButton = new Button("↵");
    private final Button cancelButton = new Button("Cancel");
    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar();
    private final VBox results = new VBox(8);
    private VBox topBox;
    private HBox commandRow;
    private Runnable resultObserver = () -> { };
    private VoicePanel voicePanel;
    private com.jade.ui.ExecutionBrainPanel executionBrain;
    private final Label searchScopeLabel = new Label();
    private CommandSubscription currentSubscription;
    private Consumer<VoiceState> stateObserver = ignored -> { };
    private Consumer<java.net.URI> sourceOpener = ignored -> { };
    private Consumer<String> projectObserver = ignored -> { };

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
        setPadding(Insets.EMPTY);
        setPrefSize(820, 620);

        commandField.setPromptText("Ask JADE anything…");
        commandField.setId("command-input");
        commandField.setAccessibleText("JADE command");
        commandField.setOnAction(ignored -> onSubmit());
        HBox.setHgrow(commandField, Priority.ALWAYS);

        submitButton.getStyleClass().add("command-submit");
        submitButton.setAccessibleText("Submit command (Enter)");
        submitButton.setTooltip(new Tooltip("Submit · Enter"));
        submitButton.setDefaultButton(true);
        submitButton.setDisable(true);
        submitButton.setOnAction(ignored -> onSubmit());

        cancelButton.getStyleClass().add("button-secondary");
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        cancelButton.setOnAction(ignored -> onCancel());

        commandField.textProperty().addListener((ignored, oldValue, newValue) ->
                submitButton.setDisable(currentSubscription != null || newValue == null || newValue.isBlank()));

        commandRow = new HBox(8, commandField, submitButton, cancelButton);
        commandRow.getStyleClass().add("command-surface");
        commandRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox top = new VBox(8, commandRow, statusLabel, progressBar);
        topBox = top;
        top.setPadding(new Insets(0, 0, 12, 0));
        statusLabel.getStyleClass().add("label-subtle");
        statusLabel.setManaged(false);
        statusLabel.setVisible(false);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        setTop(top);

        results.setPadding(new Insets(8));
        showMessage("Enter a command to get started", "label-subtle");
        ScrollPane scroll = new ScrollPane(results);
        scroll.getStyleClass().add("center-scroll");
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
        results.getChildren().clear();
        showMessage("Working on your request…", "result-heading");
        stateObserver.accept(VoiceState.PROCESSING);
        if (executionBrain != null) {
            topBox.getChildren().remove(executionBrain);
            results.getChildren().add(executionBrain);
            executionBrain.resetForNewCommand();
        }
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
        if (executionBrain != null) {
            executionBrain.onProgressEvent(event);
        }
        onFxThread(() -> {
            stateObserver.accept(JadeShellModel.forProgress(event.stage()));
            statusLabel.setText(event.message());
            statusLabel.setManaged(true);
            statusLabel.setVisible(true);
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
            stateObserver.accept(outcome.status() == CommandStatus.FAILED || outcome.status() == CommandStatus.REJECTED
                    || outcome.result().orElse(null) instanceof AnswerResult answer && answer.status() == AnswerStatus.FAILED
                    ? VoiceState.ERROR : VoiceState.IDLE);
            if (outcome.result().orElse(null) instanceof com.jade.api.ProjectContext project) {
                projectObserver.accept(project.name());
            }
            if (executionBrain != null) {
                if (outcome.result().orElse(null) instanceof com.jade.api.ExecutionResult) {
                    executionBrain.markMultiStepPlan();
                }
                executionBrain.onOutcome(outcome);
            }
            results.getChildren().clear();
            if (executionBrain != null) topBox.getChildren().add(executionBrain);
            switch (outcome.status()) {
                case CANCELLED -> showMessage("Command cancelled", "label-subtle");
                case REJECTED -> showError("Request rejected", errorMessage(outcome));
                case FAILED -> showError("Could not complete the request", errorMessage(outcome));
                case SUCCEEDED -> outcome.result().ifPresentOrElse(
                        this::showResult,
                        () -> showMessage(outcome.summary(), "label-subtle"));
            }
            resultObserver.run();
            if (outcome.status() == CommandStatus.SUCCEEDED) commandField.clear();
            commandField.requestFocus();
        });
    }

    private void showResult(CommandResult result) {
        switch (result) {
            case AppLaunchReceipt receipt -> {
                showMessage("Launch request accepted: " + receipt.displayName(), "text-accent");
                showMessage("Application ID: " + receipt.appId(), "label-subtle");
            }
            case FileSearchResult files -> showFiles(files);
            case AnswerResult answer -> {
                if (answer.status() == AnswerStatus.FAILED) {
                    showError("Knowledge answer unavailable", answer.answerText());
                } else {
                    showMessage(answer.mode() == AnswerMode.WEB_RESEARCH ? "Web research" : "General question", "result-heading");
                    showMessage(answer.answerText(), "result-metadata");
                    if (!answer.provider().isBlank()) showMessage(answer.provider().equals("local")
                            ? (answer.usedWeb() ? "WEB · LOCAL SYNTHESIS" : "LOCAL · " + answer.model())
                            : answer.provider() + " · " + answer.model(), "label-subtle");
                    for (var source : answer.evidence()) {
                        try {
                            var hit = new WebSearchProvider.Hit(source.title(), java.net.URI.create(source.reference()), "");
                            Hyperlink link = new Hyperlink(hit.title());
                            link.getStyleClass().add("result-metadata"); link.setWrapText(true);
                            link.setTooltip(new Tooltip(hit.uri().toString()));
                            link.setOnAction(event -> { try { sourceOpener.accept(hit.uri()); } catch (RuntimeException unavailable) { statusLabel.setText("Could not open the source link."); } });
                            results.getChildren().add(link);
                            showMessage(hit.uri().getHost(), "label-subtle");
                        } catch (IllegalArgumentException invalidSource) { /* No unsafe link handlers. */ }
                    }
                }
            }
            case SystemSnapshot snapshot -> showSystem(snapshot);
            case HistoryResult history -> showHistory(history);
            case MutationReceipt mutation -> showMutation(mutation);
            case UndoResult undo -> showUndo(undo);
            case ContentSearchResult content -> showContentSearch(content);
            case SelectedFileResult selected -> {
                VBox card = new VBox(3);
                card.getStyleClass().add("result-card");
                Label name = new Label("✓ " + selected.fileName());
                name.getStyleClass().add("text-accent");
                Label meta = new Label(formatBytes(selected.sizeBytes()) + "  ·  opened with the default app");
                meta.getStyleClass().add("label-subtle");
                Label path = new Label(selected.path().toString());
                path.getStyleClass().add("label-subtle");
                path.setWrapText(true);
                card.getChildren().addAll(name, meta, path);
                results.getChildren().add(card);
            }
            case FileMutationPreview preview -> showMutationPreview(preview);
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
            case ProjectInspectionResult inspection -> showProjectInspection(inspection);
            case ProjectTree tree -> showProjectTree(tree);
            case ProjectInspectionResult.SourceInventory sources -> showSourceInventory(sources);
            case DependencyList dependencies -> showDependencies(dependencies);
            case MainClassCandidates mains -> showMainCandidates(mains);
            case TodoFindings todos -> showTodoFindings(todos);
            case DiagnosticsReport report -> showDiagnostics(report);
            case DiagnosticCount count -> showMessage(
                    count.failedTestCount() == 0
                            ? "No failing tests detected — last operation: " + count.lastStatus()
                            : count.failedTestCount() + " failing test(s) detected — last operation: "
                                    + count.lastStatus(),
                    count.failedTestCount() == 0 ? "text-accent" : "label-warning");
            case com.jade.api.ExecutionResult execution -> showExecutionTrace(execution);
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

    /** The in-place confirmation card: exactly what will change, and how. */
    private void showMutationPreview(FileMutationPreview preview) {
        com.jade.api.PendingConfirmation pending = preview.pending();
        VBox card = new VBox(6);
        card.getStyleClass().add("result-card");
        Label headline = new Label("⏸  " + capitalize(pending.kind().name().toLowerCase(java.util.Locale.ROOT))
                + "  ·  risk: " + pending.riskLevel());
        headline.getStyleClass().add("text-accent");
        Label command = new Label(pending.originalCommand());
        command.getStyleClass().add("text-primary");
        command.setWrapText(true);
        card.getChildren().addAll(headline, command);
        Label destination = new Label("Destination: " + pending.destinationDescription());
        destination.getStyleClass().add("label-subtle");
        destination.setWrapText(true);
        card.getChildren().add(destination);
        for (com.jade.api.PendingConfirmation.PlannedFile file : pending.plannedFiles()) {
            Label line = new Label("  " + file.source() + "  →  " + file.target());
            line.getStyleClass().add("text-primary");
            line.setWrapText(true);
            card.getChildren().add(line);
        }
        HBox actions = new HBox(8);
        Button confirm = new Button("Confirm");
        confirm.getStyleClass().add("button-primary");
        confirm.setOnAction(ignored -> {
            commandField.setText("confirm");
            onSubmit();
        });
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("button-secondary");
        cancel.setOnAction(ignored -> {
            commandField.setText("cancel");
            onSubmit();
        });
        actions.getChildren().addAll(confirm, cancel);
        card.getChildren().add(actions);
        results.getChildren().add(card);
    }

    /** The undo timeline: most recent reversed operation first. */
    private void showUndo(UndoResult undo) {
        VBox timeline = new VBox(4);
        timeline.getStyleClass().add("result-card");
        Label title = new Label(undo.fullyReversed()
                ? "↩ Undo complete" : "↩ Undo partially applied");
        title.getStyleClass().add(undo.fullyReversed() ? "text-accent" : "label-warning");
        timeline.getChildren().add(title);
        for (UndoEntry.JournalRow row : undo.rows()) {
            String line = row.entry().source().getFileName() + "  →  " + row.entry().target().getFileName();
            Label entry = new Label(switch (row.status()) {
                case UNDONE -> "✓ restored  " + line;
                case FAILED -> "✗ could not restore  " + line + " — "
                        + row.error().map(StructuredError::message).orElse("failed");
                case ACTIVE -> "• still active  " + line;
            });
            entry.getStyleClass().add(switch (row.status()) {
                case UNDONE -> "text-primary";
                case FAILED -> "label-warning";
                case ACTIVE -> "label-subtle";
            });
            entry.setWrapText(true);
            timeline.getChildren().add(entry);
        }
        results.getChildren().add(timeline);
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

    /** Renders the full static project inspection (summary view). */
    private void showProjectInspection(ProjectInspectionResult inspection) {
        ProjectInspectionResult.Coordinates c = inspection.coordinates();
        showMessage("Project: " + c.artifactId() + "  (" + c.groupId() + ":" + c.artifactId()
                + ":" + c.version() + ")", "text-accent");
        showMessage("Build system: Maven  ·  Packaging: " + c.packaging()
                + c.name().map(name -> "  ·  Name: " + name).orElse("")
                + c.javaVersion().map(java -> "  ·  Java: " + java).orElse(""), "text-primary");
        ProjectInspectionResult.SourceInventory s = inspection.sources();
        showMessage("Java files: " + s.javaSourceFiles() + " source, " + s.javaTestFiles()
                + " test  ·  Other files: " + s.resourceFiles() + "  ·  Packages: "
                + s.packages(), "text-primary");
        showMessage("Source roots: " + String.join(", ", s.sourceRoots()) + "  ·  Test roots: "
                + String.join(", ", s.testRoots()), "label-subtle");
        showMessage("Dependencies: " + inspection.dependencies().size() + " declared", "text-primary");
        for (DependencyInfo dependency : inspection.dependencies()) {
            showMessage("    • " + dependency.groupId() + ":" + dependency.artifactId()
                    + dependency.version().map(version -> ":" + version).orElse("")
                    + dependency.scope().map(scope -> " (" + scope + ")").orElse(""),
                    "label-subtle");
        }
        MainClassCandidates mains = inspection.mainCandidates();
        showMessage("Main candidates: "
                + (mains.candidates().isEmpty() ? "none found"
                        : String.valueOf(mains.candidates().size())), "text-primary");
        for (MainClassCandidates.Candidate candidate : mains.candidates()) {
            showMessage("    • " + candidate.className() + " — " + candidate.signature(),
                    "label-subtle");
        }
        showMessage("TODO/FIXME: " + inspection.todoFindings().size()
                + (inspection.todosTruncated() ? " (list truncated)" : ""), "text-primary");
        for (TodoFinding finding : inspection.todoFindings()) {
            showMessage("    • " + finding.path() + ":" + finding.lineNumber() + " ["
                    + finding.marker() + "] "
                    + finding.snippet().orElse(""), "label-subtle");
        }
        if (inspection.scanIncomplete()) {
            showMessage("(inspection reached walk/size limits; counts are partial)", "label-warning");
        }
    }

    /** Renders the bounded project tree with a truthful truncation marker. */
    private void showProjectTree(ProjectTree tree) {
        showMessage("Project structure of " + tree.root().getFileName() + " (depth ≤ "
                + tree.maxDepth() + ")", "text-accent");
        for (String line : tree.lines()) {
            showMessage(line.isEmpty() ? " " : line, "label-subtle");
        }
        if (tree.truncated()) {
            showMessage("(tree truncated at " + ProjectTree.MAX_LINES + " entries / "
                    + ProjectTree.MAX_DEPTH + " levels)", "label-warning");
        }
    }

    /** Renders source/test/resource counts and the test roots. */
    private void showSourceInventory(ProjectInspectionResult.SourceInventory sources) {
        showMessage("Java source files: " + sources.javaSourceFiles(), "text-accent");
        showMessage("Java test files: " + sources.javaTestFiles(), "text-accent");
        showMessage("Other files: " + sources.resourceFiles(), "text-primary");
        showMessage("Packages: " + sources.packages(), "text-primary");
        showMessage("Source roots: " + String.join(", ", sources.sourceRoots()), "label-subtle");
        showMessage("Test roots: " + String.join(", ", sources.testRoots()), "label-subtle");
    }

    /** Renders the declared dependency list. */
    private void showDependencies(DependencyList dependencies) {
        if (dependencies.dependencies().isEmpty()) {
            showMessage("The project declares no dependencies in its pom.xml.", "label-subtle");
            return;
        }
        for (DependencyInfo dependency : dependencies.dependencies()) {
            showMessage("• " + dependency.groupId() + ":" + dependency.artifactId()
                    + dependency.version().map(version -> ":" + version).orElse("")
                    + dependency.scope().map(scope -> " (" + scope + ")").orElse(""),
                    "text-primary");
        }
    }

    /** Renders main-class candidates without guessing a winner. */
    private void showMainCandidates(MainClassCandidates mains) {
        if (mains.candidates().isEmpty()) {
            showMessage("No main-method candidates found in the project sources.", "label-subtle");
            return;
        }
        for (MainClassCandidates.Candidate candidate : mains.candidates()) {
            showMessage("• " + candidate.className() + " — " + candidate.signature(),
                    "text-primary");
            showMessage("    " + candidate.path(), "label-subtle");
        }
    }

    /** Renders TODO/FIXME findings with location and bounded snippet. */
    private void showTodoFindings(TodoFindings todos) {
        if (todos.findings().isEmpty()) {
            showMessage("No TODO or FIXME markers found in the project sources.", "label-subtle");
            return;
        }
        for (TodoFinding finding : todos.findings()) {
            showMessage("• [" + finding.marker() + "] " + finding.path() + ":"
                    + finding.lineNumber(), "text-primary");
            finding.snippet().ifPresent(snippet -> showMessage("    " + snippet, "label-subtle"));
        }
        if (todos.truncated()) {
            showMessage("(findings truncated at "
                    + ProjectInspectionService.MAX_TODO_FINDINGS + " entries)", "label-warning");
        }
    }

    /** Renders bounded structured diagnostics with per-diagnostic detail. */
    private void showDiagnostics(DiagnosticsReport report) {
        if (report.diagnostics().isEmpty()) {
            if (report.lastStatus() == com.jade.api.ProjectOperationStatus.SUCCEEDED) {
                showMessage("No failures detected — the last project operation succeeded.",
                        "text-accent");
            } else {
                showMessage("No specific failures could be identified from the last run ("
                        + report.lastStatus() + ").", "label-subtle");
            }
            return;
        }
        for (Diagnostic diagnostic : report.diagnostics()) {
            String where = diagnostic.location().map(location -> " @ " + location).orElse("");
            String what = diagnostic.testClass().map(testClass -> testClass
                    + diagnostic.testMethod().map(method -> "." + method).orElse("")).orElse("");
            showMessage("✗ [" + diagnostic.kind() + "] " + what + where, "label-warning");
            diagnostic.message().ifPresent(message -> showMessage("    " + message, "text-primary"));
            diagnostic.detail().ifPresent(detail -> showMessage("    " + detail, "label-subtle"));
        }
        if (report.truncated()) {
            showMessage("(diagnostics truncated at " + DiagnosticsReport.MAX_DIAGNOSTICS
                    + " entries)", "label-warning");
        }
    }

    /** Renders the bounded typed trace of a multi-step plan execution. */
    private void showExecutionTrace(com.jade.api.ExecutionResult execution) {
        showMessage("Plan executed — " + execution.trace().size() + " step(s)", "text-accent");
        for (com.jade.api.ExecutionStepResult step : execution.trace()) {
            String icon = switch (step.status()) {
                case SUCCEEDED -> "✓";
                case FAILED_RESULT -> "✗";
                case ERROR -> "⚠";
                case SKIPPED -> "–";
            };
            showMessage(icon + " " + step.step() + " — " + step.status() + " ("
                    + step.durationMillis() + " ms)", "text-primary");
            step.result().ifPresent(result -> {
                if (result instanceof ProjectOperationResult operation) {
                    showMessage("    " + operation.operation() + " on " + operation.projectName()
                            + " — " + operation.status(), "label-subtle");
                } else if (result instanceof DiagnosticsReport report) {
                    showMessage("    " + report.diagnostics().size() + " diagnostic(s) detected",
                            "label-subtle");
                }
            });
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
            showMessage("No matching files found", "label-subtle");
        } else {
            for (FileMatch match : files.matches()) {
                results.getChildren().add(fileCard(match));
            }
        }
        HBox footer = new HBox(10);
        Label visited = new Label("Visited " + files.visitedFiles() + " file(s)");
        visited.getStyleClass().add("label-subtle");
        footer.getChildren().add(visited);
        if (files.resultLimitReached()) {
            Label limit = new Label("Result limit reached (50)");
            limit.getStyleClass().add("label-warning");
            footer.getChildren().add(limit);
        }
        if (files.scanLimitReached()) {
            Label limit = new Label("Scan limit reached (10,000 files); results are partial");
            limit.getStyleClass().add("label-warning");
            footer.getChildren().add(limit);
        }
        results.getChildren().add(footer);
    }

    /** A bounded search-result card: name, size, date, and an Open action. */
    private javafx.scene.Node fileCard(FileMatch match) {
        VBox card = new VBox(3);
        card.getStyleClass().add("result-card");
        HBox titleRow = new HBox(8);
        Label name = new Label(match.fileName());
        name.getStyleClass().add("text-primary");
        name.setWrapText(true);
        HBox.setHgrow(name, javafx.scene.layout.Priority.ALWAYS);
        Label meta = new Label(formatBytes(match.sizeBytes()) + "  ·  "
                + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(java.time.ZoneId.systemDefault()).format(match.modifiedAt()));
        meta.getStyleClass().add("label-subtle");
        Button open = new Button("Open");
        open.getStyleClass().add("button-mini");
        open.setOnAction(ignored -> {
            String command = "open the file " + match.path();
            commandField.setText(command);
            onSubmit();
        });
        titleRow.getChildren().addAll(name, meta, open);
        Label path = new Label(match.path().toString());
        path.getStyleClass().add("label-subtle");
        path.setWrapText(true);
        card.getChildren().addAll(titleRow, path);
        return card;
    }

    private void showSystem(SystemSnapshot snapshot) {
        results.getChildren().add(SystemResultView.create(snapshot));
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
            statusLabel.setManaged(false);
            statusLabel.setVisible(false);
            progressBar.setVisible(false);
            progressBar.setManaged(false);
        }
    }

    private void showError(String title, String explanation) {
        Label heading = new Label(title);
        heading.getStyleClass().addAll("result-heading", "label-warning");
        heading.setWrapText(true);
        Label detail = new Label(explanation);
        detail.getStyleClass().add("result-metadata");
        detail.setWrapText(true);
        VBox surface = new VBox(14, heading, detail);
        surface.getStyleClass().add("result-card");
        results.getChildren().add(surface);
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

    public void setSourceOpener(Consumer<java.net.URI> opener) { sourceOpener = java.util.Objects.requireNonNull(opener); }

    public void setSearchScope(String scope) {
        searchScopeLabel.setText(scope);
    }

    /** Transfers the existing command controls to the shell; submission wiring stays here. */
    public VBox detachCommandBar() {
        setTop(null);
        setBottom(null);
        topBox.getStyleClass().add("command-bar");
        return topBox;
    }

    /** Keep global completion feedback out of other destination summaries. */
    public void presentDestination(boolean home) {
        if (executionBrain != null && currentSubscription == null) {
            boolean showCompletion = home && executionBrain.getStyleClass().contains("pipeline-complete");
            executionBrain.setVisible(showCompletion);
            executionBrain.setManaged(showCompletion);
        }
        commandField.setPromptText(home ? "Ask JADE anything…" : "Ask JADE in this context…");
    }

    public String searchScope() {
        return searchScopeLabel.getText();
    }

    public void submitCommand(String command) {
        if (currentSubscription != null) return;
        commandField.setText(command);
        onSubmit();
    }

    public void onResult(Runnable observer) {
        resultObserver = java.util.Objects.requireNonNull(observer, "observer");
    }

    public void onState(Consumer<VoiceState> observer) {
        stateObserver = java.util.Objects.requireNonNull(observer, "observer");
    }

    /**
     * Observes the name of the project JADE actually activated (a typed
     * {@code ProjectContext} result), so the shell can show a truthful
     * project indicator. Never fires for a guessed project.
     */
    public void onProject(Consumer<String> observer) {
        projectObserver = java.util.Objects.requireNonNull(observer, "observer");
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
        commandRow.getChildren().add(panel.detachMicrophone());
        if (topBox != null) {
            topBox.getChildren().add(panel);
        }
    }

    /**
     * Adds the optional live execution brain strip above the results area
     * without changing the existing layout. No-op when already installed.
     */
    public void setExecutionBrain(com.jade.ui.ExecutionBrainPanel brain) {
        java.util.Objects.requireNonNull(brain, "brain");
        if (executionBrain != null) {
            return;
        }
        this.executionBrain = brain;
        if (topBox != null) {
            topBox.getChildren().add(brain);
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
