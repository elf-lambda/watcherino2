package org.example.demo.tts;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AudioPlayer {

  public static void playWav(String filePath, float volume) {
    Path path = Paths.get(filePath);
    if (!Files.exists(path)) {
      System.err.println("Audio file not found: " + filePath);
      return;
    }

    try (AudioInputStream rawStream = AudioSystem.getAudioInputStream(path.toFile())) {
      AudioFormat baseFormat = rawStream.getFormat();

      AudioFormat targetFormat = new AudioFormat(
              AudioFormat.Encoding.PCM_SIGNED,
              baseFormat.getSampleRate(),
              16,
              baseFormat.getChannels(),
              baseFormat.getChannels() * 2,
              baseFormat.getSampleRate(),
              false
      );

      try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(targetFormat, rawStream)) {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, targetFormat);
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
          line.open(targetFormat);

          // Convert volume
          if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (Math.log(volume <= 0 ? 0.0001 : volume) / Math.log(10.0) * 20.0);
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
          }

          line.start();

          byte[] buffer = new byte[4096];
          int bytesRead;
          while ((bytesRead = pcmStream.read(buffer)) != -1) {
            line.write(buffer, 0, bytesRead);
          }

          line.drain(); // blocks until all audio is played
          line.stop();
        }
      }
    } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
      System.err.println("Error playing audio: " + e.getMessage());
    }
  }
}