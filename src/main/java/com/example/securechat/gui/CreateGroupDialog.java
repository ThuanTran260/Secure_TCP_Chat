package com.example.securechat.gui;

import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import javafx.stage.Window;
import java.util.*;

public final class CreateGroupDialog extends Dialog<CreateGroupDialog.Result> {
    public record Result(String name, List<String> members) {}
    public CreateGroupDialog(Window owner, Collection<String> users, String me) {
        initOwner(owner); setTitle("Tạo nhóm"); setHeaderText("Cùng nhau trò chuyện");
        getDialogPane().getStylesheets().add(ChatApp.class.getResource("zalo.css").toExternalForm());
        TextField name = new TextField(); name.setPromptText("Tên nhóm (1–50 ký tự)");
        VBox choices = new VBox(10); Map<String, CheckBox> members = new TreeMap<>();
        users.stream().filter(user -> !me.equals(user)).distinct().sorted().forEach(user -> {
            CheckBox check = new CheckBox(user); members.put(user, check); choices.getChildren().add(check);
        });
        ScrollPane scroll = new ScrollPane(choices); scroll.setFitToWidth(true); scroll.setPrefViewportHeight(230);
        VBox content = new VBox(12, new Label("Tên nhóm"), name, new Label("Chọn thành viên"), scroll);
        content.setPadding(new Insets(8)); content.setPrefWidth(360);
        getDialogPane().setContent(content);
        ButtonType create = new ButtonType("Tạo nhóm", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(create, new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE));
        javafx.scene.Node confirm = getDialogPane().lookupButton(create); confirm.setDisable(true);
        name.textProperty().addListener((observable, oldValue, value) -> confirm.setDisable(value.isBlank() || value.strip().length() > 50));
        setResultConverter(button -> button == create ? new Result(name.getText().strip(), members.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).toList()) : null);
    }
}
