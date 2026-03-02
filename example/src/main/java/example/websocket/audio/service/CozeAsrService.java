package example.websocket.audio.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.coze.openapi.client.websocket.event.downstream.TranscriptionsMessageCompletedEvent;
import com.coze.openapi.client.websocket.event.downstream.TranscriptionsMessageUpdateEvent;
import com.coze.openapi.client.websocket.event.model.InputAudio;
import com.coze.openapi.client.websocket.event.model.TranscriptionsUpdateEventData;
import com.coze.openapi.service.service.CozeAPI;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsCallbackHandler;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsClient;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsCreateReq;

public class CozeAsrService implements AsrService {
  private static final int SAMPLE_RATE = 24000;
  private static final int CHANNELS = 1;
  private static final long SILENCE_TIMEOUT_MS = 500; // 2秒无更新则认为识别完成

  private final CozeAPI coze;
  private WebsocketsAudioTranscriptionsClient transcriptionClient;
  private Consumer<String> transcriptionCallback;
  private Consumer<String> finalTranscriptionCallback;
  private Consumer<Exception> errorCallback;
  private volatile boolean isReady = false;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private volatile ScheduledFuture<?> silenceTimer;
  private volatile String lastText = "";
  private volatile boolean hasFinalResult = false;

  public CozeAsrService(CozeAPI coze) {
    this.coze = coze;
  }

  @Override
  public void initialize() {
    try {
      System.out.println("[COZE ASR] 开始初始化...");
      transcriptionClient =
          coze.websockets()
              .audio()
              .transcriptions()
              .create(
                  new WebsocketsAudioTranscriptionsCreateReq(new TranscriptionCallbackHandler()));

      InputAudio inputAudio =
          InputAudio.builder()
              .sampleRate(SAMPLE_RATE)
              .codec("pcm")
              .format("pcm")
              .channel(CHANNELS)
              .bitDepth(16)
              .build();

      TranscriptionsUpdateEventData updateData =
          TranscriptionsUpdateEventData.builder().inputAudio(inputAudio).build();

      transcriptionClient.transcriptionsUpdate(updateData);
      isReady = true;
      System.out.println("[COZE ASR] 初始化完成");
    } catch (Exception e) {
      isReady = false;
      System.err.println("[COZE ASR] 初始化失败: " + e.getMessage());
      e.printStackTrace();
      if (errorCallback != null) {
        errorCallback.accept(e);
      }
    }
  }

  @Override
  public void sendAudio(byte[] audioData) {
    if (transcriptionClient != null && isReady) {
      try {
        transcriptionClient.inputAudioBufferAppend(audioData);
      } catch (Exception e) {
        System.err.println("[COZE ASR] 发送音频失败: " + e.getMessage());
        if (errorCallback != null) {
          errorCallback.accept(e);
        }
      }
    }
  }

  @Override
  public void commit() {
    // Coze ASR 不需要手动 commit
  }

  @Override
  public void close() {
    if (transcriptionClient != null) {
      transcriptionClient.close();
      transcriptionClient = null;
    }
    isReady = false;
    cancelSilenceTimer();
    scheduler.shutdown();
  }

  private void startSilenceTimer() {
    cancelSilenceTimer();
    hasFinalResult = false;
    silenceTimer =
        scheduler.schedule(this::onSilenceTimeout, SILENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  private void cancelSilenceTimer() {
    if (silenceTimer != null) {
      silenceTimer.cancel(false);
      silenceTimer = null;
    }
  }

  private void onSilenceTimeout() {
    if (!lastText.isEmpty() && !hasFinalResult) {
      hasFinalResult = true;
      System.out.println("[COZE ASR] 检测到静默超时，触发最终识别结果");
      if (finalTranscriptionCallback != null) {
        finalTranscriptionCallback.accept(lastText);
      }
    }
  }

  @Override
  public boolean isReady() {
    return isReady;
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

  /** 清空 ASR 音频缓存，用于 TTS 播放前避免 TTS 声音被识别 */
  public void clearAudioBuffer() {
    System.out.println("[COZE ASR] 清空音频缓存");
    // 向 Coze WebSocket 服务器发送清空音频缓存的请求
    if (transcriptionClient != null && isReady) {
      try {
        transcriptionClient.inputAudioBufferClear();
        System.out.println("[COZE ASR] 已发送 inputAudioBufferClear 请求");
      } catch (Exception e) {
        System.err.println("[COZE ASR] 清空音频缓存失败: " + e.getMessage());
        if (errorCallback != null) {
          errorCallback.accept(e);
        }
      }
    }
    // 清空本地缓存的识别文本
    lastText = "";
    // 重置最终结果状态
    hasFinalResult = false;
    // 取消当前的静默定时器，避免误触发
    cancelSilenceTimer();
  }

  private class TranscriptionCallbackHandler extends WebsocketsAudioTranscriptionsCallbackHandler {
    @Override
    public void onTranscriptionsMessageUpdate(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageUpdateEvent event) {
      String text = event.getData().getContent();

      // 如果已经有最终结果，重置状态以处理新的语音输入
      if (hasFinalResult) {
        hasFinalResult = false;
        lastText = "";
      }

      lastText = text;

      // 实时识别结果
      if (transcriptionCallback != null) {
        transcriptionCallback.accept(text);
      }

      // 每次收到更新都重新启动静默定时器
      startSilenceTimer();
    }

    @Override
    public void onTranscriptionsMessageCompleted(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageCompletedEvent event) {
      System.out.println("[COZE ASR] 收到 completed 事件");
      // Coze ASR 通常不会触发此事件，但保留处理逻辑
      cancelSilenceTimer();
      if (finalTranscriptionCallback != null && !lastText.isEmpty() && !hasFinalResult) {
        hasFinalResult = true;
        finalTranscriptionCallback.accept(lastText);
      }
    }
  }
}
