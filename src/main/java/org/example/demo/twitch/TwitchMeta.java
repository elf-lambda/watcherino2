package org.example.demo.twitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

/**
 * Fetches Twitch channel metadata
 */
public class TwitchMeta {

  private static final String GQL_URL = "https://gql.twitch.tv/gql";
  private static final String PUBLIC_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";

  /**
   * Executes a POST request to Twitch GQL
   */
  public static Map<String, String> getChannelMetadata(String channel) throws IOException,
          InterruptedException {
    HttpClient client = HttpClient.newHttpClient();

    String query = """
            [
              {
                "operationName": "GetChannelMetadata",
                "variables": {
                  "login": "%s"
                },
                "query": "query GetChannelMetadata($login: String!) { user(login: $login) { stream { title game { name } } lastBroadcast { title game { name } } } }"
              }
            ]
            """.formatted(channel);

    String deviceId = UUID.randomUUID().toString().replace("-", "");

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(GQL_URL))
            .header("Content-Type", "application/json")
            .header("Client-Id", PUBLIC_CLIENT_ID)
            .header("X-Device-Id", deviceId)
            .POST(HttpRequest.BodyPublishers.ofString(query))
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
    var root = array.get(0).getAsJsonObject();
    var user = root.getAsJsonObject("data").getAsJsonObject("user");

    String title = "offline";
    String game = "unknown";

    // Try to get live stream info first
    if (user.has("stream") && !user.get("stream").isJsonNull()) {
      var stream = user.getAsJsonObject("stream");
      title = stream.has("title") && !stream.get("title").isJsonNull() ?
              stream.get("title").getAsString() : "No title";
      if (stream.has("game") && !stream.get("game").isJsonNull()) {
        game = stream.getAsJsonObject("game").get("name").getAsString();
      }
    }
    // Fallback to last broadcast
    else if (user.has("lastBroadcast") && !user.get("lastBroadcast").isJsonNull()) {
      var last = user.getAsJsonObject("lastBroadcast");
      title = last.has("title") && !last.get("title").isJsonNull() ?
              last.get("title").getAsString() : "No title";
      if (last.has("game") && !last.get("game").isJsonNull()) {
        game = last.getAsJsonObject("game").get("name").getAsString();
      }
    }

    return Map.of("title", title, "game", game);
  }
}

