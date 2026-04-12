package org.example.demo.twitch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.scene.control.ListView;
import org.example.demo.tts.AudioPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

public class TwitchLiveChecker {
  private final HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();
  private final ListView<TwitchChannel> channelList;
  private final BiConsumer<String, Boolean> onStatusUpdate; // channel -> isLive
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final ConcurrentHashMap<String, Boolean> previousLiveState = new ConcurrentHashMap<>();
  private final LinkedBlockingQueue<String> soundQueue = new LinkedBlockingQueue<>();

  public TwitchLiveChecker(ListView<TwitchChannel> channelList,
                           BiConsumer<String, Boolean> onStatusUpdate) {
    this.channelList = channelList;
    this.onStatusUpdate = onStatusUpdate;
    startSoundWorker();
  }

  // Drains the queue one sound at a time
  private void startSoundWorker() {
    Thread worker = new Thread(() -> {
      while (true) {
        try {
          String channel = soundQueue.take();
          AudioPlayer.playWav("./tts/" + channel + ".wav", 0.1f);
          System.out.println("🔔 " + channel + " went live!");
        } catch (InterruptedException e) {
          return;
        }
      }
    }, "sound-worker");
    worker.setDaemon(true);
    worker.start();
  }

  public void start() {
    // Wait 3 seconds before start
    scheduler.scheduleAtFixedRate(this::checkAll, 3, 60, TimeUnit.SECONDS);
  }

  public void stop() {
    scheduler.shutdownNow();
  }

  private void checkAll() {
    List<TwitchChannel> channels = new ArrayList<>(channelList.getItems());

    for (TwitchChannel ch : channels) {
      Thread.startVirtualThread(() -> {
        boolean live = isLive(ch.getName());

        // Get previous state, default false
        boolean wasPreviouslyLive = previousLiveState.getOrDefault(ch.getName(), false);

        previousLiveState.put(ch.getName(), live);

        if (live && !wasPreviouslyLive) {
          soundQueue.offer(ch.getName());
        }

        // Notify UI if state changed
        if (ch.isLive() != live) {
          Platform.runLater(() -> onStatusUpdate.accept(ch.getName(), live));
        }
      });
    }
  }

  public boolean isLive(String channel) {
    channel = channel.replaceFirst("^#", "");


    String query = "{\"query\":\"{ user(login:\\\"" + channel + "\\\") { stream { id } } }\"}";

    HttpRequest request = createGqlRequest(query);

    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      JsonObject user = getUserObject(response.body());

      if (user == null) return false;

      boolean live = !user.get("stream").isJsonNull();
//      System.out.println(channel + " is live: " + live);
      return live;
    } catch (Exception e) {
      System.out.println("Failed isLive with exception " + e);
      return false;
    }
  }

  public int getViewers(String channel) {
    channel = channel.replaceFirst("^#", "");
    String query = "{\"query\":\"{ user(login:\\\"" + channel + "\\\") { stream { viewersCount } " +
            "} }\"}";

    HttpRequest request = createGqlRequest(query);

    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      JsonObject user = getUserObject(response.body());

      // If user is offline the "stream" object is null in Twitchs GQL
      if (user == null || user.get("stream").isJsonNull()) {
        return 0;
      }

      return user.getAsJsonObject("stream").get("viewersCount").getAsInt();
    } catch (Exception e) {
      System.err.println("Failed to get viewers for " + channel + ": " + e.getMessage());
      return 0;
    }
  }

  /**
   * Helper to build the GQL request with required headers
   */
  private HttpRequest createGqlRequest(String query) {
    return HttpRequest.newBuilder()
            .uri(URI.create("https://gql.twitch.tv/gql"))
            .header("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(query))
            .timeout(Duration.ofSeconds(10))
            .build();
  }

  /**
   * Helper to safely navigate the Twitch GQL response tree
   */
  private JsonObject getUserObject(String responseBody) {
    try {
      JsonElement rootElement = JsonParser.parseString(responseBody);
      if (!rootElement.isJsonObject()) return null;

      JsonObject root = rootElement.getAsJsonObject();
      if (!root.has("data") || root.get("data").isJsonNull()) return null;

      JsonObject data = root.getAsJsonObject("data");
      if (!data.has("user") || data.get("user").isJsonNull()) return null;

      return data.getAsJsonObject("user");
    } catch (Exception e) {
      return null;
    }
  }

  public void setSoundConfig(String ttsExecutablePath, float volume) {
    //TODO: implement
  }
}