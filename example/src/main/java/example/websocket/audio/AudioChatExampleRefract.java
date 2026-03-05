package example.websocket.audio;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.coze.openapi.service.auth.TokenAuth;
import com.coze.openapi.service.service.CozeAPI;

import example.websocket.audio.config.AppConfig;
import example.websocket.audio.service.*;

public class AudioChatExampleRefract {

  // Configuration
  private final AppConfig config = AppConfig.getInstance();
  private final String SPEECH_SERVICE = config.getSpeechService();
  private final String ASR_PROVIDER = config.getAsrProvider();
  private final String TTS_PROVIDER = config.getTtsProvider();
  private final String DASHSCOPE_API_KEY = config.getQwenApiKey();
  private final String DASHSCOPE_API_BASE = config.getQwenBaseUrl();
  private final String COZE_API_TOKEN = config.getCozeApiToken();
  private final String COZE_API_BASE = config.getCozeApiBase();
  private final String BOT_ID = config.getCozeBotId();
  private final String USER_ID = config.getUserId();
  private final String VOICE_ID = config.getCozeVoiceId();
  private final String QWEN_VOICE_ID = config.getQwenVoiceId();
  private final String MODEL = config.getLlmModel();
  private final boolean TTS_STREAMING = config.isTtsStreaming();

  // State
  private final AtomicBoolean isRunning = new AtomicBoolean(true);
  private final AtomicBoolean isResponding = new AtomicBoolean(false);
  private final AtomicBoolean userSpeaking = new AtomicBoolean(false);
  private volatile long userSpeakingStartTime = 0;
  private static final long USER_SPEAKING_TIMEOUT = 5000;

  // Audio playback control flags
  // When any of these is true, audio playback should be blocked
  private final AtomicBoolean isAsrProcessing = new AtomicBoolean(false);
  private final AtomicBoolean isWorkflowProcessing = new AtomicBoolean(false);
  private final AtomicBoolean isTtsSynthesizing = new AtomicBoolean(false);

  // Services
  private AudioRecorder audioRecorder;
  private AsrService asrService;
  private ChatService chatService;
  private TtsService ttsService;
  private AudioPlayer audioPlayer;
  private EchoCanceller echoCanceller;
  private ExecutorService executorService;

  // Performance Logger
  private PerformanceLogger performanceLogger;

  // Session tracking for performance metrics
  private volatile String lastAsrText = "";
  private volatile String lastWorkflowOutput = "";
  private volatile byte[] lastTtsAudio = null;
  private volatile long sessionStartTime = 0;

  public static void main(String[] args) {
    try {
      AudioChatExampleRefract app = new AudioChatExampleRefract();
      app.initialize();
      app.run();
    } catch (Exception e) {
      System.err.println("[Main] 错误: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private void initialize() throws Exception {
    System.out.println("[系统] 初始化服务...");

    // 打印配置信息
    System.out.println("[配置] ASR Provider: " + ASR_PROVIDER);
    System.out.println("[配置] TTS Provider: " + TTS_PROVIDER);
    System.out.println("[配置] Coze API Base: " + COZE_API_BASE);
    System.out.println("[配置] Bot ID: " + BOT_ID);
    System.out.println("[配置] User ID: " + USER_ID);

    // Get sample rate based on ASR provider
    int sampleRate = config.getAsrSampleRate();
    System.out.println("[音频] 麦克风采样率设置为: " + sampleRate + "Hz (ASR: " + ASR_PROVIDER + ")");

    // Initialize Performance Logger (16-bit PCM = 2 bytes per sample)
    performanceLogger = new PerformanceLogger(sampleRate, 2);
    System.out.println("[性能日志] 已初始化，日志文件将保存到当前目录");

    // Initialize Echo Canceller with sample rate
    echoCanceller = new EchoCanceller();
    echoCanceller.setSampleRate(sampleRate);

    // Initialize Audio Player
    audioPlayer = new AudioPlayer(echoCanceller);

    // Initialize Executor
    executorService =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "AudioWorker");
              t.setDaemon(true);
              return t;
            });

    // Initialize Coze API
    CozeAPI coze =
        new CozeAPI.Builder()
            .baseURL(COZE_API_BASE)
            .auth(new TokenAuth(COZE_API_TOKEN))
            .readTimeout(30000)
            .build();

    // Initialize Chat Service
    System.out.println("[ChatService] 初始化, SPEECH_SERVICE=" + SPEECH_SERVICE);
    chatService = new ChatService(coze, BOT_ID, USER_ID, MODEL);
    if ("QWEN".equalsIgnoreCase(SPEECH_SERVICE)) {
      System.out.println("[ChatService] 使用QWEN模式，创建自定义会话ID");
      chatService.createConversation(UUID.randomUUID().toString());
    } else {
      System.out.println("[ChatService] 使用COZE模式，调用API创建会话");
      chatService.createConversation();
    }

    // Initialize ASR Service
    if ("QWEN".equalsIgnoreCase(ASR_PROVIDER)) {
      asrService = new QwenAsrService(DASHSCOPE_API_KEY, DASHSCOPE_API_BASE, true);
    } else {
      asrService = new CozeAsrService(coze);
    }
    asrService.initialize();
    asrService.setTranscriptionCallback(this::onTranscription);
    asrService.setFinalTranscriptionCallback(this::onFinalTranscription);
    asrService.setErrorCallback(this::onError);

    // Initialize TTS Service
    if ("QWEN".equalsIgnoreCase(TTS_PROVIDER)) {
      ttsService = new QwenTtsService(DASHSCOPE_API_KEY, DASHSCOPE_API_BASE);
      ttsService.setAudioCallback(this::onQwenTtsAudio);
    } else if (TTS_STREAMING) {
      // Coze 流式TTS (WebSocket)
      System.out.println("[TTS] 使用Coze流式TTS模式 (WebSocket)");
      ttsService = new CozeStreamingTtsService(coze, VOICE_ID);
      // 流式TTS内部自己处理播放，不需要外部回调
      ttsService.setAudioCallback(this::onStreamingTtsAudio);
    } else {
      // Coze 非流式TTS (HTTP)
      System.out.println("[TTS] 使用Coze非流式TTS模式 (HTTP)");
      ttsService = new CozeTtsService(coze);
      ttsService.setAudioCallback(this::onCozeTtsAudio);
    }
    ttsService.initialize();
    ttsService.setErrorCallback(this::onError);

    // Initialize Audio Recorder with sample rate based on ASR provider
    audioRecorder = new AudioRecorder(sampleRate);

    // Setup shutdown hook
    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

    System.out.println("[系统] 初始化完成");
  }

  private void run() throws Exception {
    // Select audio device
    List<AudioDevice> devices = audioRecorder.getAudioDevices();
    if (devices.isEmpty()) {
      System.err.println("[错误] 没有可用的音频输入设备");
      return;
    }

    System.out.println("可用的音频输入设备:");
    for (int i = 0; i < devices.size(); i++) {
      System.out.println((i + 1) + ". " + devices.get(i).getDeviceName());
    }

    AudioDevice selectedDevice = selectDevice(devices);
    System.out.println("\n选择的音频设备: " + selectedDevice.getDeviceName());

    // Open device
    audioRecorder.openDevice(selectedDevice);

    // Start recording
    System.out.println("[系统] 开始录音...");
    audioRecorder.startRecording(this::processAudioData, this::onError);

    // Wait for user to stop
    System.out.println("按 Enter 键停止程序...");
    new BufferedReader(new InputStreamReader(System.in)).readLine();

    isRunning.set(false);
  }

  private AudioDevice selectDevice(List<AudioDevice> devices) {
    System.out.print("\n请选择音频设备编号 (1-" + devices.size() + "): ");
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
    try {
      String input = reader.readLine();
      int index = Integer.parseInt(input.trim()) - 1;
      if (index >= 0 && index < devices.size()) {
        return devices.get(index);
      }
    } catch (Exception e) {
      // ignore
    }
    System.out.println("[警告] 无效选择，使用第一个设备");
    return devices.get(0);
  }

  private void processAudioData(AudioRecorder.AudioData audioData) {
    if (!isRunning.get()) {
      return;
    }

    // Check user speaking timeout
    if (userSpeaking.get()
        && System.currentTimeMillis() - userSpeakingStartTime > USER_SPEAKING_TIMEOUT) {
      userSpeaking.set(false);
    }

    // Echo detection
    boolean isEcho = false;
    if (!userSpeaking.get()) {
      isEcho = echoCanceller.isEcho(audioData.getData());
    }

    // Send to ASR (skip echo)
    if (!isEcho && asrService.isReady()) {
      asrService.sendAudio(audioData.getData());
      // 累积ASR音频数据用于性能统计
      if (performanceLogger != null) {
        performanceLogger.accumulateAsrAudio(audioData.getData());
      }
    }

    // Sleep to avoid busy loop
    try {
      TimeUnit.MILLISECONDS.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void onTranscription(String text) {
    // 实时识别结果
    System.out.println("[" + ASR_PROVIDER + " ASR] 实时识别: " + text);

    if (text == null || text.trim().isEmpty()) {
      return;
    }

    // 标记 ASR 正在处理
    isAsrProcessing.set(true);

    // 只要有识别结果就打断播放（ASR阶段）
    interruptPlayback();

    // 记录ASR会话开始时间（首次识别到内容）
    if (performanceLogger != null && sessionStartTime == 0) {
      sessionStartTime = System.currentTimeMillis();
      performanceLogger.startAsrSession();
    }
  }

  private void onFinalTranscription(String text) {
    // 最终识别结果，发送到 Chat Bot
    System.out.println("[" + ASR_PROVIDER + " ASR] 最终识别: " + text);

    if (text == null || text.trim().isEmpty()) {
      isAsrProcessing.set(false);
      // 重置会话开始时间和ASR累积数据
      sessionStartTime = 0;
      if (performanceLogger != null) {
        performanceLogger.resetAsrAccumulation();
      }
      return;
    }

    // 保存ASR文本用于性能日志
    lastAsrText = text;

    // 记录ASR性能指标（内部会自动重置ASR累积数据）
    if (performanceLogger != null) {
      performanceLogger.logAsrResult(text);
      // 标记端到端统计开始（从ASR最后一次收到音频开始）
      performanceLogger.markEndToEndStart();
    }

    // 重置端到端会话开始时间，从Workflow开始重新计时
    sessionStartTime = System.currentTimeMillis();

    // 打断播放（ASR最终阶段）
    interruptPlayback();

    // Send to chat bot
    final String asrText = text;
    executorService.submit(
        () -> {
          // Bot调用阶段开始前再次打断
          interruptPlayback();

          // 标记 Workflow 开始处理
          isWorkflowProcessing.set(true);
          // ASR 处理完成
          isAsrProcessing.set(false);

          // 记录Workflow开始时间
          if (performanceLogger != null) {
            performanceLogger.startWorkflow();
          }

          chatService.sendMessage(
              asrText,
              chatId -> {
                // onResponseStart
                System.out.println("[Chat] 开始响应: " + chatId);
                // Bot开始响应时打断播放
                interruptPlayback();
              },
              delta -> {
                // onResponseDelta
                // Streamed response (not used for TTS)
              },
              response -> {
                // onResponseComplete
                System.out.println("[Chat] 响应完成: " + response);
                // Workflow 处理完成
                isWorkflowProcessing.set(false);
                // 记录Workflow性能指标
                if (performanceLogger != null) {
                  performanceLogger.logWorkflowResult(asrText, response);
                }
                lastWorkflowOutput = response;
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

    String voiceId = "QWEN".equalsIgnoreCase(TTS_PROVIDER) ? QWEN_VOICE_ID : VOICE_ID;

    // 标记 TTS 开始合成
    isTtsSynthesizing.set(true);
    isResponding.set(true);

    // 重置TTS音频缓存（用于流式TTS累积）
    lastTtsAudio = null;

    // 记录TTS开始时间
    if (performanceLogger != null) {
      performanceLogger.startTts();
    }

    ttsService.synthesize(content, voiceId, tone);

    // 如果是流式TTS，状态在播放完成后重置
    // 非流式TTS立即重置状态
    if (!(TTS_STREAMING && "COZE".equalsIgnoreCase(TTS_PROVIDER))) {
      isResponding.set(false);
      isTtsSynthesizing.set(false);
    } else {
      // 流式TTS：在后台线程中等待播放完成并记录统计
      executorService.submit(
          () -> {
            try {
              // 等待最多30秒，直到流式TTS完成
              int waitCount = 0;
              while (ttsService.isPlaying() && waitCount < 300) {
                Thread.sleep(100);
                waitCount++;
              }
              // 重置状态
              isResponding.set(false);
              isTtsSynthesizing.set(false);

              // 重置会话开始时间，准备下一次会话
              sessionStartTime = 0;
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }
  }

  private void onCozeTtsAudio(byte[] audioData) {
    // For Coze TTS, play audio via AudioPlayer

    // 记录TTS性能指标 (Coze TTS 使用 24000Hz 采样率)
    if (performanceLogger != null) {
      performanceLogger.logTtsResult(lastWorkflowOutput, audioData, 24000);
    }
    lastTtsAudio = audioData;

    // 记录端到端统计（从结束语音输入到开始播放）
    if (performanceLogger != null) {
      performanceLogger.logEndToEndStats(lastAsrText, lastWorkflowOutput, audioData);
    }

    // TTS 播放前清空 ASR 缓存，避免 TTS 声音被识别
    if (asrService instanceof CozeAsrService) {
      ((CozeAsrService) asrService).clearAudioBuffer();
    }

    // 先停止之前的播放，避免重叠
    if (audioPlayer != null) {
      audioPlayer.stop();
    }

    // 检查是否可以播放音频
    // 只有当 ASR、Workflow、TTS 都没有在进行时才允许播放
    if (canPlayAudio()) {
      // 播放音频
      audioPlayer.play(audioData);
    } else {
      System.out.println("[TTS] 播放被阻止：ASR/Workflow/TTS 正在进行中");
    }

    // 重置会话开始时间，准备下一次会话
    sessionStartTime = 0;
  }

  /** 检查是否可以播放音频 只有当 ASR、Workflow 都没有在进行时才返回 true 注意：TTS 合成完成后的回调播放时，isTtsSynthesizing 已经为 false */
  private boolean canPlayAudio() {
    return !isAsrProcessing.get() && !isWorkflowProcessing.get();
  }

  private void onQwenTtsAudio(byte[] audioData) {
    // For Qwen TTS, play audio via AudioPlayer

    // 记录TTS性能指标 (Qwen TTS 使用 24000Hz 采样率)
    if (performanceLogger != null) {
      performanceLogger.logTtsResult(lastWorkflowOutput, audioData, 24000);
    }
    lastTtsAudio = audioData;

    // 记录端到端统计（从结束语音输入到开始播放）
    if (performanceLogger != null) {
      performanceLogger.logEndToEndStats(lastAsrText, lastWorkflowOutput, audioData);
    }

    // TTS 播放前清空 ASR 缓存，避免 TTS 声音被识别
    if (asrService instanceof CozeAsrService) {
      ((CozeAsrService) asrService).clearAudioBuffer();
    }

    // 先停止之前的播放，避免重叠
    if (audioPlayer != null) {
      audioPlayer.stop();
    }

    // 检查是否可以播放音频
    // 只有当 ASR、Workflow 都没有在进行时才允许播放
    if (canPlayAudio()) {
      // 播放音频
      audioPlayer.play(audioData);
    } else {
      System.out.println("[TTS] 播放被阻止：ASR/Workflow 正在进行中");
    }

    // 重置会话开始时间，准备下一次会话
    sessionStartTime = 0;
  }

  /** 流式TTS音频回调 - 用于性能统计 流式TTS内部自己处理播放，这里只记录性能指标 */
  private void onStreamingTtsAudio(byte[] audioData) {
    // 流式TTS内部自己处理播放，这里只用于性能统计

    // 记录TTS性能指标 (Coze Streaming TTS 使用 24000Hz 采样率)
    // 注意：流式TTS会多次回调，我们只记录第一次作为开始，最后一次作为完成
    if (performanceLogger != null && lastTtsAudio == null) {
      // 第一次收到音频数据，记录TTS性能
      performanceLogger.logTtsResult(lastWorkflowOutput, audioData, 24000);

      // 记录端到端统计（从结束语音输入到开始播放）
      performanceLogger.logEndToEndStats(lastAsrText, lastWorkflowOutput, audioData);

      // TTS 播放前清空 ASR 缓存，避免 TTS 声音被识别
      // 只在第一次收到音频时清空，避免重复调用
      if (asrService instanceof CozeAsrService) {
        ((CozeAsrService) asrService).clearAudioBuffer();
      }
    }

    // 累积音频数据用于端到端统计
    if (lastTtsAudio == null) {
      lastTtsAudio = audioData;
    } else {
      // 合并音频数据
      byte[] combined = new byte[lastTtsAudio.length + audioData.length];
      System.arraycopy(lastTtsAudio, 0, combined, 0, lastTtsAudio.length);
      System.arraycopy(audioData, 0, combined, lastTtsAudio.length, audioData.length);
      lastTtsAudio = combined;
    }
  }

  private void interruptPlayback() {
    if (ttsService != null) {
      ttsService.stop();
    }
    if (audioPlayer != null) {
      audioPlayer.stop();
    }
    isResponding.set(false);
  }

  private void onError(Exception e) {
    System.err.println("[错误] " + e.getMessage());
  }

  private void shutdown() {
    System.out.println("[系统] 正在关闭...");

    isRunning.set(false);

    if (audioRecorder != null) {
      audioRecorder.close();
    }

    if (asrService != null) {
      asrService.close();
    }

    if (ttsService != null) {
      ttsService.close();
    }

    if (audioPlayer != null) {
      audioPlayer.close();
    }

    if (executorService != null) {
      executorService.shutdown();
      try {
        if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
          executorService.shutdownNow();
        }
      } catch (InterruptedException e) {
        executorService.shutdownNow();
      }
    }

    // 关闭性能日志记录器
    if (performanceLogger != null) {
      performanceLogger.close();
      System.out.println("[性能日志] 已保存到文件");
    }

    System.out.println("[系统] 已关闭");
  }
}
