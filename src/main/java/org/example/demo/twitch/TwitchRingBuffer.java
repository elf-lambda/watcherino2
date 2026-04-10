package org.example.demo.twitch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class TwitchRingBuffer {
  private final TwitchMessage[] buffer;
  private final int size;
  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private int index = 0;

  public TwitchRingBuffer(int size) {
    this.size = size;
    this.buffer = new TwitchMessage[size];
  }

  public void add(TwitchMessage msg) {
    lock.writeLock().lock();
    try {
      buffer[index] = msg;
      index = (index + 1) % size;
    } finally {
      lock.writeLock().unlock();
    }
  }

  public List<TwitchMessage> getLast(int n) {
    lock.readLock().lock();
    try {
      if (n > size) n = size;
      List<TwitchMessage> result = new ArrayList<>();
      for (int i = n - 1; i >= 0; i--) {
        int idx = (index - 1 - i + size) % size;
        if (buffer[idx] != null) result.add(buffer[idx]);
      }
      return result;
    } finally {
      lock.readLock().unlock();
    }
  }

  public List<TwitchMessage> getAll() {
    lock.readLock().lock();
    try {
      List<TwitchMessage> result = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        int idx = (index + i) % size;
        if (buffer[idx] != null) result.add(buffer[idx]);
      }
      return result;
    } finally {
      lock.readLock().unlock();
    }
  }
}