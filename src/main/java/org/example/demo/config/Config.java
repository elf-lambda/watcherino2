package org.example.demo.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.example.demo.logger.Debug;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

class ConfigData {
  float volume = 0.2f;
  String ttsModelPath = "";
  String ttsExecutablePath = "";
  List<Config.ChannelConfig> channels = new ArrayList<>();
  List<String> filters = new ArrayList<>();
  String oauthToken = "";
  String twitchUsername = "";
}

public class Config {

  private static final Path CONFIG_DIR = Paths.get(
          System.getProperty("user.home"), ".config", "watcherino"
  );
  private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Config INSTANCE = new Config();
  private String oauthToken = "";
  private String twitchUsername = "";
  // Root config structure
  private float volume = 0.1f;
  private String ttsModelPath = "";
  private String ttsExecutablePath = "";
  private List<ChannelConfig> channels = new ArrayList<>();
  private List<String> filters = new ArrayList<>();

  public Config() {
    load();
  }

  public static Config get() {
    return INSTANCE;
  }

  // Getters / Setters

  public List<String> getFilters() {
    return filters;
  }

  public void setFilters(List<String> f) {
    this.filters = f;
  }

  public float getVolume() {
    return volume;
  }

  public void setVolume(float v) {
    this.volume = v;
  }

  public String getTtsModelPath() {
    return ttsModelPath;
  }

  public void setTtsModelPath(String p) {
    this.ttsModelPath = p;
  }

  public String getTtsExecutablePath() {
    return ttsExecutablePath;
  }

  public void setTtsExecutablePath(String p) {
    this.ttsExecutablePath = p;
  }

  public List<ChannelConfig> getChannels() {
    return channels;
  }

  public void setChannels(List<ChannelConfig> c) {
    this.channels = c;
  }

  public ChannelConfig getChannel(String name) {
    return channels.stream()
            .filter(c -> c.getName().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
  }

  public void upsertChannel(ChannelConfig cfg) {
    channels.removeIf(c -> c.getName().equalsIgnoreCase(cfg.getName()));
    channels.add(cfg);
    save();
  }

  public void removeChannel(String name) {
    channels.removeIf(c -> c.getName().equalsIgnoreCase(name));
    save();
  }

// ----------

  public void save() {
    try {
      Files.createDirectories(CONFIG_DIR);
      ConfigData data = new ConfigData();
      data.volume = this.volume;
      data.ttsModelPath = this.ttsModelPath;
      data.ttsExecutablePath = this.ttsExecutablePath;
      data.channels = this.channels;
      data.filters = this.filters;
      data.oauthToken = this.oauthToken;
      data.twitchUsername = this.twitchUsername;
      try (Writer w = Files.newBufferedWriter(CONFIG_FILE)) {
        GSON.toJson(data, w);
      }
    } catch (IOException e) {
      Debug.error("Failed to save config: \"" + e.getMessage());
    }
  }

  private void load() {
    if (!Files.exists(CONFIG_FILE)) return;
    try (Reader r = Files.newBufferedReader(CONFIG_FILE)) {
      ConfigData data = GSON.fromJson(r, ConfigData.class);
      if (data != null) {
        this.volume = data.volume;
        this.ttsModelPath = data.ttsModelPath != null ? data.ttsModelPath : "";
        this.ttsExecutablePath = data.ttsExecutablePath != null ? data.ttsExecutablePath : "";
        if (data.channels != null) this.channels = data.channels;
        this.filters = data.filters != null ? data.filters : new ArrayList<>();
        this.oauthToken = data.oauthToken != null ? data.oauthToken : "";
        this.twitchUsername = data.twitchUsername != null ? data.twitchUsername : "";
      }
    } catch (IOException e) {
      Debug.error("Failed to load config: " + e.getMessage());
    }
  }

  public String getOauthToken() {
    return oauthToken;
  }

  public void setOauthToken(String t) {
    this.oauthToken = t;
  }

  public String getTwitchUsername() {
    return twitchUsername;
  }

  public void setTwitchUsername(String u) {
    this.twitchUsername = u;
  }
  // Channel config model

  public static class ChannelConfig {
    private final String name;
    private boolean favorite;
    private boolean ttsEnabled;

    public ChannelConfig(String name) {
      this.name = name;
      this.favorite = false;
      this.ttsEnabled = true;
    }

    public String getName() {
      return name;
    }

    public boolean isFavorite() {
      return favorite;
    }

    public void setFavorite(boolean f) {
      this.favorite = f;
    }

    public boolean isTtsEnabled() {
      return ttsEnabled;
    }

    public void setTtsEnabled(boolean t) {
      this.ttsEnabled = t;
    }
  }
}
