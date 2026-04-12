package org.example.demo.tts;

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
        if (!errorMsg.isEmpty()) System.err.println("Piper says: " + errorMsg);
      }

      int exitCode = process.waitFor();
      if (exitCode == 0) {
        System.out.println("Success! Saved to: " + finalOutputFile);
      } else {
        System.err.println("Piper failed with exit code: " + exitCode);
      }

    } catch (IOException | InterruptedException e) {
      e.printStackTrace();
    }
  }
}
