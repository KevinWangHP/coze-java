package example.websocket.audio.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.sound.sampled.*;

import com.coze.openapi.client.audio.speech.CreateSpeechReq;
import com.coze.openapi.client.audio.speech.CreateSpeechResp;
import com.coze.openapi.service.service.CozeAPI;

public class CozeTtsService implements TtsService {
  private final CozeAPI coze;
  private Consumer<byte[]> audioCallback;
  private Consumer<Exception> errorCallback;
  private volatile boolean isReady = true;

  // Audio playback
  private SourceDataLine audioLine;
  private Thread playThread;
  private final AtomicBoolean isPlaying = new AtomicBoolean(false);
  private final AtomicBoolean stopped = new AtomicBoolean(false);
  private volatile byte[] currentAudioData = null;

  public CozeTtsService(CozeAPI coze) {
    this.coze = coze;
  }

  @Override
  public void initialize() {
    initAudioPlayer();
    isReady = true;
  }

  private void initAudioPlayer() {
    try {
      AudioFormat format = new AudioFormat(24000, 16, 1, true, false);
      DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
      audioLine = (SourceDataLine) AudioSystem.getLine(info);
      int bufferSize = 24000 * 2 * 2;
      audioLine.open(format, bufferSize);
      audioLine.start();
      stopped.set(false);

      playThread = new Thread(this::playLoop, "COZETtsPlayer");
      playThread.setDaemon(true);
      playThread.start();

    } catch (Exception e) {
      System.err.println("[COZE TTS] 音频播放器初始化失败: " + e.getMessage());
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
        // 播放完成或被打断
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
      String contextTexts = tone;

      String actualVoiceId = (voiceId != null && !voiceId.isEmpty()) ? voiceId : "alloy";

      System.out.println(
          "[COZE TTS] 开始合成: " + content.substring(0, Math.min(30, content.length())) + "...");
      if (contextTexts != null && !contextTexts.isEmpty()) {
        System.out.println("[COZE TTS] 语气指令: " + contextTexts);
      }

      // 构建请求
      CreateSpeechReq.CreateSpeechReqBuilder reqBuilder =
          CreateSpeechReq.builder()
              .input(content)
              .voiceID(actualVoiceId)
              .responseFormat(com.coze.openapi.client.audio.common.AudioFormat.WAV)
              .sampleRate(24000);

      // 根据文档，使用 context_texts 参数控制语音情绪/语气
      // 仅当 voice_id 为豆包语音合成大模型 2.0 音色时才支持该参数
      if (contextTexts != null && !contextTexts.isEmpty()) {
        reqBuilder.contextTexts(contextTexts);
      }

      CreateSpeechReq speechReq = reqBuilder.build();

      CreateSpeechResp speechResp = coze.audio().speech().create(speechReq);
      byte[] audioData = speechResp.getResponse().bytes();

      System.out.println("[COZE TTS] 收到音频数据: " + audioData.length + " 字节");

      // 回调音频数据
      if (audioCallback != null) {
        audioCallback.accept(audioData);
      }

      // 开始播放
      currentAudioData = audioData;
      isPlaying.set(true);

      System.out.println("[COZE TTS] 开始播放音频");

    } catch (Exception e) {
      System.err.println("[COZE TTS] 合成失败: " + e.getMessage());
      isPlaying.set(false);
      if (errorCallback != null) {
        errorCallback.accept(e);
      }
    }
  }

  @Override
  public void stop() {
    if (isPlaying.get()) {
      System.out.println("[COZE TTS] 停止播放");
      isPlaying.set(false);
      currentAudioData = null;
      if (audioLine != null) {
        audioLine.flush();
      }
    }
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

    isReady = false;
  }

  @Override
  public boolean isReady() {
    return isReady;
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
