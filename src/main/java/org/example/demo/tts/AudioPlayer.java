package org.example.demo.tts;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AudioPlayer {

  /**
   * Plays a WAV file with volume control
   */
  public static void playWav(String filePath, float volume) {
    Path path = Paths.get(filePath);

    if (!Files.exists(path)) {
      System.err.println("Audio file not found: " + filePath);
      return;
    }

    new Thread(() -> {
      try (AudioInputStream inputStream = AudioSystem.getAudioInputStream(path.toFile())) {

        Clip clip = AudioSystem.getClip();
        clip.open(inputStream);

        if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
          FloatControl gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);

          // Convert linear 0.0-1.0 to decibels
          float dB = (float) (Math.log(volume <= 0 ? 0.0001 : volume) / Math.log(10.0) * 20.0);
          gainControl.setValue(dB);
        }

        clip.start();

        // Wait for the clip to finish before closing the thread
        // (Otherwise the thread dies and the sound cuts off)
        Thread.sleep(clip.getMicrosecondLength() / 1000);

      } catch (UnsupportedAudioFileException | IOException | LineUnavailableException |
               InterruptedException e) {
        System.err.println("Error playing audio: " + e.getMessage());
      }
    }).start();
  }
}