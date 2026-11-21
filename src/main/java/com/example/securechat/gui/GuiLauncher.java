package com.example.securechat.gui;

import javafx.application.Application;

/** Non-Application entry point supports the shaded jar's class path launch. */
public final class GuiLauncher {
    private GuiLauncher() {}
    public static void main(String[] args) { Application.launch(ChatApp.class, args); }
}
