package org.example.demo.emotes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.demo.logger.Debug;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

public class FFZEmotesDownloader extends BaseEmoteDownloader {

  private static final String GLOBAL_URL = "https://api.frankerfacez.com/v1/set/global";
  private static final String CHANNEL_URL = "https://api.frankerfacez.com/v1/room/%s";

  private static final Semaphore downloadSemaphore = new Semaphore(10);

  public void fetchGlobal() {
    Thread.startVirtualThread(() -> {
      try {
        Debug.info("Fetching FFZ global emotes...");
        String body = get(GLOBAL_URL);
        if (body == null) return;

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        Path dir = EMOTE_BASE.resolve("ffz/global");
        Files.createDirectories(dir);

        processSets(root.getAsJsonObject("sets"), dir, null, "global");
      } catch (Exception e) {
        Debug.error("Failed to fetch FFZ global emotes: {}", e.getMessage(), e);
      }
    });
  }

  public void fetchChannel(String channelName) {
    Thread.startVirtualThread(() -> {
      String channel = channelName.replace("#", "").toLowerCase();
      try {
        String url = String.format(CHANNEL_URL, channel);
        String body = get(url);
        if (body == null) return;

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        Path dir = EMOTE_BASE.resolve("ffz/" + channel);
        Files.createDirectories(dir);

        processSets(root.getAsJsonObject("sets"), dir, channel, channel);
      } catch (Exception e) {
        Debug.error("Failed to fetch FFZ emotes for {}: {}", channelName, e.getMessage(), e);
      }
    });
  }

  private void processSets(JsonObject sets, Path dir, String channel, String logName) throws InterruptedException {
    int downloaded = 0, skipped = 0;
    List<Thread> threads = new ArrayList<>();

    for (Map.Entry<String, JsonElement> setEntry : sets.entrySet()) {
      JsonObject set = setEntry.getValue().getAsJsonObject();
      if (!set.has("emoticons")) continue;

      for (JsonElement el : set.getAsJsonArray("emoticons")) {
        JsonObject emote = el.getAsJsonObject();
        String id = emote.get("id").getAsString();
        String name = emote.get("name").getAsString();
        String imageURL = pickBestUrl(emote.getAsJsonObject("urls"));

        if (imageURL == null) continue;
        
        String contentType = getContentType(imageURL);
        boolean isAnimated = contentType != null && contentType.contains("gif");
        String extension = isAnimated ? ".gif" : ".png";

        Path dest = dir.resolve(id + extension);

        if (isCached(dest)) {
          register(channel, new EmoteInfo(id, name, dest, imageURL, EmoteProvider.FFZ));
          skipped++;
          continue;
        }

        threads.add(Thread.startVirtualThread(() -> {
          try {
            downloadSemaphore.acquire();

            boolean ok = downloadFile(imageURL, dest);

            if (ok) {
              register(channel, new EmoteInfo(id, name, dest, imageURL, EmoteProvider.FFZ));
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            downloadSemaphore.release();
          }
        }));
        downloaded++;
      }
    }

    for (Thread t : threads) t.join();
    Debug.info("FFZ {}: {} downloaded, {} cached", logName, downloaded, skipped);
  }

  private String pickBestUrl(JsonObject urls) {
    // FFZ returns a map of "1", "2", "4" scale factors
    String[] priorities = {"4", "2", "1"};
    for (String p : priorities) {
      if (urls.has(p) && !urls.get(p).isJsonNull()) {
        String url = urls.get(p).getAsString();
        return url.startsWith("//") ? "https:" + url : url;
      }
    }
    return null;
  }

  private void register(String channel, EmoteInfo info) {
    if (channel == null) {
      EmoteRegistry.get().registerGlobal(info);
    } else {
      EmoteRegistry.get().registerChannel(channel, info);
    }
  }

  private String getContentType(String url) {
    try {
      HttpRequest headReq = HttpRequest.newBuilder()
              .uri(URI.create(url))
              .method("HEAD", HttpRequest.BodyPublishers.noBody())
              .timeout(java.time.Duration.ofSeconds(5))
              .build();
      HttpResponse<Void> resp = client.send(headReq, HttpResponse.BodyHandlers.discarding());
      return resp.headers().firstValue("Content-Type").orElse("");
    } catch (Exception e) {
      return "";
    }
  }

  private String get(String url) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(15))
            .build();
    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() == 404) return null;
    if (resp.statusCode() != 200) {
      Debug.warn("FFZ API returned {} for {}", resp.statusCode(), url);
      return null;
    }
    return resp.body();
  }
}