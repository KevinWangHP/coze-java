package example.websocket.audio.service;

import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.sound.sampled.*;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.Constants;

public class QwenTtsService implements TtsService {
  private static final String MODEL = "qwen3-tts-instruct-flash";
  private static final int SAMPLE_RATE = 24000;

  private final String apiKey;
  private Consumer<byte[]> audioCallback;
  private Consumer<Exception> errorCallback;
  private final AtomicBoolean isReady = new AtomicBoolean(false);
  private final AtomicBoolean isPlaying = new AtomicBoolean(false);
  private final AtomicBoolean isSynthesizing = new AtomicBoolean(false);

  private SourceDataLine audioLine;
  private Thread playThread;
  private final AtomicBoolean stopped = new AtomicBoolean(false);
  private volatile byte[] currentAudioData = null;

  public QwenTtsService(String apiKey) {
    this.apiKey = apiKey;
  }

  public QwenTtsService(String apiKey, String baseUrl) {
    this.apiKey = apiKey;
    // baseUrl 在 HTTP API 模式下不需要，但为了兼容接口保留
  }

  @Override
  public void initialize() {
    if (apiKey == null || apiKey.isEmpty()) {
      System.err.println("[Qwen TTS] API Key 未设置");
      return;
    }
    initAudioPlayer();
    isReady.set(true);
    System.out.println("[Qwen TTS] 初始化完成");
  }

  private void initAudioPlayer() {
    try {
      AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
      DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
      audioLine = (SourceDataLine) AudioSystem.getLine(info);
      int bufferSize = SAMPLE_RATE * 2 * 2;
      audioLine.open(format, bufferSize);
      audioLine.start();

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
      byte[] audioData = currentAudioData;
      if (audioData != null && isPlaying.get()) {
        int bytesWritten = 0;
        while (bytesWritten < audioData.length && isPlaying.get() && !stopped.get()) {
          int remaining = audioData.length - bytesWritten;
          int toWrite = Math.min(remaining, audioLine.available());
          if (toWrite > 0) {
            bytesWritten += audioLine.write(audioData, bytesWritten, toWrite);
          } else {
            try {
              Thread.sleep(5);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
          }
        }
        isPlaying.set(false);
        currentAudioData = null;
      } else {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  @Override
  public void synthesize(String text, String voiceId, String tone) {
    try {
      // 如果正在播放，先停止
      if (isPlaying.get()) {
        stop();
      }

      String content = text;
      String instructions = tone;

      String actualVoiceId = (voiceId != null && !voiceId.isEmpty()) ? voiceId : "Cherry";

      System.out.println(
          "[Qwen TTS] 开始合成: " + content.substring(0, Math.min(30, content.length())) + "...");
      if (instructions != null && !instructions.isEmpty()) {
        System.out.println("[Qwen TTS] 语气指令: " + instructions);
      }

      isSynthesizing.set(true);

      // 根据 ref.txt 示例构建参数
      MultiModalConversation conv = new MultiModalConversation();
      
      MultiModalConversationParam param = MultiModalConversationParam.builder()
          .apiKey(apiKey)
          .model(MODEL)
          .text(content)
          .voice(AudioParameters.Voice.valueOf(actualVoiceId.toUpperCase()))
          .languageType("Chinese")
          .build();

      // 如果有语气指令，需要重新构建参数（因为 SDK 可能不支持链式调用后修改）
      if (instructions != null && !instructions.isEmpty()) {
        param = MultiModalConversationParam.builder()
            .apiKey(apiKey)
            .model(MODEL)
            .text(content)
            .voice(AudioParameters.Voice.valueOf
              (actualVoiceId.toUpperCase()))
            .languageType("Chinese")
            .parameter("instructions", instructions)
            .parameter("optimize_instructions", true)
            .build();
      }

      MultiModalConversationResult result = conv.call(param);
      String audioUrl = result.getOutput().getAudio().getUrl();

      System.out.println("[Qwen TTS] 收到音频URL");

      byte[] audioData = downloadAudio(audioUrl);
      System.out.println("[Qwen TTS] 音频数据大小: " + audioData.length + " 字节");

      // 回调音频数据，由外部播放器播放
      if (audioCallback != null) {
        audioCallback.accept(audioData);
      }

      // Note: 音频播放已移至 onQwenTtsAudio 回调中进行
      // 避免内部播放和外部回调播放重复
      System.out.println("[Qwen TTS] 音频数据已回调，等待外部播放");

    } catch (Exception e) {
      System.err.println("[Qwen TTS] 合成失败: " + e.getMessage());
      isSynthesizing.set(false);
      if (errorCallback != null) {
        errorCallback.accept(e);
      }
    }
  }

  private byte[] downloadAudio(String audioUrl) throws Exception {
    try (InputStream in = new URL(audioUrl).openStream()) {
      java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
      byte[] data = new byte[8192];
      int bytesRead;
      while ((bytesRead = in.read(data, 0, data.length)) != -1) {
        buffer.write(data, 0, bytesRead);
      }
      return buffer.toByteArray();
    }
  }

  @Override
  public void stop() {
    if (isPlaying.get()) {
      System.out.println("[Qwen TTS] 停止播放");
      isPlaying.set(false);
      currentAudioData = null;
      if (audioLine != null) {
        audioLine.flush();
      }
    }
    isSynthesizing.set(false);
  }

  @Override
  public void close() {
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

    isReady.set(false);
    isSynthesizing.set(false);
    isPlaying.set(false);
  }

  @Override
  public boolean isReady() {
    return isReady.get();
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
