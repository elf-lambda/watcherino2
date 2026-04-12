package org.example.demo;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import netscape.javascript.JSObject;
import org.example.demo.tts.TTSGenerator;
import org.example.demo.twitch.*;

import java.io.File;
import java.util.HashMap;
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
  private final String[] defaults = {
          "forsen"
  };
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
      for (String channel : defaults) {
        // TODO: remove when config done
        String channelPath = "/home/void/IdeaProjects/demo/tts/" + channel + ".wav";
        String modelPath = "/home/void/IdeaProjects/demo/models/en_US-joe-medium.onnx";
        String piperPath = "/home/void/.local/bin/piper";
        File f = new File(channelPath);
        if (!f.exists() && !f.isDirectory()) {
          System.out.println("Generating TTS for " + channel);
          TTSGenerator.generate(piperPath, modelPath, channelPath, channel + " is now streaming!");
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
              sortChannels();
              channelList.refresh();
            },
            ch -> {
              ch.setFavorite(false);
              sortChannels();
              channelList.refresh();
            }
    ));

    channelList.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldVal, newVal) -> {
              if (newVal != null) switchChannel(newVal);
            }
    );

    // Thread TODO: rewrite these
    liveChecker.start();
    startViewerUpdater();

    Platform.runLater(() -> {
      loadInitialChannels();
    });
  }

  private void loadInitialChannels() {
    Thread loaderThread = new Thread(() -> {
      try {
        Thread.sleep(200);
      } catch (InterruptedException ignored) {
      }

      AtomicInteger count = new AtomicInteger();
      // TODO: write proper config
      // Replace your existing defaults array with this:
//      String[] dummyChannels = java.util.stream.IntStream.rangeClosed(1, 25)
//              .mapToObj(i -> "test_user_" + i)
//              .toArray(String[]::new);
//
//      String[] realChannels = {"pajlada", "forsen", "brian6932"};
//
//      // Combine them into the final defaults array
//      String[] defaults = java.util.stream.Stream.concat(
//              java.util.Arrays.stream(dummyChannels),
//              java.util.Arrays.stream(realChannels)
//      ).toArray(String[]::new);

      for (String name : defaults) {
        Platform.runLater(() -> {
          addChannel(name);
          count.getAndIncrement();
        });
      }

      Platform.runLater(() -> {
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

    sortChannels();
  }

  private void removeChannel(TwitchChannel channel) {
    channelList.getItems().remove(channel);
    twitchManager.leaveChannel(channel.getName());
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
        System.err.println("Error updating viewer count: " + e.getMessage());
      }

    }, 0, 1, TimeUnit.MINUTES);
  }


  private void onNewMessage(TwitchMessage msg) {
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

    Platform.runLater(() ->
            engine.executeScript(
                    String.format("appendMessage(`%s`, `%s`, `%s`, `%s`, %b, %b, %b, %b)",
                            time, safeUser2, safeColor, safeRendered,
                            message.isSystemMessage,
                            message.isModerator,
                            message.isVIP,
                            message.isStreamer)
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
        System.err.println("Resource not found: " + resourcePath);
        return "";
      }
      byte[] bytes = is.readAllBytes();
      String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
      String mime = resourcePath.endsWith(".png") ? "image/png" : "image/jpeg";
      return "data:" + mime + ";base64," + b64;
    } catch (Exception e) {
      e.printStackTrace();
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
    liveChecker.stop();
    twitchManager.stopAll();
    if (viewerScheduler != null) {
      viewerScheduler.shutdownNow();
    }
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
              System.err.println("Failed to open link: " + payload);
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