package com.jade.ui;

import com.jade.api.CommandGateway;
import com.jade.api.CommandRequest;
import com.jade.api.CommandStatus;
import com.jade.api.CommandSubscription;
import com.jade.api.HistoryEntry;
import com.jade.api.HistoryResult;
import com.jade.api.SystemSnapshot;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The System / Activity tab: a live system snapshot, the capability status of
 * the session, and the operation timeline backed by the real SQLite history.
 * Data always comes from the same typed gateway pipeline as typed commands
 * ("system status", "show history") — no parallel query path.
 */
public final class SystemActivityPanel extends VBox {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault());

    private final CommandGateway gateway;
    private final Label capabilityLine = new Label();
    private final VBox systemBox = new VBox(8);
    private final VBox timelineBox = new VBox(4);
    private final Label statusLine = new Label();
    private CommandSubscription systemSubscription;
    private CommandSubscription historySubscription;
    private boolean refreshPending;

    public SystemActivityPanel(CommandGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");

        setPadding(new Insets(16));
        setSpacing(12);

        Label title = new Label("SYSTEM / ACTIVITY");
        title.getStyleClass().add("tab-title");
        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("button-secondary");
        refresh.setOnAction(ignored -> refresh());
        HBox headerRow = new HBox(10, title, statusLine, refresh);

        Label systemHeader = new Label("System");
        systemHeader.getStyleClass().add("section-title");
        Label timelineHeader = new Label("Operation timeline (SQLite history)");
        timelineHeader.getStyleClass().add("section-title");

        capabilityLine.getStyleClass().add("label-subtle");
        capabilityLine.setWrapText(true);

        ScrollPane systemScroll = new ScrollPane(systemBox);
        systemScroll.setFitToWidth(true);
        systemScroll.setPrefHeight(190);
        systemScroll.getStyleClass().add("center-scroll");

        ScrollPane timelineScroll = new ScrollPane(timelineBox);
        timelineScroll.setFitToWidth(true);
        timelineScroll.getStyleClass().add("center-scroll");
        VBox.setVgrow(timelineScroll, javafx.scene.layout.Priority.ALWAYS);

        getChildren().addAll(headerRow, capabilityLine, systemHeader, systemScroll,
                timelineHeader, timelineScroll);
        showEmptyStates();
    }

    /** Surfaces the session capability status (voice availability, scope). */
    public void setCapabilities(String description) {
        Objects.requireNonNull(description, "description");
        Platform.runLater(() -> capabilityLine.setText(description));
    }

    /** True while this panel owns gateway submissions. */
    public boolean isBusy() {
        return systemSubscription != null || historySubscription != null;
    }

    /** Re-queries system status and history through the gateway. */
    public void refresh() {
        if (refreshPending) {
            return;
        }
        refreshPending = true;
        statusLine.setText("Querying…");
        systemSubscription = gateway.submit(CommandRequest.create("system status"),
                event -> { /* progress surfaces in the brain strip */ },
                outcome -> {
                    systemSubscription = null;
                    Platform.runLater(() -> {
                        if (outcome.status() == CommandStatus.SUCCEEDED
                                && outcome.result().orElse(null) instanceof SystemSnapshot snapshot) {
                            renderSystem(snapshot);
                        } else {
                            systemBox.getChildren().setAll(subtle(outcome.summary()));
                        }
                        settle();
                    });
                });
        historySubscription = gateway.submit(CommandRequest.create("show history"),
                event -> { /* progress surfaces in the brain strip */ },
                outcome -> {
                    historySubscription = null;
                    Platform.runLater(() -> {
                        if (outcome.status() == CommandStatus.SUCCEEDED
                                && outcome.result().orElse(null) instanceof HistoryResult history) {
                            renderTimeline(history.entries());
                        } else {
                            timelineBox.getChildren().setAll(subtle(outcome.summary()));
                        }
                        settle();
                    });
                });
    }

    private void settle() {
        if (systemSubscription == null && historySubscription == null) {
            refreshPending = false;
            statusLine.setText("");
        }
    }

    private void showEmptyStates() {
        systemBox.getChildren().setAll(subtle("No system snapshot yet — choose Refresh."));
        timelineBox.getChildren().setAll(subtle("No operations recorded yet."));
    }

    /** Renders the typed system snapshot as metric cards. */
    public void showSystem(SystemSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Platform.runLater(() -> renderSystem(snapshot));
    }

    private void renderSystem(SystemSnapshot snapshot) {
        HBox row1 = new HBox(8,
                metricCard("OS", snapshot.osName() + " " + snapshot.osVersion()),
                metricCard("ARCHITECTURE", snapshot.architecture()));
        HBox row2 = new HBox(8,
                metricCard("CPU LOAD", snapshot.cpuLoadPercent().isPresent()
                        ? String.format("%.1f%%", snapshot.cpuLoadPercent().getAsDouble())
                        : "Unavailable"),
                metricCard("MEMORY AVAILABLE", SystemActivityPanel.bytes(snapshot.availableMemoryBytes())),
                metricCard("MEMORY TOTAL", SystemActivityPanel.bytes(snapshot.totalMemoryBytes())));
        systemBox.getChildren().setAll(row1, row2,
                subtle("Captured " + TIME_FORMAT.format(snapshot.capturedAt())));
    }

    /** Renders the bounded history as an operation timeline. */
    public void showHistory(HistoryResult history) {
        Objects.requireNonNull(history, "history");
        Platform.runLater(() -> renderTimeline(history.entries()));
    }

    private void renderTimeline(List<HistoryEntry> entries) {
        if (entries.isEmpty()) {
            timelineBox.getChildren().setAll(subtle("No operations recorded yet."));
            return;
        }
        List<Node> rows = new ArrayList<>();
        for (HistoryEntry entry : entries) {
            String icon = switch (entry.status()) {
                case SUCCEEDED -> "✓";
                case FAILED -> "✗";
                case REJECTED -> "⊘";
                case CANCELLED -> "–";
            };
            Label line = new Label(icon + "  " + TIME_FORMAT.format(entry.completedAt())
                    + "   " + entry.originalText());
            line.getStyleClass().add(switch (entry.status()) {
                case SUCCEEDED -> "text-primary";
                case FAILED, REJECTED -> "label-warning";
                case CANCELLED -> "label-subtle";
            });
            line.setWrapText(true);
            line.setMaxWidth(Double.MAX_VALUE);
            rows.add(line);
        }
        timelineBox.getChildren().setAll(rows);
    }

    private static String bytes(java.util.OptionalLong value) {
        return value.isPresent() ? format(value.getAsLong()) : "Unavailable";
    }

    private static String format(long bytes) {
        if (bytes < 1_024) return bytes + " B";
        if (bytes < 1_048_576) return String.format("%.1f KiB", bytes / 1_024.0);
        if (bytes < 1_073_741_824L) return String.format("%.1f MiB", bytes / 1_048_576.0);
        return String.format("%.1f GiB", bytes / 1_073_741_824.0);
    }

    private static Node metricCard(String term, String value) {
        Label termLabel = new Label(term);
        termLabel.getStyleClass().add("metric-term");
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("metric-value");
        valueLabel.setWrapText(true);
        VBox card = new VBox(2, termLabel, valueLabel);
        card.getStyleClass().add("metric-card");
        return card;
    }

    private static Label subtle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("label-subtle");
        label.setWrapText(true);
        return label;
    }

    /** Closes any in-flight gateway submissions. */
    public void close() {
        if (systemSubscription != null) {
            systemSubscription.close();
            systemSubscription = null;
        }
        if (historySubscription != null) {
            historySubscription.close();
            historySubscription = null;
        }
        refreshPending = false;
    }
}
