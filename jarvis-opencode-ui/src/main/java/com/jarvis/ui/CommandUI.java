package com.jarvis.ui;

import com.jarvis.api.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.*;

/**
 * The main JARVIS command UI view.
 *
 * Receives a CommandGateway dependency through its constructor.
 * Subscribes to progress and completion callbacks, marshals UI
 * changes onto the JavaFX application thread, and unsubscribes
 * on disposal.
 */
public class CommandUI extends Region {

    private static final int GAP = 8;
    private static final double CORNER_RADIUS = 8.0;

    private final CommandGateway gateway;
    private CommandSubscription currentSubscription;
    private final boolean cancellable;

    // UI controls
    private TextField commandField;
    private Button submitButton;
    private Button cancelButton;
    private Label statusLabel;
    private ProgressBar progressBar;
    private StackPane contentPane;
    private Label searchScopeLabel;

    private static final Class<?>[] API_CLASSES = new Class[0];

    public CommandUI(CommandGateway gateway) {
        this(gateway, true);
    }

    public CommandUI(CommandGateway gateway, boolean cancellable) {
        this.gateway = gateway;
        this.cancellable = cancellable;
        setStyle("-fx-background-color: #0a0a2e;");
        initializeUI();
        initializeBindings();
    }

    private void initializeUI() {
        setPrefSize(800, 600);
        setMinSize(600, 400);
        setMaxSize(1200, 800);

        // Command row at top
        HBox commandRow = new HBox(GAP);
        commandRow.setStyle("-fx-padding: 8;" + "-fx-background-color: #16213e;");
        commandRow.setMaxHeight(40);

        commandField = new TextField();
        commandField.setPromptText("Enter command...");
        commandField.setMaxWidth(400);
        commandField.setStyle("-fx-control-inner-background: #0a0a2e;" + "-fx-text-fill: #f1f1f1;" + "-fx-caret-color: #0fbcef;");
        commandField.setOnAction(e -> onSubmit());

        submitButton = new Button("Submit");
        submitButton.setDefaultButton(true);
        submitButton.setOnAction(e -> onSubmit());
        submitButton.setStyle(
            "-fx-background-color: #0fbcef;" +
            "-fx-text-fill: #0a0a2e;" +
            "-fx-font-weight: bold;" +
            "-fx-transition: background-color 0.2s;"
        );
        submitButton.setDisable(true);

        cancelButton = new Button("Cancel");
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        cancelButton.setOnAction(e -> onCancel());
        cancelButton.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-text-fill: #e94d5f;" +
            "-fx-cursor: hand;"
        );

        searchScopeLabel = new Label("Search scope: ~/Documents");
        searchScopeLabel.getStyleClass().add("label-subtle");

        HBox commandTop = new HBox(GAP, commandField, submitButton, cancelButton);
        commandTop.setStyle("-fx-padding: 8 12 4 12;");

        // Status / progress area
        statusLabel = new Label();
        statusLabel.getStyleClass().add("label-subtle");
        statusLabel.setMaxWidth(Region.USE_COMPUTED_SIZE);

        progressBar = new ProgressBar();
        progressBar.setVisible(false);
        progressBar.setStyle("-fx-accent: #0fbcef;");

        HBox statusRow = new HBox(GAP, statusLabel, progressBar);
        statusRow.setStyle("-fx-padding: 4 12 4 12;");
        statusRow.setMaxHeight(25);

        // Content area - shows results or empty state
        contentPane = new StackPane();
        contentPane.setStyle("-fx-padding: 12;");
        contentPane.setStyle(
            "-fx-background-color: #16213e;" +
            "-fx-background-radius: " + CORNER_RADIUS + ";"
        );

        // Empty state placeholder
        Label emptyLabel = new Label("Enter a command to get started");
        emptyLabel.getStyleClass().add("label-subtle");
        emptyLabel.setStyle("-fx-opacity: 0.6;");
        contentPane.getChildren().add(emptyLabel);
        StackPane.setAlignment(emptyLabel, Pos.CENTER);

        // Search scope bar
        HBox scopeBar = new HBox(GAP, searchScopeLabel);
        scopeBar.setStyle("-fx-padding: 4 12 4 12;");

        // Main layout - VBox with command row, status row, content, scope
        VBox root = new VBox(GAP, commandRow, statusRow, contentPane, scopeBar);
        root.setStyle("-fx-padding: 0;");
        root.setMaxWidth(Region.USE_COMPUTED_SIZE);

        // Root VBox is the content of this UI region
        getChildren().add(root);
    }

    private void initializeBindings() {
        // When command text changes, enable submit only if non-empty
        commandField.textProperty().addListener((obs, old, newVal) -> {
            submitButton.setDisable(newVal == null || newVal.trim().isEmpty());
        });

        // Cancel button visibility is managed manually
        // Progress bar visibility is managed in onProgress() callback
    }

// Progress callback from the gateway
    private void onProgress(ProgressEvent event) {
        Platform.runLater(() -> {
            String message = getProgressEventMessage(event);
            if (message != null && !message.isEmpty()) {
                statusLabel.setText(message);
            }

            ProgressStage stage = getProgressEventStage(event);
            if (stage != null) {
                switch (stage) {
                    case QUEUED:
                        statusLabel.setText("Queued");
                        break;
                    case PARSING:
                        statusLabel.setText("Parsing command...");
                        break;
                    case EXECUTING:
                        statusLabel.setText("Executing...");
                        break;
                    case PERSISTING:
                        statusLabel.setText("Persisting results...");
                        break;
                }
            }

            long completed = getProgressEventCompletedUnits(event);
            OptionalLong total = getProgressEventTotalUnits(event);
            if (total.isPresent()) {
                double pct = (double) completed / total.getAsLong();
                progressBar.setProgress(pct);
                progressBar.setVisible(true);
            } else if (completed > 0) {
                progressBar.setProgress(1.0);
                progressBar.setVisible(true);
            }
        });
    }

    // Command completion callback from the gateway
    private void onCommandComplete(CommandOutcome outcome) {
        Platform.runLater(() -> {
            currentSubscription = null;
            submitButton.setDisable(false);
            cancelButton.setVisible(false);
            cancelButton.setManaged(false);
            progressBar.setVisible(false);
            statusLabel.setText("");

            // Clear previous content
            contentPane.getChildren().clear();

            CommandStatus status = getCommandOutcomeStatus(outcome);

            switch (status) {
                case CANCELLED:
                    showCancellationState();
                    break;
                case REJECTED:
                    showRejectedState(outcome);
                    break;
                case FAILED:
                    showFailureState(outcome);
                    break;
                case SUCCEEDED:
                    showSuccessState(outcome);
                    break;
            }
        });
    }

    private CommandStatus getCommandOutcomeStatus(CommandOutcome outcome) {
        try {
            Field statusField = CommandOutcome.class.getDeclaredField("status");
            statusField.setAccessible(true);
            return (CommandStatus) statusField.get(outcome);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read CommandOutcome status", e);
        }
    }

    private Optional<StructuredError> getCommandOutcomeErrorOptional(CommandOutcome outcome) {
        try {
            Field errorField = CommandOutcome.class.getDeclaredField("error");
            errorField.setAccessible(true);
            StructuredError error = (StructuredError) errorField.get(outcome);
            return Optional.ofNullable(error);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read CommandOutcome error", e);
        }
    }

    private Optional<CommandResult> getCommandOutcomeResultOptional(CommandOutcome outcome) {
        try {
            Field resultField = CommandOutcome.class.getDeclaredField("result");
            resultField.setAccessible(true);
            Object result = resultField.get(outcome);
            return (Optional<CommandResult>) result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read CommandOutcome result", e);
        }
    }

    private String getCommandOutcomeSummary(CommandOutcome outcome) {
        try {
            Field summaryField = CommandOutcome.class.getDeclaredField("summary");
            summaryField.setAccessible(true);
            return (String) summaryField.get(outcome);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read CommandOutcome summary", e);
        }
    }

    private void showCancellationState() {
        Label label = new Label("Command cancelled");
        label.getStyleClass().add("label-subtle");
        contentPane.getChildren().add(label);
        StackPane.setAlignment(label, Pos.CENTER);
    }

    private void showRejectedState(CommandOutcome outcome) {
        Optional<StructuredError> error = getCommandOutcomeErrorOptional(outcome);
        String message = error.map(e -> e.message()).orElse("Command rejected");
        Label label = new Label("Rejected: " + message);
        label.getStyleClass().add("label-warning");
        contentPane.getChildren().add(label);
        StackPane.setAlignment(label, Pos.CENTER);
    }

    private void showFailureState(CommandOutcome outcome) {
        Optional<StructuredError> error = getCommandOutcomeErrorOptional(outcome);
        String message = error.map(e -> e.message()).orElse("An unexpected error occurred");
        Label label = new Label("Failed: " + message);
        label.getStyleClass().add("label-warning");
        contentPane.getChildren().add(label);
        StackPane.setAlignment(label, Pos.CENTER);
    }

    private void showSuccessState(CommandOutcome outcome) {
        Optional<CommandResult> resultOptional = getCommandOutcomeResultOptional(outcome);
        String summary = getCommandOutcomeSummary(outcome);
        resultOptional.ifPresent(result -> {
            contentPane.getChildren().clear();
            String resultType = result.getClass().getSimpleName();

            switch (resultType) {
                case "AppLaunchReceipt":
                    showAppLaunchResult((AppLaunchReceipt) result);
                    break;
                case "FileSearchResult":
                    showFileSearchResult((FileSearchResult) result);
                    break;
                case "SystemSnapshot":
                    showSystemSnapshotResult((SystemSnapshot) result);
                    break;
                case "HistoryResult":
                    showHistoryResult((HistoryResult) result);
                    break;
                default:
                    showGenericResult(result);
                    break;
            }
        });
        if (!resultOptional.isPresent()) {
            Label label = new Label("Result: " + summary);
            label.getStyleClass().add("label-subtle");
            contentPane.getChildren().add(label);
            StackPane.setAlignment(label, Pos.CENTER);
        }
    }

    private void showAppLaunchResult(AppLaunchReceipt receipt) {
        Label title = new Label("Launched: " + receipt.displayName());
        title.getStyleClass().add("text-accent");
        contentPane.getChildren().add(title);
        StackPane.setAlignment(title, Pos.TOP_LEFT);

        Label details = new Label("App ID: " + receipt.appId() + " | Launched at: " + receipt.launchedAt());
        details.getStyleClass().add("label-subtle");
        contentPane.getChildren().add(details);
        StackPane.setAlignment(details, Pos.CENTER);
    }

    private void showFileSearchResult(FileSearchResult result) {
        VBox box = new VBox(GAP);

        try {
            Field matchesField = FileSearchResult.class.getDeclaredField("matches");
            matchesField.setAccessible(true);
            List<FileMatch> matches = (List<FileMatch>) matchesField.get(result);

            if (matches.isEmpty()) {
                Label empty = new Label("No files found matching the search criteria");
                empty.getStyleClass().add("label-subtle");
                box.getChildren().add(empty);
            } else {
                for (FileMatch match : matches) {
                    HBox matchRow = new HBox(GAP);
                    Label pathLabel = new Label(match.path() + "");
                    pathLabel.getStyleClass().add("text-primary");
                    Label nameLabel = new Label(match.fileName());
                    nameLabel.getStyleClass().add("label-subtle");
                    Label sizeLabel = new Label(formatBytes(match.sizeBytes()));
                    sizeLabel.getStyleClass().add("label-subtle");
                    matchRow.getChildren().addAll(pathLabel, nameLabel, sizeLabel);
                    box.getChildren().add(matchRow);
                }
            }

            Field visitedFilesField = FileSearchResult.class.getDeclaredField("visitedFiles");
            visitedFilesField.setAccessible(true);
            long visited = (Long) visitedFilesField.get(result);
            Label visitedLabel = new Label("Visited files: " + visited);
            visitedLabel.getStyleClass().add("label-subtle");
            box.getChildren().add(visitedLabel);

            Field resultLimitReachedField = FileSearchResult.class.getDeclaredField("resultLimitReached");
            resultLimitReachedField.setAccessible(true);
            boolean resultLimitReached = (Boolean) resultLimitReachedField.get(result);
            if (resultLimitReached) {
                Label limitWarn = new Label("Result limit reached (max 50)");
                limitWarn.getStyleClass().add("label-subtle");
                box.getChildren().add(limitWarn);
            }

            Field scanLimitReachedField = FileSearchResult.class.getDeclaredField("scanLimitReached");
            scanLimitReachedField.setAccessible(true);
            boolean scanLimitReached = (Boolean) scanLimitReachedField.get(result);
            if (scanLimitReached) {
                Label scanWarn = new Label("Scan limit reached (max 10,000 files)");
                scanWarn.getStyleClass().add("label-subtle");
                box.getChildren().add(scanWarn);
            }
        } catch (Exception e) {
            Label error = new Label("Error displaying search results: " + e.getMessage());
            error.getStyleClass().add("label-warning");
            box.getChildren().add(error);
        }

        contentPane.getChildren().add(box);
        VBox.setVgrow(box, Priority.ALWAYS);
    }

    private void showSystemSnapshotResult(SystemSnapshot snapshot) {
        VBox box = new VBox(GAP);

        Label osLabel = new Label("OS: " + snapshot.osName() + " " + snapshot.osVersion());
        osLabel.getStyleClass().add("label-subtle");
        box.getChildren().add(osLabel);

        Label archLabel = new Label("Architecture: " + snapshot.architecture());
        archLabel.getStyleClass().add("label-subtle");
        box.getChildren().add(archLabel);

        // CPU load - show "Unavailable" if null
        String cpuStr = "Unavailable";
        if (snapshot.cpuLoadPercent().isPresent()) {
            cpuStr = String.format("%.1f%% CPU load", snapshot.cpuLoadPercent().getAsDouble());
        }
        Label cpuLabel = new Label("CPU load: " + cpuStr);
        cpuLabel.getStyleClass().add("label-subtle");
        box.getChildren().add(cpuLabel);

        // Memory - show "Unavailable" if null
        String totalMem = "Unavailable";
        if (snapshot.totalMemoryBytes().isPresent()) {
            totalMem = formatBytes(snapshot.totalMemoryBytes().getAsLong());
        }
        String availMem = "Unavailable";
        if (snapshot.availableMemoryBytes().isPresent()) {
            availMem = formatBytes(snapshot.availableMemoryBytes().getAsLong());
        }
        Label memLabel = new Label("Memory: total " + totalMem + ", available " + availMem);
        memLabel.getStyleClass().add("label-subtle");
        box.getChildren().add(memLabel);

        contentPane.getChildren().add(box);
    }

    private void showHistoryResult(HistoryResult result) {
        VBox box = new VBox(GAP);

        try {
            Field entriesField = HistoryResult.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            List<HistoryEntry> entries = (List<HistoryEntry>) entriesField.get(result);

            if (entries.isEmpty()) {
                Label empty = new Label("No command history available");
                empty.getStyleClass().add("label-subtle");
                box.getChildren().add(empty);
            } else {
                // Show last 10 entries
                int startIndex = Math.max(0, entries.size() - 10);
                List<HistoryEntry> recent = entries.subList(startIndex, entries.size());
                for (HistoryEntry entry : recent) {
                    HBox entryRow = new HBox(GAP);
                    Label textLabel = new Label(entry.originalText());
                    textLabel.getStyleClass().add("text-primary");
                    textLabel.setWrapText(true);
                    Label statusLabel = new Label("[" + entry.status() + "]");
                    statusLabel.getStyleClass().add("label-subtle");
                    entryRow.getChildren().addAll(textLabel, statusLabel);
                    box.getChildren().add(entryRow);
                }
            }
        } catch (Exception e) {
            Label error = new Label("Error displaying history: " + e.getMessage());
            error.getStyleClass().add("label-warning");
            box.getChildren().add(error);
        }

        contentPane.getChildren().add(box);
        VBox.setVgrow(box, Priority.ALWAYS);
    }

    private void showGenericResult(CommandResult result) {
        Label label = new Label("Result: " + result);
        label.getStyleClass().add("label-subtle");
        contentPane.getChildren().add(label);
        StackPane.setAlignment(label, Pos.CENTER);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // Command submission
    private void onSubmit() {
        String text = commandField.getText().trim();
        if (text.isEmpty()) return;

        // Prevent duplicate submissions
        if (submitButton.isDisabled()) return;

        submitButton.setDisable(true);
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);

        CommandRequest request = CommandRequest.create(text);

        Consumer<ProgressEvent> progressConsumer = this::onProgress;
        Consumer<CommandOutcome> completeConsumer = this::onCommandComplete;

        CommandSubscription submission = gateway.submit(request, progressConsumer, completeConsumer);

        // Store subscription for cancellation and state tracking
        this.currentSubscription = submission;

        // Update UI based on subscription state
        updateSubscriptionUI(submission);
    }

    private String getProgressEventMessage(ProgressEvent event) {
        try {
            Field messageField = ProgressEvent.class.getDeclaredField("message");
            messageField.setAccessible(true);
            return (String) messageField.get(event);
        } catch (Exception e) {
            return null;
        }
    }

    private ProgressStage getProgressEventStage(ProgressEvent event) {
        try {
            Field stageField = ProgressEvent.class.getDeclaredField("stage");
            stageField.setAccessible(true);
            return (ProgressStage) stageField.get(event);
        } catch (Exception e) {
            return null;
        }
    }

    private long getProgressEventCompletedUnits(ProgressEvent event) {
        try {
            Field completedField = ProgressEvent.class.getDeclaredField("completedUnits");
            completedField.setAccessible(true);
            return (Long) completedField.get(event);
        } catch (Exception e) {
            return 0L;
        }
    }

    private OptionalLong getProgressEventTotalUnits(ProgressEvent event) {
        try {
            Field totalField = ProgressEvent.class.getDeclaredField("totalUnits");
            totalField.setAccessible(true);
            return (OptionalLong) totalField.get(event);
        } catch (Exception e) {
            return OptionalLong.of(0L);
        }
    }

    private void updateSubscriptionUI(CommandSubscription subscription) {
        // Cancel button: visible when we have an active subscription
        if (cancellable) {
            boolean hasActive = subscription != null && !subscription.isCancellationRequested();
            cancelButton.setVisible(hasActive);
            cancelButton.setManaged(hasActive);
        }
    }

    private void onCancel() {
        if (currentSubscription != null && cancellable) {
            currentSubscription.cancel();
            // Cancellation is cooperative; we do NOT reverse completed actions
            // The onCommandComplete will be called with CANCELLED status
            statusLabel.setText("Cancelling...");
        }
    }

    // Dispose / close the subscription when the UI is no longer needed
    public void close() {
        if (currentSubscription != null) {
            currentSubscription.close();
            currentSubscription = null;
        }
    }

    // Public method to set the search scope text
    public void setSearchScope(String scope) {
        if (searchScopeLabel != null) {
            searchScopeLabel.setText("Search scope: " + scope);
        }
    }
}