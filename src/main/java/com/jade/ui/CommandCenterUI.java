package com.jade.ui;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.util.Objects;

/**
 * The command center shell: keeps the existing assistant view as the first
 * tab and adds the Project and System/Activity views beside it. This is a
 * layout container only — every panel still talks to the same gateway and no
 * new data path is introduced.
 */
public final class CommandCenterUI extends TabPane {

    public CommandCenterUI(CommandUI assistant, ProjectCenterPanel projectPanel, SystemActivityPanel systemPanel) {
        Objects.requireNonNull(assistant, "assistant");
        Objects.requireNonNull(projectPanel, "projectPanel");
        Objects.requireNonNull(systemPanel, "systemPanel");
        getStyleClass().add("command-center");

        Tab assistantTab = new Tab("Assistant", assistant);
        assistantTab.setClosable(false);

        Tab projectTab = new Tab("Project", projectPanel);
        projectTab.setClosable(false);

        Tab systemTab = new Tab("System / Activity", systemPanel);
        systemTab.setClosable(false);

        getTabs().addAll(assistantTab, projectTab, systemTab);

        // Lazily refresh side panels the first time they are shown, so an
        // idle session performs no extra gateway work.
        projectTab.setOnSelectionChanged(ignored -> {
            if (projectTab.isSelected() && !projectPanel.isBusy()) {
                projectPanel.refresh();
            }
        });
        systemTab.setOnSelectionChanged(ignored -> {
            if (systemTab.isSelected() && !systemPanel.isBusy()) {
                systemPanel.refresh();
            }
        });
        setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    }
}
