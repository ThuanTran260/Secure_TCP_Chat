package com.example.securechat.gui;

import com.example.securechat.client.ChatClient;
import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import com.example.securechat.model.ChatMessage;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import javax.net.ssl.SSLException;
import java.security.GeneralSecurityException;

public final class LoginView extends HBox {
    private final TextField host = new TextField("localhost");
    private final TextField port = new TextField("5000");
    private final TextField cert = new TextField("certificates/client/server.crt");
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label error = new Label();
    private final Button login = new Button("Đăng nhập");
    private volatile ChatClient pending;
    private volatile boolean disposed;

    public LoginView(Executor work, BiConsumer<ChatClient, String> onLogin) {
        getStyleClass().add("login-root");
        VBox brand = new VBox(24);
        brand.getStyleClass().add("login-brand");
        brand.setAlignment(Pos.CENTER_LEFT); brand.setPadding(new Insets(54));
        brand.setPrefWidth(420);
        Label icon = new Label("S"); icon.getStyleClass().add("brand-icon");
        Label title = new Label("Gần nhau hơn,\nqua từng tin nhắn."); title.getStyleClass().add("brand-title");
        Label caption = new Label("Kết nối bạn bè. Chia sẻ khoảnh khắc.\nTrò chuyện an toàn trên kết nối TLS.");
        caption.getStyleClass().add("brand-caption"); caption.setWrapText(true);
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);
        Label secure = new Label("●  TCP / TLS 1.2 & 1.3"); secure.getStyleClass().add("brand-caption");
        brand.getChildren().addAll(icon, title, caption, spacer, secure);
        VBox form = new VBox(12); form.setAlignment(Pos.CENTER_LEFT); form.setPadding(new Insets(38, 48, 38, 48));
        form.setMaxWidth(620); HBox.setHgrow(form, Priority.ALWAYS);
        Label heading = new Label("Secure Chat"); heading.getStyleClass().add("login-title");
        Label welcome = new Label("Đăng nhập để bắt đầu cuộc trò chuyện"); welcome.getStyleClass().add("muted");
        HBox address = new HBox(12, field("Máy chủ", host), field("Cổng", port));
        HBox.setHgrow(address.getChildren().get(0), Priority.ALWAYS); port.setPrefColumnCount(5);
        Button choose = new Button("Chọn…"); choose.getStyleClass().add("secondary-button");
        choose.setOnAction(event -> {
            FileChooser chooser = new FileChooser(); chooser.setTitle("Chọn chứng chỉ server");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Chứng chỉ X.509", "*.crt", "*.pem", "*.cer"));
            java.io.File selected = chooser.showOpenDialog(getScene().getWindow());
            if (selected != null) cert.setText(selected.getAbsolutePath());
        });
        HBox certRow = new HBox(8, cert, choose); HBox.setHgrow(cert, Priority.ALWAYS);
        VBox certField = new VBox(6, new Label("Chứng chỉ server"), certRow);
        username.setPromptText("Nhập tên đăng nhập"); password.setPromptText("Nhập mật khẩu");
        error.getStyleClass().add("error-text"); error.setWrapText(true); error.setMinHeight(32);
        login.setMaxWidth(Double.MAX_VALUE); login.getStyleClass().add("primary-button");
        login.setDefaultButton(true);
        login.setOnAction(event -> {
            final int selectedPort;
            try { selectedPort = Integer.parseInt(port.getText().trim()); if (selectedPort < 1 || selectedPort > 65535) throw new NumberFormatException(); }
            catch (NumberFormatException e) { error.setText("Cổng phải nằm trong khoảng 1–65535"); return; }
            String selectedHost = host.getText().trim();
            String selectedName = username.getText().trim().toLowerCase(Locale.ROOT);
            if (selectedHost.isBlank() || selectedName.isBlank() || password.getText().isEmpty()) {
                error.setText("Vui lòng nhập máy chủ, tên đăng nhập và mật khẩu"); return;
            }
            String certPath = cert.getText().trim();
            char[] secret = password.getText().toCharArray(); password.clear();
            login.setDisable(true); login.setText("Đang kết nối…"); error.setText("");
            work.execute(() -> {
                ChatClient connected = null;
                try {
                    connected = new ChatClient(TlsContexts.client(Path.of(certPath))); pending = connected;
                    if (disposed) return;
                    connected.connect(selectedHost, selectedPort, Protocol.CONNECT_TIMEOUT_MS);
                    ChatMessage response = connected.login(selectedName, secret);
                    if (!Boolean.TRUE.equals(response.getSuccess())) throw new LoginFailure(response.getContent());
                    ChatClient ready = connected; connected = null;
                    if (disposed) { ready.close(); return; }
                    Platform.runLater(() -> {
                        if (disposed) { Thread cleanup = new Thread(ready::close, "login-close"); cleanup.setDaemon(true); cleanup.start(); return; }
                        pending = null; onLogin.accept(ready, selectedName);
                    });
                } catch (Exception failure) {
                    String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                    if (failure instanceof SSLException || failure instanceof GeneralSecurityException) {
                        message = "Chứng chỉ server không hợp lệ: " + message;
                    }
                    String shown = message;
                    Platform.runLater(() -> { if (!disposed) { error.setText(shown); login.setDisable(false); login.setText("Đăng nhập"); } });
                } finally {
                    Arrays.fill(secret, '\0');
                    if (connected != null) connected.close();
                }
            });
        });
        form.getChildren().addAll(heading, welcome, new Separator(), address, certField,
                field("Tên đăng nhập", username), field("Mật khẩu", password), error, login);
        getChildren().addAll(brand, form);
    }

    private static VBox field(String label, Control control) { return new VBox(6, new Label(label), control); }
    public void dispose() {
        disposed = true;
        ChatClient connecting = pending;
        if (connecting != null) { Thread thread = new Thread(connecting::close, "login-close"); thread.setDaemon(true); thread.start(); }
    }
    private static final class LoginFailure extends Exception { LoginFailure(String message) { super(message); } }
}
