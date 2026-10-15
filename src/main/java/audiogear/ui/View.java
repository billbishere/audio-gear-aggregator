package audiogear.ui;

import javafx.scene.Node;

/** One screen of the app. */
public interface View {
    String title();

    Node root();

    /** Called each time the screen becomes visible. */
    default void onShow() {}
}
