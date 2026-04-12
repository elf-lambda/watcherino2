package org.example.demo.twitch;

public class TwitchChannel {
  private final String name;
  private boolean hasHighlightAlert = false;
  private boolean live;
  private boolean favorite;

  public TwitchChannel(String name) {
    this.name = name.replaceFirst("^#", "").toLowerCase();
    this.live = false;
    this.favorite = false;
  }

  public boolean hasHighlightAlert() {
    return hasHighlightAlert;
  }

  public void setHighlightAlert(boolean alert) {
    this.hasHighlightAlert = alert;
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