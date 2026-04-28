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
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import netscape.javascript.JSObject;
import org.example.demo.config.Config;
import org.example.demo.emoji.EmojiDownloader;
import org.example.demo.emoji.EmojiSubstituter;
import org.example.demo.emotes.*;
import org.example.demo.logger.ChatLogger;
import org.example.demo.logger.Debug;
import org.example.demo.settings.SettingsController;
import org.example.demo.tts.TTSGenerator;
import org.example.demo.twitch.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatController {

  // Useless for now, only used for minimize/maximize list panel
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
  private TwitchManager twitchManager; // THE OS THREAD DESTROYER'S keeper
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
      // Copy
      if (event.isControlDown() && event.getCode() == javafx.scene.input.KeyCode.C) {
        engine.executeScript("copySelectionToJava()");
        event.consume();
        return;
      }
      // Up Down
      if (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.DOWN) {
        String keyName = (event.getCode() == KeyCode.UP) ? "ArrowUp" : "ArrowDown";

        engine.executeScript(String.format(
                "if(document.activeElement.id === 'custom-channel') {" +
                        "   document.dispatchEvent(new KeyboardEvent('keydown', { 'key': '%s', " +
                        "'bubbles': true }));" +
                        "}", keyName
        ));
        event.consume();
      }
      // Escape (close autocomplete)
      if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
        engine.executeScript(
                "if(document.activeElement.id === 'custom-channel') {" +
                        "   document.dispatchEvent(new KeyboardEvent('keydown', { 'key': " +
                        "'Escape', 'bubbles': true }));" +
                        "}"
        );
        event.consume();
      }
      // Enter
      if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
        engine.executeScript(
                "if(document.activeElement.id === 'custom-channel') {" +
                        "   document.dispatchEvent(new KeyboardEvent('keydown', { 'key': 'Enter'," +
                        " 'bubbles': true }));" +
                        "}"
        );
        event.consume();
      }
    });

    SEVENTVEmotesDownloader sevenTV = new SEVENTVEmotesDownloader();
    sevenTV.fetchGlobal();
    BTTVEmotesDownloader bttv = new BTTVEmotesDownloader();
    bttv.fetchGlobal();
    FFZEmotesDownloader ffz = new FFZEmotesDownloader();
    ffz.fetchGlobal();

    java.net.URL chatUrl = getClass().getResource("/org/example/demo/web/chat.html");
    engine.load(chatUrl.toExternalForm());
    engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
      if (newState == Worker.State.SUCCEEDED) {
        injectBridge();
      }
    });

    Thread.startVirtualThread(() -> {
      EmojiDownloader.downloadAll(() -> Debug.info("All emojis ready"));
    });

    twitchManager = new TwitchManager(this::onNewMessage);
    liveChecker = new TwitchLiveChecker(channelList, this::setChannelStatus);

    channelList.setCellFactory(list -> new ChannelListCell(
            this::removeChannel,
            ch -> {
              ch.setFavorite(true);
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

    liveChecker.start();
    startViewerUpdater();

    Platform.runLater(this::loadInitialChannels);
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
          tc.setFavorite(ch.isFavorite());

          channelList.getItems().add(tc);
          twitchManager.joinChannel(key);

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

  private void addChannel(String name) {
    String key = name.replaceFirst("^#", "").toLowerCase();
    boolean exists = channelList.getItems().stream()
            .anyMatch(c -> c.getName().equals(key));
    if (exists) return;

    TwitchChannel ch = new TwitchChannel(key);
    channelList.getItems().add(ch);
    twitchManager.joinChannel(key);

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

  private void switchChannel(TwitchChannel channel) {
    activeChannelName = channel.getName();
    activeChannel.setText("#" + channel.getName());

    Platform.runLater(() -> engine.executeScript("clearChat()"));
    Platform.runLater(() -> engine.executeScript("var activeChannelName = '" + activeChannelName +
            "';"));

    TwitchRingBuffer buffer = twitchManager.getBuffer(activeChannelName);
    if (buffer != null) {
      buffer.getLast(128).forEach(this::addMessage);
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

    String withTwitchEmotes = substituteTwitchEmotes(message);
    String emojied = EmojiSubstituter.substitute(withTwitchEmotes);
    String linked = linkify(emojied);

    String rendered = substituteEmotes(linked);

    String safeUser = escapeForTemplateLiteral(escapeHtml(message.username));
    String safeRendered = escapeForTemplateLiteral(rendered);
    String safeColor = escapeForTemplateLiteral(message.userColor);

    Platform.runLater(() ->
            engine.executeScript(
                    String.format("appendMessage(`%s`, `%s`, `%s`, `%s`, %b, %b, %b, %b, %b)",
                            time, safeUser, safeColor, safeRendered,
                            message.isSystemMessage,
                            message.isModerator,
                            message.isVIP,
                            message.isStreamer,
                            message.isHighlighted)
            )
    );
  }


  /**
   * This substitutes global twitch/sub emotes
   */
  private String substituteTwitchEmotes(TwitchMessage message) {
    String emoteTag = message.tags.getOrDefault("emotes", "");
    if (emoteTag.isBlank()) return escapeHtml(message.content);

    Map<String, Path> paths = TwitchEmotesHandler.processEmoteTag(emoteTag);
    List<TwitchEmotesHandler.EmoteOccurrence> occurrences =
            TwitchEmotesHandler.parseOccurrences(emoteTag, paths);

    if (occurrences.isEmpty()) return escapeHtml(message.content);

    int[] codePoints = message.content.codePoints().toArray();
    StringBuilder result = new StringBuilder();
    int cursor = 0;

    for (var occ : occurrences) {
      StringBuilder textBefore = new StringBuilder();
      for (int i = cursor; i < occ.start() && i < codePoints.length; i++) {
        textBefore.appendCodePoint(codePoints[i]);
      }
      result.append(escapeHtml(textBefore.toString()));

      StringBuilder emoteTextBuilder = new StringBuilder();
      for (int i = occ.start(); i <= occ.end() && i < codePoints.length; i++) {
        emoteTextBuilder.appendCodePoint(codePoints[i]);
      }
      String emoteName = escapeHtml(emoteTextBuilder.toString());

      String url = occ.localPath().toUri().toString();
      result.append("<img src='").append(url)
              .append("' alt='").append(emoteName)
              .append("' title='").append(emoteName)
              .append("' style='vertical-align:middle;'>");

      cursor = occ.end() + 1;
    }
    StringBuilder remaining = new StringBuilder();
    for (int i = cursor; i < codePoints.length; i++) {
      remaining.appendCodePoint(codePoints[i]);
    }
    result.append(escapeHtml(remaining.toString()));

    return result.toString();
  }

  private String linkify(String text) {
    return text.replaceAll(
            "(https://[^\\s<>\"']+)",
            "<a href=\"#\" onclick=\"notifyJava('link','$1'); return false;\" " +
                    "style=\"color:#1a6bc4; text-decoration:underline; cursor:pointer;\">$1</a>"
    );
  }

  /**
   * Safely substitutes native and 7TV/BTTV/FFZ emotes without breaking existing HTML tags.
   */
  private String substituteEmotes(String message) {
    Map<String, String> sevenTvEmotes = getSevenTVEmotesForActiveChannel();
    Map<String, String> BTTVEmotes = getBTTVEmotesForActiveChannel();
    Map<String, String> FFZEmotes = getFFZEmotesForActiveChannel();

    // This regex catches either an existing HTML tag (Group 1)
    // OR a standalone word (Group 2)
//    Pattern pattern = Pattern.compile("(<[^>]+>)|(\\b\\w+\\b)");
    Pattern pattern = Pattern.compile("(<[^>]+>)|(:[\\w-]+:|\\b[\\w-]+\\b)");
    Matcher matcher = pattern.matcher(message);
    StringBuilder sb = new StringBuilder();

    while (matcher.find()) {
      String tag = matcher.group(1);
      String word = matcher.group(2);

      if (tag != null) {
        // Html tag, skip
        matcher.appendReplacement(sb, Matcher.quoteReplacement(tag));
      } else if (word != null) {
        if (emoteMap.containsKey(word)) {
          String dataUri = emoteMap.get(word);
          String imgTag = String.format("<img src='%s' title='%s' alt='%s'" +
                  "loading='lazy' style='vertical-align:middle;'>", dataUri, word, word);
          matcher.appendReplacement(sb, Matcher.quoteReplacement(imgTag));
        } else if (sevenTvEmotes.containsKey(word)) {
          String emotePath = sevenTvEmotes.get(word);
          String imgTag = String.format("<img src='%s' title='%s' alt='%s'" +
                  "loading='lazy' style='vertical-align:middle;'>", emotePath, word, word);
          matcher.appendReplacement(sb, Matcher.quoteReplacement(imgTag));
        } else if (BTTVEmotes.containsKey(word)) {
          String emotePath = BTTVEmotes.get(word);
          String imgTag = String.format("<img src='%s' title='%s' alt='%s'" +
                  "loading='lazy' style='vertical-align:middle;'>", emotePath, word, word);
          matcher.appendReplacement(sb, Matcher.quoteReplacement(imgTag));
        } else if (FFZEmotes.containsKey(word)) {
          String emotePath = FFZEmotes.get(word);
          String imgTag = String.format("<img src='%s' title='%s' alt='%s'" +
                  "loading='lazy' style='vertical-align:middle;'>", emotePath, word, word);
          matcher.appendReplacement(sb, Matcher.quoteReplacement(imgTag));
        } else {
          // Just normal text
          matcher.appendReplacement(sb, Matcher.quoteReplacement(word));
        }
      }
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  /**
   * Helper to load 7TV emotes for the active channel
   */
  private Map<String, String> getSevenTVEmotesForActiveChannel() {
    List<EmoteInfo> infoList = EmoteRegistry.get().getAllForChannel(activeChannelName);

    Map<String, String> emoteMap = new HashMap<>();
    for (EmoteInfo info : infoList) {
      if (info.provider() == EmoteProvider.SEVENTV) {
        emoteMap.put(info.name(), info.localPath().toUri().toString());
      }
    }
    return emoteMap;
  }

  /**
   * Helper to load BTTV emotes for the active channel
   */
  private Map<String, String> getBTTVEmotesForActiveChannel() {
    List<EmoteInfo> infoList = EmoteRegistry.get().getAllForChannel(activeChannelName);

    Map<String, String> emoteMap = new HashMap<>();
    for (EmoteInfo info : infoList) {
      if (info.provider() == EmoteProvider.BTTV) {
        emoteMap.put(info.name(), info.localPath().toUri().toString());
      }
    }
    return emoteMap;
  }

  /**
   * Helper to load FFZ emotes for the active channel
   */
  private Map<String, String> getFFZEmotesForActiveChannel() {
    List<EmoteInfo> infoList = EmoteRegistry.get().getAllForChannel(activeChannelName);

    Map<String, String> emoteMap = new HashMap<>();
    for (EmoteInfo info : infoList) {
      if (info.provider() == EmoteProvider.FFZ) {
        emoteMap.put(info.name(), info.localPath().toUri().toString());
      }
    }
    return emoteMap;
  }


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
            .replace("'", "&#x27;")
            .replace("\u034F", "")
            .replace("\u2800", "")
            .replace("\u200B", "")
            .replace("\uFEFF", "")
            .replace("\u00AD", "")
            .replace("\u2060", "")
            .replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]", "");
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

  public class JavaBridge {
    public void onChatEvent(String type, String payload) {
      Platform.runLater(() -> {
        switch (type) {
          case "link" -> {
            try {
              new ProcessBuilder("xdg-open", payload).start();
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
          case "message" -> {
            if (!payload.isBlank() && !activeChannelName.isBlank()) {
              TwitchMessageSender.send(activeChannelName, payload);
            }
          }
          default -> System.out.println("Unknown event: " + type + " | " + payload);
        }
      });
    }

    /**
     * Called by JS autocomplete — returns JSON array of emote results
     * JS calls: javaApp.searchEmotes(channel, query, maxResults)
     */
    public String searchEmotes(String channel, String query, int maxResults) {
      List<EmoteRegistry.EmoteSearchResult> results =
              EmoteRegistry.get().search(channel, query, maxResults);
      
      StringBuilder json = new StringBuilder("[");
      for (int i = 0; i < results.size(); i++) {
        EmoteRegistry.EmoteSearchResult r = results.get(i);
        if (i > 0) json.append(",");
        json.append("{")
                .append("\"name\":\"").append(escapeJson(r.name())).append("\",")
                .append("\"filePath\":\"").append(escapeJson(r.fileUri())).append("\",")
                .append("\"source\":\"").append(r.source()).append("\"")
                .append("}");
      }
      json.append("]");
      return json.toString();
    }

    private String escapeJson(String s) {
      return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
  }
}