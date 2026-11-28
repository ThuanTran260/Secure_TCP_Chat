package com.example.securechat.gui;

import com.example.securechat.client.ChatClient;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.ConversationIds;
import com.example.securechat.model.MessageType;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainView extends BorderPane {
    private final ChatClient client;
    private final String me;
    private final Executor work;
    private final ExecutorService commands = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-ui-send"); thread.setDaemon(true); return thread;
    });
    private final ChatState state;
    private final ListView<ChatState.Conversation> conversations = new ListView<>();
    private final VBox timeline = new VBox(10);
    private final ScrollPane scroll = new ScrollPane(timeline);
    private final Label title = new Label("Phòng chung");
    private final Label subtitle = new Label();
    private final Label banner = new Label();
    private final TextField composer = new TextField();
    private final Button send = new Button("Gửi");
    private final Button attach = new Button("＋");
    private final Button mic = new Button("●");
    private final Label recordClock = new Label();
    private final VoiceRecorder recorder = new VoiceRecorder();
    private final Set<String> requestedHistory = new HashSet<>();
    private final Map<ChatMessage, Node> bubbles = new IdentityHashMap<>();
    private final Map<String, String> pendingUploads = new LinkedHashMap<>();
    private final MediaCache cache;
    private final Timeline recordTimer;
    private MediaBubbles media;
    private String opened = ConversationIds.LOBBY;
    private boolean refreshing;
    private boolean recordingUi;
    private boolean recordingBusy;
    private boolean disconnected;
    private boolean disposed;
    private long recordStarted;
    private String recordConversation;

    public MainView(ChatClient client, String me, Executor work) {
        this.client = client; this.me = me; this.work = work; state = new ChatState(me);
        String cacheName = me.replaceAll("[^\\p{L}\\p{N}_-]", "_");
        cache = new MediaCache(Path.of(System.getProperty("java.io.tmpdir"), "securechat-cache", cacheName.isBlank() ? "user" : cacheName));
        getStyleClass().add("main-root");
        VBox sidebar = new VBox(); sidebar.getStyleClass().add("sidebar"); sidebar.setPrefWidth(320); sidebar.setMinWidth(300); sidebar.setMaxWidth(320);
        Label profileName = new Label(me); profileName.getStyleClass().add("profile-name");
        Label status = new Label("●  Đang hoạt động"); status.getStyleClass().add("profile-status");
        VBox profileText = new VBox(4, profileName, status); HBox.setHgrow(profileText, Priority.ALWAYS);
        Button group = new Button("＋"); group.getStyleClass().add("round-button"); group.setTooltip(new Tooltip("Tạo nhóm")); group.setAccessibleText("Tạo nhóm");
        group.setOnAction(event -> new CreateGroupDialog(getScene().getWindow(), state.conversations().stream()
                .flatMap(conversation -> conversation.members().stream()).toList(), me).showAndWait()
                .ifPresent(result -> run(() -> client.createGroup(result.name(), result.members()))));
        HBox profile = new HBox(10, Avatars.circle(me, 44), profileText, group); profile.setAlignment(Pos.CENTER_LEFT); profile.getStyleClass().add("profile");
        Label section = new Label("TIN NHẮN"); section.getStyleClass().add("sidebar-section");
        conversations.getStyleClass().add("conversation-list"); conversations.setCellFactory(list -> new ConversationCell(state)); VBox.setVgrow(conversations, Priority.ALWAYS);
        conversations.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (!refreshing && selected != null && !selected.id().equals(opened)) open(selected.id());
        });
        Label encrypted = new Label("◈  Kết nối được bảo vệ bằng TLS"); encrypted.getStyleClass().add("sidebar-footer");
        sidebar.getChildren().addAll(profile, section, conversations, encrypted); setLeft(sidebar);
        title.getStyleClass().add("chat-title"); subtitle.getStyleClass().add("muted");
        VBox heading = new VBox(5, title, subtitle); HBox.setHgrow(heading, Priority.ALWAYS);
        Label shield = new Label("TLS"); shield.getStyleClass().add("tls-badge");
        HBox header = new HBox(14, Avatars.circle("Phòng chung", 42), heading, shield); header.setAlignment(Pos.CENTER_LEFT); header.getStyleClass().add("chat-header");
        banner.getStyleClass().add("connection-banner"); banner.setWrapText(true); banner.setMaxWidth(Double.MAX_VALUE); banner.setManaged(false); banner.setVisible(false);
        VBox top = new VBox(header, banner);
        BorderPane conversationPane = new BorderPane(); conversationPane.setTop(top);
        timeline.getStyleClass().add("message-timeline"); timeline.setFillWidth(true);
        scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.getStyleClass().add("message-scroll");
        conversationPane.setCenter(scroll);
        composer.setPromptText("Nhập tin nhắn…"); composer.getStyleClass().add("composer-input"); composer.setOnAction(event -> sendText()); HBox.setHgrow(composer, Priority.ALWAYS);
        send.getStyleClass().add("primary-button"); send.setOnAction(event -> sendText());
        Button emoji = new Button(); emoji.getStyleClass().add("toolbar-button");
        javafx.scene.image.ImageView smile = EmojiImages.view("😊", 20); if (smile != null) emoji.setGraphic(smile); else emoji.setText("😊"); emoji.setTooltip(new Tooltip("Chọn emoji"));
        EmojiPicker picker = new EmojiPicker(composer); emoji.setOnAction(event -> picker.showAbove(emoji));
        attach.getStyleClass().add("toolbar-button"); attach.setTooltip(new Tooltip("Đính kèm")); attach.setAccessibleText("Đính kèm");
        ContextMenu attachments = new ContextMenu();
        for (String label : List.of("Ảnh", "Video", "File")) { MenuItem item = new MenuItem(label); item.setOnAction(event -> chooseAttachment(label)); attachments.getItems().add(item); }
        attach.setOnAction(event -> attachments.show(attach, javafx.geometry.Side.TOP, 0, 0));
        mic.getStyleClass().add("toolbar-button"); mic.setTooltip(new Tooltip("Ghi âm tin nhắn thoại")); mic.setAccessibleText("Ghi âm");
        mic.setOnAction(event -> toggleRecording()); recordClock.getStyleClass().add("record-clock");
        recordClock.setManaged(false); recordClock.setVisible(false);
        HBox input = new HBox(10, emoji, attach, mic, recordClock, composer, send); input.setAlignment(Pos.CENTER_LEFT); input.getStyleClass().add("composer");
        conversationPane.setBottom(input); setCenter(conversationPane);
        state.addListener(this::refresh);
        recordTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            long seconds = Math.min(60, Math.max(0, (System.nanoTime() - recordStarted) / 1_000_000_000));
            recordClock.setText(seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60));
            if (seconds >= 60 || !recorder.isRecording()) stopRecording();
        })); recordTimer.setCycleCount(Animation.INDEFINITE);
        sceneProperty().addListener((observable, previous, scene) -> {
            if (scene != null) { createMedia(); refresh(); requestHistory(opened); }
        });
        refresh();
    }

    public void onMessage(ChatMessage message) {
        if (disposed) return;
        if (message.messageType().orElse(null) == MessageType.ERROR) { showError(new IllegalArgumentException(errorText(message.getContent()))); return; }
        state.apply(message);
    }

    private void open(String id) {
        opened = id;
        if (media != null) media.dispose();
        bubbles.clear(); createMedia(); state.open(id); requestHistory(id); composer.requestFocus();
        Platform.runLater(() -> scroll.setVvalue(1));
    }

    private void createMedia() {
        if (getScene() != null) media = new MediaBubbles(client, cache, work, getScene().getWindow(), this::showError);
    }

    private void requestHistory(String id) {
        if (!requestedHistory.add(id)) return;
        run(() -> { try { client.requestHistory(id); } catch (Exception e) { Platform.runLater(() -> requestedHistory.remove(id)); throw e; } });
    }

    private void refresh() {
        if (disposed) return;
        refreshing = true;
        try {
            List<ChatState.Conversation> available = state.conversations();
            conversations.setItems(FXCollections.observableArrayList(available));
            ChatState.Conversation selected = available.stream().filter(conversation -> conversation.id().equals(opened)).findFirst().orElse(available.get(0));
            conversations.getSelectionModel().select(selected);
            title.setText(selected.title());
            subtitle.setText(selected.group() ? selected.members().size() + " thành viên" : state.isOnline(selected.title()) ? "Đang hoạt động" : "Không hoạt động");
            List<Node> nodes = new ArrayList<>(); LocalDate previous = null;
            boolean atBottom = scroll.getVvalue() >= .96 || timeline.getChildren().isEmpty();
            for (ChatState.Entry entry : state.messages(opened)) {
                Long timestamp = entry.message().getTimestamp();
                LocalDate day = timestamp == null ? LocalDate.now() : Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate();
                if (!day.equals(previous)) { nodes.add(MessageBubbles.daySeparator(day)); previous = day; }
                if (media != null) nodes.add(bubbles.computeIfAbsent(entry.message(), unused -> MessageBubbles.create(entry, selected.group(), media)));
            }
            if (nodes.isEmpty()) {
                VBox empty = new VBox(12, new Label("Bắt đầu một cuộc trò chuyện"), new Label("Gửi lời chào, chia sẻ ảnh hoặc một tin nhắn thoại."));
                empty.getStyleClass().add("empty-chat"); empty.setAlignment(Pos.CENTER); nodes.add(empty);
            }
            pendingUploads.forEach((name, conversation) -> {
                if (conversation.equals(opened)) { Label uploading = new Label("Đang gửi " + name + "…"); uploading.getStyleClass().add("upload-pending"); HBox row = new HBox(uploading); row.setAlignment(Pos.CENTER_RIGHT); nodes.add(row); }
            });
            timeline.getChildren().setAll(nodes);
            if (atBottom) Platform.runLater(() -> { if (!disposed) scroll.setVvalue(1); });
        } finally { refreshing = false; }
    }

    private void sendText() {
        String text = composer.getText(); if (text.isBlank() || disconnected) return;
        String conversation = opened; composer.clear();
        commands.execute(() -> {
            try { client.sendTo(conversation, text); }
            catch (Exception failure) { Platform.runLater(() -> {
                if (!disposed) { if (composer.getText().isEmpty() && opened.equals(conversation)) composer.setText(text); showError(failure); }
            }); }
        });
    }

    private void chooseAttachment(String category) {
        if (disconnected) return;
        FileChooser chooser = new FileChooser(); chooser.setTitle("Chọn " + category.toLowerCase(Locale.ROOT));
        if (category.equals("Ảnh")) chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        if (category.equals("Video")) chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Video (MP4, MOV)", "*.mp4", "*.m4v", "*.mov"));
        java.io.File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) upload(opened, file.toPath(), AttachmentKinds.kindFor(file.getName()), false);
    }

    private void upload(String conversation, Path path, String kind, boolean deleteAfter) {
        String name = path.getFileName().toString(); String pendingKey = name;
        while (pendingUploads.containsKey(pendingKey)) pendingKey += " ";
        String key = pendingKey; pendingUploads.put(key, conversation); refresh();
        work.execute(() -> {
            try { client.uploadFile(conversation, path, kind); }
            catch (Exception failure) { Platform.runLater(() -> { if (!disposed) showError(failure); }); }
            finally {
                if (deleteAfter) try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                Platform.runLater(() -> { pendingUploads.remove(key); refresh(); });
            }
        });
    }

    private void toggleRecording() {
        if (recordingBusy || disconnected) return;
        if (recordingUi) { stopRecording(); return; }
        recordConversation = opened;
        recordingBusy = true; mic.setDisable(true);
        work.execute(() -> {
            try {
                recorder.start();
                Platform.runLater(() -> {
                    recordingBusy = false;
                    if (disposed || disconnected) { mic.setDisable(true); closeRecorder(); return; }
                    recordingUi = true; recordStarted = System.nanoTime(); mic.setDisable(false); mic.getStyleClass().add("recording"); mic.setText("■");
                    recordClock.setText("0:00"); recordClock.setManaged(true); recordClock.setVisible(true); recordTimer.playFromStart();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> { recordingBusy = false; mic.setDisable(disconnected); if (!disposed && !disconnected) showError(new IllegalStateException("Không tìm thấy micro")); });
            }
        });
    }

    private void stopRecording() {
        if (!recordingUi || recordingBusy) return;
        recordingUi = false; recordingBusy = true; recordTimer.stop();
        mic.setDisable(true); mic.getStyleClass().remove("recording"); mic.setText("●"); recordClock.setVisible(false); recordClock.setManaged(false);
        String conversation = recordConversation;
        work.execute(() -> {
            try {
                Path voice = recorder.stop();
                Platform.runLater(() -> {
                    recordingBusy = false; mic.setDisable(disconnected);
                    if (disposed) { Thread cleanup = new Thread(() -> { try { Files.deleteIfExists(voice); } catch (Exception ignored) { } }, "voice-cleanup"); cleanup.setDaemon(true); cleanup.start(); return; }
                    upload(conversation, voice, "audio", true);
                });
            } catch (Exception failure) { Platform.runLater(() -> { recordingBusy = false; mic.setDisable(disconnected); if (!disposed) showError(failure); }); }
        });
    }

    public void connectionLost() {
        disconnected = true; banner.setText("Mất kết nối tới server"); banner.setManaged(true); banner.setVisible(true);
        composer.setDisable(true); send.setDisable(true); attach.setDisable(true); mic.setDisable(true);
        recordTimer.stop(); recordingUi = false; closeRecorder();
    }
    private void run(Work action) { commands.execute(() -> { try { action.run(); } catch (Exception failure) { Platform.runLater(() -> { if (!disposed) showError(failure); }); } }); }
    private void showError(Throwable error) {
        while (error.getCause() != null && (error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException)) error = error.getCause();
        Alert alert = new Alert(Alert.AlertType.ERROR); if (getScene() != null) alert.initOwner(getScene().getWindow());
        alert.setTitle("Secure Chat"); alert.setHeaderText("Không thể hoàn tất thao tác"); alert.setContentText(error.getMessage() == null ? "Vui lòng thử lại" : error.getMessage()); alert.show();
    }
    private static String errorText(String code) {
        if (code == null) return "Thao tác không thành công";
        return switch (code) {
            case "NOT_MEMBER" -> "Bạn không thuộc hội thoại này";
            case "UNKNOWN_CONVERSATION" -> "Không tìm thấy hội thoại";
            case "INVALID_GROUP" -> "Tên nhóm hoặc thành viên không hợp lệ";
            case "FILE_TOO_LARGE" -> "File vượt quá 25 MB";
            case "FILE_INVALID" -> "Tệp không hợp lệ hoặc quá trình gửi bị gián đoạn";
            case "FILE_NOT_FOUND" -> "Không tìm thấy tệp";
            case "MESSAGE_TOO_LARGE" -> "Tin nhắn quá dài";
            default -> code;
        };
    }
    private void closeRecorder() { Thread close = new Thread(recorder::close, "recorder-close"); close.setDaemon(true); close.start(); }
    public void dispose() { disposed = true; commands.shutdownNow(); recordTimer.stop(); if (media != null) media.dispose(); closeRecorder(); }
    @FunctionalInterface private interface Work { void run() throws Exception; }
}
