package com.jade.app;

import com.jade.api.CommandGateway;
import com.jade.api.ConfirmationHandler;
import com.jade.api.HistoryRepository;
import com.jade.api.PendingConfirmation;
import com.jade.core.DefaultCommandGateway;
import com.jade.services.audio.AudioCaptureService;
import com.jade.services.audio.GreetingService;
import com.jade.services.audio.MacSaySpeechSynthesisService;
import com.jade.services.audio.MicrophoneCaptureService;
import com.jade.services.audio.SpeechSynthesisService;
import com.jade.services.audio.SpeakerProfileStore;
import com.jade.services.audio.TranscriptWakePhraseDetector;
import com.jade.services.audio.VoiceCommandController;
import com.jade.services.audio.VoskSpeechRecognitionService;
import com.jade.services.audio.VoskSpeakerIdentificationService;
import com.jade.services.audio.VoskSupport;
import com.jade.services.app.DesktopAppService;
import com.jade.services.files.ScopedFileMutationService;
import com.jade.services.history.SqliteHistoryRepository;
import com.jade.services.search.FileSystemFileSearchService;
import com.jade.services.system.OshiSystemInfoService;
import com.jade.ui.CommandUI;
import com.jade.ui.CommandCenterUI;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class JadeApplication extends Application {
    private ExecutorService executor;
    private HistoryRepository historyRepository;
    private CommandGateway gateway;
    private CommandUI commandUI;
    private VoskSupport voskSupport;
    private AudioCaptureService audioCapture;
    private SpeechSynthesisService speechSynthesis;
    private VoiceCommandController voiceController;
    private com.jade.ui.ProjectCenterPanel projectCenterPanel;
    private com.jade.ui.SystemActivityPanel systemActivityPanel;
    private CommandCenterUI center;

    @Override
    public void start(Stage stage) {
        stage.setTitle("JADE");
        AppConfiguration configuration = AppConfiguration.load();
        if (configuration.searchRoots().isEmpty()) {
            showSetup(stage, configuration);
        } else {
            startAssistant(stage, configuration);
        }
    }

    private void showSetup(Stage stage, AppConfiguration configuration) {
        Label title = new Label("Choose a folder JADE may search and mutate");
        Label detail = new Label(
                "Only folders configured here are scanned, and JADE moves or copies files only inside "
                        + "this scope. JADE never deletes or overwrites files.");
        detail.setWrapText(true);
        if (!configuration.warnings().isEmpty()) {
            detail.setText(String.join("\n", configuration.warnings()) + "\n\n" + detail.getText());
        }
        TextField alias = new TextField(configuration.calculatorAlias());
        alias.setPromptText("Optional custom alias for Calculator");
        Button choose = new Button("Choose scope folder…");
        choose.setOnAction(ignored -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose JADE scope folder");
            File selected = chooser.showDialog(stage);
            if (selected != null) {
                startAssistant(stage, configuration.withSelection(selected.toPath(), alias.getText()));
            }
        });
        VBox setup = new VBox(12, title, detail, alias, choose);
        setup.setPadding(new Insets(24));
        Scene scene = new Scene(setup, 560, 280);
        applyStyles(scene);
        stage.setScene(scene);
        stage.show();
    }

    private void startAssistant(Stage stage, AppConfiguration configuration) {
        try {
            executor = newExecutor();
            SqliteHistoryRepository history =
                    new SqliteHistoryRepository(configuration.dataDirectory().resolve("history.db"));
            historyRepository = history;
            gateway = new DefaultCommandGateway(
                    new DesktopAppService(configuration.configuredApps()),
                    new FileSystemFileSearchService(configuration.searchRoots()),
                    new OshiSystemInfoService(),
                    history,
                    executor,
                    new ScopedFileMutationService(configuration.searchRoots(), history),
                    history,
                    configuration.searchRoots(),
                    new DialogConfirmationHandler(),
                    Clock.systemUTC(),
                    new com.jade.services.files.FileSystemFileService(),
                    new com.jade.services.files.ContentSearchService(),
                    new DesktopFileOpener(),
                    new com.jade.services.project.FileSystemProjectService(),
                    new com.jade.services.project.MavenProjectProcessRunner());
            commandUI = new CommandUI(gateway);
            commandUI.setSearchScope(configuration.scopeDescription());
            com.jade.ui.ExecutionBrainPanel brain = new com.jade.ui.ExecutionBrainPanel();
            commandUI.setExecutionBrain(brain);
            com.jade.ui.VoicePanel voicePanel = buildVoiceStack(configuration);
            commandUI.setVoicePanel(voicePanel);
            com.jade.ui.ProjectCenterPanel projectPanel = new com.jade.ui.ProjectCenterPanel(gateway, message -> { });
            com.jade.ui.SystemActivityPanel systemPanel = new com.jade.ui.SystemActivityPanel(gateway);
            systemPanel.setCapabilities("Search scope: " + configuration.scopeDescription());
            this.projectCenterPanel = projectPanel;
            this.systemActivityPanel = systemPanel;
            center = new CommandCenterUI(commandUI, projectPanel, systemPanel);
            center.setVoiceAvailable(voiceController != null);
            voicePanel.onExternalState(center::onVoiceState);
            Scene scene = new Scene(center, 1440, 900);
            applyStyles(scene);
            stage.setMinWidth(1000);
            stage.setMinHeight(680);
            stage.setScene(scene);
            stage.show();
        } catch (RuntimeException e) {
            closeRuntime();
            showStartupFailure(stage, e);
        }
    }

    /**
     * Opens one concrete scope file with the host platform's default
     * handler. Deliberately minimal: the path arrives fully resolved from
     * the gateway and is never reinterpreted here.
     */
    private static final class DesktopFileOpener implements com.jade.api.FileOpener {
        @Override
        public void open(java.nio.file.Path path, com.jade.api.CancellationToken cancellation)
                throws com.jade.api.ServiceException {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            java.util.List<String> command;
            if (os.contains("mac")) {
                command = java.util.List.of("/usr/bin/open", path.toString());
            } else if (os.contains("linux")) {
                command = java.util.List.of("xdg-open", path.toString());
            } else {
                throw new com.jade.api.ServiceException(new com.jade.api.StructuredError(
                        com.jade.api.ErrorCode.UNSUPPORTED_PLATFORM,
                        "Opening files is not supported on this platform",
                        java.util.Optional.empty()));
            }
            try {
                new ProcessBuilder(command).start();
            } catch (java.io.IOException e) {
                throw new com.jade.api.ServiceException(new com.jade.api.StructuredError(
                        com.jade.api.ErrorCode.IO_FAILURE,
                        "Could not open " + path.getFileName(),
                        java.util.Optional.ofNullable(e.getMessage())));
            }
        }
    }

    /**
     * Bridges the worker-thread confirmation seam to a modal JavaFX dialog.
     * Every mutation command asks exactly once for its whole planned batch;
     * the dialog lists each affected file before anything changes on disk.
     */
    private static final class DialogConfirmationHandler implements ConfirmationHandler {
        @Override
        public Decision confirm(PendingConfirmation pending) {
            CompletableFuture<Decision> answer = new CompletableFuture<>();
            Platform.runLater(() -> answer.complete(showDialog(pending)));
            try {
                return answer.get(30, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                return Decision.DENIED;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Decision.DENIED;
            } catch (ExecutionException e) {
                return Decision.DENIED;
            }
        }

        private Decision showDialog(PendingConfirmation pending) {
            Dialog<Decision> dialog = new Dialog<>();
            dialog.setTitle("Confirm before JADE changes anything");
            dialog.setHeaderText(pending.originalCommand());
            Label kind = new Label("Action: " + pending.kind() + "   ·   Risk: " + pending.riskLevel());
            Label destination = new Label("Destination: " + pending.destinationDescription());
            destination.setWrapText(true);
            ListView<String> files = new ListView<>();
            files.setPrefHeight(220);
            for (PendingConfirmation.PlannedFile file : pending.plannedFiles()) {
                files.getItems().add(file.source() + "  →  " + file.target());
            }
            ButtonType confirm = new ButtonType("Confirm", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().setContent(new VBox(8, kind, destination, files));
            dialog.getDialogPane().getButtonTypes().setAll(confirm, cancel);
            dialog.setResultConverter(button -> button == confirm ? Decision.CONFIRMED : Decision.DENIED);
            return dialog.showAndWait().orElse(Decision.DENIED);
        }
    }

    /**
     * Builds the optional voice stack behind the audio seams. When the STT
     * model is not installed (or TTS/mic setup fails) voice is visibly
     * unavailable and the rest of JADE is unaffected.
     */
    private com.jade.ui.VoicePanel buildVoiceStack(AppConfiguration configuration) {
        com.jade.ui.VoicePanel panel = new com.jade.ui.VoicePanel();
        java.nio.file.Path modelDir = resolvePathSetting(
                "jade.audio.model.dir", "JADE_AUDIO_MODEL_DIR",
                "jarvis.audio.model.dir", "JARVIS_AUDIO_MODEL_DIR");
        java.nio.file.Path spkModelDir = resolvePathSetting(
                "jade.audio.spk.model.dir", "JADE_AUDIO_SPK_MODEL_DIR",
                "jarvis.audio.spk.model.dir", "JARVIS_AUDIO_SPK_MODEL_DIR");
        try {
            if (modelDir == null || !java.nio.file.Files.isDirectory(modelDir)) {
                panel.stateConsumer().accept(null); // panel renders its idle hint
                return panel;
            }
            voskSupport = VoskSupport.load(modelDir, spkModelDir);
            VoskSpeechRecognitionService recognition = new VoskSpeechRecognitionService(voskSupport);
            audioCapture = new MicrophoneCaptureService();
            speechSynthesis = MacSaySpeechSynthesisService.createForCurrentPlatform();
            VoskSpeakerIdentificationService speakerId = null;
            if (spkModelDir != null && java.nio.file.Files.isDirectory(spkModelDir)) {
                SpeakerProfileStore profiles = new SpeakerProfileStore(
                        configuration.dataDirectory().resolve("speakers"));
                speakerId = new VoskSpeakerIdentificationService(voskSupport, profiles, 0.60);
            }
            voiceController = new VoiceCommandController(
                    audioCapture,
                    recognition,
                    new TranscriptWakePhraseDetector(),
                    speechSynthesis,
                    speakerId,
                    new GreetingService(Clock.systemDefaultZone()),
                    gateway,
                    panel.stateConsumer(),
                    panel.transcriptConsumer(),
                    panel.spokenConsumer(),
                    panel.identityConsumer());
            panel.onStart(voiceController::beginSession);
        } catch (RuntimeException | com.jade.services.audio.AudioException e) {
            panel.stateConsumer().accept(null);
            panel.transcriptConsumer().accept("Voice unavailable: " + e.getMessage());
            closeVoiceStack();
        }
        return panel;
    }

    /**
     * Canonical JADE settings win; the legacy JARVIS keys only fill in when
     * no canonical value exists. Order mirrors AppConfiguration.setting:
     * canonical property, canonical environment, legacy property, legacy
     * environment.
     */
    private static java.nio.file.Path resolvePathSetting(String canonicalProperty, String canonicalEnvironment,
                                                         String legacyProperty, String legacyEnvironment) {
        String value = System.getProperty(canonicalProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(canonicalEnvironment);
        }
        if (value == null || value.isBlank()) {
            value = System.getProperty(legacyProperty);
        }
        if (value == null || value.isBlank()) {
            value = System.getenv(legacyEnvironment);
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        return java.nio.file.Path.of(value.strip());
    }

    private void closeVoiceStack() {
        if (voiceController != null) {
            voiceController.close();
            voiceController = null;
        }
        if (audioCapture != null) {
            audioCapture.close();
            audioCapture = null;
        }
        if (speechSynthesis != null) {
            speechSynthesis.close();
            speechSynthesis = null;
        }
        if (voskSupport != null) {
            voskSupport.close();
            voskSupport = null;
        }
    }

    private static ExecutorService newExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16),
                task -> {
                    Thread thread = new Thread(task, "jade-worker-" + sequence.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static void applyStyles(Scene scene) {
        scene.getStylesheets().add(java.util.Objects.requireNonNull(
                JadeApplication.class.getResource("/ui/style.css"), "UI stylesheet missing").toExternalForm());
    }

    private static void showStartupFailure(Stage stage, RuntimeException failure) {
        Label message = new Label("JADE could not start:\n" + failure.getMessage()
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
        if (center != null) {
            center.close();
            center = null;
        }
        closeVoiceStack();
        if (commandUI != null) {
            commandUI.close();
            commandUI = null;
        }
        if (projectCenterPanel != null) {
            projectCenterPanel.close();
            projectCenterPanel = null;
        }
        if (systemActivityPanel != null) {
            systemActivityPanel.close();
            systemActivityPanel = null;
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
                System.err.println("Could not close JADE history: " + e.getMessage());
            }
            historyRepository = null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
