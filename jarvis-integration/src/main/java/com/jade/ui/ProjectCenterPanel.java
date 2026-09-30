package com.jade.ui;

import com.jade.api.CommandGateway;
import com.jade.api.CommandRequest;
import com.jade.api.CommandStatus;
import com.jade.api.CommandSubscription;
import com.jade.api.DependencyInfo;
import com.jade.api.DependencyList;
import com.jade.api.MainClassCandidates;
import com.jade.api.ProjectInspectionResult;
import com.jade.api.ProjectTree;
import com.jade.api.TodoFinding;
import com.jade.api.TodoFindings;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The Project Command Center: one tab that renders the typed static
 * inspection of the active Maven project — summary metric cards, a
 * dependency chip wall, the bounded structure tree, TODO/FIXME findings and
 * main-class candidates. It drives the same {@link CommandGateway} pipeline
 * as typed commands (no second parsing path); all rendering comes from typed
 * results, never from scraped text.
 */
public final class ProjectCenterPanel extends VBox {

    private final CommandGateway gateway;
    private final Consumer<String> onNotice;
    private final Label emptyState = new Label("No active project. Type:  open project /path/to/project");
    private final Label statusLine = new Label();
    private final VBox content = new VBox(14);
    private final VBox summaryBox = new VBox(10);
    private final FlowPane dependencyChips = new FlowPane(6, 6);
    private final VBox todoBox = new VBox(4);
    private final VBox mainsBox = new VBox(4);
    private final VBox structureBox = new VBox(4);
    private final Label structureHeader = new Label();
    private CommandSubscription currentSubscription;

    public ProjectCenterPanel(CommandGateway gateway, Consumer<String> onNotice) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.onNotice = Objects.requireNonNull(onNotice, "onNotice");

        setPadding(new Insets(16));
        setSpacing(12);

        Label title = new Label("PROJECT COMMAND CENTER");
        title.getStyleClass().add("tab-title");
        statusLine.getStyleClass().add("label-subtle");
        HBox headerRow = new HBox(10, title, statusLine);

        emptyState.getStyleClass().add("empty-state");
        emptyState.setWrapText(true);

        content.getChildren().addAll(summaryBox, structureHeader, structureBox, dependencyChips, todoBox, mainsBox);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("center-scroll");

        getChildren().addAll(headerRow, emptyState, scroll);
        showEmpty();
    }

    /** True while this panel owns a gateway submission. */
    public boolean isBusy() {
        return currentSubscription != null;
    }

    /**
     * Requests a fresh inspection through the real gateway pipeline using the
     * existing typed phrase grammar. Failures land in the honest empty state.
     */
    public void refresh() {
        if (currentSubscription != null) {
            return; // one inspection at a time
        }
        statusLine.setText("Inspecting…");
        currentSubscription = gateway.submit(
                CommandRequest.create("give me a project summary"),
                event -> { /* progress surfaces in the execution brain strip */ },
                this::onComplete);
    }

    private void onComplete(com.jade.api.CommandOutcome outcome) {
        CommandSubscription finished = currentSubscription;
        currentSubscription = null;
        Platform.runLater(() -> {
            if (outcome.status() != CommandStatus.SUCCEEDED) {
                emptyState.setText(outcome.summary());
                statusLine.setText("");
                showEmpty();
                return;
            }
            if (outcome.result().orElse(null) instanceof ProjectInspectionResult inspection) {
                renderInspection(inspection);
            } else {
                emptyState.setText(outcome.summary());
                showEmpty();
            }
        });
        if (finished != null) {
            finished.close();
        }
    }

    private void showEmpty() {
        emptyState.setVisible(true);
        emptyState.setManaged(true);
        content.setVisible(false);
        content.setManaged(false);
    }

    private void showFilled() {
        emptyState.setVisible(false);
        emptyState.setManaged(false);
        content.setVisible(true);
        content.setManaged(true);
    }

    /**
     * Renders the full typed inspection (the V2 summary view). Safe from any
     * thread; rendering is marshalled onto the FX thread.
     */
    public void showInspection(ProjectInspectionResult inspection) {
        Objects.requireNonNull(inspection, "inspection");
        Platform.runLater(() -> renderInspection(inspection));
    }

    private void renderInspection(ProjectInspectionResult inspection) {
        showFilled();
        ProjectInspectionResult.Coordinates c = inspection.coordinates();
        ProjectInspectionResult.SourceInventory s = inspection.sources();
        statusLine.setText(c.groupId() + ":" + c.artifactId() + ":" + c.version());

        List<Node> rows = new ArrayList<>();
        rows.add(new HBox(8,
                metricCard("GROUP ID", c.groupId()),
                metricCard("ARTIFACT", c.artifactId()),
                metricCard("VERSION", c.version()),
                metricCard("PACKAGING", c.packaging())));
        c.name().ifPresent(name -> rows.add(new HBox(8, metricCard("NAME", name))));
        c.javaVersion().ifPresent(java -> rows.add(new HBox(8, metricCard("JAVA", java))));
        rows.add(new HBox(8,
                metricCard("JAVA FILES", String.valueOf(s.javaSourceFiles())),
                metricCard("TEST FILES", String.valueOf(s.javaTestFiles())),
                metricCard("RESOURCES", String.valueOf(s.resourceFiles())),
                metricCard("PACKAGES", String.valueOf(s.packages()))));
        summaryBox.getChildren().setAll(rows);

        renderDependencies(inspection.dependencies());
        renderTodos(inspection.todoFindings(), inspection.todosTruncated());
        renderMains(inspection.mainCandidates());
        renderSourceRoots(s);
        if (inspection.scanIncomplete()) {
            Label warning = new Label("⚠ Inspection reached walk/size limits; counts are partial.");
            warning.getStyleClass().add("label-warning");
            warning.setWrapText(true);
            summaryBox.getChildren().add(warning);
        }
    }

    private void renderSourceRoots(ProjectInspectionResult.SourceInventory s) {
        if (structureHeader.getStyleClass().isEmpty()) {
            structureHeader.getStyleClass().add("section-title");
        }
        structureHeader.setText("Source roots: " + String.join(", ", s.sourceRoots())
                + "   ·   Test roots: " + String.join(", ", s.testRoots()));
    }

    /** Renders the bounded structure tree from the typed result. */
    public void showStructure(ProjectTree tree) {
        Objects.requireNonNull(tree, "tree");
        Platform.runLater(() -> renderStructure(ProjectStructureModel.from(tree)));
    }

    private void renderStructure(ProjectStructureModel model) {
        showFilled();
        structureHeader.getStyleClass().setAll("section-title");
        structureHeader.setText("Structure — " + model.rootName()
                + (model.truncated() ? "  (truncated at " + ProjectTree.MAX_LINES
                        + " entries / " + ProjectTree.MAX_DEPTH + " levels)" : ""));

        TreeItem<String> root = new TreeItem<>(model.rootName());
        root.setExpanded(true);
        // Parent of a depth-d node is the most recent depth-(d-1) node.
        TreeItem<String>[] lastAtDepth = new TreeItem[ProjectTree.MAX_DEPTH + 2];
        for (ProjectStructureModel.Node node : model.nodes()) {
            if (node.depth() < 0 || node.depth() >= lastAtDepth.length) {
                continue; // deeper than the bounded tree can express — skip honestly
            }
            TreeItem<String> parent = node.depth() == 0
                    ? root
                    : java.util.Objects.requireNonNullElse(lastAtDepth[node.depth() - 1], root);
            TreeItem<String> item = new TreeItem<>(node.name());
            parent.getChildren().add(item);
            lastAtDepth[node.depth()] = item;
            for (int deeper = node.depth() + 1; deeper < lastAtDepth.length; deeper += 1) {
                lastAtDepth[deeper] = null;
            }
        }
        TreeView<String> view = new TreeView<>(root);
        view.getStyleClass().add("structure-tree");
        view.setShowRoot(true);
        structureBox.getChildren().setAll(view);
    }

    /** Renders the dependency chip wall. */
    public void showDependencies(DependencyList dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        Platform.runLater(() -> renderDependencies(dependencies.dependencies()));
    }

    private void renderDependencies(List<DependencyInfo> dependencies) {
        if (dependencies.isEmpty()) {
            dependencyChips.getChildren().setAll(subtle("No declared dependencies in the pom.xml."));
            return;
        }
        dependencyChips.getChildren().setAll(dependencies.stream()
                .map(ProjectCenterPanel::dependencyChip)
                .toList());
    }

    private static Node dependencyChip(DependencyInfo dependency) {
        Label chip = new Label(dependency.artifactId()
                + dependency.version().map(version -> "  " + version).orElse(""));
        chip.getStyleClass().add("dep-chip");
        chip.setTooltip(new Tooltip(dependency.groupId() + ":" + dependency.artifactId()
                + dependency.version().map(version -> ":" + version).orElse("")
                + dependency.scope().map(scope -> " (" + scope + ")").orElse("")));
        return chip;
    }

    /** Renders TODO/FIXME findings. */
    public void showTodos(TodoFindings todos) {
        Objects.requireNonNull(todos, "todos");
        Platform.runLater(() -> renderTodos(todos.findings(), todos.truncated()));
    }

    private void renderTodos(List<TodoFinding> findings, boolean truncated) {
        List<Node> rows = new ArrayList<>();
        rows.add(sectionLabel("TODO / FIXME" + (truncated ? "  (truncated)" : "")));
        if (findings.isEmpty()) {
            rows.add(subtle("No TODO or FIXME markers found in the project sources."));
        } else {
            for (TodoFinding finding : findings) {
                Label line = new Label("• [" + finding.marker() + "] " + finding.path()
                        + ":" + finding.lineNumber()
                        + finding.snippet().map(snippet -> "  —  " + snippet).orElse(""));
                line.getStyleClass().add("label-subtle");
                line.setWrapText(true);
                rows.add(line);
            }
        }
        todoBox.getChildren().setAll(rows);
    }

    /** Renders main-class candidates without guessing a winner. */
    public void showMains(MainClassCandidates mains) {
        Objects.requireNonNull(mains, "mains");
        Platform.runLater(() -> renderMains(mains));
    }

    private void renderMains(MainClassCandidates mains) {
        List<Node> rows = new ArrayList<>();
        rows.add(sectionLabel("Main candidates"));
        if (mains.candidates().isEmpty()) {
            rows.add(subtle("No main-method candidates found in the project sources."));
        } else {
            for (MainClassCandidates.Candidate candidate : mains.candidates()) {
                Label line = new Label("• " + candidate.className() + " — " + candidate.signature());
                line.getStyleClass().add("text-primary");
                line.setWrapText(true);
                rows.add(line);
            }
        }
        mainsBox.getChildren().setAll(rows);
    }

    /** Renders the source/test metric cards only. */
    public void showInventory(ProjectInspectionResult.SourceInventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        Platform.runLater(() -> {
            showFilled();
            summaryBox.getChildren().setAll(new HBox(8,
                    metricCard("JAVA FILES", String.valueOf(inventory.javaSourceFiles())),
                    metricCard("TEST FILES", String.valueOf(inventory.javaTestFiles())),
                    metricCard("RESOURCES", String.valueOf(inventory.resourceFiles())),
                    metricCard("PACKAGES", String.valueOf(inventory.packages()))));
        });
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

    private static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private static Label subtle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("label-subtle");
        label.setWrapText(true);
        return label;
    }

    /** Closes any in-flight refresh subscription. */
    public void close() {
        if (currentSubscription != null) {
            currentSubscription.close();
            currentSubscription = null;
        }
    }
}
