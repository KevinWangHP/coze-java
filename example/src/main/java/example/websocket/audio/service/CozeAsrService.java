package example.websocket.audio.service;

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

  private final CozeAPI coze;
  private WebsocketsAudioTranscriptionsClient transcriptionClient;
  private Consumer<String> transcriptionCallback;
  private Consumer<String> finalTranscriptionCallback;
  private Consumer<Exception> errorCallback;
  private volatile boolean isReady = false;

  public CozeAsrService(CozeAPI coze) {
    this.coze = coze;
  }

  @Override
  public void initialize() {
    try {
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
      System.out.println("[Coze ASR] 初始化完成");
    } catch (Exception e) {
      isReady = false;
      System.err.println("[Coze ASR] 初始化失败: " + e.getMessage());
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
        System.err.println("[Coze ASR] 发送音频失败: " + e.getMessage());
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

  private class TranscriptionCallbackHandler extends WebsocketsAudioTranscriptionsCallbackHandler {
    private String lastText = "";

    @Override
    public void onTranscriptionsMessageUpdate(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageUpdateEvent event) {
      String text = event.getData().getContent();
      lastText = text;
      // 实时识别结果
      if (transcriptionCallback != null) {
        transcriptionCallback.accept(text);
      }
    }

    @Override
    public void onTranscriptionsMessageCompleted(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageCompletedEvent event) {
      System.out.println("[Coze ASR] 转录完成");
      // Coze ASR 在 completed 时触发最终回调
      if (finalTranscriptionCallback != null && !lastText.isEmpty()) {
        finalTranscriptionCallback.accept(lastText);
        lastText = "";
      }
    }
  }
}
