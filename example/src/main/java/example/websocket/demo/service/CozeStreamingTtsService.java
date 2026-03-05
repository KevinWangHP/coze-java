package example.websocket.demo.service;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.sound.sampled.*;

import com.coze.openapi.client.websocket.event.downstream.SpeechAudioCompletedEvent;
import com.coze.openapi.client.websocket.event.downstream.SpeechAudioUpdateEvent;
import com.coze.openapi.client.websocket.event.downstream.SpeechCreatedEvent;
import com.coze.openapi.client.websocket.event.model.OutputAudio;
import com.coze.openapi.client.websocket.event.model.PCMConfig;
import com.coze.openapi.client.websocket.event.model.SpeechUpdateEventData;
import com.coze.openapi.service.service.CozeAPI;
import com.coze.openapi.service.service.websocket.audio.speech.WebsocketsAudioSpeechCallbackHandler;
import com.coze.openapi.service.service.websocket.audio.speech.WebsocketsAudioSpeechClient;
import com.coze.openapi.service.service.websocket.audio.speech.WebsocketsAudioSpeechCreateReq;

/** Coze 流式TTS服务 - 使用WebSocket实现实时语音合成 每次合成时创建新的WebSocket连接，避免长连接问题 */
public class CozeStreamingTtsService implements TtsService {
  private static final int SAMPLE_RATE = 24000;
  private static final int BUFFER_THRESHOLD_MS = 200; // 缓存阈值：收到200ms音频后开始播放
  private static final int BYTES_PER_MS = (SAMPLE_RATE * 2) / 1000; // 16bit PCM, 单声道

  private final CozeAPI coze;
  private final String voiceId;
  private Consumer<byte[]> audioCallback;
  private Consumer<Exception> errorCallback;
  private volatile boolean isReady = false;

  // WebSocket客户端 - 每次合成时创建
  private volatile WebsocketsAudioSpeechClient speechClient;

  // 音频缓存池
  private final ConcurrentLinkedQueue<byte[]> audioBuffer = new ConcurrentLinkedQueue<>();
  private final AtomicInteger bufferedBytes = new AtomicInteger(0);
  private final AtomicBoolean isPlaying = new AtomicBoolean(false);
  private final AtomicBoolean isSynthesizing = new AtomicBoolean(false);
  private final AtomicBoolean stopRequested = new AtomicBoolean(false);

  // 音频播放
  private SourceDataLine audioLine;
  private Thread playThread;
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  // 时延统计
  private volatile long synthesisStartTime = 0; // 合成开始时间
  private volatile long firstAudioReceivedTime = 0; // 第一次收到音频的时间
  private volatile long playbackStartTime = 0; // 开始播放的时间

  public CozeStreamingTtsService(CozeAPI coze, String voiceId) {
    this.coze = coze;
    this.voiceId = voiceId != null && !voiceId.isEmpty() ? voiceId : "alloy";
  }

  @Override
  public void initialize() {
    try {
      System.out.println("[COZE Streaming TTS] 开始初始化...");
      // 只初始化音频播放器，WebSocket连接在合成时创建
      initAudioPlayer();
      isReady = true;
      System.out.println("[COZE Streaming TTS] 初始化完成，Voice ID: " + voiceId);
    } catch (Exception e) {
      isReady = false;
      System.err.println("[COZE Streaming TTS] 初始化失败: " + e.getMessage());
      e.printStackTrace();
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
      int bufferSize = SAMPLE_RATE * 2 * 2; // 2秒缓冲
      audioLine.open(format, bufferSize);
      audioLine.start();

      stopped.set(false);
      playThread = new Thread(this::playLoop, "StreamingTtsPlayer");
      playThread.setDaemon(true);
      playThread.start();

    } catch (Exception e) {
      System.err.println("[COZE Streaming TTS] 音频播放器初始化失败: " + e.getMessage());
      throw new RuntimeException(e);
    }
  }

  /** 播放循环 - 从缓存池中读取音频数据并播放 */
  private void playLoop() {
    while (!stopped.get()) {
      // 如果收到停止请求，清空缓存并停止播放
      if (stopRequested.get()) {
        audioBuffer.clear();
        bufferedBytes.set(0);
        isPlaying.set(false);
        // 重置停止请求，准备下一次播放
        stopRequested.set(false);
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        continue;
      }

      if (isPlaying.get() && !audioBuffer.isEmpty()) {
        byte[] audioData = audioBuffer.poll();
        if (audioData != null) {
          bufferedBytes.addAndGet(-audioData.length);
          int bytesWritten = 0;
          while (bytesWritten < audioData.length && isPlaying.get() && !stopped.get()) {
            if (stopRequested.get()) {
              break;
            }
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
        }
      } else {
        // 如果缓存为空且合成已完成，停止播放
        if (!isSynthesizing.get() && audioBuffer.isEmpty() && isPlaying.get()) {
          isPlaying.set(false);
        }
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
    if (text == null || text.trim().isEmpty()) {
      return;
    }

    // 停止之前的播放和连接
    stop();

    String content = text.trim();
    System.out.println(
        "[COZE Streaming TTS] 开始流式合成: "
            + content.substring(0, Math.min(30, content.length()))
            + "...");

    // 记录合成开始时间
    synthesisStartTime = System.currentTimeMillis();
    firstAudioReceivedTime = 0;
    playbackStartTime = 0;

    // 重置所有状态
    isSynthesizing.set(true);
    isPlaying.set(false);
    stopRequested.set(false);

    try {
      // 清空缓存
      audioBuffer.clear();
      bufferedBytes.set(0);

      // 创建新的WebSocket连接
      speechClient =
          coze.websockets()
              .audio()
              .speech()
              .create(new WebsocketsAudioSpeechCreateReq(new SpeechCallbackHandler()));

      // 配置语音参数
      PCMConfig pcmConfig = PCMConfig.builder().sampleRate(SAMPLE_RATE).build();

      OutputAudio outputAudio =
          OutputAudio.builder().voiceId(this.voiceId).codec("pcm").pcmConfig(pcmConfig).build();

      SpeechUpdateEventData updateData =
          SpeechUpdateEventData.builder().outputAudio(outputAudio).build();
      speechClient.speechUpdate(updateData);

      // 发送文本到TTS服务
      speechClient.inputTextBufferAppend(content);
      speechClient.inputTextBufferComplete();

    } catch (Exception e) {
      System.err.println("[COZE Streaming TTS] 合成失败: " + e.getMessage());
      isSynthesizing.set(false);
      if (errorCallback != null) {
        errorCallback.accept(e);
      }
    }
  }

  /** 将音频数据添加到缓存池 */
  private void addAudioToBuffer(byte[] audioData) {
    if (audioData == null || audioData.length == 0) {
      return;
    }

    // 如果收到停止请求，不再添加音频到缓存
    if (stopRequested.get()) {
      return;
    }

    // 如果不在合成状态，也不再添加音频
    if (!isSynthesizing.get()) {
      return;
    }

    // 记录第一次收到音频的时间
    if (firstAudioReceivedTime == 0) {
      firstAudioReceivedTime = System.currentTimeMillis();
      long timeToFirstAudio = firstAudioReceivedTime - synthesisStartTime;
      System.out.println("[COZE Streaming TTS] 首包时延: " + timeToFirstAudio + " ms (从调用合成到收到第一帧音频)");
    }

    audioBuffer.offer(audioData);
    int totalBytes = bufferedBytes.addAndGet(audioData.length);

    // 回调音频数据给外部（用于性能统计等）
    if (audioCallback != null) {
      audioCallback.accept(audioData);
    }

    // 当缓存达到阈值时开始播放
    int thresholdBytes = BUFFER_THRESHOLD_MS * BYTES_PER_MS;
    if (!isPlaying.get() && totalBytes >= thresholdBytes && isSynthesizing.get()) {
      playbackStartTime = System.currentTimeMillis();
      long timeToPlayback = playbackStartTime - synthesisStartTime;
      System.out.println(
          "[COZE Streaming TTS] 缓存达到阈值，开始播放 ("
              + totalBytes
              + " bytes)，播放时延: "
              + timeToPlayback
              + " ms");
      isPlaying.set(true);
    }
  }

  @Override
  public void stop() {
    if (isPlaying.get() || isSynthesizing.get()) {
      System.out.println("[COZE Streaming TTS] 停止播放/合成");
      // 设置停止请求标志，让播放循环处理停止逻辑
      stopRequested.set(true);
      isSynthesizing.set(false);

      // 立即清空音频行，停止当前播放
      if (audioLine != null) {
        audioLine.flush();
      }

      // 关闭WebSocket连接
      if (speechClient != null) {
        try {
          speechClient.close();
        } catch (Exception e) {
          // ignore
        }
        speechClient = null;
      }

      // 重置时间戳
      synthesisStartTime = 0;
      firstAudioReceivedTime = 0;
      playbackStartTime = 0;
    }
  }

  @Override
  public void close() {
    System.out.println("[COZE Streaming TTS] 关闭服务...");
    stopped.set(true);
    stop();

    if (playThread != null) {
      playThread.interrupt();
      try {
        playThread.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    if (audioLine != null) {
      audioLine.close();
      audioLine = null;
    }

    isReady = false;
  }

  @Override
  public boolean isReady() {
    return isReady;
  }

  @Override
  public boolean isPlaying() {
    return isPlaying.get() || isSynthesizing.get();
  }

  @Override
  public void setAudioCallback(Consumer<byte[]> callback) {
    this.audioCallback = callback;
  }

  @Override
  public void setErrorCallback(Consumer<Exception> callback) {
    this.errorCallback = callback;
  }

  /** WebSocket回调处理器 */
  private class SpeechCallbackHandler extends WebsocketsAudioSpeechCallbackHandler {
    @Override
    public void onSpeechCreated(WebsocketsAudioSpeechClient client, SpeechCreatedEvent event) {
      System.out.println("[COZE Streaming TTS] 语音合成会话已创建");
    }

    @Override
    public void onSpeechAudioUpdate(
        WebsocketsAudioSpeechClient client, SpeechAudioUpdateEvent event) {
      // 收到音频数据片段
      byte[] audioData = event.getDelta();
      if (audioData != null && audioData.length > 0) {
        addAudioToBuffer(audioData);
      }
    }

    @Override
    public void onSpeechAudioCompleted(
        WebsocketsAudioSpeechClient client, SpeechAudioCompletedEvent event) {
      System.out.println("[COZE Streaming TTS] 音频合成完成");
      isSynthesizing.set(false);

      // 如果缓存中还有数据，继续播放直到完成
      if (!audioBuffer.isEmpty() && !isPlaying.get()) {
        isPlaying.set(true);
      }

      // 启动一个后台线程等待播放完成
      new Thread(
              () -> {
                int waitCount = 0;
                // 最多等待30秒
                while ((isPlaying.get() || !audioBuffer.isEmpty()) && waitCount < 300) {
                  try {
                    Thread.sleep(100);
                    waitCount++;
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                  }
                }
                // 确保播放状态被重置
                isPlaying.set(false);
                // 关闭WebSocket连接
                if (speechClient != null) {
                  try {
                    speechClient.close();
                  } catch (Exception e) {
                    // ignore
                  }
                  speechClient = null;
                }
                System.out.println("[COZE Streaming TTS] 播放完成，可以接收新输入");
              },
              "TtsCompletionMonitor")
          .start();
    }

    @Override
    public void onError(
        WebsocketsAudioSpeechClient client,
        com.coze.openapi.client.websocket.event.downstream.ErrorEvent event) {
      System.err.println("[COZE Streaming TTS] 错误: " + event.getData().getMsg());
      isSynthesizing.set(false);
      if (errorCallback != null) {
        errorCallback.accept(new RuntimeException(event.getData().getMsg()));
      }
    }
  }
}
