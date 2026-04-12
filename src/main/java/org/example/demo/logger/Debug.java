package org.example.demo.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Debug {
  private static final Logger log = LoggerFactory.getLogger("Watcherino");

  public static void info(String msg, Object... args) {
    log.info(msg, args);
  }

  public static void warn(String msg, Object... args) {
    log.warn(msg, args);
  }

  public static void error(String msg, Object... args) {
    log.error(msg, args);
  }

  public static void debug(String msg, Object... args) {
    log.debug(msg, args);
  }
}