package example.websocket.demo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import example.websocket.demo.config.AppConfig;
import example.websocket.demo.service.AudioDevice;
import example.websocket.demo.service.AudioPlayer;
import example.websocket.demo.service.AudioRecorder;
import example.websocket.demo.service.EchoCanceller;
import example.websocket.demo.websocket.AudioWebSocketMessage;

public class AudioChatExampleTrans {

  // Configuration
  private final AppConfig config = AppConfig.getInstance();
  // 统一使用24kHz采样率发送给后端，下采样到16kHz音质更好
  private final int SAMPLE_RATE = 24000;

  // State
  private final AtomicBoolean isRunning = new AtomicBoolean(true);
  private final AtomicBoolean isResponding = new AtomicBoolean(false);
  private volatile boolean isStreamingPlayback = false;

  // Audio playback control flags
  // When any of these is true, audio playback should be blocked
  private final AtomicBoolean isAsrProcessing = new AtomicBoolean(false);
  private final AtomicBoolean isWorkflowProcessing = new AtomicBoolean(false);
  private final AtomicBoolean isTtsSynthesizing = new AtomicBoolean(false);

  // Services
  private AudioRecorder audioRecorder;
  private AudioPlayer audioPlayer;
  private ExecutorService executorService;

  // WebSocket
  private AudioWebSocketClient webSocketClient;
  private final String serverUrl;

  public AudioChatExampleTrans(String serverUrl) {
    this.serverUrl = serverUrl != null ? serverUrl : "ws://localhost:8080";
  }

  public static void main(String[] args) {
    String serverUrl = args.length > 0 ? args[0] : "ws://localhost:8080";
    try {
      AudioChatExampleTrans client = new AudioChatExampleTrans(serverUrl);
      client.initialize();
      client.run();
    } catch (Exception e) {
      System.err.println("[Main] 错误: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private void initialize() throws Exception {
    System.out.println("[系统] 初始化客户端...");
    System.out.println("[配置] 服务器地址: " + serverUrl);
    System.out.println("[配置] 采样率: " + SAMPLE_RATE + "Hz");

    // Initialize Audio Player
    audioPlayer = new AudioPlayer(null);
    audioPlayer.startRealtimePlayback();
    isStreamingPlayback = true;

    // Initialize Executor
    executorService = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "AudioWorker");
      t.setDaemon(true);
      return t;
    });

    // Initialize Audio Recorder with sample rate
    audioRecorder = new AudioRecorder(SAMPLE_RATE);

    // Initialize WebSocket Client
    webSocketClient = new AudioWebSocketClient(new URI(serverUrl));
    webSocketClient.connectBlocking(5, TimeUnit.SECONDS);

    if (!webSocketClient.isOpen()) {
      throw new RuntimeException("无法连接到WebSocket服务器");
    }

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
    System.out.println("[系统] 说话开始识别，停止说话后等待AI回复...");
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

    // Send to server via WebSocket
    if (webSocketClient != null && webSocketClient.isOpen()) {
      webSocketClient.send(audioData.getData());
    }

    // Sleep to avoid busy loop
    try {
      TimeUnit.MILLISECONDS.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void onWebSocketMessage(AudioWebSocketMessage message) {
    String type = message.getType();
    if (type == null) {
      return;
    }

    switch (type) {
      case "transcription":
        handleTranscription(message);
        break;
      case "chat_response":
        handleChatResponse(message);
        break;
      case "audio_output":
        handleAudioOutput(message);
        break;
      case "interrupt":
        System.out.println("[WebSocket] 收到打断信号，清空播放缓存");
        if (audioPlayer != null) {
          audioPlayer.cancelRealtimePlayback();
          audioPlayer.resumeRealtimePlayback();
        }
        break;
      case "session_start":
        System.out.println("[WebSocket] 会话开始: " + message.getSessionId());
        break;
      case "session_end":
        System.out.println("[WebSocket] 会话结束: " + message.getSessionId());
        break;
      case "error":
        System.err.println("[WebSocket] 错误: " + message.getError());
        break;
      default:
        // Ignore other message types
    }
  }

  private void handleTranscription(AudioWebSocketMessage message) {
    String text = message.getText();
    Boolean isFinal = message.getIsFinal();

    if (text == null || text.trim().isEmpty()) {
      return;
    }

    if (Boolean.TRUE.equals(isFinal)) {
      System.out.println("[ASR] 最终识别: " + text);
      isAsrProcessing.set(false);
    } else {
      System.out.println("[ASR] 实时识别: " + text);
      isAsrProcessing.set(true);
    }
  }

  private void handleChatResponse(AudioWebSocketMessage message) {
    String text = message.getText();
    if (text != null) {
      System.out.println("[Chat] 响应: " + text);
      isWorkflowProcessing.set(false);
    }
  }

  private void handleAudioOutput(AudioWebSocketMessage message) {
    String audioData = message.getAudioData();
    Boolean isFinal = message.getIsFinal();

    if (audioData == null || audioData.isEmpty()) {
      return;
    }

    try {
      byte[] pcmData = Base64.getDecoder().decode(audioData);

      // Mark TTS as synthesizing
      isTtsSynthesizing.set(true);
      isResponding.set(true);

      // 使用流式播放：直接写入缓冲区
      if (isStreamingPlayback) {
        audioPlayer.writeRealtimeAudioRaw(pcmData);
      } else {
        // Fallback: 同步播放（参考refract）
        audioPlayer.stop();
        if (canPlayAudio()) {
          audioPlayer.play(pcmData);
        }
      }

      // If this is the final chunk, reset TTS state
      if (Boolean.TRUE.equals(isFinal)) {
        isTtsSynthesizing.set(false);
        isResponding.set(false);
        if (isStreamingPlayback) {
          // 流式播放完成后停止
          audioPlayer.stop();
        }
      }

    } catch (Exception e) {
      System.err.println("[TTS] 播放音频失败: " + e.getMessage());
    }
  }

  /**
   * Check if audio can be played
   * Only return true when ASR and Workflow are not processing
   */
  private boolean canPlayAudio() {
    return !isAsrProcessing.get() && !isWorkflowProcessing.get();
  }

  private void interruptPlayback() {
    if (audioPlayer != null) {
      if (isStreamingPlayback) {
        audioPlayer.cancelRealtimePlayback();
      } else {
        audioPlayer.stop();
      }
    }
    isResponding.set(false);
    isTtsSynthesizing.set(false);
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

    if (audioPlayer != null) {
      audioPlayer.close();
    }

    if (webSocketClient != null) {
      try {
        // Send session end message
        AudioWebSocketMessage endMsg = AudioWebSocketMessage.createSessionEnd(webSocketClient.getSessionId());
        webSocketClient.send(endMsg.toJson());
      } catch (Exception e) {
        // ignore
      }
      webSocketClient.close();
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

    System.out.println("[系统] 已关闭");
  }

  /**
   * WebSocket Client for audio chat
   */
  private class AudioWebSocketClient extends WebSocketClient {

    private volatile String sessionId;

    public AudioWebSocketClient(URI serverUri) {
      super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
      System.out.println("[WebSocket] 已连接到服务器");
    }

    @Override
    public void onMessage(String message) {
      try {
        AudioWebSocketMessage msg = AudioWebSocketMessage.fromJson(message);
        if (sessionId == null && msg.getSessionId() != null) {
          sessionId = msg.getSessionId();
        }
        onWebSocketMessage(msg);
      } catch (Exception e) {
        System.err.println("[WebSocket] 消息解析错误: " + e.getMessage());
      }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
      System.out.println("[WebSocket] 连接关闭: " + reason);
    }

    @Override
    public void onError(Exception ex) {
      System.err.println("[WebSocket] 错误: " + ex.getMessage());
    }

    public String getSessionId() {
      return sessionId != null ? sessionId : "unknown";
    }
  }
}
