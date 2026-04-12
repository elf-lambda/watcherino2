package org.example.demo.logger;

import com.google.gson.Gson;
import org.example.demo.twitch.TwitchMessage;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatLogger {

  private static final Path LOG_DIR = Paths.get(
          System.getProperty("user.home"), ".config", "watcherino", "logs"
  );
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final Gson GSON = new Gson();

  // channel name -> open writer
  private final Map<String, BufferedWriter> writers = new HashMap<>();
  // channel name -> date the current file was opened for
  private final Map<String, LocalDate> openDates = new HashMap<>();

  // Single thread -> all file writes go through here, no synchronization needed
  private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "chat-logger");
    t.setDaemon(true);
    return t;
  });
  private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "chat-logger-flusher");
    t.setDaemon(true);
    return t;
  });

  public ChatLogger() {
    flusher.scheduleAtFixedRate(this::flushAll, 5, 5, TimeUnit.MINUTES);
  }

  private void flushAll() {
    // Submit flush task to the logger thread so it doesn't race with writes
    executor.submit(() -> {
      for (Map.Entry<String, BufferedWriter> entry : writers.entrySet()) {
        try {
          entry.getValue().flush();
          Debug.info("Flushed chat log for {}", entry.getKey());
        } catch (IOException e) {
          Debug.error("Failed to flush log for {}: {}", entry.getKey(), e.getMessage());
        }
      }
      Debug.debug("Flushed {} channel logs", writers.size());
    });
  }

  public void log(TwitchMessage msg) {
    // Submit to logger thread
    executor.submit(() -> {
      try {
        String channel = msg.channel.replace("#", "").toLowerCase();
        BufferedWriter writer = getWriter(channel);

        // Building JSONL entry
        Map<String, Object> entry = new HashMap<>();
        entry.put("time", msg.timestamp.format(TIME_FORMAT));
        entry.put("user", msg.username);
        entry.put("color", msg.userColor);
        entry.put("message", msg.content);
        entry.put("mod", msg.isModerator);
        entry.put("vip", msg.isVIP);
        entry.put("system", msg.isSystemMessage);

        writer.write(GSON.toJson(entry));
        writer.newLine();
//        writer.flush();

      } catch (IOException e) {
        Debug.error("Failed to log message: " + e.getMessage());
      }
    });
  }

  private BufferedWriter getWriter(String channel) throws IOException {
    LocalDate today = LocalDate.now();

    // Check if we need to rotate -> date changed since file was opened
    if (writers.containsKey(channel) && !openDates.get(channel).equals(today)) {
      writers.get(channel).close();
      writers.remove(channel);
      openDates.remove(channel);
      Debug.info("Rotated log for " + channel);
    }

    // Open new writer if needed
    if (!writers.containsKey(channel)) {
      Path channelDir = LOG_DIR.resolve(channel);
      Files.createDirectories(channelDir);

      Path logFile = channelDir.resolve(today.format(DATE_FORMAT) + ".log");

      BufferedWriter writer = new BufferedWriter(
              new FileWriter(logFile.toFile(), true)
      );

      writers.put(channel, writer);
      openDates.put(channel, today);
      Debug.info("Opened log: " + logFile);
    }

    return writers.get(channel);
  }

  public void shutdown() {
    flusher.shutdownNow();
    executor.submit(() -> {
      // Flush and close all open writers
      for (Map.Entry<String, BufferedWriter> entry : writers.entrySet()) {
        try {
          entry.getValue().flush();
          entry.getValue().close();
          Debug.info("Closed log for " + entry.getKey());
        } catch (IOException e) {
          Debug.error("Failed to close log for " + entry.getKey());
        }
      }
      writers.clear();
      openDates.clear();
    });

    executor.shutdown();
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
    }
  }
}