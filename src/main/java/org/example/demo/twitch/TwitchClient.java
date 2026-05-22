package org.example.demo.twitch;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.example.demo.logger.Debug;
import org.example.demo.settings.SettingsController;
import org.example.demo.tts.AudioPlayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

public class TwitchClient {

  private static final String SERVER = "irc.chat.twitch.tv";
  private static final int PORT = 6667;
  private static final int BUFFER_SIZE = 128;
  private static final String[] DEFAULT_COLORS = {
          "#e74c3c", "#3498db", "#2ecc71", "#9b59b6", "#e67e22",
          "#1abc9c", "#f1c40f", "#95a5a6", "#e91e63", "#00bcd4"
  };
  // Light theme colors (unused for now)
//  private static final String[] DEFAULT_COLORS = {
//          "#c0392b", "#2471a3", "#1e8449", "#6c3483", "#d35400",
//          "#117a65", "#b7770d", "#1a252f", "#922b21", "#0e6655"
//  };
  private static final int SOCKET_TIMEOUT_MS = 60_000;
  private static final int PING_INTERVAL_MS = 30_000;

  private final String channel;
  private final String username;
  private final TwitchRingBuffer messageBuffer = new TwitchRingBuffer(BUFFER_SIZE);
  private final List<String> filterList = new java.util.concurrent.CopyOnWriteArrayList<>();

  private String roomId = "";
  private volatile boolean running = false;
  private volatile boolean connected = false;
  private volatile boolean pingRunning = false;

  // These are replaced as a unit inside openSocket(), which is the only place
  // that touches them. listen() and the ping thread snapshot them into locals
  // so they don't hold a stale ref if reconnect() swaps them mid-flight
  private Socket socket;
  private PrintWriter writer;
  private BufferedReader reader;

  private Thread listenerThread;
  private Thread pingThread;

  // Set by TwitchManager after construction
  private Consumer<TwitchMessage> onMessage;


  public TwitchClient(String channel) {
    this.channel = channel.startsWith("#") ? channel : "#" + channel;
    this.username = "justinfan" + (1000 + new Random().nextInt(8999));
    setFilters(SettingsController.filterWords);
  }

  public String getRoomId() {
    return roomId;
  }

  public void setFilters(ObservableList<String> sourceList) {
    filterList.clear();
    filterList.addAll(sourceList);

    sourceList.addListener((ListChangeListener<String>) c -> {
      filterList.clear();
      filterList.addAll(sourceList);
      Debug.info("Filters synchronized: " + filterList);
    });
  }

  public void setOnMessage(Consumer<TwitchMessage> onMessage) {
    this.onMessage = onMessage;
  }

  public void connect() throws IOException {
    running = true;
    openSocket();

    listenerThread = new Thread(this::listen, "twitch-" + channel);
    listenerThread.setDaemon(true);
    listenerThread.start();

    startPingThread();
  }

  // Opens (or reopens) the socket and wires up a fresh reader/writer pair
  // Always close the old socket first so we don't leak file descriptors
  private void openSocket() throws IOException {
    closeSocket();

    socket = new Socket(SERVER, PORT);
    socket.setSoTimeout(SOCKET_TIMEOUT_MS);

    writer = new PrintWriter(socket.getOutputStream(), true);
    reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

    writer.println("CAP REQ :twitch.tv/tags twitch.tv/commands");
    writer.println("NICK " + username);
    writer.println("JOIN " + channel);

    connected = true;
    Debug.info("Connected to " + channel);
  }

  private void closeSocket() {
    connected = false;
    try {
      if (reader != null) reader.close();
    } catch (IOException ignored) {
    }
    try {
      if (writer != null) writer.close();
    } catch (Exception ignored) {
    }
    try {
      if (socket != null && !socket.isClosed()) socket.close();
    } catch (IOException ignored) {
    }
    reader = null;
    writer = null;
  }

  private void listen() {
    while (running) {
      try {
        // Snapshot to a local reconnect() can swap the field while we're
        // blocked on readLine() and we don't want to suddenly read from null
        BufferedReader localReader = reader;
        if (localReader == null) {
          Thread.sleep(100);
          continue;
        }

        String line = localReader.readLine();

        if (line == null) {
          if (!running) return;
          Debug.info("Stream EOF on " + channel + ", reconnecting...");
          reconnect();
          continue;
        }

        if (line.startsWith("PING")) {
          PrintWriter w = writer;
          if (w != null) w.println("PONG :tmi.twitch.tv");
          continue;
        }

        handleLine(line);

      } catch (java.net.SocketTimeoutException e) {
        if (!running) return;
        connected = false;
        Debug.error("Socket timeout on " + channel + ", reconnecting...");
        reconnect();
      } catch (IOException e) {
        if (!running) return;
        connected = false;
        Debug.error("Connection lost on " + channel + ": " + e.getMessage());
        reconnect();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void reconnect() {
    int attempt = 0;
    while (running) {
      attempt++;
      // 2s / 4s / 8s / 16s / 30s max
      long delay = Math.min(2000L * (1L << Math.min(attempt - 1, 4)), 30_000L);
      try {
        Thread.sleep(delay);
        openSocket();
        Debug.info("Reconnected to " + channel + " (attempt " + attempt + ")");
        return;
      } catch (IOException e) {
        Debug.error("Reconnect attempt " + attempt + " failed for " + channel + ": " + e.getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void startPingThread() {
    stopPingThread();

    pingRunning = true;
    pingThread = new Thread(() -> {
      while (running && pingRunning) {
        try {
          Thread.sleep(PING_INTERVAL_MS);
          PrintWriter w = writer;
          if (w != null && !w.checkError()) {
            w.println("PING :tmi.twitch.tv");
          }
        } catch (InterruptedException e) {
          return;
        } catch (Exception e) {
          if (!running) return;
        }
      }
    }, "twitch-ping-" + channel);
    pingThread.setDaemon(true);
    pingThread.start();
    Debug.info("Ping started for " + channel);
  }

  private void stopPingThread() {
    pingRunning = false;
    if (pingThread != null) {
      pingThread.interrupt();
      try {
        pingThread.join(1000);
      } catch (InterruptedException ignored) {
      }
      pingThread = null;
    }
  }

  public void stop() {
    running = false;
    stopPingThread();
    closeSocket();
    if (listenerThread != null) {
      listenerThread.interrupt();
      try {
        if (pingThread != null) pingThread.join(2000);
        listenerThread.join(2000);
      } catch (InterruptedException ignored) {
      }
    }
    Debug.info("Stopped " + channel);
  }

  private void handleLine(String data) {
    TwitchMessage msg = null;

    if (data.contains(" PRIVMSG ")) {
      msg = parsePrivMsg(data);
    } else if (data.contains(" CLEARCHAT ")) {
      msg = parseClearChat(data);
      msg.isSystemMessage = true;
    } else if (data.contains(" USERNOTICE ")) {
      msg = parseUserNotice(data);
      msg.isSystemMessage = true;
    } else if (data.contains(" ROOMSTATE ")) {
      parseRoomState(data);
      return;
    }

    if (msg != null) {
      messageBuffer.add(msg);
      if (onMessage != null) onMessage.accept(msg);
    }
  }

  private Map<String, String> parseTags(String data) {
    Map<String, String> tags = new HashMap<>();
    if (!data.startsWith("@")) return tags;
    int spaceIdx = data.indexOf(' ');
    if (spaceIdx == -1) return tags;
    String tagStr = data.substring(1, spaceIdx);
    for (String tag : tagStr.split(";")) {
      String[] kv = tag.split("=", 2);
      if (kv.length == 2) tags.put(kv[0], kv[1]);
    }
    return tags;
  }

  private String stripTags(String data) {
    if (!data.startsWith("@")) return data;
    int spaceIdx = data.indexOf(' ');
    return spaceIdx == -1 ? data : data.substring(spaceIdx + 1);
  }

  private void parseRoomState(String data) {
    Map<String, String> tags = parseTags(data);
    String id = tags.get("room-id");
    if (id != null && !id.isBlank()) {
      roomId = id;
    }
  }

  private TwitchMessage parsePrivMsg(String data) {
    Map<String, String> tags = parseTags(data);
    String payload = stripTags(data);

    String[] parts = payload.split(" PRIVMSG ", 2);
    if (parts.length < 2) return null;

    String username = tags.getOrDefault("display-name", "");
    if (username.isEmpty()) {
      int bang = parts[0].indexOf('!');
      username = bang != -1 ? parts[0].substring(1, bang) : "unknown";
    }

    String[] contentParts = parts[1].split(" :", 2);
    if (contentParts.length < 2) return null;
    String content = contentParts[1];

    String color = tags.getOrDefault("color", "");
    String userColor = color.isEmpty() ? getDefaultColor(username) : adjustColorForDark(color);

    TwitchMessage msg = new TwitchMessage(username, content, channel, userColor, tags, false);

    // "broadcaster/1,moderator/1,vip/1" etc
    String badges = tags.getOrDefault("badges", "");
    msg.isStreamer = badges.contains("broadcaster");
    msg.isModerator = badges.contains("moderator");
    msg.isVIP = badges.contains("vip");

    // TODO: rework, might be too slow for high-volume channels
    String lowerContent = content.toLowerCase();
    for (String word : filterList) {
      if (word.length() > lowerContent.length()) continue;
      if (lowerContent.contains(word.toLowerCase())) {
        msg.isHighlighted = true;
        Thread.startVirtualThread(() -> {
          Path configBase = Path.of(System.getProperty("user.home"), ".config", "watcherino",
                  "tts");
          AudioPlayer.playWav(configBase + "/ding.wav", 0.1f);
        });
        break;
      }
    }
    return msg;
  }

  private TwitchMessage parseClearChat(String data) {
    Map<String, String> tags = parseTags(data);
    String payload = stripTags(data);

    String[] parts = payload.split(" CLEARCHAT ", 2);
    if (parts.length < 2) return null;

    String remaining = parts[1];
    String target = "";
    if (remaining.contains(" :")) {
      target = remaining.split(" :", 2)[1];
    }

    String content;
    if (tags.containsKey("ban-duration")) {
      content = "[TIMEOUT] " + target + " for " + tags.get("ban-duration") + "s";
    } else if (!target.isEmpty()) {
      content = "[BAN] " + target;
    } else {
      content = "[CLEARED] Chat was cleared by a moderator";
    }

    return new TwitchMessage("<SYSTEM>", content, channel, "#cc0000", tags, true);
  }

  private TwitchMessage parseUserNotice(String data) {
    Map<String, String> tags = parseTags(data);
    String payload = stripTags(data);

    String systemMsg = tags.getOrDefault("system-msg", "")
            .replace("\\s", " ")
            .replace("\\n", "")
            .replace("\\r", "");

    String[] parts = payload.split(" USERNOTICE ", 2);
    String userContent = "";
    if (parts.length >= 2 && parts[1].contains(" :")) {
      userContent = parts[1].split(" :", 2)[1];
    }

    String content = userContent.isEmpty()
            ? "✨ " + systemMsg
            : "✨ " + systemMsg + ": " + userContent;

    String username = tags.getOrDefault("display-name", tags.getOrDefault("login", ""));

    return new TwitchMessage(username, content, channel, "#b8860b", tags, true);
  }

  private String adjustColorForDark(String hex) {
    try {
      hex = hex.replace("#", "");
      if (hex.length() != 6) return "#cccccc";

      int r = Integer.parseInt(hex.substring(0, 2), 16);
      int g = Integer.parseInt(hex.substring(2, 4), 16);
      int b = Integer.parseInt(hex.substring(4, 6), 16);

      // Lighten colors that are too dark to read on a dark background
      double luminance = 0.299 * r + 0.587 * g + 0.114 * b;
      if (luminance < 80) {
        r = (int) (r + (255 - r) * 0.5);
        g = (int) (g + (255 - g) * 0.5);
        b = (int) (b + (255 - b) * 0.5);
      }

      return String.format("#%02x%02x%02x", r, g, b);
    } catch (Exception e) {
      return "#cccccc";
    }
  }

  private String getDefaultColor(String username) {
    int hash = username.chars().sum();
    return DEFAULT_COLORS[hash % DEFAULT_COLORS.length];
  }

  public boolean isConnected() {
    return connected;
  }

  public String getChannel() {
    return channel;
  }

  public TwitchRingBuffer getBuffer() {
    return messageBuffer;
  }
}