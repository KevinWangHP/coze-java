package example.websocket.demo.service;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.*;

public class AudioPlayer {
  private static final int SAMPLE_RATE = 24000;
  private static final int CHUNK_SIZE = 1024 * 8;
  private static final int PRE_BUFFER_MS = 400;  // 预缓冲400ms

  private SourceDataLine audioLine;
  private final EchoCanceller echoCanceller;
  private volatile boolean shouldStop = false;
  private final AtomicBoolean isPlaying = new AtomicBoolean(false);

  private Queue<byte[]> rawAudioBuffer;
  private Thread playerThread;
  private final AtomicBoolean stopped = new AtomicBoolean(false);
  private final AtomicBoolean preBufferReady = new AtomicBoolean(false);
  private volatile int bufferedDataSize = 0;

  public AudioPlayer(EchoCanceller echoCanceller) {
    this.echoCanceller = echoCanceller;
  }

  public void play(byte[] audioData) {
    if (audioData == null || audioData.length == 0) {
      return;
    }

    shouldStop = false;
    isPlaying.set(true);

    try {
      AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
      DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
      audioLine = (SourceDataLine) AudioSystem.getLine(info);
      audioLine.open(format);
      audioLine.start();

      if (echoCanceller != null) {
        echoCanceller.addPlaybackAudio(audioData);
      }

      int offset = 0;
      while (offset < audioData.length && !shouldStop) {
        int length = Math.min(CHUNK_SIZE, audioData.length - offset);
        audioLine.write(audioData, offset, length);
        offset += length;
      }

      if (!shouldStop) {
        audioLine.drain();
      }

    } catch (Exception e) {
      System.err.println("[AudioPlayer] 播放失败: " + e.getMessage());
    } finally {
      closeLine();
      isPlaying.set(false);
    }
  }

  public void startRealtimePlayback() {
    rawAudioBuffer = new ConcurrentLinkedQueue<>();
    stopped.set(false);
    preBufferReady.set(false);
    bufferedDataSize = 0;

    try {
      AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
      DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
      audioLine = (SourceDataLine) AudioSystem.getLine(info);
      int bufferSize = SAMPLE_RATE * 2 * 2;
      audioLine.open(format, bufferSize);
      audioLine.start();

      playerThread = new Thread(this::playLoop, "AudioPlayer");
      playerThread.start();

    } catch (Exception e) {
      System.err.println("[AudioPlayer] 初始化失败: " + e.getMessage());
    }
  }

  public void writeRealtimeAudioRaw(byte[] pcmData) {
    if (pcmData == null || pcmData.length == 0 || stopped.get()) {
      return;
    }

    rawAudioBuffer.add(pcmData);
    bufferedDataSize += pcmData.length;
  }

  public void cancelRealtimePlayback() {
    if (rawAudioBuffer != null) {
      rawAudioBuffer.clear();
    }
    bufferedDataSize = 0;
    preBufferReady.set(false);
  }

  public void resumeRealtimePlayback() {
    preBufferReady.set(false);
  }

  private void playLoop() {
    while (!stopped.get()) {
      // 等待预缓冲完成
      if (!preBufferReady.get()) {
        int preBufferBytes = PRE_BUFFER_MS * SAMPLE_RATE * 2 / 1000;
        if (bufferedDataSize >= preBufferBytes) {
          preBufferReady.set(true);
        } else {
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          continue;
        }
      }

      byte[] rawAudio = rawAudioBuffer.poll();
      if (rawAudio != null) {
        bufferedDataSize -= rawAudio.length;

        if (echoCanceller != null) {
          echoCanceller.addPlaybackAudio(rawAudio);
        }

        int bytesWritten = 0;
        while (bytesWritten < rawAudio.length) {
          bytesWritten += audioLine.write(rawAudio, bytesWritten, rawAudio.length - bytesWritten);
        }
        isPlaying.set(true);
      } else {
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  public void stop() {
    shouldStop = true;

    if (audioLine != null) {
      try {
        if (audioLine.isRunning()) {
          audioLine.stop();
        }
        audioLine.flush();
      } catch (Exception e) {
      }
    }

    stopped.set(true);
    isPlaying.set(false);
  }

  public void close() {
    stop();

    if (playerThread != null) {
      try {
        playerThread.join(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    closeLine();
  }

  private void closeLine() {
    if (audioLine != null) {
      try {
        audioLine.close();
      } catch (Exception e) {
      }
      audioLine = null;
    }
  }

  public boolean isPlaying() {
    return isPlaying.get();
  }
}
