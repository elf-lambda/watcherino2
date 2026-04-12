package org.example.demo.tts;

import org.example.demo.logger.Debug;

import java.io.IOException;

public class TTSGenerator {

  public static void generate(String PIPER_EXECUTABLE, String modelPath, String outputPath,
                              String text) {
    try {
      String finalOutputFile = outputPath.endsWith(".wav") ? outputPath : outputPath + "/message" +
                                                                          ".wav";
      ProcessBuilder pb = new ProcessBuilder(
              PIPER_EXECUTABLE,
              "--model", modelPath,
              "--output_file", finalOutputFile
      );

      Process process = pb.start();

      try (var os = process.getOutputStream()) {
        os.write(text.getBytes());
        os.flush();
      }

      try (var err = process.getErrorStream()) {
        String errorMsg = new String(err.readAllBytes());
        if (!errorMsg.isEmpty()) Debug.error("Piper says: " + errorMsg);
      }

      int exitCode = process.waitFor();
      if (exitCode == 0) {
        Debug.info("Success! Saved to: " + finalOutputFile);
      } else {
        Debug.error("Piper failed with exit code: " + exitCode);
      }

    } catch (IOException | InterruptedException e) {
      Debug.error(e.getMessage());
      e.printStackTrace();
    }
  }
}
