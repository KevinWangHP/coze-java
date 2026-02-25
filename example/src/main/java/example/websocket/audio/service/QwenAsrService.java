package example.websocket.audio.service;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.alibaba.dashscope.audio.omni.*;
import com.google.gson.JsonObject;

public class QwenAsrService implements AsrService {
  private static final String MODEL = "qwen3-asr-flash-realtime";
  private static final String URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";

  private final String apiKey;
  private OmniRealtimeConversation conversation;
  private Consumer<String> transcriptionCallback;
  private Consumer<String> finalTranscriptionCallback;
  private Consumer<Exception> errorCallback;
  private final AtomicBoolean isReady = new AtomicBoolean(false);
  private final AtomicBoolean sessionStarted = new AtomicBoolean(false);
  private final boolean vadMode;
  private String lastTranscription = "";

  public QwenAsrService(String apiKey, boolean vadMode) {
    this.apiKey = apiKey;
    this.vadMode = vadMode;
  }

  @Override
  public void initialize() {
    if (apiKey == null || apiKey.isEmpty()) {
      System.err.println("[Qwen ASR] API Key 未设置");
      return;
    }

    try {
      OmniRealtimeParam param =
          OmniRealtimeParam.builder().model(MODEL).url(URL).apikey(apiKey).build();

      conversation =
          new OmniRealtimeConversation(
              param,
              new OmniRealtimeCallback() {
                @Override
                public void onOpen() {
                  System.out.println("[Qwen ASR] WebSocket 连接已建立");
                  isReady.set(true);
                }

                @Override
                public void onEvent(JsonObject message) {
                  handleEvent(message);
                }

                @Override
                public void onClose(int code, String reason) {
                  isReady.set(false);
                  sessionStarted.set(false);
                  System.out.println("[Qwen ASR] 连接关闭: " + code + ", " + reason);
                }
              });

      conversation.connect();
      TimeUnit.MILLISECONDS.sleep(500);

      configureSession();

    } catch (Exception e) {
      System.err.println("[Qwen ASR] 初始化失败: " + e.getMessage());
      if (errorCallback != null) {
        errorCallback.accept(e);
      }
    }
  }

  private void configureSession() {
    OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
    transcriptionParam.setLanguage("zh");
    transcriptionParam.setInputSampleRate(16000);
    transcriptionParam.setInputAudioFormat("pcm");

    OmniRealtimeConfig.OmniRealtimeConfigBuilder configBuilder =
        OmniRealtimeConfig.builder()
            .modalities(Arrays.asList(OmniRealtimeModality.TEXT))
            .transcriptionConfig(transcriptionParam);

    if (vadMode) {
      configBuilder.enableTurnDetection(true);
      configBuilder.turnDetectionType("server_vad");
      configBuilder.turnDetectionThreshold(0.0f);
      configBuilder.turnDetectionSilenceDurationMs(3000);
      System.out.println("[Qwen ASR] VAD 模式");
    } else {
      configBuilder.enableTurnDetection(false);
      System.out.println("[Qwen ASR] 手动提交模式");
    }

    conversation.updateSession(configBuilder.build());
    sessionStarted.set(true);
  }

  private void handleEvent(JsonObject message) {
    String type = message.get("type").getAsString();

    switch (type) {
      case "session.updated":
        System.out.println("[Qwen ASR] 会话配置已更新");
        sessionStarted.set(true);
        break;
      case "conversation.item.input_audio_transcription.text":
        String text = message.get("text").getAsString();
        String stash = message.has("stash") ? message.get("stash").getAsString() : "";
        String fullText = text + stash;
        lastTranscription = fullText;
        // 实时识别结果
        if (transcriptionCallback != null) {
          transcriptionCallback.accept(fullText);
        }
        break;
      case "conversation.item.input_audio_transcription.completed":
        // 最终识别结果
        String finalText = message.get("transcript").getAsString();
        System.out.println("[Qwen ASR] 最终转录: " + finalText);
        if (finalTranscriptionCallback != null) {
          finalTranscriptionCallback.accept(finalText);
        }
        lastTranscription = "";
        break;
    }
  }

  @Override
  public void sendAudio(byte[] audioData) {
    if (conversation != null && sessionStarted.get()) {
      String audioBase64 = java.util.Base64.getEncoder().encodeToString(audioData);
      conversation.appendAudio(audioBase64);
    }
  }

  @Override
  public void commit() {
    if (conversation != null && !vadMode) {
      conversation.commit();
    }
  }

  @Override
  public void close() {
    if (conversation != null) {
      try {
        conversation.endSession();
      } catch (Exception e) {
        // ignore
      }
      conversation.close();
      conversation = null;
    }
    isReady.set(false);
    sessionStarted.set(false);
  }

  @Override
  public boolean isReady() {
    return isReady.get() && sessionStarted.get();
  }

  @Override
  public void setTranscriptionCallback(Consumer<String> callback) {
    this.transcriptionCallback = callback;
  }

  @Override
  public void setFinalTranscriptionCallback(Consumer<String> callback) {
    this.finalTranscriptionCallback = callback;
  }

  @Override
  public void setErrorCallback(Consumer<Exception> callback) {
    this.errorCallback = callback;
  }
}
