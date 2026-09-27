package com.jade.ui;

import com.jade.services.audio.VoiceState;
import com.jade.ui.shell.JadeShellModel;
import com.jade.ui.shell.MotionPreferences;
import com.jade.ui.shell.VisualCore;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Pane;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** One JavaFX shell around the existing command and information panels. */
public final class CommandCenterUI extends BorderPane implements AutoCloseable {
    private final CommandUI assistant;
    private final ProjectCenterPanel projectPanel;
    private final SystemActivityPanel systemPanel;
    private final JadeShellModel model = new JadeShellModel();
    private final MotionPreferences motion = new MotionPreferences();
    private final VisualCore core = new VisualCore(motion);
    private final Label stateLabel = new Label("READY");
    private final HBox statusChips = new HBox(8);
    private final Map<JadeShellModel.Destination, Button> navigation =
            new EnumMap<>(JadeShellModel.Destination.class);
    private final Node home;
    private final BorderPane workspace = new BorderPane();
    private final VBox homeLayout = new VBox(16);
    private final VBox coreStage = new VBox(8);
    private final HBox coreIdentity = new HBox(10);
    private final Label prompt = new Label("What can I help you with?");
    private final HBox suggestions = new HBox(10);
    private final VBox commandDock;
    private boolean resultAvailable;
    private FadeTransition pageFade;

    public CommandCenterUI(CommandUI assistant, ProjectCenterPanel projectPanel, SystemActivityPanel systemPanel) {
        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.projectPanel = Objects.requireNonNull(projectPanel, "projectPanel");
        this.systemPanel = Objects.requireNonNull(systemPanel, "systemPanel");
        getStyleClass().add("command-center");

        Label brand = new Label("JADE");
        brand.getStyleClass().add("shell-brand");
        Label subtitle = new Label("LOCAL COMPUTATIONAL ASSISTANT");
        subtitle.getStyleClass().add("shell-subtitle");
        VBox identity = new VBox(2, brand, subtitle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        CheckBox reducedMotion = new CheckBox("Reduced motion");
        reducedMotion.getStyleClass().add("motion-toggle");
        reducedMotion.setOnAction(event -> {
            motion.setMode(reducedMotion.isSelected()
                    ? MotionPreferences.Mode.REDUCED : MotionPreferences.Mode.FULL);
            if (model.navigation() == JadeShellModel.Destination.HOME) core.setState(model.appState());
        });
        HBox header = new HBox(20, identity, spacer, statusChips, reducedMotion);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("shell-header");
        setTop(header);

        VBox rail = new VBox(6);
        rail.getStyleClass().add("navigation-rail");
        for (JadeShellModel.Destination destination : JadeShellModel.Destination.values()) {
            Button button = new Button(destination.name().substring(0, 1) + destination.name().substring(1).toLowerCase(java.util.Locale.ROOT));
            button.getStyleClass().add("navigation-button");
            button.setGraphic(destinationGlyph(destination));
            button.setGraphicTextGap(10);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(event -> navigate(destination));
            navigation.put(destination, button);
            rail.getChildren().add(button);
        }
        setLeft(rail);
        VBox commandBar = assistant.detachCommandBar();
        Label scopeName = new Label("SCOPE");
        scopeName.getStyleClass().add("scope-caption");
        Label scopePath = new Label(assistant.searchScope());
        scopePath.getStyleClass().add("scope-path");
        scopePath.setMinWidth(0);
        scopePath.setMaxWidth(Double.MAX_VALUE);
        scopePath.setTooltip(new Tooltip(assistant.searchScope()));
        HBox.setHgrow(scopePath, Priority.ALWAYS);
        HBox scope = new HBox(12, scopeName, scopePath);
        scope.setAlignment(Pos.CENTER_LEFT);
        scope.getStyleClass().add("scope-context");
        commandDock = new VBox(12, commandBar, scope);
        commandDock.setMaxWidth(780);
        commandDock.getStyleClass().add("command-dock");

        core.setMinSize(0, 0);
        stateLabel.getStyleClass().add("core-state-label");
        coreStage.getChildren().addAll(core, stateLabel);
        coreStage.setAlignment(Pos.CENTER);
        coreStage.setMaxWidth(780);
        coreIdentity.setAlignment(Pos.CENTER_LEFT);
        coreStage.getStyleClass().add("core-stage");
        prompt.getStyleClass().add("interaction-prompt");
        suggestions.setAlignment(Pos.CENTER);
        for (JadeShellModel.Suggestion suggestion : JadeShellModel.Suggestion.values()) {
            suggestions.getChildren().add(suggestionButton(suggestion));
        }
        assistant.setMinSize(0, 0);
        assistant.setMaxWidth(780);
        assistant.setPrefHeight(Region.USE_COMPUTED_SIZE);
        assistant.setMaxHeight(380);
        VBox.setVgrow(assistant, Priority.NEVER);
        homeLayout.getChildren().addAll(coreStage, prompt, suggestions, assistant, commandDock);
        homeLayout.setAlignment(Pos.CENTER);
        homeLayout.getStyleClass().add("home-stage");
        home = new StackPane(homeLayout);
        workspace.getStyleClass().add("main-workspace");
        setCenter(workspace);
        assistant.onResult(() -> {
            resultAvailable = true;
            updateHome();
            navigate(JadeShellModel.Destination.HOME);
        });
        updateHome();

        assistant.onState(this::onApplicationState);
        assistant.onProject(project -> {
            model.setObservedProject(project);
            renderStatus();
        });
        navigate(JadeShellModel.Destination.HOME);
        renderStatus();
    }

    public void setVoiceAvailable(boolean available) {
        model.setVoiceAvailable(available);
        renderStatus();
    }

    public void onVoiceState(VoiceState state) {
        if (state == null) {
            setVoiceAvailable(false);
            return;
        }
        model.setVoiceState(state);
        onApplicationState(state);
    }

    public void onApplicationState(VoiceState state) {
        model.setAppState(state);
        if (state == VoiceState.PROCESSING || state == VoiceState.EXECUTING) resultAvailable = false;
        updateHome();
        if (model.navigation() == JadeShellModel.Destination.HOME) core.setState(state);
        stateLabel.setText(model.motionProfile().stateLabel());
        renderStatus();
    }

    public void navigate(JadeShellModel.Destination destination) {
        model.navigate(destination);
        assistant.presentDestination(destination == JadeShellModel.Destination.HOME);
        Node dockScope = commandDock.lookup(".scope-context");
        if (dockScope != null) {
            dockScope.setVisible(destination != JadeShellModel.Destination.FILES);
            dockScope.setManaged(destination != JadeShellModel.Destination.FILES);
        }
        if (destination == JadeShellModel.Destination.HOME) core.setState(model.appState());
        else core.shutdown();
        workspace.setBottom(null);
        if (commandDock.getParent() instanceof Pane parent) parent.getChildren().remove(commandDock);
        if (destination == JadeShellModel.Destination.HOME) homeLayout.getChildren().add(commandDock);
        else if (destination != JadeShellModel.Destination.FILES) {
            workspace.setBottom(commandDock);
            BorderPane.setAlignment(commandDock, Pos.CENTER);
        }
        Node page = switch (destination) {
            case HOME -> home;
            case FILES -> filesSummary();
            case PROJECT -> projectPanel;
            case ACTIVITY -> placeholder("Activity", "Command history is recorded locally. Use the existing history command to review recent operations.", "show history");
            case SYSTEM -> systemPanel;
        };
        workspace.setCenter(page);
        BorderPane.setMargin(page, new Insets(12, 32, 16, 32));
        navigation.forEach((key, button) -> {
            button.getStyleClass().remove("navigation-active");
            if (key == destination) button.getStyleClass().add("navigation-active");
        });
        if (destination == JadeShellModel.Destination.PROJECT && !projectPanel.isBusy()) projectPanel.refresh();
        if (destination == JadeShellModel.Destination.SYSTEM && !systemPanel.isBusy()) systemPanel.refresh();
        if (pageFade != null) pageFade.stop();
        page.setOpacity(1);
        if (motion.animationsEnabled()) {
            FadeTransition fade = new FadeTransition(Duration.millis(MotionPreferences.PAGE_FADE_MILLIS), page);
            fade.setFromValue(0.65);
            fade.setToValue(1);
            pageFade = fade;
            fade.play();
        }
    }

    private static Node destinationGlyph(JadeShellModel.Destination destination) {
        javafx.scene.shape.SVGPath glyph = new javafx.scene.shape.SVGPath();
        glyph.setContent(switch (destination) {
            case HOME -> "M2 8 L8 2 L14 8 M4 7 V14 H12 V7";
            case FILES -> "M2 4 H6 L8 6 H14 V14 H2 Z";
            case PROJECT -> "M2 3 H6 V7 H2 Z M10 9 H14 V13 H10 Z M4 7 V11 H10";
            case ACTIVITY -> "M1 8 H4 L6 3 L9 13 L11 8 H15";
            case SYSTEM -> "M3 3 H13 V13 H3 Z M6 6 H10 V10 H6 Z";
        });
        glyph.getStyleClass().add("destination-glyph");
        return glyph;
    }

    private Button suggestionButton(JadeShellModel.Suggestion suggestion) {
        return commandButton(suggestion.label(), suggestion.command());
    }

    private Button commandButton(String label, String command) {
        Button button = new Button(label);
        button.getStyleClass().add("suggestion-chip");
        button.setTooltip(new Tooltip(command));
        button.setOnAction(event -> {
            navigate(JadeShellModel.Destination.HOME);
            assistant.submitCommand(command);
        });
        return button;
    }

    private void updateHome() {
        boolean focused = resultAvailable || model.appState() != VoiceState.IDLE;
        prompt.setVisible(!focused);
        prompt.setManaged(!focused);
        suggestions.setVisible(!focused);
        suggestions.setManaged(!focused);
        assistant.setVisible(focused);
        assistant.setManaged(focused);
        if (focused && core.getParent() != coreIdentity) {
            coreStage.getChildren().clear();
            coreIdentity.getChildren().setAll(core, stateLabel);
            coreStage.getChildren().setAll(coreIdentity);
        } else if (!focused && core.getParent() == coreIdentity) {
            coreIdentity.getChildren().clear();
            coreStage.getChildren().setAll(core, stateLabel);
        }
        coreStage.setAlignment(focused ? Pos.CENTER_LEFT : Pos.CENTER);
        core.getStyleClass().remove("core-compact");
        if (focused) core.getStyleClass().add("core-compact");
        double size = focused ? 44 : 260;
        core.setMinSize(size, size);
        core.setPrefSize(size, size);
        core.setMaxSize(size, size);
        coreStage.setMinHeight(Region.USE_PREF_SIZE);
        coreStage.setMaxHeight(Region.USE_PREF_SIZE);
        homeLayout.setMaxHeight(Region.USE_PREF_SIZE);
        homeLayout.getStyleClass().remove("home-focused");
        if (focused) homeLayout.getStyleClass().add("home-focused");
    }

    private Node filesSummary() {
        Label heading = new Label("Files");
        heading.getStyleClass().add("result-heading");
        Label description = new Label("Search and document intelligence, within your chosen scope.");
        description.getStyleClass().add("result-metadata");
        description.setWrapText(true);
        Label caption = new Label("CURRENT SCOPE");
        caption.getStyleClass().add("result-eyebrow");
        Label path = new Label(assistant.searchScope());
        path.getStyleClass().add("scope-path");
        path.setWrapText(true);
        VBox scope = new VBox(10, caption, path);
        scope.getStyleClass().add("file-scope");
        Label note = new Label("File intelligence is available through JADE commands. Choose a search to see its results.");
        note.getStyleClass().add("result-metadata");
        note.setWrapText(true);
        HBox actions = new HBox(10, suggestionButton(JadeShellModel.Suggestion.FILES),
                commandButton("Files from yesterday", "find files from yesterday"));
        HBox title = new HBox(12, destinationGlyph(JadeShellModel.Destination.FILES), heading);
        title.setAlignment(Pos.CENTER_LEFT);
        VBox page = new VBox(20, title, description, scope, note, actions, commandDock);
        page.getStyleClass().add("files-stage");
        page.setMaxWidth(780);
        page.setMaxHeight(Region.USE_PREF_SIZE);
        return new StackPane(page);
    }

    private Node placeholder(String title, String detail, String command) {
        Label heading = new Label(title);
        heading.getStyleClass().add("result-heading");
        Label body = new Label(detail);
        body.getStyleClass().add("result-metadata");
        body.setWrapText(true);
        VBox page = new VBox(20, heading, body, commandButton("Show history", command));
        page.getStyleClass().add("placeholder-stage");
        return page;
    }

    private void renderStatus() {
        statusChips.getChildren().setAll(model.statusChips().stream().map(chip -> {
            Label label = new Label(chip.name() + " · " + chip.value());
            label.setMaxWidth(220);
            label.setMinWidth(0);
            label.setTooltip(new Tooltip(chip.name() + " · " + chip.value()));
            label.getStyleClass().addAll("status-chip", "status-" + chip.tone());
            return label;
        }).toList());
    }

    @Override
    public void close() {
        if (pageFade != null) pageFade.stop();
        core.shutdown();
    }
}
