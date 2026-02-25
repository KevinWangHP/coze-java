package example.websocket.audio.service;

import java.util.Base64;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.*;

public class AudioPlayer {
  private static final int SAMPLE_RATE = 24000;
  private static final int PRE_BUFFER_MS = 200;
  private static final int CHUNK_SIZE = 1024 * 8;

  private SourceDataLine audioLine;
  private final EchoCanceller echoCanceller;
  private volatile boolean shouldStop = false;
  private final AtomicBoolean isPlaying = new AtomicBoolean(false);

  // Realtime PCM Player components
  private Queue<String> b64AudioBuffer;
  private Queue<byte[]> rawAudioBuffer;
  private Thread decoderThread;
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

      // Add to echo buffer
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
    b64AudioBuffer = new ConcurrentLinkedQueue<>();
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

      decoderThread = new Thread(this::decodeLoop, "AudioDecoder");
      playerThread = new Thread(this::playLoop, "AudioPlayer");
      decoderThread.start();
      playerThread.start();

    } catch (Exception e) {
      System.err.println("[AudioPlayer] 初始化失败: " + e.getMessage());
    }
  }

  private void decodeLoop() {
    while (!stopped.get()) {
      String b64Audio = b64AudioBuffer.poll();
      if (b64Audio != null) {
        byte[] rawAudio = Base64.getDecoder().decode(b64Audio);
        rawAudioBuffer.add(rawAudio);
        bufferedDataSize += rawAudio.length;
      } else {
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private void playLoop() {
    while (!stopped.get()) {
      if (!preBufferReady.get()) {
        int preBufferBytes = PRE_BUFFER_MS * SAMPLE_RATE * 2 / 1000;
        if (bufferedDataSize >= preBufferBytes) {
          preBufferReady.set(true);
          System.out.println("[AudioPlayer] 预缓冲完成");
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
        playChunk(rawAudio);
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

  private void playChunk(byte[] chunk) {
    if (chunk == null || chunk.length == 0) return;

    if (echoCanceller != null) {
      echoCanceller.addPlaybackAudio(chunk);
    }

    int bytesWritten = 0;
    while (bytesWritten < chunk.length) {
      bytesWritten += audioLine.write(chunk, bytesWritten, chunk.length - bytesWritten);
    }
  }

  public void writeRealtimeAudio(String b64Audio) {
    if (b64AudioBuffer != null) {
      b64AudioBuffer.add(b64Audio);
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
        // ignore
      }
    }

    stopped.set(true);
    isPlaying.set(false);
  }

  public void cancelRealtimePlayback() {
    if (b64AudioBuffer != null) {
      b64AudioBuffer.clear();
    }
    if (rawAudioBuffer != null) {
      rawAudioBuffer.clear();
    }
    bufferedDataSize = 0;
    preBufferReady.set(false);
  }

  public void close() {
    stop();
    cancelRealtimePlayback();

    if (decoderThread != null) {
      try {
        decoderThread.join(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
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
        if (audioLine.isOpen()) {
          audioLine.close();
        }
      } catch (Exception e) {
        // ignore
      }
      audioLine = null;
    }
  }

  public boolean isPlaying() {
    return isPlaying.get();
  }
}
