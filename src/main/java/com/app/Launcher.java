package com.app;

import com.app.ui.MainApp;
import javafx.application.Application;

public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        Application.launch(MainApp.class, args);
    }
}
