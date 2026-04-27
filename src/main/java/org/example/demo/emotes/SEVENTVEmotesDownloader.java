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

public class SEVENTVEmotesDownloader extends BaseEmoteDownloader {

  private static final String GLOBAL_URL = "https://7tv.io/v3/emote-sets/global";
  private static final String CHANNEL_URL = "https://7tv.io/v3/users/twitch/%s";

  private static final Semaphore downloadSemaphore = new Semaphore(10);

  public void fetchGlobal() {
    Thread.startVirtualThread(() -> {
      try {
        Debug.info("Fetching 7TV global emotes...");
        String body = get(GLOBAL_URL);
        if (body == null) return;

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray emotes = root.getAsJsonArray("emotes");

        Path dir = EMOTE_BASE.resolve("7tv/global");
        Files.createDirectories(dir);

        int downloaded = 0, skipped = 0;
        List<Thread> threads = new ArrayList<>();

        for (JsonElement el : emotes) {
          JsonObject emote = el.getAsJsonObject();
          String id = emote.get("id").getAsString();
          String name = emote.get("name").getAsString();

          ImageCandidate img = pickImage(emote);
          if (img == null) continue;

          Path dest = dir.resolve(id + img.extension());

          if (isCached(dest)) {
            EmoteRegistry.get().registerGlobal(
                    new EmoteInfo(id, name, dest, img.url(), EmoteProvider.SEVENTV)
            );
            skipped++;
            continue;
          }

          threads.add(Thread.startVirtualThread(() -> {
            try {
              // Acquire a permit before starting the download
              downloadSemaphore.acquire();

              boolean ok = downloadFile(img.url(), dest);
              if (ok) {
                EmoteRegistry.get().registerGlobal(
                        new EmoteInfo(id, name, dest, img.url(), EmoteProvider.SEVENTV)
                );
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              // Always release the permit so the next thread can start
              downloadSemaphore.release();
            }
          }));
          downloaded++;
        }

        // Wait for all throttled downloads to finish
        for (Thread t : threads) t.join();
        Debug.info("7TV global: {} downloaded, {} cached", downloaded, skipped);

      } catch (Exception e) {
        Debug.error("Failed to fetch 7TV global emotes: {}", e.getMessage(), e);
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
        if (!root.has("emote_set") || root.get("emote_set").isJsonNull()) return;

        JsonArray emotes = root.getAsJsonObject("emote_set").getAsJsonArray("emotes");

        String channel = channelName.replace("#", "").toLowerCase();
        Path dir = EMOTE_BASE.resolve("7tv/" + channel);
        Files.createDirectories(dir);

        List<Thread> threads = new ArrayList<>();

        for (JsonElement el : emotes) {
          JsonObject emote = el.getAsJsonObject();
          String id = emote.get("id").getAsString();
          String name = emote.get("name").getAsString();

          ImageCandidate img = pickImage(emote);
          if (img == null) continue;

          Path dest = dir.resolve(id + img.extension());

          if (isCached(dest)) {
            EmoteRegistry.get().registerChannel(channel,
                    new EmoteInfo(id, name, dest, img.url(), EmoteProvider.SEVENTV)
            );
            continue;
          }

          threads.add(Thread.startVirtualThread(() -> {
            try {
              downloadSemaphore.acquire();

              boolean ok = downloadFile(img.url(), dest);
              if (ok) {
                EmoteRegistry.get().registerChannel(channel,
                        new EmoteInfo(id, name, dest, img.url(), EmoteProvider.SEVENTV)
                );
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              downloadSemaphore.release();
            }
          }));
        }

        for (Thread t : threads) t.join();
        Debug.info("7TV channel {} emotes loaded", channel);

      } catch (Exception e) {
        Debug.error("Failed to fetch 7TV emotes for {}: {}", channelName, e.getMessage(), e);
      }
    });
  }

  private ImageCandidate pickImage(JsonObject emote) {
    try {
      JsonObject data = emote.has("data") ? emote.getAsJsonObject("data") : emote;
      JsonObject host = data.getAsJsonObject("host");
      String baseUrl = "https:" + host.get("url").getAsString();
      JsonArray files = host.getAsJsonArray("files");

      boolean isAnimated = data.has("animated") && data.get("animated").getAsBoolean();
      String extension = isAnimated ? ".gif" : ".png";

      String fileName = "";
      for (JsonElement f : files) {
        JsonObject fileObj = f.getAsJsonObject();
        String format = fileObj.get("format").getAsString().toLowerCase();

        if (isAnimated && format.equals("gif")) {
          fileName = fileObj.get("name").getAsString();
        } else if (!isAnimated && format.equals("png")) {
          fileName = fileObj.get("name").getAsString();
        }
      }

      if (fileName.isEmpty() && files.size() > 0) {
        JsonObject lastFile = files.get(files.size() - 1).getAsJsonObject();
        fileName = lastFile.get("name").getAsString();
        String actualFormat = lastFile.get("format").getAsString().toLowerCase();
        if (actualFormat.equals("webp")) {
          extension = isAnimated ? ".gif" : ".png";
        } else {
          extension = "." + actualFormat;
        }
      }

      return new ImageCandidate(baseUrl + "/" + fileName, extension);
    } catch (Exception e) {
      Debug.warn("Could not pick image for emote: {}", e.getMessage());
    }
    return null;
  }

  private String get(String url) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(15))
            .build();
    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      Debug.warn("7TV API returned {} for {}", resp.statusCode(), url);
      return null;
    }
    return resp.body();
  }

  private record ImageCandidate(String url, String extension) {
  }
}