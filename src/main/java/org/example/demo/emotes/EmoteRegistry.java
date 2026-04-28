package org.example.demo.emotes;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EmoteRegistry {

  private static final EmoteRegistry INSTANCE = new EmoteRegistry();
  // Global emotes visible in all channels
  private final Map<String, EmoteInfo> globalEmotes = new ConcurrentHashMap<>();
  // Channel emotes: channelName -> (emoteName -> EmoteInfo)
  private final Map<String, Map<String, EmoteInfo>> channelEmotes = new ConcurrentHashMap<>();
  // ID -> EmoteInfo for fast Twitch tag lookups
  private final Map<String, EmoteInfo> byId = new ConcurrentHashMap<>();

  public static EmoteRegistry get() {
    return INSTANCE;
  }

  public void registerGlobal(EmoteInfo emote) {
    globalEmotes.put(emote.name(), emote);
    byId.put(emote.id(), emote);
  }

  public void registerChannel(String channel, EmoteInfo emote) {
    channelEmotes
            .computeIfAbsent(normalize(channel), k -> new ConcurrentHashMap<>())
            .put(emote.name(), emote);
    byId.put(emote.id(), emote);
  }

  /**
   * Look up emote by name, channel emotes take priority over global
   */
  public Optional<EmoteInfo> find(String channel, String name) {
    Map<String, EmoteInfo> channelMap = channelEmotes.get(normalize(channel));
    if (channelMap != null && channelMap.containsKey(name)) {
      return Optional.of(channelMap.get(name));
    }
    return Optional.ofNullable(globalEmotes.get(name));
  }

  /**
   * Returns a combined list of channel-specific and global emotes.
   * Channel emotes override globals if names collide.
   */
  public List<EmoteInfo> getAllForChannel(String channel) {
    String normalized = normalize(channel);

    // Start with a map to handle shadowing (overwriting globals with channel versions)
    Map<String, EmoteInfo> combined = new HashMap<>(globalEmotes);

    // Overwrite twitch globals
    Map<String, EmoteInfo> channelMap = channelEmotes.get(normalized);
    if (channelMap != null) {
      for (EmoteInfo emote : channelMap.values()) {
        combined.put(emote.name(), emote);
      }
    }

    return new ArrayList<>(combined.values());
  }

  public boolean hasChannel(String channel) {
    return channelEmotes.containsKey(normalize(channel));
  }

  private String normalize(String channel) {
    return channel.replace("#", "").toLowerCase();
  }

  /**
   * Searches emotes by name prefix for autocomplete
   * Returns channel emotes first, then globals, up to maxResults
   */
  public List<EmoteSearchResult> search(String channel, String query, int maxResults) {
    String q = query.toLowerCase();
    List<EmoteSearchResult> results = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    // Channel emotes first - they take priority
    Map<String, EmoteInfo> channelMap = channelEmotes.get(normalize(channel));
    if (channelMap != null) {
      channelMap.values().stream()
              .filter(e -> e.name().toLowerCase().contains(q))
              .filter(e -> e.localPath() != null && Files.exists(e.localPath()))
              .sorted(Comparator.comparing(e -> e.name().toLowerCase().indexOf(q)))
              .forEach(e -> {
                if (seen.add(e.name()) && results.size() < maxResults) {
                  results.add(new EmoteSearchResult(
                          e.name(),
                          e.localPath().toUri().toString(),
                          providerLabel(e.provider(), false)
                  ));
                }
              });
    }

    // Global emotes second
    globalEmotes.values().stream()
            .filter(e -> e.name().toLowerCase().contains(q))
            .filter(e -> e.localPath() != null && Files.exists(e.localPath()))
            .sorted(Comparator.comparing(e -> e.name().toLowerCase().indexOf(q)))
            .forEach(e -> {
              if (seen.add(e.name()) && results.size() < maxResults) {
                results.add(new EmoteSearchResult(
                        e.name(),
                        e.localPath().toUri().toString(),
                        providerLabel(e.provider(), true)
                ));
              }
            });

    return results;
  }

  private String providerLabel(EmoteProvider provider, boolean global) {
    return switch (provider) {
      case SEVENTV -> global ? "7tv-global" : "7tv";
      case BTTV -> global ? "bttv-global" : "bttv";
      case FFZ -> global ? "ffz-global" : "ffz";
      case TWITCH -> "twitch";
    };
  }

  public record EmoteSearchResult(String name, String fileUri, String source) {
  }
}