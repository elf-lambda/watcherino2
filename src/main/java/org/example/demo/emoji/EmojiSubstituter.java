package org.example.demo.emoji;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class EmojiSubstituter {

  /**
   * Replaces emoji unicode in text with <img> tags pointing to local files
   * Downloads missing emojis on demand
   */
  public static String substitute(String text) {
    if (text == null || text.isEmpty()) return text;

    StringBuilder result = new StringBuilder();
    int[] codePoints = text.codePoints().toArray();
    int i = 0;

    while (i < codePoints.length) {
      int cp = codePoints[i];

      if (isEmojiStart(cp)) {
        // Try to build a multi-codepoint sequence (flags, skin tones, ZWJ sequences)
        List<Integer> sequence = new ArrayList<>();
        sequence.add(cp);
        i++;

        // Consume variation selector, ZWJ, and continuation codepoints
        while (i < codePoints.length && isContinuation(codePoints[i])) {
          sequence.add(codePoints[i]);
          i++;
        }

        String imgTag = buildImgTag(sequence);
        if (imgTag != null) {
          result.append(imgTag);
        } else {
          // Fallback — just append the raw characters
          for (int seqCp : sequence) {
            result.appendCodePoint(seqCp);
          }
        }
      } else {
        result.appendCodePoint(cp);
        i++;
      }
    }

    return result.toString();
  }

  private static String buildImgTag(List<Integer> sequence) {
    // Build hex string — filter out variation selectors for the filename
    List<Integer> significant = sequence.stream()
            .filter(cp -> cp != 0xFE0F && cp != 0xFE0E) // strip variation selectors
            .toList();

    String hex;
    if (significant.size() == 1) {
      hex = String.format("%x", significant.get(0));
    } else {
      // Multi-codepoint: joined with "-" e.g. "1f1fa-1f1f8" for 🇺🇸
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < significant.size(); i++) {
        if (i > 0) sb.append("-");
        sb.append(String.format("%x", significant.get(i)));
      }
      hex = sb.toString();
    }

    Path localFile = EmojiDownloader.getEmojiDir().resolve(hex + ".png");
    String url;

    if (Files.exists(localFile)) {
      url = localFile.toUri().toString();
    } else {
      Thread.startVirtualThread(() -> EmojiDownloader.getEmojiUrl(
              significant.isEmpty() ? sequence.get(0) : significant.get(0)
      ));
      return null;
    }

    return "<img src='" + url + "' " +
            "alt='" + hex + "' " +
            "style='width:1.2em;height:1.2em;vertical-align:-0.2em;'>";
  }

  private static boolean isEmojiStart(int cp) {
    return Character.getType(cp) == Character.SURROGATE ||
            Character.getType(cp) == Character.OTHER_SYMBOL ||
            (cp >= 0x2600 && cp <= 0x27BF);
  }

  private static boolean isContinuation(int cp) {
    return cp == 0xFE0F          // variation selector-16
            || cp == 0xFE0E          // variation selector-15
            || cp == 0x200D          // zero-width joiner
            || cp == 0x20E3          // combining enclosing keycap
            || (cp >= 0x1F3FB && cp <= 0x1F3FF)  // skin tone modifiers
            || (cp >= 0x1F1E0 && cp <= 0x1F1FF); // regional indicators (second half of flag)
  }
}