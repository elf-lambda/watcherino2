package org.example.demo.twitch;

public class TwitchChannel {
  private final String name;
  private boolean live;
  private boolean favorite;

  public TwitchChannel(String name) {
    this.name = name.replaceFirst("^#", "").toLowerCase();
    this.live = false;
    this.favorite = false;
  }

  public String getName() {
    return name;
  }

  public boolean isLive() {
    return live;
  }

  public void setLive(boolean live) {
    this.live = live;
  }

  public boolean isFavorite() {
    return favorite;
  }

  public void setFavorite(boolean favorite) {
    this.favorite = favorite;
  }

  @Override
  public String toString() {
    return name;
  }
}