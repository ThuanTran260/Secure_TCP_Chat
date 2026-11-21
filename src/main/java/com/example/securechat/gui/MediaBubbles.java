package com.example.securechat.gui;

import com.example.securechat.client.ChatClient;
import com.example.securechat.model.ChatMessage;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.media.*;
import javafx.stage.*;
import javafx.util.Duration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Owns players so a conversation switch or window close releases native resources. */
public final class MediaBubbles {
    private final ChatClient client;
    private final MediaCache cache;
    private final Executor work;
    private final Window owner;
    private final Consumer<Throwable> errors;
    private final List<MediaPlayer> players = new ArrayList<>();
    private final List<Stage> previews = new ArrayList<>();
    private boolean disposed;

    public MediaBubbles(ChatClient client, MediaCache cache, Executor work, Window owner, Consumer<Throwable> errors) {
        this.client = client; this.cache = cache; this.work = work; this.owner = owner; this.errors = errors;
    }

    public Node create(ChatMessage message) {
        VBox box = new VBox(8); box.setMaxWidth(340);
        if ("file".equals(message.getKind())) { fileCard(box, message, "Tải về"); return box; }
        Label loading = new Label("Đang tải " + name(message) + "…"); loading.getStyleClass().add("muted");
        box.getChildren().add(loading);
        cache.get(client, message.getFileId(), message.getFileName()).whenComplete((path, failure) -> Platform.runLater(() -> {
            if (disposed) return;
            box.getChildren().clear();
            if (failure != null) { box.getChildren().add(new Label("Không tải được tệp này")); fileCard(box, message, "Thử tải lại"); return; }
            switch (message.getKind()) {
                case "image" -> image(box, message, path);
                case "video", "audio" -> player(box, message, path);
                default -> fileCard(box, message, "Tải về");
            }
        }));
        return box;
    }

    private void image(VBox box, ChatMessage message, Path path) {
        Image image = new Image(path.toUri().toString(), 0, 0, true, true, true);
        ImageView view = new ImageView(image); view.setFitWidth(240); view.setFitHeight(240); view.setPreserveRatio(true); view.setSmooth(true);
        view.setCursor(Cursor.HAND); view.setOnMouseClicked(event -> {
            Stage preview = new Stage(); preview.initOwner(owner); preview.setTitle(name(message));
            ImageView full = new ImageView(image); full.setPreserveRatio(true);
            StackPane pane = new StackPane(full); pane.setPadding(new Insets(16)); pane.setStyle("-fx-background-color: #17202e;");
            full.fitWidthProperty().bind(pane.widthProperty().subtract(32)); full.fitHeightProperty().bind(pane.heightProperty().subtract(32));
            preview.setScene(new Scene(pane, 800, 600)); previews.add(preview);
            preview.setOnHidden(hidden -> previews.remove(preview)); preview.show();
        });
        box.getChildren().add(view);
        image.errorProperty().addListener((observable, oldValue, failed) -> {
            if (failed) { box.getChildren().clear(); box.getChildren().add(new Label("Không hiển thị được ảnh này")); fileCard(box, message, "Lưu về máy"); }
        });
        if (image.isError()) { box.getChildren().clear(); box.getChildren().add(new Label("Không hiển thị được ảnh này")); }
        Button save = new Button("Lưu về máy"); save.getStyleClass().add("media-action"); save.setOnAction(event -> save(message)); box.getChildren().add(save);
    }

    private void player(VBox box, ChatMessage message, Path path) {
        boolean video = "video".equals(message.getKind());
        try {
            Media source = new Media(path.toUri().toString()); MediaPlayer player = new MediaPlayer(source); players.add(player);
            if (video) { MediaView view = new MediaView(player); view.setFitWidth(320); view.setFitHeight(180); view.setPreserveRatio(true); box.getChildren().add(view); }
            else { Label title = new Label("Tin nhắn thoại"); title.getStyleClass().add("attachment-title"); box.getChildren().add(title); }
            Button play = new Button("▶"); play.getStyleClass().add("media-play");
            Label duration = new Label("0:00"); duration.getStyleClass().add("muted");
            Slider seek = new Slider(0, 1, 0); seek.setPrefWidth(video ? 200 : 160); HBox.setHgrow(seek, Priority.ALWAYS);
            play.setOnAction(event -> {
                if (player.getStatus() == MediaPlayer.Status.PLAYING) player.pause();
                else { if (player.getCurrentTime().greaterThanOrEqualTo(player.getTotalDuration())) player.seek(Duration.ZERO); player.play(); }
            });
            player.statusProperty().addListener((observable, oldValue, value) -> play.setText(value == MediaPlayer.Status.PLAYING ? "⏸" : "▶"));
            player.setOnReady(() -> { seek.setMax(Math.max(1, player.getTotalDuration().toSeconds())); duration.setText(format(player.getTotalDuration())); });
            player.currentTimeProperty().addListener((observable, oldValue, current) -> {
                if (!seek.isValueChanging()) seek.setValue(current.toSeconds());
                duration.setText(format(current) + " / " + format(player.getTotalDuration()));
            });
            seek.setOnMouseReleased(event -> player.seek(Duration.seconds(seek.getValue())));
            seek.valueChangingProperty().addListener((observable, previous, changing) -> { if (!changing) player.seek(Duration.seconds(seek.getValue())); });
            HBox controls = new HBox(8, play, seek, duration); controls.setAlignment(Pos.CENTER_LEFT); box.getChildren().add(controls);
            Runnable failed = () -> { player.dispose(); players.remove(player); box.getChildren().clear(); box.getChildren().add(new Label(video ? "Không phát được video này" : "Không phát được âm thanh này")); fileCard(box, message, "Lưu về máy"); };
            source.setOnError(failed); player.setOnError(failed);
            if (source.getError() != null || player.getError() != null) failed.run();
        } catch (MediaException e) {
            box.getChildren().clear(); box.getChildren().add(new Label(video ? "Không phát được video này" : "Không phát được âm thanh này")); fileCard(box, message, "Lưu về máy");
        }
    }

    private void fileCard(VBox box, ChatMessage message, String action) {
        Label title = new Label("📄  " + name(message)); title.setWrapText(true); title.setMaxWidth(310); title.getStyleClass().add("attachment-title");
        long size = message.getSize() == null ? 0 : message.getSize();
        Label length = new Label(String.format(Locale.ROOT, "%.1f %s", size >= 1024 * 1024 ? size / (1024d * 1024) : size / 1024d, size >= 1024 * 1024 ? "MB" : "KB")); length.getStyleClass().add("muted");
        Button download = new Button(action); download.getStyleClass().add("media-action"); download.setOnAction(event -> save(message));
        box.getChildren().addAll(title, length, download);
    }

    private void save(ChatMessage message) {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Lưu tệp về máy");
        String safe = name(message).replace('\\', '/'); safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[<>:\"|?*\\p{Cntrl}]", "_");
        chooser.setInitialFileName(safe.isBlank() ? "attachment" : safe);
        java.io.File chosen = chooser.showSaveDialog(owner); if (chosen == null) return;
        cache.get(client, message.getFileId(), message.getFileName()).thenAcceptAsync(path -> {
            try { Files.copy(path, chosen.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }, work).exceptionally(error -> { Platform.runLater(() -> { if (!disposed) errors.accept(error); }); return null; });
    }
    private static String name(ChatMessage message) { return message.getFileName() == null ? "Tệp đính kèm" : message.getFileName(); }
    private static String format(Duration duration) { long seconds = duration == null || duration.isUnknown() || duration.isIndefinite() ? 0 : Math.max(0, (long) duration.toSeconds()); return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60); }
    public void dispose() { disposed = true; players.forEach(MediaPlayer::dispose); players.clear(); List.copyOf(previews).forEach(Stage::close); previews.clear(); }
}
