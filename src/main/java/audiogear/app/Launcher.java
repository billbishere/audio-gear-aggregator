package audiogear.app;

import javafx.application.Application;

/** Plain main class so the app also starts when JavaFX is on the class path (jpackage, IDE runs). */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        Application.launch(AudioGearApp.class, args);
    }
}
