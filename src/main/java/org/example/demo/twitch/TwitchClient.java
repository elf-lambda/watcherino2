package org.example.demo.twitch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class TwitchClient {

  private static final String SERVER = "irc.chat.twitch.tv";
  private static final int PORT = 6667;
  private static final int BUFFER_SIZE = 512;
  private static final String[] DEFAULT_COLORS = {
          "#e74c3c", "#3498db", "#2ecc71", "#9b59b6", "#e67e22",
          "#1abc9c", "#f1c40f", "#95a5a6", "#e91e63", "#00bcd4"
  };
  // Light
//  private static final String[] DEFAULT_COLORS = {
//          "#c0392b", "#2471a3", "#1e8449", "#6c3483", "#d35400",
//          "#117a65", "#b7770d", "#1a252f", "#922b21", "#0e6655"
//  };
  private static final int SOCKET_TIMEOUT_MS = 60_000;
  private static final int PING_INTERVAL_MS = 30_000;
  private final String channel;
  private final String username;
  private final TwitchRingBuffer messageBuffer = new TwitchRingBuffer(BUFFER_SIZE);
  private final BlockingQueue<TwitchMessage> messageQueue = new LinkedBlockingQueue<>(100);
  private volatile boolean pingRunning = false;
  private Thread pingThread;
  private Socket socket;
  private volatile boolean running = false;
  private volatile boolean connected = false;
  private Thread listenerThread;
  // Called on every new message, set by TwitchManager
  private Consumer<TwitchMessage> onMessage;


  public TwitchClient(String channel) {
    this.channel = channel.startsWith("#") ? channel : "#" + channel;
    this.username = "justinfan" + (1000 + new Random().nextInt(8999));
  }

  public void setOnMessage(Consumer<TwitchMessage> onMessage) {
    this.onMessage = onMessage;
  }

  private void startPingThread() {
    if (pingThread != null && pingThread.isAlive()) {
      pingThread.interrupt();
      try {
        pingThread.join(1000);
      } catch (InterruptedException ignored) {
      }
    }

    pingRunning = true;
    pingThread = new Thread(() -> {
      while (running && pingRunning) {
        try {
          Thread.sleep(PING_INTERVAL_MS);
          if (socket != null && !socket.isClosed()) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println("PING :tmi.twitch.tv");
          }
        } catch (InterruptedException e) {
          return; // cleanly killed
        } catch (Exception e) {
          if (!running) return;
        }
      }
    }, "twitch-ping-" + channel);
    pingThread.setDaemon(true);
    pingThread.start();
    System.out.println("Ping started for " + channel);
  }

  public void connect() throws IOException {
    socket = new Socket(SERVER, PORT);
    socket.setSoTimeout(SOCKET_TIMEOUT_MS);
    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

    out.println("CAP REQ :twitch.tv/tags twitch.tv/commands");
    out.println("NICK " + username);
    out.println("JOIN " + channel);

    connected = true;
    running = true;

    listenerThread = new Thread(this::listen, "twitch-" + channel);
    listenerThread.setDaemon(true);
    listenerThread.start();

    startPingThread();
  }

  private void listen() {
    while (running) {
      try {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream())
        );
        String line;
        while ((line = reader.readLine()) != null && running) {
          if (line.startsWith("PING")) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println("PONG :tmi.twitch.tv");
            continue;
          }
          handleLine(line);
        }
      } catch (java.net.SocketTimeoutException e) {
        if (!running) return;
        connected = false;
        System.err.println("Socket timeout on " + channel + ", reconnecting...");
        reconnect();
      } catch (IOException e) {
        if (!running) return;
        connected = false;
        System.err.println("Connection lost on " + channel + ", reconnecting...");
        reconnect();
      }
    }
  }

  private void reconnect() {
    while (running) {
      try {
        Thread.sleep(5000);
        socket = new Socket(SERVER, PORT);
        socket.setSoTimeout(60_000);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        out.println("CAP REQ :twitch.tv/tags twitch.tv/commands");
        out.println("NICK " + username);
        out.println("JOIN " + channel);
        connected = true;
        System.out.println("Reconnected to " + channel);
        startPingThread(); // <-- restart ping for this channel
        return;
      } catch (Exception e) {
        System.err.println("Reconnect failed for " + channel + ", retrying...");
      }
    }
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

    // Parse badges tag — "broadcaster/1,moderator/1,vip/1" etc
    String badges = tags.getOrDefault("badges", "");
    msg.isStreamer = badges.contains("broadcaster");
    msg.isModerator = badges.contains("moderator");
    msg.isVIP = badges.contains("vip");

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

  // Color utilities

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

      // If too dark for dark background, lighten it
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

  // Light
//  private String adjustColorForLight(String hex) {
//    try {
//      hex = hex.replace("#", "");
//      if (hex.length() != 6) return "#333333";
//
//      int r = Integer.parseInt(hex.substring(0, 2), 16);
//      int g = Integer.parseInt(hex.substring(2, 4), 16);
//      int b = Integer.parseInt(hex.substring(4, 6), 16);
//
//      // Darken by 40% toward black
//      r = (int) (r * 0.6);
//      g = (int) (g * 0.6);
//      b = (int) (b * 0.6);
//
//      // If it's still too light for a light background, darken more
//      double luminance = 0.299 * r + 0.587 * g + 0.114 * b;
//      if (luminance > 140) {
//        r = (int) (r * 0.5);
//        g = (int) (g * 0.5);
//        b = (int) (b * 0.5);
//      }
//
//      return String.format("#%02x%02x%02x", r, g, b);
//    } catch (Exception e) {
//      return "#333333";
//    }
//  }


  private String getDefaultColor(String username) {
    int hash = username.chars().sum();
    return DEFAULT_COLORS[hash % DEFAULT_COLORS.length];
  }

  public void stop() {
    running = false;
    pingRunning = false;
    if (pingThread != null) pingThread.interrupt();
    connected = false;
    try {
      if (socket != null) socket.close();
    } catch (IOException ignored) {
    }
    // To kill ping threads TODO: rework with virutal threads?
    try {
      if (pingThread != null) pingThread.join(2000);
      if (listenerThread != null) listenerThread.join(2000);
    } catch (InterruptedException ignored) {
    }
    System.out.println("Stopped " + channel);
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