package org.example.demo.twitch;

import org.example.demo.logger.Debug;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// THE OS THREAD DESTROYER
public class TwitchManager {

  // channel name -> client
  private final Map<String, TwitchClient> clients = new ConcurrentHashMap<>();

  private final Consumer<TwitchMessage> globalOnMessage;
  private final ExecutorService connectionPool = Executors.newCachedThreadPool();

  public TwitchManager(Consumer<TwitchMessage> globalOnMessage) {
    this.globalOnMessage = globalOnMessage;
  }

  public void joinChannel(String channel) {
    String key = channel.toLowerCase().replace("#", "");
    if (clients.containsKey(key)) return;

    TwitchClient client = new TwitchClient(key);
    client.setOnMessage(msg -> {
      if (globalOnMessage != null) globalOnMessage.accept(msg);
    });

    clients.put(key, client);

    // Run the connection logic in a background thread
    connectionPool.submit(() -> {
      try {
        client.connect();
        Debug.info("Joined channel: " + key);
      } catch (IOException e) {
        Debug.error("Failed to join " + key + ": " + e.getMessage());
        clients.remove(key);
      }
    });
  }

  public void leaveChannel(String channel) {
    String key = channel.toLowerCase().replace("#", "");
    TwitchClient client = clients.remove(key);
    if (client != null) {
      // Stop the client in the background to avoid blocking the UI
      // if the socket takes time to close
      connectionPool.submit(client::stop);
    }
  }

  public void stopAll() {
    clients.values().forEach(client -> connectionPool.submit(client::stop));
    clients.clear();
    connectionPool.shutdown();
  }

  public TwitchRingBuffer getBuffer(String channel) {
    String key = channel.toLowerCase().replace("#", "");
    TwitchClient client = clients.get(key);
    return client != null ? client.getBuffer() : null;
  }

  public boolean isConnected(String channel) {
    String key = channel.toLowerCase().replace("#", "");
    TwitchClient client = clients.get(key);
    return client != null && client.isConnected();
  }

}