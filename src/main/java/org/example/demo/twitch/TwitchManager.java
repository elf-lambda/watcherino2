package org.example.demo.twitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.example.demo.emotes.SEVENTVEmotesDownloader;
import org.example.demo.logger.Debug;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// THE OS THREAD DESTROYER
public class TwitchManager {

  // channel name -> client
  private final Map<String, TwitchClient> clients = new ConcurrentHashMap<>();

  private final HttpClient httpClient = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  private final Consumer<TwitchMessage> globalOnMessage;
  private final ExecutorService connectionPool = Executors.newCachedThreadPool();

  public TwitchManager(Consumer<TwitchMessage> globalOnMessage) {
    this.globalOnMessage = globalOnMessage;

  }

  /**
   * Fetches the numeric Twitch User ID using the IVR API.
   * This avoids needing a Twitch Client-ID/Secret for simple lookups.
   */
  private String fetchTwitchId(String username) {
    try {
      HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create("https://api.ivr.fi/v2/twitch/user?login=" + username))
              .header("User-Agent", "EmoteDownloader/1.0") // Good practice
              .GET()
              .build();

      HttpResponse<String> response = httpClient.send(request,
              HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
        if (array.size() > 0) {
          return array.get(0).getAsJsonObject().get("id").getAsString();
        }
      }
    } catch (Exception e) {
      Debug.error("Error fetching Twitch ID for {}: {}", username, e.getMessage());
    }
    return null;
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

        String twitchId = fetchTwitchId(key);
        if (twitchId != null) {
          SEVENTVEmotesDownloader sevenTV = new SEVENTVEmotesDownloader();
          sevenTV.fetchChannel(twitchId, key);
        } else {
          Debug.warn("Could not fetch ID for {}, 7TV emotes might not load.", key);
        }
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