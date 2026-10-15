package audiogear.ui;

import audiogear.domain.Enums;
import audiogear.domain.GearNames;
import audiogear.domain.GearSummary;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Type-ahead search over the catalog. Enter picks the first suggestion; the value is null until one is picked. */
public final class GearPicker extends TextField {

    private static final int MAX_SUGGESTIONS = 15;

    private final ObjectProperty<GearSummary> value = new SimpleObjectProperty<>();
    private final ContextMenu menu = new ContextMenu();
    private final Supplier<List<GearSummary>> items;
    private final Predicate<GearSummary> filter;
    private List<GearSummary> suggestions = List.of();
    private boolean updating;

    public GearPicker(String prompt, Supplier<List<GearSummary>> items, Predicate<GearSummary> filter) {
        this.items = items;
        this.filter = filter;
        setPromptText(prompt);
        setPrefColumnCount(22);
        menu.setAutoHide(true);
        textProperty().addListener((o, a, b) -> {
            if (updating) return;
            if (value.get() != null && !value.get().displayName().equals(b)) value.set(null);
            suggest(b);
        });
        setOnAction(e -> {
            if (!suggestions.isEmpty()) select(suggestions.getFirst());
        });
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) menu.hide();
        });
        focusedProperty().addListener((o, a, focused) -> {
            if (!focused) menu.hide();
        });
    }

    private void suggest(String text) {
        String q = GearNames.normalize(text);
        menu.getItems().clear();
        if (q.isEmpty() || !isFocused()) {
            suggestions = List.of();
            menu.hide();
            return;
        }
        List<GearSummary> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (GearSummary g : items.get()) {
            if (!filter.test(g)) continue;
            String name = GearNames.normalize(g.displayName());
            String model = GearNames.normalize(g.model());
            if (name.startsWith(q) || model.startsWith(q)) starts.add(g);
            else if (name.contains(q)) contains.add(g);
            if (starts.size() >= MAX_SUGGESTIONS) break;
        }
        List<GearSummary> all = new ArrayList<>(starts);
        for (GearSummary g : contains) if (all.size() < MAX_SUGGESTIONS) all.add(g);
        suggestions = all;
        for (GearSummary g : all) {
            Label l = new Label(g.displayName() + "   ·  " + Enums.label(g.type()));
            l.setPrefWidth(Math.max(260, getWidth() - 20));
            CustomMenuItem item = new CustomMenuItem(l, true);
            item.setOnAction(e -> select(g));
            menu.getItems().add(item);
        }
        if (all.isEmpty()) menu.hide();
        else if (!menu.isShowing()) menu.show(this, Side.BOTTOM, 0, 0);
    }

    public void select(GearSummary g) {
        updating = true;
        try {
            setText(g == null ? "" : g.displayName());
            value.set(g);
        } finally {
            updating = false;
        }
        menu.hide();
        positionCaret(getText().length());
    }

    /** Selects the catalog item with this id, if it passes the filter. */
    public void selectId(Long id) {
        if (id == null) { select(null); return; }
        for (GearSummary g : items.get()) if (g.id() == id && filter.test(g)) { select(g); return; }
    }

    public GearSummary getValue() { return value.get(); }

    public ObjectProperty<GearSummary> valueProperty() { return value; }
}
