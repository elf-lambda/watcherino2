package org.example.demo.emotes;

import com.google.gson.JsonArray;
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
import java.util.concurrent.Semaphore;

public class BTTVEmotesDownloader extends BaseEmoteDownloader {

  private static final String GLOBAL_URL = "https://api.betterttv.net/3/cached/emotes/global";
  private static final String CHANNEL_URL = "https://api.betterttv.net/3/cached/users/twitch/%s";
  private static final String CDN_TEMPLATE = "https://cdn.betterttv.net/emote/%s/3x";

  private static final Semaphore downloadSemaphore = new Semaphore(10);

  public void fetchGlobal() {
    Thread.startVirtualThread(() -> {
      try {
        Debug.info("Fetching BTTV global emotes...");
        String body = get(GLOBAL_URL);
        if (body == null) return;

        JsonArray emotes = JsonParser.parseString(body).getAsJsonArray();

        Path dir = EMOTE_BASE.resolve("bttv/global");
        Files.createDirectories(dir);

        processEmoteList(emotes, dir, null, "global");
      } catch (Exception e) {
        Debug.error("Failed to fetch BTTV global emotes: {}", e.getMessage(), e);
      }
    });
  }

  public void fetchChannel(String twitchUserId, String channelName) {
    Thread.startVirtualThread(() -> {
      try {
        String url = String.format(CHANNEL_URL, twitchUserId);
        String body = get(url);
        if (body == null) return;

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String channel = channelName.replace("#", "").toLowerCase();
        Path dir = EMOTE_BASE.resolve("bttv/" + channel);
        Files.createDirectories(dir);

        JsonArray allEmotes = new JsonArray();
        if (root.has("channelEmotes")) allEmotes.addAll(root.getAsJsonArray("channelEmotes"));
        if (root.has("sharedEmotes")) allEmotes.addAll(root.getAsJsonArray("sharedEmotes"));

        processEmoteList(allEmotes, dir, channel, channel);
      } catch (Exception e) {
        Debug.error("Failed to fetch BTTV emotes for {}: {}", channelName, e.getMessage(), e);
      }
    });
  }

  private void processEmoteList(JsonArray emotes, Path dir, String channel, String logName) throws InterruptedException {
    int downloaded = 0, skipped = 0;
    List<Thread> threads = new ArrayList<>();

    for (JsonElement el : emotes) {
      JsonObject emote = el.getAsJsonObject();
      String id = emote.get("id").getAsString();
      String name = emote.get("code").getAsString();
      String url = String.format(CDN_TEMPLATE, id);
      
      String contentType = getContentType(url);
      boolean isAnimated = contentType != null && contentType.contains("gif");
      String extension = isAnimated ? ".gif" : ".png";

      Path dest = dir.resolve(id + extension);

      if (isCached(dest)) {
        register(channel, new EmoteInfo(id, name, dest, url, EmoteProvider.BTTV));
        skipped++;
        continue;
      }

      threads.add(Thread.startVirtualThread(() -> {
        try {
          downloadSemaphore.acquire();

          boolean ok;
          ok = downloadFile(url, dest);


          if (ok) {
            register(channel, new EmoteInfo(id, name, dest, url, EmoteProvider.BTTV));
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          downloadSemaphore.release();
        }
      }));
      downloaded++;
    }

    for (Thread t : threads) t.join();
    Debug.info("BTTV {}: {} downloaded, {} cached", logName, downloaded, skipped);
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
      Debug.warn("BTTV API returned {} for {}", resp.statusCode(), url);
      return null;
    }
    return resp.body();
  }
}