package audiogear.ui;

import atlantafx.base.theme.Styles;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

/** A text field for an optional number. Invalid input is highlighted and reported through validProperty(). */
public final class NumberField extends TextField {

    private final ReadOnlyBooleanWrapper valid = new ReadOnlyBooleanWrapper(true);
    private final Double min;
    private final boolean minExclusive;

    /** @param min lowest accepted value (null for none); minExclusive makes it a strict bound */
    public NumberField(String prompt, Double min, boolean minExclusive) {
        this.min = min;
        this.minExclusive = minExclusive;
        setPromptText(prompt);
        setPrefColumnCount(7);
        textProperty().addListener((o, a, b) -> validate());
    }

    public NumberField(String prompt) { this(prompt, null, false); }

    /** Positive numbers only (e.g. impedance, power). */
    public static NumberField positive(String prompt) { return new NumberField(prompt, 0.0, true); }

    /** Zero or more (e.g. prices, output impedance). */
    public static NumberField nonNegative(String prompt) { return new NumberField(prompt, 0.0, false); }

    private void validate() {
        String t = getText() == null ? "" : getText().strip();
        String problem = null;
        if (!t.isEmpty()) {
            try {
                double v = Double.parseDouble(t.replace(',', '.'));
                if (!Double.isFinite(v)) problem = "Not a number";
                else if (min != null && (minExclusive ? v <= min : v < min)) problem = "Must be " + (minExclusive ? "more than " : "at least ") + Fx.num(min, 6);
            } catch (NumberFormatException e) {
                problem = "Not a number";
            }
        }
        valid.set(problem == null);
        pseudoClassStateChanged(Styles.STATE_DANGER, problem != null);
        setTooltip(problem == null ? null : new Tooltip(problem));
    }

    /** The value, or null when empty or invalid. */
    public Double getValue() {
        if (!valid.get()) return null;
        String t = getText() == null ? "" : getText().strip();
        return t.isEmpty() ? null : Double.parseDouble(t.replace(',', '.'));
    }

    public Integer getIntValue() {
        Double d = getValue();
        return d == null ? null : (int) Math.round(d);
    }

    public void setValue(Number v) {
        setText(v == null ? "" : Fx.num(v.doubleValue(), 6));
    }

    public boolean isValid() { return valid.get(); }

    public ReadOnlyBooleanProperty validProperty() { return valid.getReadOnlyProperty(); }
}
