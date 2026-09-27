package com.jade.ui;

import com.jade.api.SystemSnapshot;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.OptionalDouble;

/** A shared presentation of the real OSHI snapshot; unavailable readings stay unavailable. */
final class SystemResultView {
    private SystemResultView() { }

    static VBox create(SystemSnapshot snapshot) {
        Label eyebrow = label("SYSTEM", "result-eyebrow");
        Label heading = label("System status", "result-heading");
        Label captured = label("Local snapshot · " + DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(snapshot.capturedAt()), "result-metadata");
        Label badge = label("✓ Captured", "status-success");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox title = new HBox(12, new VBox(6, eyebrow, heading, captured), spacer, badge);
        title.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        String architecture = switch (snapshot.architecture().toLowerCase(java.util.Locale.ROOT)) {
            case "aarch64", "arm64" -> "ARM64";
            case "amd64", "x86_64" -> "x86_64";
            default -> snapshot.architecture();
        };
        HBox tiles = new HBox(16, tile("OPERATING SYSTEM", snapshot.osName() + " " + snapshot.osVersion()),
                tile("ARCHITECTURE", architecture));
        tiles.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        OptionalDouble cpu = cpuUsage(snapshot);
        OptionalDouble memory = memoryUsage(snapshot);
        String memoryValue = memory.isPresent()
                ? String.format(java.util.Locale.ROOT, "%.1f GiB",
                    (snapshot.totalMemoryBytes().getAsLong() - snapshot.availableMemoryBytes().getAsLong()) / 1073741824.0)
                : "Unavailable";
        String memoryDetail = memory.isPresent()
                ? String.format(java.util.Locale.ROOT, "used of %.1f GiB total", snapshot.totalMemoryBytes().getAsLong() / 1073741824.0)
                : "No memory reading available";
        HBox readings = new HBox(36,
                progress("CPU LOAD", cpu.isPresent() ? String.format(java.util.Locale.ROOT, "%.0f%%", cpu.getAsDouble() * 100) : "Unavailable",
                        "Measured processor load", cpu),
                progress("MEMORY", memoryValue, memoryDetail, memory));
        readings.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        VBox surface = new VBox(28, title, tiles, readings);
        surface.getStyleClass().add("system-result");
        surface.setMaxWidth(780);
        return surface;
    }

    static OptionalDouble cpuUsage(SystemSnapshot snapshot) {
        if (snapshot.cpuLoadPercent().isEmpty()) return OptionalDouble.empty();
        double percent = snapshot.cpuLoadPercent().getAsDouble();
        return Double.isFinite(percent) && percent >= 0 && percent <= 100
                ? OptionalDouble.of(percent / 100) : OptionalDouble.empty();
    }

    static OptionalDouble memoryUsage(SystemSnapshot snapshot) {
        if (snapshot.totalMemoryBytes().isEmpty() || snapshot.availableMemoryBytes().isEmpty()) return OptionalDouble.empty();
        long total = snapshot.totalMemoryBytes().getAsLong();
        long available = snapshot.availableMemoryBytes().getAsLong();
        return total > 0 && available >= 0 && available <= total
                ? OptionalDouble.of((double) (total - available) / total) : OptionalDouble.empty();
    }

    private static Node tile(String term, String value) {
        VBox tile = new VBox(10, label(term, "metric-term"), label(value, "metric-value"));
        tile.getStyleClass().add("system-identity");
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    private static Node progress(String term, String value, String detail, OptionalDouble ratio) {
        Label number = label(value, "progress-value");
        ProgressBar bar = new ProgressBar(ratio.orElse(0));
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setAccessibleText(term + ": " + value);
        bar.getStyleClass().add("metric-progress");
        bar.setVisible(ratio.isPresent());
        bar.setManaged(ratio.isPresent());
        VBox reading = new VBox(10, label(term, "metric-term"), number, label(detail, "result-metadata"), bar);
        reading.getStyleClass().add("system-reading");
        reading.setMaxWidth(Double.MAX_VALUE);
        return reading;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }
}
