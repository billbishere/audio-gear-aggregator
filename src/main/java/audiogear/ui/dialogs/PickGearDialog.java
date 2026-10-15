package audiogear.ui.dialogs;

import audiogear.domain.GearSummary;
import audiogear.ui.Fx;
import audiogear.ui.GearPicker;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Supplier;

/** Asks for one catalog item. */
public final class PickGearDialog extends Dialog<GearSummary> {

    public PickGearDialog(String title, String hint, Supplier<List<GearSummary>> catalog) {
        setTitle(title);
        setHeaderText(null);
        GearPicker picker = new GearPicker("Start typing a brand or model", catalog, g -> true);
        picker.setPrefColumnCount(32);
        getDialogPane().setContent(new VBox(8, Fx.muted(hint), picker));
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(picker.valueProperty().isNull());
        setResultConverter(b -> b == ButtonType.OK ? picker.getValue() : null);
        Platform.runLater(picker::requestFocus);
    }
}
