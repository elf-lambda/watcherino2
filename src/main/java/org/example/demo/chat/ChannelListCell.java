package org.example.demo.chat;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.shape.Rectangle;
import org.example.demo.twitch.TwitchChannel;

import java.util.function.Consumer;

public class ChannelListCell extends ListCell<TwitchChannel> {

  private final HBox container;
  private final Label nameLabel;
  private final Rectangle activityIndicator;
  private final Rectangle statusIndicator;
  private final Button removeBtn;

  public ChannelListCell(Consumer<TwitchChannel> onRemoveChannel,
                         Consumer<TwitchChannel> onFavoriteChannel,
                         Consumer<TwitchChannel> onUnfavoriteChannel) {

    container = new HBox(6);
    container.setAlignment(Pos.CENTER_LEFT);
    container.setPadding(new Insets(2, 4, 2, 4));

    container.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
      if (e.isSecondaryButtonDown()) {
        e.consume(); // prevent right-click from selecting the cell
      }
    });

    nameLabel = new Label();
    nameLabel.setStyle("""
                -fx-text-fill: #cccccc;
                -fx-font-family: 'Consolas', 'Monospaced';
                -fx-font-size: 13px;
            """);
    HBox.setHgrow(nameLabel, Priority.ALWAYS);
    nameLabel.setMaxWidth(Double.MAX_VALUE);

    // Gold button -  favorite indicator, invisible by default
    activityIndicator = new Rectangle(8, 8);
    activityIndicator.setFill(javafx.scene.paint.Color.web("#f2cb6b"));
    activityIndicator.setOpacity(0.0);
    activityIndicator.setMouseTransparent(true);

    // Green/red - live status
    statusIndicator = new Rectangle(8, 8);
    statusIndicator.setFill(javafx.scene.paint.Color.RED);
    statusIndicator.setMouseTransparent(true);

    // Remove button
    removeBtn = new Button("✕");
    removeBtn.setStyle("""
                -fx-background-color: transparent;
                -fx-text-fill: #555555;
                -fx-font-size: 11px;
                -fx-padding: 0 2 0 2;
                -fx-cursor: hand;
                -fx-border-color: transparent;
            """);
    removeBtn.setOnMouseEntered(e -> removeBtn.setStyle("""
                -fx-background-color: transparent;
                -fx-text-fill: #ff4444;
                -fx-font-size: 11px;
                -fx-padding: 0 2 0 2;
                -fx-cursor: hand;
                -fx-border-color: transparent;
            """));
    removeBtn.setOnMouseExited(e -> removeBtn.setStyle("""
                -fx-background-color: transparent;
                -fx-text-fill: #555555;
                -fx-font-size: 11px;
                -fx-padding: 0 2 0 2;
                -fx-cursor: hand;
                -fx-border-color: transparent;
            """));
    removeBtn.setOnAction(e -> {
      TwitchChannel channel = getItem();
      if (channel != null) onRemoveChannel.accept(channel);
    });

    // Hover
    container.setOnMouseEntered(e ->
            container.setStyle("-fx-background-color: #1f1f1f;")
    );
    container.setOnMouseExited(e ->
            container.setStyle("-fx-background-color: transparent;")
    );

    // Right-click context menu
    ContextMenu contextMenu = new ContextMenu();
    MenuItem favoriteItem = new MenuItem("⭐ Favorite");
    MenuItem unfavoriteItem = new MenuItem("✕ Unfavorite");
    contextMenu.getItems().addAll(favoriteItem, unfavoriteItem);

    favoriteItem.setOnAction(e -> {
      TwitchChannel channel = getItem();
      if (channel != null) onFavoriteChannel.accept(channel);
    });
    unfavoriteItem.setOnAction(e -> {
      TwitchChannel channel = getItem();
      if (channel != null) onUnfavoriteChannel.accept(channel);
    });

    container.setOnContextMenuRequested(e ->
            contextMenu.show(container, e.getScreenX(), e.getScreenY())
    );

    container.getChildren().addAll(nameLabel, activityIndicator, statusIndicator, removeBtn);
  }

  @Override
  protected void updateItem(TwitchChannel channel, boolean empty) {
    super.updateItem(channel, empty);

    if (empty || channel == null) {
      setGraphic(null);
      setText(null);
      setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
    } else {
      nameLabel.setText(channel.getName());

      // TODO fix/implement
      String bgColor = channel.hasHighlightAlert() ? "rgba(231, 76, 60, 0.4)" : "transparent";

      setGraphic(container);
      setText(null);

      setStyle(String.format("""
                  -fx-background-color: %s;
                  -fx-border-color: #2a2a2a;
                  -fx-border-width: 0 0 1 0;
                  -fx-padding: 0;
              """, bgColor));

      // Ensure indicators still work
      statusIndicator.setFill(channel.isLive() ? javafx.scene.paint.Color.web("#27ae60") :
              javafx.scene.paint.Color.RED);
      activityIndicator.setOpacity(channel.isFavorite() ? 1.0 : 0.0);
    }
  }

  public void flashActivity() {
    activityIndicator.setOpacity(1.0);
    new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(
                    javafx.util.Duration.millis(400),
                    e -> {
                      TwitchChannel ch = getItem();
                      activityIndicator.setOpacity(ch != null && ch.isFavorite() ? 1.0 : 0.0);
                    }
            )
    ).play();
  }
}