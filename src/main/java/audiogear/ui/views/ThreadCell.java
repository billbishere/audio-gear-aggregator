package audiogear.ui.views;

import audiogear.domain.Enums;
import audiogear.domain.ForumThread;
import audiogear.ui.Fx;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A thread in a list: title, source badge, date, Reddit score, linked gear and (optionally) the snippet. */
final class ThreadCell extends ListCell<ForumThread> {

    private final Map<Long, List<String>> linked;
    private final boolean showSnippet;

    /** @param linked thread id → linked gear names, or null to omit */
    ThreadCell(Map<Long, List<String>> linked, boolean showSnippet) {
        this.linked = linked;
        this.showSnippet = showSnippet;
    }

    @Override
    protected void updateItem(ForumThread t, boolean empty) {
        super.updateItem(t, empty);
        if (empty || t == null) {
            setGraphic(null);
            setText(null);
            return;
        }
        Label title = new Label(t.title());
        title.getStyleClass().add("thread-title");
        title.setWrapText(true);
        Label badge = new Label(Enums.label(t.source()));
        badge.getStyleClass().addAll("badge", "badge-" + t.source().name().toLowerCase(Locale.ROOT));
        List<String> meta = new ArrayList<>();
        String date = Fx.date(t.postedAt() != null ? t.postedAt() : t.firstSeenAt());
        if (!date.isEmpty()) meta.add((t.postedAt() == null ? "found " : "") + date);
        if (t.score() != null) meta.add(t.score() + " points");
        if (t.commentCount() != null) meta.add(t.commentCount() + " comments");
        if (t.author() != null) meta.add("by " + t.author());
        if (linked != null && linked.containsKey(t.id())) meta.add("linked: " + String.join(", ", linked.get(t.id())));
        Label metaLabel = new Label(String.join("  ·  ", meta));
        metaLabel.getStyleClass().add("thread-meta");
        VBox box = new VBox(3, title, new HBox(8, badge, metaLabel));
        if (showSnippet && t.snippet() != null) {
            Label s = new Label(t.snippet());
            s.setWrapText(true);
            s.getStyleClass().add("thread-snippet");
            box.getChildren().add(s);
        }
        box.setPadding(new javafx.geometry.Insets(4, 0, 6, 0));
        box.prefWidthProperty().bind(getListView().widthProperty().subtract(30));
        title.maxWidthProperty().bind(box.prefWidthProperty());
        setGraphic(box);
        setText(null);
    }
}
