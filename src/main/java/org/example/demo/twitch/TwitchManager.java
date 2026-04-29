package org.example.demo.twitch;

import org.example.demo.emotes.BTTVEmotesDownloader;
import org.example.demo.emotes.FFZEmotesDownloader;
import org.example.demo.emotes.SEVENTVEmotesDownloader;
import org.example.demo.logger.Debug;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// THE OS THREAD DESTROYER
public class TwitchManager {

  private static final ExecutorService emoteFetchExecutor = Executors.newSingleThreadExecutor();
  // channel name -> client
  private final Map<String, TwitchClient> clients = new ConcurrentHashMap<>();
  private final HttpClient httpClient = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();
  private final Consumer<TwitchMessage> globalOnMessage;
  private final ExecutorService connectionPool = Executors.newCachedThreadPool();
  private final SEVENTVEmotesDownloader sevenTV = new SEVENTVEmotesDownloader();
  private final BTTVEmotesDownloader bttv = new BTTVEmotesDownloader();
  private final FFZEmotesDownloader ffz = new FFZEmotesDownloader();


  public TwitchManager(Consumer<TwitchMessage> globalOnMessage) {
    this.globalOnMessage = globalOnMessage;

  }

  /**
   * Fetches the numeric Twitch User ID (Room ID)
   */
  private String fetchTwitchId(String username) {
    String key = username.toLowerCase().replace("#", "");
    TwitchClient client = clients.get(key);
    return client != null ? client.getRoomId() : "";

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

        // TODO: Rework at some point :^)
        int attempts = 0;
        while (client.getRoomId().isBlank() && attempts < 20) {
          Thread.sleep(250);
          attempts++;
        }

        String twitchId = client.getRoomId();
        if (!twitchId.isBlank()) {
          emoteFetchExecutor.submit(() -> {
            try {
              sevenTV.fetchChannel(twitchId, key);
              bttv.fetchChannel(twitchId, key);
              ffz.fetchChannel(key);
            } catch (Exception e) {
              Debug.error("Emote fetch failed for " + key + ": " + e.getMessage());
            }
          });
        } else {
          Debug.warn("Could not get room-id for {} after waiting, emotes may not load", key);
        }
      } catch (IOException e) {
        Debug.error("Failed to join " + key + ": " + e.getMessage());
        clients.remove(key);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
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