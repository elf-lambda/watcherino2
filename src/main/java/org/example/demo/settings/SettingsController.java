package org.example.demo.settings;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.demo.config.Config;

import java.io.File;
import java.util.ArrayList;

// TODO: add style to own css file, maybe

public class SettingsController {

  public static final ObservableList<String> filterWords = FXCollections.observableArrayList();
  private final ObservableList<Config.ChannelConfig> allChannels =
          FXCollections.observableArrayList();
  private final FilteredList<Config.ChannelConfig> filteredChannels =
          new FilteredList<>(allChannels, p -> true);
  @FXML
  private Slider volumeSlider;
  @FXML
  private Label volumeLabel;
  @FXML
  private TextField piperPathField;
  @FXML
  private TextField modelPathField;
  @FXML
  private ListView<Config.ChannelConfig> channelListView;
  @FXML
  private ListView<String> filterListView;
  @FXML
  private TextField filterField;
  @FXML
  private TextField newChannelField;
  @FXML
  private TextField newFilterField;

  @FXML
  public void initialize() {
    // Volume
    volumeSlider.setValue(Config.get().getVolume());
    updateVolumeLabel(Config.get().getVolume());
    volumeSlider.valueProperty().addListener((obs, old, val) ->
            updateVolumeLabel(val.floatValue())
    );

    // Paths
    piperPathField.setText(Config.get().getTtsExecutablePath());
    modelPathField.setText(Config.get().getTtsModelPath());

    // Deep copy channels
    for (Config.ChannelConfig ch : Config.get().getChannels()) {
      Config.ChannelConfig copy = new Config.ChannelConfig(ch.getName());
      copy.setFavorite(ch.isFavorite());
      copy.setTtsEnabled(ch.isTtsEnabled());
      allChannels.add(copy);
    }

    channelListView.setItems(filteredChannels);
    channelListView.setCellFactory(lv -> new ChannelSettingsCell());

    // Filters
    filterWords.clear();
    filterWords.addAll(Config.get().getFilters());
    filterListView.setItems(filterWords);
    filterListView.setCellFactory(lv -> new FilterWordCell());
  }

  private void updateVolumeLabel(float val) {
    volumeLabel.setText(Math.round(val * 100) + "%");
  }

  @FXML
  private void onFilter() {
    String text = filterField.getText().trim().toLowerCase();
    filteredChannels.setPredicate(ch ->
            text.isEmpty() || ch.getName().toLowerCase().contains(text)
    );
  }

  @FXML
  private void onAddChannel() {
    String name = newChannelField.getText().trim().toLowerCase()
            .replaceFirst("^#", "");
    if (name.isBlank()) return;
    boolean exists = allChannels.stream()
            .anyMatch(c -> c.getName().equalsIgnoreCase(name));
    if (exists) return;
    allChannels.add(new Config.ChannelConfig(name));
    newChannelField.clear();
  }

  @FXML
  private void onAddFilter() {
    String word = newFilterField.getText().trim().toLowerCase();
    if (word.isBlank()) return;
    if (filterWords.contains(word)) return;
    filterWords.add(word);
    newFilterField.clear();
    Config.get().setFilters(new ArrayList<>(filterWords));
    Config.get().save();
  }

  @FXML
  private void onBrowsePiper() {
    File f = browseFile("Select Piper Executable", null);
    if (f != null) piperPathField.setText(f.getAbsolutePath());
  }

  @FXML
  private void onBrowseModel() {
    File f = browseFile("Select TTS Model", "*.onnx");
    if (f != null) modelPathField.setText(f.getAbsolutePath());
  }

  private File browseFile(String title, String extension) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle(title);
    if (extension != null) {
      chooser.getExtensionFilters().add(
              new FileChooser.ExtensionFilter("Model files", extension)
      );
    }
    return chooser.showOpenDialog(volumeSlider.getScene().getWindow());
  }

  @FXML
  private void onSave() {
    Config.get().setVolume((float) volumeSlider.getValue());
    Config.get().setTtsExecutablePath(piperPathField.getText().trim());
    Config.get().setTtsModelPath(modelPathField.getText().trim());
    Config.get().setChannels(new ArrayList<>(allChannels));
    Config.get().setFilters(new ArrayList<>(filterWords));
    Config.get().save();
    close();
  }

  @FXML
  private void onCancel() {
    close();
  }

  private void close() {
    ((Stage) volumeSlider.getScene().getWindow()).close();
  }

  // Channel cell

  private class ChannelSettingsCell extends ListCell<Config.ChannelConfig> {

    private final HBox container = new HBox(8);
    private final Label nameLabel = new Label();
    private final CheckBox favBox = new CheckBox("★");
    private final CheckBox ttsBox = new CheckBox("TTS");
    private final Button removeBtn = new Button("✕");

    public ChannelSettingsCell() {
      container.setAlignment(Pos.CENTER_LEFT);
      container.setStyle("-fx-padding: 3 6 3 6;");

      HBox.setHgrow(nameLabel, Priority.ALWAYS);
      nameLabel.setMaxWidth(Double.MAX_VALUE);
      nameLabel.setStyle("""
                  -fx-text-fill: #888888;
                  -fx-font-family: 'Consolas';
                  -fx-font-size: 12px;
              """);

      favBox.setStyle("-fx-text-fill: #f2cb6b; -fx-font-size: 11px;");
      ttsBox.setStyle("-fx-text-fill: #666666; -fx-font-size: 11px;");

      styleRemoveBtn(false);

      removeBtn.setOnMouseEntered(e -> styleRemoveBtn(true));
      removeBtn.setOnMouseExited(e -> styleRemoveBtn(false));
      removeBtn.setOnAction(e -> {
        Config.ChannelConfig ch = getItem();
        if (ch != null) allChannels.remove(ch);
      });

      favBox.selectedProperty().addListener((obs, old, val) -> {
        if (getItem() != null) getItem().setFavorite(val);
      });
      ttsBox.selectedProperty().addListener((obs, old, val) -> {
        if (getItem() != null) getItem().setTtsEnabled(val);
      });

      container.getChildren().addAll(nameLabel, favBox, ttsBox, removeBtn);
    }

    private void styleRemoveBtn(boolean hover) {
      removeBtn.setStyle("""
                  -fx-background-color: transparent;
                  -fx-text-fill: %s;
                  -fx-font-size: 11px;
                  -fx-cursor: hand;
                  -fx-border-color: transparent;
                  -fx-padding: 0 2 0 2;
              """.formatted(hover ? "#cc3333" : "#444444"));
    }

    @Override
    protected void updateItem(Config.ChannelConfig ch, boolean empty) {
      super.updateItem(ch, empty);
      if (empty || ch == null) {
        setGraphic(null);
        setText(null);
        setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
      } else {
        nameLabel.setText(ch.getName());
        favBox.setSelected(ch.isFavorite());
        ttsBox.setSelected(ch.isTtsEnabled());
        setGraphic(container);
        setText(null);
        setStyle("""
                    -fx-background-color: transparent;
                    -fx-border-color: #1a1a1a;
                    -fx-border-width: 0 0 1 0;
                    -fx-padding: 0;
                """);
      }
    }
  }

  // Filter words

  private class FilterWordCell extends ListCell<String> {

    private final HBox container = new HBox(8);
    private final Label wordLabel = new Label();
    private final Button removeBtn = new Button("✕");

    public FilterWordCell() {
      container.setAlignment(Pos.CENTER_LEFT);
      container.setStyle("-fx-padding: 2 6 2 6;");

      HBox.setHgrow(wordLabel, Priority.ALWAYS);
      wordLabel.setMaxWidth(Double.MAX_VALUE);
      wordLabel.setStyle("""
                  -fx-text-fill: #888888;
                  -fx-font-family: 'Consolas';
                  -fx-font-size: 12px;
              """);

      styleRemoveBtn(false);
      removeBtn.setOnMouseEntered(e -> styleRemoveBtn(true));
      removeBtn.setOnMouseExited(e -> styleRemoveBtn(false));
      removeBtn.setOnAction(e -> {
        String word = getItem();
        if (word != null) filterWords.remove(word);
        Config.get().setFilters(new ArrayList<>(filterWords));
        Config.get().save();
      });

      container.getChildren().addAll(wordLabel, removeBtn);
    }

    private void styleRemoveBtn(boolean hover) {
      removeBtn.setStyle("""
                  -fx-background-color: transparent;
                  -fx-text-fill: %s;
                  -fx-font-size: 11px;
                  -fx-cursor: hand;
                  -fx-border-color: transparent;
                  -fx-padding: 0 2 0 2;
              """.formatted(hover ? "#cc3333" : "#444444"));
    }

    @Override
    protected void updateItem(String word, boolean empty) {
      super.updateItem(word, empty);
      if (empty || word == null) {
        setGraphic(null);
        setText(null);
        setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
      } else {
        wordLabel.setText(word);
        setGraphic(container);
        setText(null);
        setStyle("""
                    -fx-background-color: transparent;
                    -fx-border-color: #1a1a1a;
                    -fx-border-width: 0 0 1 0;
                    -fx-padding: 0;
                """);
      }
    }
  }
}