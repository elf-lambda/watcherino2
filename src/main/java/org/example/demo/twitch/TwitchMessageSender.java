package org.example.demo.twitch;

import org.example.demo.config.Config;
import org.example.demo.logger.Debug;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class TwitchMessageSender {

  private static final String IRC_WS = "wss://irc-ws.chat.twitch.tv:443";

  /**
   * Sends a message to a Twitch channel via WebSocket IRC
   * Opens a connection, authenticates, sends, then closes
   * Async -> does not block the caller
   */
  public static void send(String channel, String message) {
    String token = Config.get().getOauthToken();
    String username = Config.get().getTwitchUsername();

    if (token.isBlank() || username.isBlank()) {
      Debug.warn("Cannot send message — OAuth token or username not configured");
      return;
    }

    String normalizedChannel = channel.startsWith("#") ? channel : "#" + channel;
    String normalizedToken = token.startsWith("oauth:") ? token : "oauth:" + token;

    Thread.startVirtualThread(() -> {
      try {
        CompletableFuture<Void> done = new CompletableFuture<>();

        HttpClient client = HttpClient.newHttpClient();

        WebSocket ws = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(IRC_WS), new WebSocket.Listener() {

                  private final StringBuilder buffer = new StringBuilder();
                  private boolean messageSent = false;

                  @Override
                  public void onOpen(WebSocket ws) {
                    Debug.debug("WS open, authenticating...");
                    ws.sendText("PASS " + normalizedToken, true);
                    ws.sendText("NICK " + username, true);
                    ws.sendText("JOIN " + normalizedChannel, true);
                    ws.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                      String line = buffer.toString().trim();
                      buffer.setLength(0);
                      handleLine(ws, line);
                    }
                    ws.request(1);
                    return null;
                  }

                  private void handleLine(WebSocket ws, String line) {
                    Debug.debug("IRC: {}", line);

                    if (line.startsWith("PING")) {
                      ws.sendText("PONG " + line.substring(5), true);
                      return;
                    }

                    // Ready to send once we get JOIN confirmation
                    if (!messageSent && (line.contains("366") || line.contains("JOIN"))) {
                      messageSent = true;
                      ws.sendText("PRIVMSG " + normalizedChannel + " :" + message, true);
                      Debug.info("Sent message to {}: {}", normalizedChannel, message);

                      // Small delay then close
                      try {
                        Thread.sleep(500);
                      } catch (InterruptedException ignored) {
                      }
                      ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                      done.complete(null);
                    }
                  }

                  @Override
                  public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    Debug.debug("WS closed: {}", reason);
                    done.complete(null);
                    return null;
                  }

                  @Override
                  public void onError(WebSocket ws, Throwable error) {
                    Debug.error("WS error sending message: {}", error.getMessage());
                    done.completeExceptionally(error);
                  }
                })
                .get(10, TimeUnit.SECONDS);

        // Wait for send to complete (max 15s total)
        done.get(15, TimeUnit.SECONDS);

      } catch (Exception e) {
        Debug.error("Failed to send Twitch message: {}", e.getMessage(), e);
      }
    });
  }
}