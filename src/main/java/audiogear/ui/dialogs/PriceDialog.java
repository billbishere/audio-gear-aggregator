package audiogear.ui.dialogs;

import audiogear.domain.Condition;
import audiogear.domain.PriceEntry;
import audiogear.ui.Fx;
import audiogear.ui.NumberField;
import javafx.beans.binding.Bindings;
import javafx.scene.control.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/** Records one observed price for a gear item. */
public final class PriceDialog extends Dialog<PriceEntry> {

    public PriceDialog(long gearId, String gearName, String defaultCurrency, List<String> knownRetailers) {
        setTitle("Add price");
        setHeaderText(gearName);
        ComboBox<String> retailer = new ComboBox<>();
        retailer.setEditable(true);
        retailer.getItems().setAll(knownRetailers);
        retailer.setPromptText("e.g. Headphones.com");
        NumberField price = NumberField.nonNegative("price");
        TextField currency = new TextField(defaultCurrency);
        currency.setPrefColumnCount(5);
        ComboBox<Condition> condition = Fx.enumCombo(Condition.class, null);
        condition.setValue(Condition.NEW);
        TextField url = new TextField();
        url.setPromptText("optional link to the listing");
        DatePicker date = new DatePicker(LocalDate.now());

        FormGrid grid = new FormGrid()
                .add("Retailer *", retailer).add("Price *", price)
                .add("Currency", currency).add("Condition", condition)
                .add("Seen on", date).add("", new Label(""))
                .wide("Link", url);
        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(Bindings.createBooleanBinding(
                () -> retailer.getEditor().getText().isBlank() || price.getValue() == null || date.getValue() == null,
                retailer.getEditor().textProperty(), price.textProperty(), date.valueProperty()));
        setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            String seen = date.getValue().atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toString();
            String cur = currency.getText() == null || currency.getText().isBlank() ? defaultCurrency : currency.getText().strip().toUpperCase();
            return new PriceEntry(null, gearId, retailer.getEditor().getText().strip(), price.getValue(), cur, condition.getValue(),
                    url.getText() == null || url.getText().isBlank() ? null : url.getText().strip(), seen);
        });
    }
}
