package com.jarvis.app;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public final class JarvisApplication extends Application {
    @Override
    public void start(Stage stage) {
        stage.setTitle("JARVIS");
        stage.setScene(new Scene(new StackPane(
                new Label("Stage A: command execution is not available yet.")), 520, 180));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
