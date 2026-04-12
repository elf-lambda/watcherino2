package org.example.demo.emoji;

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
import java.util.List;

public class EmojiDownloader {

  private static final Path EMOJI_DIR = Paths.get(
          System.getProperty("user.home"), ".config", "watcherino", "emoji"
  );
  private static final String CDN = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@latest/assets" +
          "/72x72/";
  private static final HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  /**
   * Returns the local file:// URL for an emoji codepoint
   * Downloads it first if not cached
   * Returns null if download fails
   */
  public static String getEmojiUrl(int codePoint) {
    String hex = codePointToHex(codePoint);
    Path localFile = EMOJI_DIR.resolve(hex + ".png");

    if (Files.exists(localFile)) {
      return localFile.toUri().toString();
    }

    return download(hex, localFile);
  }

  private static String download(String hex, Path destination) {
    try {
      Files.createDirectories(EMOJI_DIR);

      HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(CDN + hex + ".png"))
              .timeout(Duration.ofSeconds(10))
              .build();

      HttpResponse<InputStream> response = client.send(
              request, HttpResponse.BodyHandlers.ofInputStream()
      );

      if (response.statusCode() == 200) {
        Files.copy(response.body(), destination, StandardCopyOption.REPLACE_EXISTING);
        Debug.info("Downloaded emoji: {}", hex);
        return destination.toUri().toString();
      } else {
        Debug.warn("Emoji not found on CDN: {}", hex);
        return null;
      }
    } catch (Exception e) {
      Debug.warn("Failed to download emoji {}: {}", hex, e.getMessage());
      return null;
    }
  }

  /**
   * Fetches the full list of emoji filenames from the twemoji GitHub repo
   * and downloads all of them in parallel using virtual threads
   * Only downloads files that don't already exist
   */
  public static void downloadAll(Runnable onComplete) {
    Thread.startVirtualThread(() -> {
      try {
        Files.createDirectories(EMOJI_DIR);
        Debug.info("Fetching full emoji file list via git tree API...");
        HttpRequest treeRequest = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://api.github.com/repos/jdecked/twemoji/git/trees/main" +
                                "?recursive=1"
                ))
                .header("Accept", "application/vnd.github.v3+json")
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> treeResponse = client.send(
                treeRequest, HttpResponse.BodyHandlers.ofString()
        );

        // Parse response, filter only 72x72 PNG files
        com.google.gson.JsonObject root = com.google.gson.JsonParser
                .parseString(treeResponse.body())
                .getAsJsonObject();

        com.google.gson.JsonArray tree = root.getAsJsonArray("tree");

        List<String> filenames = new ArrayList<>();
        for (com.google.gson.JsonElement el : tree) {
          String path = el.getAsJsonObject().get("path").getAsString();
          if (path.startsWith("assets/72x72/") && path.endsWith(".png")) {
            // Extract just the filename e.g. "1f600.png"
            filenames.add(path.substring("assets/72x72/".length()));
          }
        }

        int total = filenames.size();
        Debug.info("Found {} emoji files total", total);

        // Count already cached
        long cached = filenames.stream()
                .filter(name -> Files.exists(EMOJI_DIR.resolve(name)))
                .count();
        Debug.info("{} already cached, downloading {} new ones", cached, total - cached);

        // Download missing ones in parallel
        int[] downloaded = {0};
        int[] failed = {0};

        List<Thread> threads = filenames.stream()
                .filter(name -> !Files.exists(EMOJI_DIR.resolve(name)))
                .map(name -> Thread.startVirtualThread(() -> {
                  Path dest = EMOJI_DIR.resolve(name);
                  try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(CDN + name))
                            .timeout(Duration.ofSeconds(15))
                            .build();

                    HttpResponse<InputStream> resp = client.send(
                            req, HttpResponse.BodyHandlers.ofInputStream()
                    );

                    if (resp.statusCode() == 200) {
                      Files.copy(resp.body(), dest, StandardCopyOption.REPLACE_EXISTING);
                      synchronized (downloaded) {
                        downloaded[0]++;
                      }
                    } else {
                      synchronized (failed) {
                        failed[0]++;
                      }
                      Debug.warn("404 for emoji: {}", name);
                    }
                  } catch (Exception e) {
                    synchronized (failed) {
                      failed[0]++;
                    }
                    Debug.warn("Failed to download {}: {}", name, e.getMessage());
                  }
                }))
                .toList();

        for (Thread t : threads) t.join();

        Debug.info("Emoji download complete: {} downloaded, {} failed, {} total cached",
                downloaded[0], failed[0], total - failed[0]);

        if (onComplete != null) onComplete.run();

      } catch (Exception e) {
        Debug.error("Failed to download emoji set: {}", e.getMessage(), e);
      }
    });
  }

  /**
   * Converts a Unicode codepoint to twemoji hex filename
   * Multi-codepoint sequences (like flags) are joined with "-"
   */
  public static String codePointToHex(int codePoint) {
    return String.format("%x", codePoint);
  }

  public static Path getEmojiDir() {
    return EMOJI_DIR;
  }
}