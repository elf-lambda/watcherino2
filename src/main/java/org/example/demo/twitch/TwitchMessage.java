package org.example.demo.twitch;

import java.time.LocalTime;
import java.util.Map;

public class TwitchMessage {
  public final String username;
  public final String content;
  public final String channel;
  public final String userColor;
  public final LocalTime timestamp;
  public final Map<String, String> tags;
  public boolean isSystemMessage;
  public boolean isModerator;
  public boolean isVIP;
  public boolean isStreamer;
  public boolean isHighlighted;

  public TwitchMessage(String username, String content, String channel,
                       String userColor, Map<String, String> tags, boolean isSystemMessage) {
    this.username = username;
    this.content = content;
    this.channel = channel;
    this.userColor = userColor;
    this.tags = tags;
    this.isSystemMessage = isSystemMessage;
    this.timestamp = LocalTime.now();
  }
}