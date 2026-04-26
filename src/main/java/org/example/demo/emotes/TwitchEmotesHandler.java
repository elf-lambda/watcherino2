package org.example.demo.emotes;

import org.example.demo.logger.Debug;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TwitchEmotesHandler {

  private static final Path EMOTE_DIR = Paths.get(
          System.getProperty("user.home"), ".config", "watcherino", "emotes", "twitch"
  );

  private static final String CDN = "https://static-cdn.jtvnw.net/emoticons/v2/%s/default/dark/2.0";

  private static final HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  // In-memory cache: emoteId -> local path
  private static final Map<String, Path> cache = new ConcurrentHashMap<>();

  /**
   * Parses the IRC emotes tag and downloads any emotes not yet cached
   * Returns a map of emoteId -> local file Path.
   */
  public static Map<String, Path> processEmoteTag(String emoteTag) {
    Map<String, Path> result = new ConcurrentHashMap<>();

    if (emoteTag == null || emoteTag.isBlank()) return result;

    for (String group : emoteTag.split("/")) {
      String[] parts = group.split(":", 2);
      if (parts.length < 2) continue;

      String emoteId = parts[0];
      Path localPath = getOrDownload(emoteId);
      if (localPath != null) {
        result.put(emoteId, localPath);
      }
    }

    return result;
  }

  /**
   * Returns the local path for an emote, downloading it if needed
   * Checks for both .png and .gif extensions.
   */
  public static Path getOrDownload(String emoteId) {
    if (cache.containsKey(emoteId)) {
      return cache.get(emoteId);
    }

    for (String ext : new String[]{".png", ".gif"}) {
      Path path = EMOTE_DIR.resolve(emoteId + ext);
      if (Files.exists(path)) {
        cache.put(emoteId, path);
        return path;
      }
    }

    return download(emoteId);
  }

  private static Path download(String emoteId) {
    try {
      Files.createDirectories(EMOTE_DIR);

      String url = String.format(CDN, emoteId);
      HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(10))
              .build();

      HttpResponse<InputStream> response = client.send(
              request, HttpResponse.BodyHandlers.ofInputStream()
      );

      if (response.statusCode() == 200) {
        // Get extension from Content-Type header
        String contentType = response.headers().firstValue("Content-Type").orElse("image/png");
        String extension = contentType.toLowerCase().contains("gif") ? ".gif" : ".png";

        Path dest = EMOTE_DIR.resolve(emoteId + extension);

        Files.copy(response.body(), dest, StandardCopyOption.REPLACE_EXISTING);
        cache.put(emoteId, dest);

        Debug.info("Downloaded Twitch emote: {} (Type: {})", emoteId, extension);
        return dest;
      } else {
        Debug.warn("Failed to download emote {}: HTTP {}", emoteId, response.statusCode());
        return null;
      }

    } catch (Exception e) {
      Debug.warn("Error downloading emote {}: {}", emoteId, e.getMessage());
      return null;
    }
  }

  /**
   * Parses the emotes tag into a structured list of EmoteOccurrence records.
   */
  public static List<EmoteOccurrence> parseOccurrences(String emoteTag, Map<String,
          Path> paths) {
    List<EmoteOccurrence> result = new ArrayList<>();

    if (emoteTag == null || emoteTag.isBlank()) return result;

    for (String group : emoteTag.split("/")) {
      String[] parts = group.split(":", 2);
      if (parts.length < 2) continue;

      String emoteId = parts[0];
      Path path = paths.get(emoteId);
      if (path == null) continue;

      for (String posStr : parts[1].split(",")) {
        String[] range = posStr.split("-");
        if (range.length != 2) continue;

        try {
          int start = Integer.parseInt(range[0]);
          int end = Integer.parseInt(range[1]);
          result.add(new EmoteOccurrence(emoteId, path, start, end));
        } catch (NumberFormatException ignored) {
        }
      }
    }

    result.sort(Comparator.comparingInt(e -> e.start()));
    return result;
  }

  public record EmoteOccurrence(String emoteId, Path localPath, int start, int end) {
  }
}