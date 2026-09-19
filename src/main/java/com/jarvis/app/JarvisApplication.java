package com.jarvis.app;

import com.jarvis.api.CommandGateway;
import com.jarvis.api.ConfirmationHandler;
import com.jarvis.api.HistoryRepository;
import com.jarvis.api.PendingConfirmation;
import com.jarvis.core.DefaultCommandGateway;
import com.jarvis.services.audio.AudioCaptureService;
import com.jarvis.services.audio.GreetingService;
import com.jarvis.services.audio.MacSaySpeechSynthesisService;
import com.jarvis.services.audio.MicrophoneCaptureService;
import com.jarvis.services.audio.SpeechSynthesisService;
import com.jarvis.services.audio.SpeakerProfileStore;
import com.jarvis.services.audio.TranscriptWakePhraseDetector;
import com.jarvis.services.audio.VoiceCommandController;
import com.jarvis.services.audio.VoskSpeechRecognitionService;
import com.jarvis.services.audio.VoskSpeakerIdentificationService;
import com.jarvis.services.audio.VoskSupport;
import com.jarvis.services.app.DesktopAppService;
import com.jarvis.services.files.ScopedFileMutationService;
import com.jarvis.services.history.SqliteHistoryRepository;
import com.jarvis.services.search.FileSystemFileSearchService;
import com.jarvis.services.system.OshiSystemInfoService;
import com.jarvis.ui.CommandUI;
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

public final class JarvisApplication extends Application {
    private ExecutorService executor;
    private HistoryRepository historyRepository;
    private CommandGateway gateway;
    private CommandUI commandUI;
    private VoskSupport voskSupport;
    private AudioCaptureService audioCapture;
    private SpeechSynthesisService speechSynthesis;
    private VoiceCommandController voiceController;

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
        Label title = new Label("Choose a folder JARVIS may search and mutate");
        Label detail = new Label(
                "Only folders configured here are scanned, and JARVIS moves or copies files only inside "
                        + "this scope. JARVIS never deletes or overwrites files.");
        detail.setWrapText(true);
        if (!configuration.warnings().isEmpty()) {
            detail.setText(String.join("\n", configuration.warnings()) + "\n\n" + detail.getText());
        }
        TextField alias = new TextField(configuration.calculatorAlias());
        alias.setPromptText("Optional custom alias for Calculator");
        Button choose = new Button("Choose scope folder…");
        choose.setOnAction(ignored -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose JARVIS scope folder");
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
                    new com.jarvis.services.files.FileSystemFileService(),
                    new com.jarvis.services.files.ContentSearchService());
            commandUI = new CommandUI(gateway);
            commandUI.setSearchScope(configuration.scopeDescription());
            commandUI.setVoicePanel(buildVoiceStack(configuration));
            Scene scene = new Scene(commandUI, 900, 680);
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
            dialog.setTitle("Confirm before JARVIS changes anything");
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
     * unavailable and the rest of JARVIS is unaffected.
     */
    private com.jarvis.ui.VoicePanel buildVoiceStack(AppConfiguration configuration) {
        com.jarvis.ui.VoicePanel panel = new com.jarvis.ui.VoicePanel();
        java.nio.file.Path modelDir = resolvePathSetting("jarvis.audio.model.dir", "JARVIS_AUDIO_MODEL_DIR");
        java.nio.file.Path spkModelDir = resolvePathSetting("jarvis.audio.spk.model.dir", "JARVIS_AUDIO_SPK_MODEL_DIR");
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
        } catch (RuntimeException | com.jarvis.services.audio.AudioException e) {
            panel.stateConsumer().accept(null);
            panel.transcriptConsumer().accept("Voice unavailable: " + e.getMessage());
            closeVoiceStack();
        }
        return panel;
    }

    private static java.nio.file.Path resolvePathSetting(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
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
        closeVoiceStack();
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
