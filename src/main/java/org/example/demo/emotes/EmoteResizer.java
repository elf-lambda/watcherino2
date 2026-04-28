package org.example.demo.emotes;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

public class EmoteResizer {

  private static final int MAX_EMOTE_SIZE = 32;

  public static BufferedImage processEmoteStream(InputStream inputStream) throws IOException {
    BufferedImage src = ImageIO.read(inputStream);
    if (src == null) return null;

    int srcWidth = src.getWidth();
    int srcHeight = src.getHeight();

    // No resize needed
    if (srcHeight <= MAX_EMOTE_SIZE) return src;

    double scale = (double) MAX_EMOTE_SIZE / srcHeight;
    int targetWidth = (int) Math.round(srcWidth * scale);
    int targetHeight = MAX_EMOTE_SIZE;

    // Progressive scaling: never reduce by more than half in one step
    // to avoid aliasing / pixelation
    BufferedImage current = src;
    int currentWidth = srcWidth;
    int currentHeight = srcHeight;

    while (currentHeight > targetHeight || currentWidth > targetWidth) {
      int nextWidth = Math.max(targetWidth, currentWidth / 2);
      int nextHeight = Math.max(targetHeight, currentHeight / 2);

      BufferedImage next = new BufferedImage(nextWidth, nextHeight, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = next.createGraphics();
      try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(current, 0, 0, nextWidth, nextHeight, null);
      } finally {
        g.dispose();
      }
      current = next;
      currentWidth = nextWidth;
      currentHeight = nextHeight;
    }

    return current;
  }
}