package example.websocket.demo.websocket;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.coze.openapi.service.auth.TokenAuth;
import com.coze.openapi.service.service.CozeAPI;

import example.websocket.demo.config.AppConfig;
import example.websocket.demo.service.*;

public class AudioChatWebSocketServer extends WebSocketServer {

  private final AppConfig config = AppConfig.getInstance();
  private final Map<WebSocket, AudioSession> sessions = new ConcurrentHashMap<>();
  private final ExecutorService executorService;
  private final CozeAPI coze;

  // Configuration
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

  public AudioChatWebSocketServer(int port) {
    super(new InetSocketAddress(port));
    this.executorService =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "WebSocketWorker");
              t.setDaemon(true);
              return t;
            });

    // Initialize Coze API
    this.coze =
        new CozeAPI.Builder()
            .baseURL(COZE_API_BASE)
            .auth(new TokenAuth(COZE_API_TOKEN))
            .readTimeout(30000)
            .build();

    System.out.println("[WebSocket Server] 初始化完成，端口: " + port);
    System.out.println("[配置] ASR Provider: " + ASR_PROVIDER);
    System.out.println("[配置] TTS Provider: " + TTS_PROVIDER);
    System.out.println("[配置] TTS Streaming: " + TTS_STREAMING);
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    String sessionId = conn.getRemoteSocketAddress().toString();
    System.out.println("[WebSocket] 新连接: " + sessionId);

    try {
      AudioSession session = createSession(conn, sessionId);
      sessions.put(conn, session);

      // Send session start message
      AudioWebSocketMessage msg = AudioWebSocketMessage.createSessionStart(sessionId);
      conn.send(msg.toJson());

      System.out.println("[WebSocket] 会话创建成功: " + sessionId);
    } catch (Exception e) {
      System.err.println("[WebSocket] 创建会话失败: " + e.getMessage());
      try {
        AudioWebSocketMessage errorMsg =
            AudioWebSocketMessage.createError(sessionId, e.getMessage());
        conn.send(errorMsg.toJson());
        conn.close();
      } catch (Exception ex) {
        // ignore
      }
    }
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    AudioSession session = sessions.remove(conn);
    if (session != null) {
      session.close();
      System.out.println("[WebSocket] 连接关闭: " + session.getSessionId() + ", 原因: " + reason);
    }
  }

  @Override
  public void onMessage(WebSocket conn, String message) {
    AudioSession session = sessions.get(conn);
    if (session == null) {
      return;
    }

    try {
      AudioWebSocketMessage msg = AudioWebSocketMessage.fromJson(message);
      handleMessage(conn, session, msg);
    } catch (Exception e) {
      System.err.println("[WebSocket] 消息解析错误: " + e.getMessage());
      sendError(conn, session.getSessionId(), "消息格式错误: " + e.getMessage());
    }
  }

  @Override
  public void onMessage(WebSocket conn, ByteBuffer message) {
    // Handle binary audio data
    AudioSession session = sessions.get(conn);
    if (session == null) {
      return;
    }

    try {
      byte[] audioData = new byte[message.remaining()];
      message.get(audioData);
      session.processAudioData(audioData);
    } catch (Exception e) {
      System.err.println("[WebSocket] 处理音频数据错误: " + e.getMessage());
    }
  }

  @Override
  public void onError(WebSocket conn, Exception ex) {
    if (conn != null) {
      AudioSession session = sessions.get(conn);
      String sessionId = session != null ? session.getSessionId() : "unknown";
      System.err.println("[WebSocket] 错误 [" + sessionId + "]: " + ex.getMessage());
    } else {
      System.err.println("[WebSocket] 服务器错误: " + ex.getMessage());
    }
  }

  @Override
  public void onStart() {
    System.out.println("[WebSocket Server] 启动成功，端口: " + getPort());
  }

  private void handleMessage(WebSocket conn, AudioSession session, AudioWebSocketMessage msg)
      throws Exception {
    String type = msg.getType();

    if (type == null) {
      sendError(conn, session.getSessionId(), "消息类型不能为空");
      return;
    }

    switch (type) {
      case "audio_input":
        handleAudioInput(conn, session, msg);
        break;
      case "config":
        handleConfig(conn, session, msg);
        break;
      case "interrupt":
        handleInterrupt(conn, session, msg);
        break;
      case "session_end":
        handleSessionEnd(conn, session, msg);
        break;
      default:
        sendError(conn, session.getSessionId(), "未知的消息类型: " + type);
    }
  }

  private void handleAudioInput(WebSocket conn, AudioSession session, AudioWebSocketMessage msg)
      throws Exception {
    String audioData = msg.getAudioData();
    if (audioData == null || audioData.isEmpty()) {
      return;
    }

    // Decode base64 audio data
    byte[] pcmData = Base64.getDecoder().decode(audioData);
    session.processAudioData(pcmData);
  }

  private void handleConfig(WebSocket conn, AudioSession session, AudioWebSocketMessage msg)
      throws Exception {
    // Handle configuration from client
    Integer sampleRate = msg.getSampleRate();
    String codec = msg.getCodec();

    System.out.println(
        "[WebSocket] 收到配置 ["
            + session.getSessionId()
            + "]: sampleRate="
            + sampleRate
            + ", codec="
            + codec);

    // Send config acknowledgment
    AudioWebSocketMessage response =
        AudioWebSocketMessage.createConfig(
            session.getSessionId(), config.getAsrSampleRate(), "pcm");
    conn.send(response.toJson());
  }

  private void handleInterrupt(WebSocket conn, AudioSession session, AudioWebSocketMessage msg)
      throws Exception {
    System.out.println("[WebSocket] 收到打断请求 [" + session.getSessionId() + "]");
    session.interrupt();

    // Send interrupt acknowledgment
    AudioWebSocketMessage response = new AudioWebSocketMessage();
    response.setType(AudioWebSocketMessage.MessageType.INTERRUPT.getValue());
    response.setSessionId(session.getSessionId());
    response.setMessage("Interrupted");
    conn.send(response.toJson());
  }

  private void handleSessionEnd(WebSocket conn, AudioSession session, AudioWebSocketMessage msg)
      throws Exception {
    System.out.println("[WebSocket] 收到会话结束请求 [" + session.getSessionId() + "]");
    session.close();

    // Send session end acknowledgment
    AudioWebSocketMessage response = AudioWebSocketMessage.createSessionEnd(session.getSessionId());
    conn.send(response.toJson());

    // Close connection
    conn.close();
  }

  private void sendError(WebSocket conn, String sessionId, String error) {
    try {
      AudioWebSocketMessage msg = AudioWebSocketMessage.createError(sessionId, error);
      conn.send(msg.toJson());
    } catch (Exception e) {
      System.err.println("[WebSocket] 发送错误消息失败: " + e.getMessage());
    }
  }

  private AudioSession createSession(WebSocket conn, String sessionId) throws Exception {
    return new AudioSession(
        sessionId,
        conn,
        coze,
        executorService,
        ASR_PROVIDER,
        TTS_PROVIDER,
        TTS_STREAMING,
        DASHSCOPE_API_KEY,
        DASHSCOPE_API_BASE,
        BOT_ID,
        USER_ID,
        VOICE_ID,
        QWEN_VOICE_ID,
        MODEL);
  }

  public void shutdown() {
    System.out.println("[WebSocket Server] 正在关闭...");

    // Close all sessions
    for (AudioSession session : sessions.values()) {
      session.close();
    }
    sessions.clear();

    // Stop server
    try {
      stop(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Shutdown executor
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      executorService.shutdownNow();
    }

    System.out.println("[WebSocket Server] 已关闭");
  }
}
