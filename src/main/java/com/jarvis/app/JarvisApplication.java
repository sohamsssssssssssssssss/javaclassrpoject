package com.jarvis.app;

import com.jarvis.api.CommandGateway;
import com.jarvis.api.HistoryRepository;
import com.jarvis.core.DefaultCommandGateway;
import com.jarvis.services.app.DesktopAppService;
import com.jarvis.services.history.SqliteHistoryRepository;
import com.jarvis.services.search.FileSystemFileSearchService;
import com.jarvis.services.system.OshiSystemInfoService;
import com.jarvis.ui.CommandUI;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class JarvisApplication extends Application {
    private ExecutorService executor;
    private HistoryRepository historyRepository;
    private CommandGateway gateway;
    private CommandUI commandUI;

    @Override
    public void start(Stage stage) {
        stage.setTitle("JARVIS");
        AppConfiguration configuration = AppConfiguration.load();
        if (configuration.searchRoots().isEmpty()) {
            showSetup(stage, configuration);
        } else {
            startAssistant(stage, configuration);
        }
    }

    private void showSetup(Stage stage, AppConfiguration configuration) {
        Label title = new Label("Choose a folder JARVIS may search");
        Label detail = new Label("Only filenames under this folder are scanned. JARVIS never deletes or overwrites files.");
        detail.setWrapText(true);
        if (!configuration.warnings().isEmpty()) {
            detail.setText(String.join("\n", configuration.warnings()) + "\n\n" + detail.getText());
        }
        TextField alias = new TextField(configuration.calculatorAlias());
        alias.setPromptText("Optional custom alias for Calculator");
        Button choose = new Button("Choose search folder…");
        choose.setOnAction(ignored -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose JARVIS search folder");
            File selected = chooser.showDialog(stage);
            if (selected != null) {
                startAssistant(stage, configuration.withSelection(selected.toPath(), alias.getText()));
            }
        });
        VBox setup = new VBox(12, title, detail, alias, choose);
        setup.setPadding(new Insets(24));
        Scene scene = new Scene(setup, 560, 260);
        applyStyles(scene);
        stage.setScene(scene);
        stage.show();
    }

    private void startAssistant(Stage stage, AppConfiguration configuration) {
        try {
            executor = newExecutor();
            historyRepository = new SqliteHistoryRepository(configuration.dataDirectory().resolve("history.db"));
            gateway = new DefaultCommandGateway(
                    new DesktopAppService(configuration.configuredApps()),
                    new FileSystemFileSearchService(configuration.searchRoots()),
                    new OshiSystemInfoService(),
                    historyRepository,
                    executor);
            commandUI = new CommandUI(gateway);
            commandUI.setSearchScope(configuration.scopeDescription());
            Scene scene = new Scene(commandUI, 820, 620);
            applyStyles(scene);
            stage.setMinWidth(640);
            stage.setMinHeight(440);
            stage.setScene(scene);
            stage.show();
        } catch (RuntimeException e) {
            closeRuntime();
            showStartupFailure(stage, e);
        }
    }

    private static ExecutorService newExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16),
                task -> {
                    Thread thread = new Thread(task, "jarvis-worker-" + sequence.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static void applyStyles(Scene scene) {
        scene.getStylesheets().add(java.util.Objects.requireNonNull(
                JarvisApplication.class.getResource("/ui/style.css"), "UI stylesheet missing").toExternalForm());
    }

    private static void showStartupFailure(Stage stage, RuntimeException failure) {
        Label message = new Label("JARVIS could not start:\n" + failure.getMessage()
                + "\n\nCheck the configured folder and data-directory permissions, then restart.");
        message.setWrapText(true);
        VBox root = new VBox(message);
        root.setPadding(new Insets(24));
        stage.setScene(new Scene(root, 580, 240));
        stage.show();
    }

    @Override
    public void stop() {
        closeRuntime();
    }

    private void closeRuntime() {
        if (commandUI != null) {
            commandUI.close();
            commandUI = null;
        }
        if (gateway != null) {
            gateway.close();
            gateway = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        if (historyRepository != null) {
            try {
                historyRepository.close();
            } catch (Exception e) {
                System.err.println("Could not close JARVIS history: " + e.getMessage());
            }
            historyRepository = null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
