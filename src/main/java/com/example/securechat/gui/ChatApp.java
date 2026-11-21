package com.example.securechat.gui;

import com.example.securechat.client.ChatClient;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.util.concurrent.*;

public final class ChatApp extends Application {
    private final ExecutorService work = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "chat-ui-worker"); thread.setDaemon(true); return thread;
    });
    private ChatClient client;
    private MainView mainView;
    private LoginView loginView;
    private boolean stopped;

    @Override public void start(Stage stage) {
        stage.setTitle("Secure Chat");
        loginView = new LoginView(work, (connected, username) -> {
            if (stopped) { closeInBackground(connected); return; }
            client = connected;
            mainView = new MainView(connected, username, work);
            show(stage, mainView, 1160, 780);
            stage.setMinWidth(840); stage.setMinHeight(600);
            connected.startReceiving(message -> Platform.runLater(() -> {
                if (!stopped) mainView.onMessage(message);
            }), reason -> Platform.runLater(() -> {
                if (!stopped) mainView.connectionLost();
            }));
        });
        show(stage, loginView, 1060, 720);
        stage.setMinWidth(780); stage.setMinHeight(640);
        stage.show();
    }

    private void show(Stage stage, javafx.scene.Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(ChatApp.class.getResource("zalo.css").toExternalForm());
        stage.setScene(scene);
    }

    @Override public void stop() {
        stopped = true;
        if (loginView != null) loginView.dispose();
        if (mainView != null) mainView.dispose();
        if (client != null) {
            closeInBackground(client);
        }
        work.shutdownNow();
    }

    private static void closeInBackground(ChatClient client) {
        Thread cleanup = new Thread(client::close, "chat-ui-close");
        cleanup.setDaemon(true); cleanup.start();
    }

    public static void main(String[] args) { launch(args); }
}
