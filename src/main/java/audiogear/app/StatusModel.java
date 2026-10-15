package audiogear.app;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** What the status bar shows. Changed only on the FX thread. */
public final class StatusModel {
    public final StringProperty message = new SimpleStringProperty("Ready");
    public final BooleanProperty error = new SimpleBooleanProperty(false);
    public final IntegerProperty running = new SimpleIntegerProperty(0);
    /** -1 for indeterminate. */
    public final DoubleProperty progress = new SimpleDoubleProperty(-1);
    /** Set while a cancellable task runs. */
    public final ObjectProperty<Runnable> cancel = new SimpleObjectProperty<>();

    public void info(String text) {
        message.set(text);
        error.set(false);
    }

    public void fail(String text) {
        message.set(text);
        error.set(true);
    }
}
