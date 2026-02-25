package example.websocket.audio.service;

import java.util.Base64;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.sound.sampled.*;

import com.alibaba.dashscope.audio.qwen_tts_realtime.*;
import com.google.gson.JsonObject;

public class QwenTtsService implements TtsService {
  private static final String MODEL = "qwen3-tts-instruct-flash-realtime";
  private static final String URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
  private static final int SAMPLE_RATE = 24000;

  private final String apiKey;
  private QwenTtsRealtime qwenTtsRealtime;
  private Consumer<byte[]> audioCallback;
  private Consumer<Exception> errorCallback;
  private final AtomicBoolean isReady = new AtomicBoolean(false);
  private final AtomicBoolean sessionStarted = new AtomicBoolean(false);
  private final AtomicBoolean isPlaying = new AtomicBoolean(false);
  private final AtomicBoolean isSynthesizing = new AtomicBoolean(false);

  // Audio playback
  private SourceDataLine audioLine;
  private Queue<byte[]> audioBuffer;
  private Thread playThread;
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  public QwenTtsService(String apiKey) {
    this.apiKey = apiKey;
  }

  @Override
  public void initialize() {
    if (apiKey == null || apiKey.isEmpty()) {
      System.err.println("[Qwen TTS] API Key 未设置");
      return;
    }

    initConnection();
    initAudioPlayer();
  }

  private void initConnection() {
    try {
      // 如果已有连接，先关闭
      if (qwenTtsRealtime != null) {
        try {
          qwenTtsRealtime.finish();
        } catch (Exception e) {
          // ignore
        }
        qwenTtsRealtime = null;
      }

      QwenTtsRealtimeParam param =
          QwenTtsRealtimeParam.builder().model(MODEL).url(URL).apikey(apiKey).build();

      qwenTtsRealtime =
          new QwenTtsRealtime(
              param,
              new QwenTtsRealtimeCallback() {
                @Override
                public void onOpen() {
                  System.out.println("[Qwen TTS] 连接已建立");
                }

                @Override
                public void onEvent(JsonObject message) {
                  handleEvent(message);
                }

                @Override
                public void onClose(int code, String reason) {
                  System.err.println("[Qwen TTS] 连接关闭: " + code + ", " + reason);
                  isReady.set(false);
                  sessionStarted.set(false);
                  isSynthesizing.set(false);
                  qwenTtsRealtime = null;
                }
              });

      qwenTtsRealtime.connect();
      TimeUnit.MILLISECONDS.sleep(1000);
      isReady.set(true);

    } catch (Exception e) {
      System.err.println("[Qwen TTS] 初始化失败: " + e.getMessage());
      if (errorCallback != null) {
        errorCallback.accept(e);
      }
    }
  }

  private void initAudioPlayer() {
    try {
      AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
      DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
      audioLine = (SourceDataLine) AudioSystem.getLine(info);
      int bufferSize = SAMPLE_RATE * 2 * 2;
      audioLine.open(format, bufferSize);
      audioLine.start();

      audioBuffer = new ConcurrentLinkedQueue<>();
      stopped.set(false);

      playThread = new Thread(this::playLoop, "QwenTtsPlayer");
      playThread.setDaemon(true);
      playThread.start();

    } catch (Exception e) {
      System.err.println("[Qwen TTS] 音频播放器初始化失败: " + e.getMessage());
    }
  }

  private void playLoop() {
    while (!stopped.get()) {
      byte[] audioData = audioBuffer.poll();
      if (audioData != null) {
        isPlaying.set(true);
        int bytesWritten = 0;
        while (bytesWritten < audioData.length) {
          bytesWritten += audioLine.write(audioData, bytesWritten, audioData.length - bytesWritten);
        }
      } else {
        isPlaying.set(false);
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private void handleEvent(JsonObject message) {
    String type = message.get("type").getAsString();

    switch (type) {
      case "session.created":
        sessionStarted.set(true);
        break;
      case "response.audio.delta":
        String audioB64 = message.get("delta").getAsString();
        byte[] audioData = Base64.getDecoder().decode(audioB64);
        // Add to buffer for playback
        if (audioBuffer != null) {
          audioBuffer.add(audioData);
        }
        // Notify callback
        if (audioCallback != null) {
          audioCallback.accept(audioData);
        }
        break;
      case "response.done":
        System.out.println("[Qwen TTS] 响应完成");
        isSynthesizing.set(false);
        break;
      case "session.finished":
        sessionStarted.set(false);
        isPlaying.set(false);
        isSynthesizing.set(false);
        break;
    }
  }

  @Override
  public void synthesize(String text, String voiceId, String tone) {
    // 如果正在合成中，先停止当前合成
    if (isSynthesizing.get()) {
      System.out.println("[Qwen TTS] 正在合成中，先停止当前合成");
      stop();
      // 等待一小段时间确保 session 完全关闭
      try {
        TimeUnit.MILLISECONDS.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // 检查连接状态，如果需要则重新连接
    if (qwenTtsRealtime == null || !isReady.get() || !sessionStarted.get()) {
      System.out.println("[Qwen TTS] 重新建立连接");
      initConnection();
      try {
        TimeUnit.MILLISECONDS.sleep(1500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    if (qwenTtsRealtime == null) {
      System.err.println("[Qwen TTS] 初始化失败");
      return;
    }

    // Clear previous audio buffer
    if (audioBuffer != null) {
      audioBuffer.clear();
    }

    try {
      String content = text;
      if (tone != null && !tone.isEmpty()) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[(.*?)\\]");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
          content = matcher.replaceFirst("").trim();
        }
      }

      String actualVoiceId = (voiceId != null && !voiceId.isEmpty()) ? voiceId : "Cherry";

      // 标记正在合成
      isSynthesizing.set(true);

      QwenTtsRealtimeConfig config =
          QwenTtsRealtimeConfig.builder()
              .voice(actualVoiceId)
              .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
              .mode("commit")
              .speechRate(1.1f)
              .instructions(tone)
              .optimizeInstructions(tone != null && !tone.isEmpty())
              .build();

      qwenTtsRealtime.updateSession(config);
      qwenTtsRealtime.appendText(content);
      qwenTtsRealtime.commit();
      // 不调用 finish，让 session 保持打开状态以便后续使用
      // qwenTtsRealtime.finish();

      System.out.println(
          "[Qwen TTS] 合成请求已发送: " + content.substring(0, Math.min(50, content.length())) + "...");

    } catch (Exception e) {
      System.err.println("[Qwen TTS] 合成失败: " + e.getMessage());
      isSynthesizing.set(false);
      if (errorCallback != null) {
        errorCallback.accept(e);
      }
    }
  }

  @Override
  public void stop() {
    if (qwenTtsRealtime != null) {
      try {
        qwenTtsRealtime.finish();
      } catch (Exception e) {
        // ignore
      }
    }
    if (audioBuffer != null) {
      audioBuffer.clear();
    }
    sessionStarted.set(false);
    isPlaying.set(false);
    isSynthesizing.set(false);
  }

  @Override
  public void close() {
    stop();

    stopped.set(true);
    if (playThread != null) {
      try {
        playThread.join(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    if (audioLine != null) {
      try {
        audioLine.drain();
        audioLine.close();
      } catch (Exception e) {
        // ignore
      }
      audioLine = null;
    }

    if (qwenTtsRealtime != null) {
      qwenTtsRealtime = null;
    }
    isReady.set(false);
    sessionStarted.set(false);
    isSynthesizing.set(false);
  }

  @Override
  public boolean isReady() {
    return isReady.get() && sessionStarted.get();
  }

  public boolean isPlaying() {
    return isPlaying.get();
  }

  @Override
  public void setAudioCallback(Consumer<byte[]> callback) {
    this.audioCallback = callback;
  }

  @Override
  public void setErrorCallback(Consumer<Exception> callback) {
    this.errorCallback = callback;
  }
}
