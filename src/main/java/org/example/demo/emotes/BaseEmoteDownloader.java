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

public abstract class BaseEmoteDownloader {

  protected static final HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  protected static final Path EMOTE_BASE = Paths.get(
          System.getProperty("user.home"), ".config", "watcherino", "emotes"
  );

  /**
   * Downloads a file from url to dest.
   * Returns true on success.
   */
  protected boolean downloadFile(String url, Path dest) {
    try {
      Files.createDirectories(dest.getParent());

      HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(15))
              .build();

      HttpResponse<InputStream> response = client.send(
              request, HttpResponse.BodyHandlers.ofInputStream()
      );

      if (response.statusCode() == 200) {
        Files.copy(response.body(), dest, StandardCopyOption.REPLACE_EXISTING);
        return true;
      } else {
        Debug.warn("HTTP {} downloading {}", response.statusCode(), url);
        return false;
      }
    } catch (Exception e) {
      Debug.warn("Failed to download {}: {}", url, e.getMessage());
      return false;
    }
  }

  /**
   * Checks if file exists on disk and skip if so
   */
  protected boolean isCached(Path path) {
    return Files.exists(path);
  }

}