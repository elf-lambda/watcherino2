package org.example.demo.chat;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import netscape.javascript.JSObject;
import org.example.demo.config.Config;
import org.example.demo.logger.ChatLogger;
import org.example.demo.logger.Debug;
import org.example.demo.settings.SettingsController;
import org.example.demo.tts.TTSGenerator;
import org.example.demo.twitch.*;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatController {

  // Useless for now, only used for minimiaze/maximize list panel
  private static final double PANEL_WIDTH = 170.0;

  private final JavaBridge bridge = new JavaBridge();
  private final Map<String, String> emoteMap = new HashMap<>();
  private final ChatLogger chatLogger = new ChatLogger();
  @FXML
  private Label activeChannel;
  @FXML
  private Label viewerCount;
  @FXML
  private Label viewerCountName;
  @FXML
  private ListView<TwitchChannel> channelList;
  @FXML
  private BorderPane channelPanel;
  @FXML
  private Button minimizeBtn;
  @FXML
  private Button restoreBtn;
  @FXML
  private WebView chatWebView;
  @FXML
  private TextField newChannelInput;
  private WebEngine engine;
  private boolean isMinimized = false;
  private TwitchManager twitchManager;
  private TwitchLiveChecker liveChecker;
  private String activeChannelName = "";
  private ScheduledExecutorService viewerScheduler;

  @FXML
  public void initialize() {
    // Generate TTS for each channel
    Thread.startVirtualThread(() -> {
      String piperPath = Config.get().getTtsExecutablePath();
      String modelPath = Config.get().getTtsModelPath();

      if (piperPath.isBlank() || modelPath.isBlank()) {
        Debug.info("TTS paths not configured, skipping generation");
        return;
      }

      for (Config.ChannelConfig ch : Config.get().getChannels()) {
        if (!ch.isTtsEnabled()) continue;

        String channelPath = "./tts/" + ch.getName() + ".wav";
        File f = new File(channelPath);
        if (!f.exists()) {
          Debug.info("Generating TTS for " + ch.getName());
          TTSGenerator.generate(piperPath, modelPath, channelPath,
                  ch.getName() + " is now streaming!");
        }
      }
    });

    engine = chatWebView.getEngine();

    chatWebView.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
      if (event.isControlDown() && event.getCode() == javafx.scene.input.KeyCode.C) {
        engine.executeScript("copySelectionToJava()");
        event.consume();
      }
      if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
        engine.executeScript(
                "document.getElementById('custom-channel') === document.activeElement && " +
                        "sendMessage()"
        );
        event.consume();
      }
    });

    // TODO: rework
    loadEmotes();

    java.net.URL chatUrl = getClass().getResource("/org/example/demo/web/chat.html");
    engine.load(chatUrl.toExternalForm());
    engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
      if (newState == Worker.State.SUCCEEDED) {
        injectBridge();
      }
    });

    twitchManager = new TwitchManager(this::onNewMessage);
    liveChecker = new TwitchLiveChecker(channelList, this::setChannelStatus);

    channelList.setCellFactory(list -> new ChannelListCell(
            this::removeChannel,
            ch -> {
              ch.setFavorite(true);
              // Save to config
              Config.ChannelConfig cfg = Config.get().getChannel(ch.getName());
              if (cfg != null) {
                cfg.setFavorite(true);
                Config.get().save();
              }
              sortChannels();
              channelList.refresh();
            },
            ch -> {
              ch.setFavorite(false);
              // Save to config
              Config.ChannelConfig cfg = Config.get().getChannel(ch.getName());
              if (cfg != null) {
                cfg.setFavorite(false);
                Config.get().save();
              }
              sortChannels();
              channelList.refresh();
            }
    ));

    channelList.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldVal, newVal) -> {
              if (newVal != null) switchChannel(newVal);
            }
    );
    SettingsController.filterWords.addAll(Config.get().getFilters());

    // Thread TODO: rewrite these
    liveChecker.start();
    startViewerUpdater();

    Platform.runLater(() -> {
      loadInitialChannels();
    });
  }

  private void loadInitialChannels() {
    List<Config.ChannelConfig> configChannels = Config.get().getChannels();

    if (configChannels.isEmpty()) {
      addSystemMessage("-- NO CHANNELS CONFIGURED --", false);
      return;
    }

    Thread loaderThread = new Thread(() -> {
      try {
        Thread.sleep(200);
      } catch (InterruptedException ignored) {
      }

      AtomicInteger count = new AtomicInteger();

      for (Config.ChannelConfig ch : configChannels) {
        Platform.runLater(() -> {
          String key = ch.getName().replaceFirst("^#", "").toLowerCase();

          boolean exists = channelList.getItems().stream()
                  .anyMatch(c -> c.getName().equals(key));
          if (exists) return;

          TwitchChannel tc = new TwitchChannel(key);
          tc.setFavorite(ch.isFavorite()); // set favorite from config

          channelList.getItems().add(tc);
          twitchManager.joinChannel(key);

          // Check live async
          Thread.startVirtualThread(() -> {
            boolean live = liveChecker.isLive(key);
            if (live) Platform.runLater(() -> setChannelStatus(key, true));
          });

          count.getAndIncrement();
        });
      }

      Platform.runLater(() -> {
        sortChannels();
        addSystemMessage("-- " + count + " CHANNELS LOADED --", false);
      });
    });
    loaderThread.setDaemon(true);
    loaderThread.start();
  }

  /*
   * Adds a channel to the twitch manager and also check's the status and updates it
   */
  private void addChannel(String name) {
    String key = name.replaceFirst("^#", "").toLowerCase();
    // Don't add duplicates
    boolean exists = channelList.getItems().stream()
            .anyMatch(c -> c.getName().equals(key));
    if (exists) return;

    TwitchChannel ch = new TwitchChannel(key);
//    if (liveChecker.isLive(key)) {
//      ch.setLive(true);
//    }
    channelList.getItems().add(ch);
    twitchManager.joinChannel(key);

    // Save to config if new
    if (Config.get().getChannel(key) == null) {
      Config.get().upsertChannel(new Config.ChannelConfig(key));
    }

    sortChannels();
  }

  private void removeChannel(TwitchChannel channel) {
    channelList.getItems().remove(channel);
    twitchManager.leaveChannel(channel.getName());
    Config.get().removeChannel(channel.getName());
    if (activeChannelName.equals(channel.getName())) {
      activeChannelName = "";
      activeChannel.setText("No channel selected");
      Platform.runLater(() -> engine.executeScript("clearChat()"));
    }
  }

  /*
   * Switch channel and load last 512 message (or less) from the ring buffer
   * This only gets called once on switch click
   */
  private void switchChannel(TwitchChannel channel) {
    activeChannelName = channel.getName();
    activeChannel.setText("#" + channel.getName());

    Platform.runLater(() -> engine.executeScript("clearChat()"));

    TwitchRingBuffer buffer = twitchManager.getBuffer(activeChannelName);
    if (buffer != null) {
      buffer.getLast(512).forEach(this::addMessage);
    }
    int viewers = liveChecker.getViewers(activeChannelName);
    viewerCount.setText(String.valueOf(viewers));
    viewerCountName.setText("viewers");
  }

  private void sortChannels() {
    channelList.getItems().sort((a, b) -> {
      if (a.isFavorite() != b.isFavorite()) return a.isFavorite() ? -1 : 1;
      if (a.isLive() != b.isLive()) return a.isLive() ? -1 : 1;
      return a.getName().compareTo(b.getName());
    });

    channelList.scrollTo(0);
  }

  private TwitchChannel getChannelModel(String name) {
    String key = name.replaceFirst("^#", "").toLowerCase();
    return channelList.getItems().stream()
            .filter(c -> c.getName().equals(key))
            .findFirst()
            .orElse(null);
  }

  private void setChannelStatus(String channel, boolean live) {
    Platform.runLater(() -> {
      TwitchChannel model = getChannelModel(channel);
      if (model != null) {
        model.setLive(live);
        sortChannels();
        channelList.refresh();
      }
    });
  }

  /*
   * Update current live viewers every minute
   * it updates the viewerCount variable of the UI
   */
  private void startViewerUpdater() {
    viewerScheduler = Executors.newSingleThreadScheduledExecutor();

    viewerScheduler.scheduleAtFixedRate(() -> {
      String channel = activeChannel.getText();
      if (channel == null || channel.isEmpty() || channel.equals("No channel selected")) {
        return;
      }

      try {
        int viewers = liveChecker.getViewers(channel);

        Platform.runLater(() -> {
          viewerCount.setText(String.valueOf(viewers));
        });

      } catch (Exception e) {
        Debug.error("Error updating viewer count: " + e.getMessage());
      }

    }, 0, 1, TimeUnit.MINUTES);
  }


  private void onNewMessage(TwitchMessage msg) {
    chatLogger.log(msg);
    String msgChannel = msg.channel.replace("#", "").toLowerCase();
    if (msgChannel.equals(activeChannelName)) {
      addMessage(msg);
    }
  }

  private void addSystemMessage(String msg, boolean isSystem) {
    Platform.runLater(() ->
            engine.executeScript(String.format("appendSystemMessage(`%s`, %b)", msg, isSystem)));
  }

  private void addMessage(TwitchMessage message) {
    String time = message.timestamp.format(
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    );


    String safeMsg = escapeHtml(message.content);
    String safeUser = escapeHtml(message.username);

    // Linkify after HTML escape, before emote substitution
    String linked = linkify(safeMsg);
    String rendered = substituteEmotes(linked);

    String safeUser2 = escapeForTemplateLiteral(safeUser);
    String safeRendered = escapeForTemplateLiteral(rendered);
    String safeColor = escapeForTemplateLiteral(message.userColor);

//    List<String> filterList = new Config().getFilters();
//    for (String word : filterList) {
//      if (safeMsg.contains(word)) {
//        message.isHighlighted = true;
////        AudioPlayer.playWav("./tts/ding.wav", 0.1f);
//        break;
//      }
//    }

    Platform.runLater(() ->
            engine.executeScript(
                    String.format("appendMessage(`%s`, `%s`, `%s`, `%s`, %b, %b, %b, %b, %b)",
                            time, safeUser2, safeColor, safeRendered,
                            message.isSystemMessage,
                            message.isModerator,
                            message.isVIP,
                            message.isStreamer,
                            message.isHighlighted)
            )
    );
  }

  /**
   * Wraps https:// URLs in anchor tags that notify Java on click.
   * Only https — no http, no bare domains.
   */
  private String linkify(String text) {
    // Matches https:// URLs, stops at whitespace or common trailing punctuation
    return text.replaceAll(
            "(https://[^\\s<>\"']+)",
            "<a href=\"#\" onclick=\"notifyJava('link','$1'); return false;\" " +
                    "style=\"color:#1a6bc4; text-decoration:underline; cursor:pointer;\">$1</a>"
    );
  }

  // TEMPORARY TODO: rework
  private void loadEmotes() {
    emoteMap.put("pop", toBase64DataUri("/pop.png"));
  }

  private String substituteEmotes(String message) {
    for (Map.Entry<String, String> entry : emoteMap.entrySet()) {
      String word = entry.getKey();
      String dataUri = entry.getValue();
      String imgTag = "<img src='" + dataUri + "' title='" + word
              + "' alt='" + word + "' width='32' height='32'>";
      message = message.replaceAll("\\b" + word + "\\b", imgTag);
    }
    return message;
  }

  // Utilities
  private void injectBridge() {
    JSObject window = (JSObject) engine.executeScript("window");
    window.setMember("javaApp", bridge);
  }

  private String escapeHtml(String s) {
    return s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;");
  }

  private String escapeForTemplateLiteral(String s) {
    return s
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$");
  }

  private String toBase64DataUri(String resourcePath) {
    try (var is = getClass().getResourceAsStream(resourcePath)) {
      if (is == null) {
        Debug.error("Resource not found: " + resourcePath);
        return "";
      }
      byte[] bytes = is.readAllBytes();
      String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
      String mime = resourcePath.endsWith(".png") ? "image/png" : "image/jpeg";
      return "data:" + mime + ";base64," + b64;
    } catch (Exception e) {
      e.printStackTrace();
      Debug.error(e.getMessage());
      return "";
    }
  }

  // UI controls

  @FXML
  private void onMinimize() {
    isMinimized = true;

    Timeline timeline = new Timeline(new KeyFrame(Duration.millis(200),
            new KeyValue(channelPanel.prefWidthProperty(), 0),
            new KeyValue(channelPanel.minWidthProperty(), 0),
            new KeyValue(channelPanel.maxWidthProperty(), 0)
    ));

    timeline.setOnFinished(e -> {
      channelPanel.setVisible(false);
      channelPanel.setManaged(false);
      minimizeBtn.setVisible(false);
      minimizeBtn.setManaged(false);
      restoreBtn.setVisible(true);
      restoreBtn.setManaged(true);
    });

    timeline.play();
  }

  @FXML
  private void onRestore() {
    isMinimized = false;

    channelPanel.setVisible(true);
    channelPanel.setManaged(true);
    minimizeBtn.setVisible(true);
    minimizeBtn.setManaged(true);
    restoreBtn.setVisible(false);
    restoreBtn.setManaged(false);

    Timeline timeline = new Timeline(new KeyFrame(Duration.millis(200),
            new KeyValue(channelPanel.prefWidthProperty(), PANEL_WIDTH),
            new KeyValue(channelPanel.minWidthProperty(), PANEL_WIDTH),
            new KeyValue(channelPanel.maxWidthProperty(), PANEL_WIDTH)
    ));
    // Test
    timeline.setOnFinished(e ->
            Platform.runLater(() ->
                    engine.executeScript("chat.scrollTop = chat.scrollHeight;")
            )
    );

    timeline.play();
  }

  @FXML
  private void onAddChannel() {
    String name = newChannelInput.getText().trim();
    if (name.isEmpty()) return;
    addChannel(name);
    newChannelInput.clear();
  }

  @FXML
  private void onSendMessage() {
  }

  @FXML
  private void onConnect() {
  }

  @FXML
  private void onDisconnect() {
  }

  public void shutdown() {
    chatLogger.shutdown();
    liveChecker.stop();
    twitchManager.stopAll();
    if (viewerScheduler != null) {
      viewerScheduler.shutdownNow();
    }
  }

  @FXML
  private void onOpenSettings() {
    try {
      FXMLLoader loader = new FXMLLoader(
              getClass().getResource("/org/example/demo/settings/settings-view.fxml")
      );
      Stage stage = new Stage();
      stage.setTitle("Settings");
      stage.setScene(new Scene(loader.load(), 620, 560));
      stage.initModality(Modality.APPLICATION_MODAL);
      stage.getScene().getStylesheets().add(
              getClass().getResource("/org/example/demo/style.css").toExternalForm()
      );
      stage.showAndWait();

      // After settings closed, re-apply
      applyConfig();
    } catch (IOException e) {
      e.printStackTrace();
      Debug.error(e.getMessage());
    }
  }

  private void applyConfig() {
    liveChecker.setSoundConfig(
            Config.get().getTtsExecutablePath(),
            Config.get().getVolume()
    );
  }

  // Utility class for bridging between js/java

  public class JavaBridge {
    // In JavaBridge
    public void onChatEvent(String type, String payload) {
      Platform.runLater(() -> {
        switch (type) {
          // Linux only ig
          case "link" -> {
            try {
              new ProcessBuilder("xdg-open", payload)
                      .start();
            } catch (Exception e) {
              Debug.error("Failed to open link: " + payload);
            }
          }
          case "copy" -> {
            javafx.scene.input.Clipboard clipboard =
                    javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(payload);
            clipboard.setContent(content);
          }
          case "message" -> System.out.println("User sent: " + payload);
          default -> System.out.println("Unknown event: " + type + " | " + payload);
        }
      });
    }
  }
}