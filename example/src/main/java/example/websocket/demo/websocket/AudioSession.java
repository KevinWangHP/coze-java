package example.websocket.demo.websocket;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.java_websocket.WebSocket;

import com.coze.openapi.service.service.CozeAPI;

import example.websocket.demo.service.*;

public class AudioSession {

  private final String sessionId;
  private final WebSocket webSocket;
  private final ExecutorService executorService;

  // Services
  private AsrService asrService;
  private ChatService chatService;
  private TtsService ttsService;
  private EchoCanceller echoCanceller;

  // State
  private final AtomicBoolean isActive = new AtomicBoolean(true);
  private final AtomicBoolean isAsrProcessing = new AtomicBoolean(false);
  private final AtomicBoolean isWorkflowProcessing = new AtomicBoolean(false);
  private final AtomicBoolean isTtsSynthesizing = new AtomicBoolean(false);
  private final AtomicBoolean interruptSent = new AtomicBoolean(false);

  // Configuration
  private final String asrProvider;
  private final String ttsProvider;
  private final boolean ttsStreaming;

  // Session data
  private volatile String lastAsrText = "";
  private volatile String lastWorkflowOutput = "";
  private volatile byte[] lastTtsAudio = null;  // 用于跟踪流式TTS的第一帧

  // Audio conversion settings
  // 客户端统一使用24kHz，下采样到16kHz音质更好
  private final int CLIENT_SAMPLE_RATE = 24000; // 客户端统一使用24kHz
  private final int COZE_SAMPLE_RATE = 24000;   // Coze ASR使用24kHz
  private final int QWEN_SAMPLE_RATE = 16000;   // Qwen ASR使用16kHz

  public AudioSession(
      String sessionId,
      WebSocket webSocket,
      CozeAPI coze,
      ExecutorService executorService,
      String asrProvider,
      String ttsProvider,
      boolean ttsStreaming,
      String dashscopeApiKey,
      String dashscopeApiBase,
      String botId,
      String userId,
      String voiceId,
      String qwenVoiceId,
      String model)
      throws Exception {

    this.sessionId = sessionId;
    this.webSocket = webSocket;
    this.executorService = executorService;
    this.asrProvider = asrProvider;
    this.ttsProvider = ttsProvider;
    this.ttsStreaming = ttsStreaming;

    initializeServices(
        coze, dashscopeApiKey, dashscopeApiBase, botId, userId, voiceId, qwenVoiceId, model);
  }

  private void initializeServices(
      CozeAPI coze,
      String dashscopeApiKey,
      String dashscopeApiBase,
      String botId,
      String userId,
      String voiceId,
      String qwenVoiceId,
      String model)
      throws Exception {

    System.out.println("[Session " + sessionId + "] 初始化服务...");

    // Initialize Chat Service
    chatService = new ChatService(coze, botId, userId, model);
    if ("QWEN".equalsIgnoreCase(asrProvider)) {
      chatService.createConversation(UUID.randomUUID().toString());
    } else {
      chatService.createConversation();
    }

    // Initialize ASR Service
    if ("QWEN".equalsIgnoreCase(asrProvider)) {
      asrService = new QwenAsrService(dashscopeApiKey, dashscopeApiBase, true);
    } else {
      asrService = new CozeAsrService(coze);
    }
    asrService.initialize();
    asrService.setTranscriptionCallback(this::onTranscription);
    asrService.setFinalTranscriptionCallback(this::onFinalTranscription);
    asrService.setErrorCallback(this::onError);

    // Initialize TTS Service
    if ("QWEN".equalsIgnoreCase(ttsProvider)) {
      ttsService = new QwenTtsService(dashscopeApiKey, dashscopeApiBase);
      ttsService.setAudioCallback(this::onTtsAudio);
    } else if (ttsStreaming) {
      System.out.println("[Session " + sessionId + "] 使用Coze流式TTS模式");
      ttsService = new CozeStreamingTtsService(coze, voiceId);
      ttsService.setAudioCallback(this::onStreamingTtsAudio);
    } else {
      System.out.println("[Session " + sessionId + "] 使用Coze非流式TTS模式");
      ttsService = new CozeTtsService(coze);
      ttsService.setAudioCallback(this::onTtsAudio);
    }
    ttsService.initialize();
    ttsService.setErrorCallback(this::onError);

    // Initialize Echo Canceller
    echoCanceller = new EchoCanceller();
    echoCanceller.setSampleRate(24000);

    System.out.println("[Session " + sessionId + "] 服务初始化完成");
  }

  public void processAudioData(byte[] audioData) {
    if (!isActive.get() || !asrService.isReady()) {
      return;
    }

    // Echo detection - only when ASR is not processing
    if (!isAsrProcessing.get() && echoCanceller != null) {
      if (echoCanceller.isEcho(audioData)) {
        return; // Skip echo
      }
    }

    // 根据ASR提供商转换采样率
    // 客户端统一发送24kHz，Qwen需要下采样到16kHz
    byte[] convertedAudio = convertAudioForASR(audioData);

    // Send to ASR
    asrService.sendAudio(convertedAudio);
  }

  /**
   * 将客户端音频数据转换为ASR服务需要的格式
   * 客户端统一发送24kHz音频，这里根据ASR提供商进行转换
   */
  private byte[] convertAudioForASR(byte[] audioData) {
    if ("QWEN".equalsIgnoreCase(asrProvider)) {
      // Qwen ASR 使用 16kHz，需要下采样（下采样不会丢失音质）
      return AudioConverter.convert24kTo16k(audioData);
    } else {
      // Coze ASR 使用 24kHz，无需转换
      return audioData;
    }
  }

  private void onTranscription(String text) {
    if (text == null || text.trim().isEmpty()) {
      return;
    }

    // Mark ASR as processing
    isAsrProcessing.set(true);

    // 实时识别时打印日志
    System.out.println("[Session " + sessionId + "] ASR实时识别: " + text);

    // Send transcription to client
    sendTranscription(text, false);

    // 实时识别时发送打断信号给客户端
    sendInterruptToClient();
  }

  private void onFinalTranscription(String text) {
    System.out.println("[Session " + sessionId + "] ASR最终识别: " + text);

    if (text == null || text.trim().isEmpty()) {
      isAsrProcessing.set(false);
      return;
    }

    lastAsrText = text;

    // Send final transcription to client
    sendTranscription(text, true);

    // Interrupt any ongoing TTS
    interrupt();

    // Send to chat bot
    final String asrText = text;
    executorService.submit(
        () -> {
          interrupt();

          isWorkflowProcessing.set(true);
          isAsrProcessing.set(false);

          chatService.sendMessage(
              asrText,
              chatId -> {
                // onResponseStart
                System.out.println("[Session " + sessionId + "] Chat开始响应: " + chatId);
                interrupt();
              },
              delta -> {
                // onResponseDelta - not used
              },
              response -> {
                // onResponseComplete
                System.out.println("[Session " + sessionId + "] Chat响应完成: " + response);
                isWorkflowProcessing.set(false);
                lastWorkflowOutput = response;
                sendChatResponse(response);
                synthesizeSpeech(response);
              },
              this::onError);
        });
  }

  private void synthesizeSpeech(String text) {
    if (text == null || text.trim().isEmpty()) {
      return;
    }

    // Parse tone
    String tone = "";
    String content = text;
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[(.*?)\\]");
    java.util.regex.Matcher matcher = pattern.matcher(text);
    if (matcher.find()) {
      tone = matcher.group(1);
      content = matcher.replaceFirst("").trim();
    }

    String voiceId =
        "QWEN".equalsIgnoreCase(ttsProvider)
            ? System.getenv().getOrDefault("QWEN_VOICE_ID", "longxiaochun")
            : System.getenv().getOrDefault("COZE_VOICE_ID", "alloy");

    // 重置TTS音频缓存，用于流式TTS判断第一帧
    lastTtsAudio = null;
    interruptSent.set(false);
    isTtsSynthesizing.set(true);

    ttsService.synthesize(content, voiceId, tone);

    // For non-streaming TTS, reset state immediately
    if (!(ttsStreaming && "COZE".equalsIgnoreCase(ttsProvider))) {
      isTtsSynthesizing.set(false);
    } else {
      // For streaming TTS, wait for completion
      executorService.submit(
          () -> {
            try {
              int waitCount = 0;
              while (ttsService.isPlaying() && waitCount < 300) {
                Thread.sleep(100);
                waitCount++;
              }
              isTtsSynthesizing.set(false);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }
  }

  private void onTtsAudio(byte[] audioData) {
    // For non-streaming TTS
    isTtsSynthesizing.set(false);

    // Clear ASR buffer before playing (只调用一次)
    if (asrService instanceof CozeAsrService) {
      ((CozeAsrService) asrService).clearAudioBuffer();
    }

    // Send audio to client
    sendAudioOutput(audioData, true);
  }

  private void onStreamingTtsAudio(byte[] audioData) {
    // For streaming TTS - send audio chunks to client
    // 只在第一次收到音频时清空ASR缓存，避免重复调用
    if (asrService instanceof CozeAsrService && lastTtsAudio == null) {
      ((CozeAsrService) asrService).clearAudioBuffer();
    }

    // 累积音频数据用于判断是否是第一帧
    if (lastTtsAudio == null) {
      lastTtsAudio = audioData;
    } else {
      // 合并音频数据
      byte[] combined = new byte[lastTtsAudio.length + audioData.length];
      System.arraycopy(lastTtsAudio, 0, combined, 0, lastTtsAudio.length);
      System.arraycopy(audioData, 0, combined, lastTtsAudio.length, audioData.length);
      lastTtsAudio = combined;
    }

    // Send audio chunk to client (not final)
    sendAudioOutput(audioData, false);
  }

  public void interrupt() {
    // 停止 TTS 服务
    if (ttsService != null) {
      ttsService.stop();
    }
    isTtsSynthesizing.set(false);

    // 发送打断信号给客户端，让客户端清空播放缓存并停止播放
    if (!interruptSent.get()) {
      interruptSent.set(true);
      try {
        AudioWebSocketMessage msg = AudioWebSocketMessage.createInterrupt(sessionId);
        webSocket.send(msg.toJson());
      } catch (Exception e) {
        System.err.println("[Session " + sessionId + "] 发送打断信号失败: " + e.getMessage());
      }
    }
  }

  public void sendInterruptToClient() {
    // 只发送打断信号，不停止 TTS
    if (!interruptSent.get()) {
      interruptSent.set(true);
      try {
        AudioWebSocketMessage msg = AudioWebSocketMessage.createInterrupt(sessionId);
        webSocket.send(msg.toJson());
      } catch (Exception e) {
        System.err.println("[Session " + sessionId + "] 发送打断信号失败: " + e.getMessage());
      }
    }
  }

  private void onError(Exception e) {
    System.err.println("[Session " + sessionId + "] 错误: " + e.getMessage());
    sendError(e.getMessage());
  }

  // WebSocket message senders

  private void sendTranscription(String text, boolean isFinal) {
    try {
      AudioWebSocketMessage msg =
          AudioWebSocketMessage.createTranscription(sessionId, text, isFinal);
      webSocket.send(msg.toJson());
    } catch (Exception e) {
      System.err.println("[Session " + sessionId + "] 发送转录消息失败: " + e.getMessage());
    }
  }

  private void sendChatResponse(String text) {
    try {
      AudioWebSocketMessage msg = AudioWebSocketMessage.createChatResponse(sessionId, text);
      webSocket.send(msg.toJson());
    } catch (Exception e) {
      System.err.println("[Session " + sessionId + "] 发送Chat响应消息失败: " + e.getMessage());
    }
  }

  private void sendAudioOutput(byte[] audioData, boolean isFinal) {
    try {
      // 添加到回声检测器
      if (echoCanceller != null) {
        echoCanceller.addPlaybackAudio(audioData);
      }

      String base64Audio = Base64.getEncoder().encodeToString(audioData);
      AudioWebSocketMessage msg =
          AudioWebSocketMessage.createAudioOutput(sessionId, base64Audio, isFinal);
      webSocket.send(msg.toJson());
    } catch (Exception e) {
      System.err.println("[Session " + sessionId + "] 发送音频消息失败: " + e.getMessage());
    }
  }

  private void sendError(String error) {
    try {
      AudioWebSocketMessage msg = AudioWebSocketMessage.createError(sessionId, error);
      webSocket.send(msg.toJson());
    } catch (Exception e) {
      System.err.println("[Session " + sessionId + "] 发送错误消息失败: " + e.getMessage());
    }
  }

  public void close() {
    if (!isActive.compareAndSet(true, false)) {
      return;
    }

    System.out.println("[Session " + sessionId + "] 正在关闭...");

    if (asrService != null) {
      asrService.close();
    }

    if (ttsService != null) {
      ttsService.close();
    }

    System.out.println("[Session " + sessionId + "] 已关闭");
  }

  public String getSessionId() {
    return sessionId;
  }

  public boolean isActive() {
    return isActive.get();
  }
}
