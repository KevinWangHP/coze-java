package example.websocket.audio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import com.alibaba.dashscope.audio.qwen_tts_realtime.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.coze.openapi.client.audio.common.AudioFormat;
import com.coze.openapi.client.audio.speech.CreateSpeechReq;
import com.coze.openapi.client.audio.speech.CreateSpeechResp;
import com.coze.openapi.client.chat.CreateChatReq;
import com.coze.openapi.client.chat.model.ChatEvent;
import com.coze.openapi.client.chat.model.ChatEventType;
import com.coze.openapi.client.connversations.CreateConversationReq;
import com.coze.openapi.client.connversations.CreateConversationResp;
import com.coze.openapi.client.connversations.message.model.Message;
import com.coze.openapi.client.exception.CozeApiException;
import com.coze.openapi.client.websocket.event.downstream.*;
import com.coze.openapi.service.auth.TokenAuth;
import com.coze.openapi.service.service.CozeAPI;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsCallbackHandler;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsClient;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsCreateReq;
import com.google.gson.JsonObject;

import io.reactivex.Flowable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/*
This example demonstrates how to:
1. Capture audio from microphone
2. Stream audio to transcription API
3. Send transcribed text to chat bot
4. Convert bot response to speech
*/
public class AudioChatExampleAll {

  private static final int SAMPLE_RATE = 24000; 
  private static final int CHANNELS = 1;
  private static final int SAMPLE_SIZE_IN_BITS = 16;
  private static final boolean BIG_ENDIAN = false;
  private static final int SILENCE_THRESHOLD = 1000; // 静音阈值

  // 解决Java Sound API的命名冲突
  private static final javax.sound.sampled.AudioFormat JAVA_AUDIO_FORMAT =
      new javax.sound.sampled.AudioFormat(
          SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, true, BIG_ENDIAN);

  // 状态管理变量（对应前端）
  private static AtomicBoolean isTranscribing = new AtomicBoolean(true);
  private static AtomicBoolean isConnected = new AtomicBoolean(false);
  private static AtomicBoolean isResponding = new AtomicBoolean(false);

  // 转录超时检测
  private static AtomicReference<String> currentTranscription = new AtomicReference<>();
  private static List<String> transcriptionSegments = new ArrayList<>();
  private static long lastSoundTime = System.currentTimeMillis();
  private static long lastTranscriptUpdateTime = System.currentTimeMillis();
  private static final long TRANSCRIPTION_TIMEOUT = 3000; // 2秒无更新超时

  // 语音服务选择变量
  private static final String SPEECH_SERVICE = System.getenv("SPEECH_SERVICE") != null ? System.getenv("SPEECH_SERVICE") : "COZE";

  // 百炼API相关变量
  private static WebSocket webSocket = null;
  private static String taskId = UUID.randomUUID().toString();
  private static final String DASHSCOPE_API_KEY = System.getenv("DASHSCOPE_API_KEY");
  private static final String WEBSOCKET_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
  private static final String QWEN_VOICE_ID = System.getenv("QWEN_VOICE_ID") != null ? System.getenv("QWEN_VOICE_ID") : "Cherry";

  // Coze API相关变量
  private static CozeAPI coze = null;
  private static String cozeToken = System.getenv("COZE_API_TOKEN");
  private static String botID = System.getenv("COZE_BOT_ID");
  private static String userID = System.getenv("USER_ID");
  private static String voiceID = System.getenv("COZE_VOICE_ID");
  private static WebsocketsAudioTranscriptionsClient transcriptionClient = null;

  // 百炼Qwen TTS相关变量
  private static QwenTtsRealtime qwenTtsRealtime = null;
  private static RealtimePcmPlayer audioPlayer = null;
  private static AtomicReference<CountDownLatch> ttsCompleteLatch =
      new AtomicReference<>(new CountDownLatch(1));
  private static AtomicBoolean isSessionStarted = new AtomicBoolean(false);

  // 音频播放相关变量
  private static SourceDataLine audioLine = null;
  private static Future<?> currentAudioFuture = null;

  // 对话历史管理
  private static List<Message> messageHistory = new ArrayList<>();
  private static String conversationId = null;
  private static String currentChatId = null;

  // 音频播放队列管理 - 使用缓存线程池，允许立即处理新任务
  private static ExecutorService audioPlaybackExecutor =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "AudioPlaybackThread");
            t.setDaemon(true);
            return t;
          });
  
  // 音频播放停止标志
  private static volatile boolean shouldStopPlayback = false;

  // 主函数
  public static void main(String[] args) throws Exception {
    // 初始化Coze client
    TokenAuth authCli = new TokenAuth(cozeToken);
    coze =
        new CozeAPI.Builder()
            .baseURL(System.getenv("COZE_API_BASE"))
            .auth(authCli)
            .readTimeout(30000)
            .build();

    // 创建会话
    createConversation(botID, userID);
    
    // 根据语音服务选择初始化连接
    if ("QWEN".equalsIgnoreCase(SPEECH_SERVICE)) {
      // 初始化百炼语音识别服务
      startBaichuanSpeechRecognition();
    } else {
      // 初始化Coze转录客户端
      initCozeTranscriptionClient();
    }

    // Step 1: Get audio devices and select microphone
    System.out.println("[系统] 正在获取音频设备...");
    List<AudioDevice> inputDevices = getAudioDevices();

    if (inputDevices.isEmpty()) {
      System.out.println("[ERROR] 没有可用的音频输入设备");
      return;
    }

    System.out.println("可用的音频输入设备:");
    for (int i = 0; i < inputDevices.size(); i++) {
      AudioDevice device = inputDevices.get(i);
      System.out.println((i + 1) + ". " + device.getDeviceName());
    }

    // 手动选择设备
    System.out.print("\n请选择音频设备编号 (1-" + inputDevices.size() + "): ");
    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
    int selectedIndex = 0;
    try {
      String input = reader.readLine();
      selectedIndex = Integer.parseInt(input.trim()) - 1;
      if (selectedIndex < 0 || selectedIndex >= inputDevices.size()) {
        System.out.println("[警告] 无效的选择，使用第一个设备");
        selectedIndex = 0;
      }
    } catch (Exception e) {
      System.out.println("[警告] 输入错误，使用第一个设备");
      selectedIndex = 0;
    }
    
    AudioDevice selectedDevice = inputDevices.get(selectedIndex);
    System.out.println("\n选择的音频设备: " + selectedDevice.getDeviceName());
    
    // 检查设备支持的格式
    System.out.println("[麦克风] 检查设备支持的格式...");
    javax.sound.sampled.Mixer mixer = AudioSystem.getMixer(selectedDevice.getMixerInfo());
    javax.sound.sampled.Line.Info lineInfo = new DataLine.Info(TargetDataLine.class, JAVA_AUDIO_FORMAT);
    
    if (mixer.isLineSupported(lineInfo)) {
      System.out.println("[麦克风] 设备支持当前格式: " + JAVA_AUDIO_FORMAT);
    } else {
      System.out.println("[麦克风] 警告: 设备可能不完全支持当前格式");
    }

    // Step 2: Capture audio from selected microphone
    System.out.println("[系统] 正在从麦克风捕获音频...");
    DataLine.Info info = new DataLine.Info(TargetDataLine.class, JAVA_AUDIO_FORMAT);
    TargetDataLine microphone = null;

    // 尝试打开所选设备
    try {
      microphone = (TargetDataLine) mixer.getLine(info);
      System.out.println("[DEBUG] 成功打开指定设备: " + selectedDevice.getDeviceName());
    } catch (Exception e) {
      // 如果指定设备无法打开，尝试使用默认设备
      System.out.println("[WARNING] 无法打开指定设备: " + selectedDevice.getDeviceName());
      System.out.println("[WARNING] 错误信息: " + e.getMessage());
      System.out.println("[WARNING] 尝试使用默认设备");
      microphone = (TargetDataLine) AudioSystem.getLine(info);
    }

    // 检查麦克风是否可用
    if (microphone == null) {
      System.out.println("[ERROR] 麦克风无法打开");
      return;
    }

    // 打开麦克风
    try {
      microphone.open(JAVA_AUDIO_FORMAT);
      System.out.println("[麦克风] 成功以指定格式打开: " + JAVA_AUDIO_FORMAT);
    } catch (Exception e) {
      System.out.println("[麦克风] 警告: 无法以指定格式打开，尝试使用设备默认格式");
      System.out.println("[麦克风] 错误: " + e.getMessage());
      // 尝试不指定格式打开
      microphone.open();
      System.out.println("[麦克风] 使用设备默认格式: " + microphone.getFormat());
    }
    
    microphone.start();

    // 等待麦克风初始化
    TimeUnit.MILLISECONDS.sleep(1000); // 缩短初始化时间
    System.out.println("[DEBUG] 麦克风初始化完成，开始录制...");
    System.out.println("[DEBUG] 实际音频格式: " + microphone.getFormat());

    // Add shutdown hook to clean up resources
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.out.println("[DEBUG] 程序退出，关闭WebSocket连接...");
                  if (webSocket != null) {
                    webSocket.close(1000, "Program exit");
                  }

                  // 关闭音频播放线程池
                  System.out.println("[DEBUG] 关闭音频播放线程池...");
                  audioPlaybackExecutor.shutdown();
                  try {
                    if (!audioPlaybackExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                      audioPlaybackExecutor.shutdownNow();
                    }
                  } catch (InterruptedException e) {
                    audioPlaybackExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                  }
                }));

    System.out.println("[DEBUG] 转录配置已发送: " + SAMPLE_RATE + "采样率, PCM格式");

    // 实时流式输入
    byte[] audioBuffer = new byte[1024];
    int bytesRead;

    System.out.println("[系统] 开始实时录音和流式传输...");

    while (isTranscribing.get()) {
      bytesRead = microphone.read(audioBuffer, 0, audioBuffer.length);
      if (bytesRead > 0) {
        // System.out.println("[DEBUG] 读取到音频数据: " + bytesRead + " 字节");
        
        // 检测是否有声音（简单的能量检测）
        boolean hasSound = false;
        double sum = 0;
        int count = 0;
        short maxSample = 0;
        for (int i = 0; i < bytesRead; i += 2) {
          short sample = (short) ((audioBuffer[i] & 0xFF) | (audioBuffer[i+1] << 8));
          sum += Math.abs(sample);
          count++;
          if (Math.abs(sample) > maxSample) {
            maxSample = (short) Math.abs(sample);
          }
          if (Math.abs(sample) > SILENCE_THRESHOLD) {
            hasSound = true;
          }
        }
        double avgEnergy = count > 0 ? sum / count : 0;
        if (hasSound) {
          // System.out.println("[AUDIO] 检测到语音输入，平均能量: " + String.format("%.0f", avgEnergy) + ", 最大振幅: " + maxSample);
          if (isResponding.get()) {
            // 如果检测到声音且正在播放TTS，自动打断
            System.out.println("[AUDIO] 检测到用户说话，自动打断TTS播放");
            interruptAudioPlayback();
            // 重置会话状态
            isSessionStarted.set(false);
          }
        }
        // 根据语音服务选择发送音频数据
    if ("QWEN".equalsIgnoreCase(SPEECH_SERVICE)) {
      // 直接发送音频数据到百炼服务
      if (webSocket != null && isConnected.get()) {
        webSocket.send(ByteString.of(Arrays.copyOf(audioBuffer, bytesRead)));
      } else {
        System.out.println("[AUDIO] 百炼WebSocket连接未建立，跳过音频发送");
        System.out.println("[AUDIO] webSocket: " + (webSocket != null ? "已初始化" : "未初始化"));
        System.out.println("[AUDIO] isConnected: " + isConnected.get());
      }
    } else {
      // 直接发送音频数据到Coze服务
      if (transcriptionClient != null) {
        try {
          transcriptionClient.inputAudioBufferAppend(Arrays.copyOf(audioBuffer, bytesRead));
        } catch (Exception e) {
          System.err.println("[COZE ASR] 发送音频数据失败: " + e.getMessage());
          e.printStackTrace();
        }
      } else {
        System.err.println("[COZE ASR] transcriptionClient 为 null，无法发送音频");
      }
    }

        // 检测转录更新超时（2秒无更新）
        if (System.currentTimeMillis() - lastTranscriptUpdateTime > TRANSCRIPTION_TIMEOUT) {
          String lastTranscription = null;
          
          // 根据语音服务选择获取转录结果
          if ("QWEN".equalsIgnoreCase(SPEECH_SERVICE)) {
            // 百炼流程：使用 currentTranscription
            lastTranscription = currentTranscription.get();
          } else {
            // Coze流程：使用 transcriptionSegments
            if (!transcriptionSegments.isEmpty()) {
              lastTranscription = transcriptionSegments.get(transcriptionSegments.size() - 1);
            }
          }
          
          // 检查转录结果是否有效
          if (lastTranscription != null && !lastTranscription.trim().isEmpty()) {
            System.out.println("[TRANSCRIPTION] 2秒无转录更新，处理识别结果: " + lastTranscription);

            try {
              // 将全部消息历史记录发送给工作流
              callBotAndProcessResponse(coze, botID, userID, lastTranscription, voiceID);
            } catch (Exception e) {
              System.err.println("[CHAT] 调用聊天接口失败: " + e.getMessage());
              e.printStackTrace();
            }
            
            // 重置超时计时器
            lastTranscriptUpdateTime = System.currentTimeMillis();
            
            // 清空转录结果
            if ("QWEN".equalsIgnoreCase(SPEECH_SERVICE)) {
              currentTranscription.set("");
            } else {
              transcriptionSegments.clear();
            }
            System.out.println("[TRANSCRIPTION] 转录结果已清空");
          } else {
            // 转录结果为空，只重置计时器
            lastTranscriptUpdateTime = System.currentTimeMillis();
          }
        }

        // 模拟人说话的间隔，避免发送过快
        TimeUnit.MILLISECONDS.sleep(10);
      }
    }

    // 保持程序运行以维持WebSocket连接
    System.out.println("[DEBUG] 保持WebSocket连接，持续监听转录事件...");
    while (true) {
      TimeUnit.MILLISECONDS.sleep(1000);
    }
  }

  // 调用bot并处理响应（对应前端的callBot函数）
  private static void callBotAndProcessResponse(
      CozeAPI coze, String botID, String userID, String transcription, String voiceID)
      throws Exception {
    System.out.println("[系统] === 调用Bot ====");

    // 中断当前音频播放
    interruptAudioPlayback();

    // 检查并处理最后一条消息如果是用户消息
    if (!messageHistory.isEmpty()) {
      Message lastMsg = messageHistory.get(messageHistory.size() - 1);
      if (lastMsg.getRole().getValue().equals("user")) {
        // 删除最后一条用户消息
        messageHistory.remove(messageHistory.size() - 1);
        System.out.println("[历史记录] 删除重复的用户消息");
      }
    }

    // 添加最新的用户消息到历史
    Message newUserMessage = Message.buildUserQuestionText(transcription);
    messageHistory.add(newUserMessage);
    System.out.println("[历史记录] 添加新的用户消息: " + transcription);

    // 构造完整对话历史JSON字符串
    StringBuilder historyJson = new StringBuilder();
    historyJson.append("[");

    // 添加所有历史消息
    for (int i = 0; i < messageHistory.size(); i++) {
      Message msg = messageHistory.get(i);
      if (i > 0) {
        historyJson.append(",");
      }
      historyJson.append(
          String.format(
              "{\"role\":\"%s\",\"content\":\"%s\"}",
              msg.getRole().getValue(),
              msg.getContent().replace("\"", "\\\"").replace("\n", "\\n")));
    }

    historyJson.append("]");

    String fullHistoryJson = historyJson.toString();
    // System.out.println("[历史记录] 完整对话历史JSON: " + fullHistoryJson);

    // 创建包含JSON历史的用户消息
    Message userMessage = Message.buildUserQuestionText(fullHistoryJson);

    System.out.println("[历史记录] 包含历史的用户消息: " + fullHistoryJson);

    // 构造聊天请求 - 发送包含完整历史的JSON
    CreateChatReq chatReq =
        CreateChatReq.builder()
            .botID(botID)
            .userID(userID)
            .messages(Collections.singletonList(userMessage)) // 只发送包含历史的单条消息
            .build();

    Flowable<ChatEvent> chatResp = coze.chat().stream(chatReq);

    StringBuilder botResponse = new StringBuilder();

    chatResp
        .subscribeOn(Schedulers.io())
        .subscribe(
            event -> {
              // Log all events for debugging
              String eventValue = event.getEvent().getValue();
              System.out.println("[聊天] 收到事件: " + eventValue);

              if (ChatEventType.CONVERSATION_CHAT_CREATED.getValue().equals(eventValue)) {
                if (event.getLogID() != null) {
                  String oldChatId = currentChatId;
                  currentChatId = event.getLogID();
                  System.out.println(
                      "[聊天] 更新currentChatId: " + oldChatId + " -> " + currentChatId);
                }
              } else if (ChatEventType.CONVERSATION_MESSAGE_DELTA.getValue().equals(eventValue)) {
                if (event.getMessage() != null && event.getMessage().getContent() != null) {
                  botResponse.append(event.getMessage().getContent());
                }
              } else if (ChatEventType.CONVERSATION_CHAT_COMPLETED.getValue().equals(eventValue)) {
                if (event.getLogID() != null) {
                  String completedChatId = event.getLogID();
                  if (completedChatId.equals(currentChatId)) {
                    System.out.println(
                        "[聊天] 完成的聊天ID与当前匹配: " + completedChatId);
                    
                    // 发送清除音频缓冲区事件
                    System.out.println("[ASYNC] 发送清除音频缓冲区事件...");
                    if (transcriptionClient != null) {
                      transcriptionClient.inputAudioBufferClear();
                    }
                    // 等待清除完成
                    try {
                      TimeUnit.MILLISECONDS.sleep(1000);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    
                    // 将currentChatId置空
                    currentChatId = null;
                    System.out.println("[CHAT] 重置currentChatId为null");
                    
                    // ASR继续监听，不发送finish-task和run-task指令
                    
                    // Proceed with speech synthesis
                    // System.out.println("\n[CHAT] Bot response: " + botResponse.toString());

                    // 添加AI回复到对话历史
                    Message aiMessage = Message.buildAssistantAnswer(botResponse.toString());
                    messageHistory.add(aiMessage);
                    System.out.println("[历史记录] 添加AI回复到历史记录: " + botResponse.toString());

                    final String responseText = botResponse.toString();
                    // 中断所有正在进行的音频操作
                    
                    // 取消之前的音频播放任务
                    interruptAudioPlayback();
                    
                    // 等待一小段时间确保中断完成
                    try {
                      Thread.sleep(100);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    
                    // 重置停止标志，允许新音频播放（必须在中断完成后设置）
                    shouldStopPlayback = false;

                    // 异步处理TTS和播放，不阻塞录制线程
                    currentAudioFuture =
                        audioPlaybackExecutor.submit(
                            () -> {
                              try {
                                isResponding.set(true);

                                // 注意：interruptAudioPlayback() 已在外部调用，这里不再重复调用
                                // 重置会话状态
                                isSessionStarted.set(false);

                                // 发送清除音频缓冲区事件
                                System.out.println("[ASYNC] 发送清除音频缓冲区事件...");
                                if (transcriptionClient != null) {
                                  transcriptionClient.inputAudioBufferClear();
                                }
                                // 等待清除完成
                                try {
                                  TimeUnit.MILLISECONDS.sleep(1000);
                                } catch (InterruptedException e) {
                                  // 清除中断状态，继续执行
                                  Thread.interrupted();
                                  System.out.println("[ASYNC] Sleep interrupted, continuing...");
                                }

                                // 根据语音服务选择进行语音合成
                                if ("QWEN".equalsIgnoreCase(SPEECH_SERVICE)) {
                                  // 使用百炼TTS进行语音合成
                                  System.out.println("[异步] 开始使用百炼QWEN TTS生成语音...");

                                  // 初始化Qwen TTS连接
                                  if (qwenTtsRealtime == null) {
                                    initQwenTtsConnection();
                                    // 等待连接建立
                                    TimeUnit.MILLISECONDS.sleep(2000);
                                  }

                                  // 检查连接是否成功
                                  if (qwenTtsRealtime == null) {
                                    System.err.println("[错误] Qwen TTS SDK初始化失败");
                                    return;
                                  }

                                  // 解析语气和内容
                                  String tone = "";
                                  String content = responseText;

                                  // 匹配[语气] 内容格式
                                  java.util.regex.Pattern pattern =
                                      java.util.regex.Pattern.compile("\\[(.*?)\\]");
                                  java.util.regex.Matcher matcher = pattern.matcher(responseText);

                                  if (matcher.find()) {
                                    tone = matcher.group(1);
                                    content = matcher.replaceFirst("").trim();
                                    System.out.println(
                                        "[语音] 语气: " + tone + " 内容: " + content);
                                  }

                                  // 保存实际使用的语音ID和语气
                                  final String actualVoiceId =
                                      (voiceID != null && !voiceID.isEmpty())
                                          ? voiceID
                                          : "Cherry";

                                  // 发送session.update指令
                                  sendQwenTtsSessionUpdate(actualVoiceId, tone);

                                  // 发送待合成文本
                                  sendQwenTtsAppendText(content);

                                  // 发送session.finish指令
                                  sendQwenTtsFinish();

                                  System.out.println("[异步] Qwen TTS语音合成请求已发送");
                                  System.out.println("[异步] 等待音频数据块...");

                                  // 等待合成完成
                                  ttsCompleteLatch.set(new CountDownLatch(1));
                                  ttsCompleteLatch.get().await();
                                } else {
                                  // 使用Coze TTS进行语音合成
                                  System.out.println("[COZE TTS] ========== 开始使用 Coze TTS 生成语音 ==========");
                                  // System.out.println("[COZE TTS] 合成文本: '" + responseText + "'");
                                  System.out.println("[COZE TTS] 语音ID: " + voiceID);
                                  
                                  try {
                                    // 解析语气和内容
                                    String tone = "";
                                    String content = responseText;

                                    // 匹配[语气] 内容格式
                                    java.util.regex.Pattern pattern =
                                        java.util.regex.Pattern.compile("\\[(.*?)\\]");
                                    java.util.regex.Matcher matcher = pattern.matcher(responseText);

                                    if (matcher.find()) {
                                      tone = matcher.group(1);
                                      content = matcher.replaceFirst("").trim();
                                      System.out.println("[COZE TTS] 解析到语气: '" + tone + "', 内容: '" + content + "'");
                                    }

                                    // 创建语音合成请求
                                    System.out.println("[COZE TTS] 创建语音合成请求...");
                                    String actualVoiceId = (voiceID != null && !voiceID.isEmpty()) ? voiceID : "alloy";
                                    System.out.println("[COZE TTS] 使用语音ID: " + actualVoiceId);
                                    
                                    CreateSpeechReq speechReq =
                                        CreateSpeechReq.builder()
                                            .input(content)
                                            .voiceID(actualVoiceId)
                                            .responseFormat(AudioFormat.WAV)
                                            .sampleRate(24000)
                                            .build();

                                    System.out.println("[COZE TTS] 发送语音合成请求...");
                                    CreateSpeechResp speechResp =
                                        coze.audio().speech().create(speechReq);
                                    
                                    System.out.println("[COZE TTS] 收到语音数据，开始播放...");
                                    byte[] speechBytes = speechResp.getResponse().bytes();
                                    System.out.println("[COZE TTS] 语音数据大小: " + speechBytes.length + " 字节");
                                    
                                    // 播放音频
                                    playAudio(speechBytes, 24000, 16, 1, true, false);
                                    System.out.println("[COZE TTS] ========== 语音播放完成 ==========");

                                  } catch (Exception e) {
                                    System.err.println("[COZE TTS] 语音合成或播放失败: " + e.getMessage());
                                    e.printStackTrace();
                                  }
                                }

                              } catch (Exception e) {
                                System.err.println("[ASYNC] 音频播放错误: " + e.getMessage());
                                // 不打印完整栈跟踪以避免混乱
                              } finally {
                                isResponding.set(false);
                              }
                            });

                    System.out.println("[主线程] 继续录制，不等待语音播放完成");
                  } else {
                    System.out.println(
                        "[聊天] 完成的聊天ID与当前不匹配，跳过语音合成");
                    System.out.println(
                        "[聊天] 当前: " + currentChatId + " 完成: " + completedChatId);
                    // Skip speech synthesis for this completed chat
                    // ID不匹配，不清空currentChatId，保持当前对话状态
                  }
                }
              }
            },
            throwable -> {
              System.err.println("[聊天] 错误: " + throwable.getMessage());
              if (throwable instanceof CozeApiException) {
                CozeApiException apiException = (CozeApiException) throwable;
                System.err.println("[API] 错误代码: " + apiException.getCode());
                System.err.println("[API] 错误消息: " + apiException.getMsg());
                System.err.println("[API] 日志ID: " + apiException.getLogID());
              }
              throwable.printStackTrace();
              isResponding.set(false);
              // Reset currentChatId on error
            },
            () -> {
              // Empty onComplete handler - all processing is done in event handler
              isResponding.set(false);
            });

    isResponding.set(true);
  }









  // 调用百炼语音识别服务
  private static void startBaichuanSpeechRecognition() {
    if (DASHSCOPE_API_KEY == null || DASHSCOPE_API_KEY.isEmpty()) {
      System.err.println("[错误] DASHSCOPE_API_KEY环境变量未设置");
      return;
    }

    OkHttpClient client = new OkHttpClient();

    Request request =
        new Request.Builder()
            .url(WEBSOCKET_URL)
            .addHeader("Authorization", "Bearer " + DASHSCOPE_API_KEY)
            .addHeader("user-agent", "Baichuan-Speech-Client/1.0")
            .build();

    webSocket =
        client.newWebSocket(
            request,
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket webSocket, Response response) {
                System.out.println("[百炼ASR] WebSocket连接已建立");
                isConnected.set(true);
                sendRunTaskCommand();
              }

              @Override
              public void onMessage(WebSocket webSocket, String text) {
                handleServerEvent(text);
              }

              @Override
              public void onMessage(WebSocket webSocket, ByteString bytes) {
                // 百炼服务不会返回二进制消息，只返回JSON事件
              }

              @Override
              public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
                isConnected.set(false);
                System.out.println("[百炼ASR] WebSocket连接正在关闭: " + reason);
              }

              @Override
              public void onClosed(WebSocket webSocket, int code, String reason) {
                isConnected.set(false);
                System.out.println("[百炼ASR] WebSocket连接已关闭: " + code + ", 原因: " + reason);
              }

              @Override
              public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("[百炼ASR] WebSocket连接失败: " + t.getMessage());
                isConnected.set(false);
              }
            });
  }

  // 发送运行任务命令
  private static void sendRunTaskCommand() {
    JSONObject command = new JSONObject();
    JSONObject header = new JSONObject();
    header.put("action", "run-task");
    header.put("task_id", taskId);
    header.put("streaming", "duplex");

    JSONObject payload = new JSONObject();
    payload.put("task_group", "audio");
    payload.put("task", "asr");
    payload.put("function", "recognition");
    payload.put("model", "fun-asr-realtime"); // 使用Fun-ASR模型

    JSONObject parameters = new JSONObject();
    parameters.put("format", "pcm");
    parameters.put("sample_rate", 16000); // 按照文档要求使用16000采样率
    parameters.put("language_hints", Arrays.asList("zh"));
    parameters.put("punctuation_prediction_enabled", true);
    parameters.put("inverse_text_normalization_enabled", true);
    parameters.put("max_sentence_silence", 800); // 设置VAD断句静音时长阈值为800ms
    parameters.put("multi_threshold_mode_enabled", true); // 防止VAD断句切割过长
    parameters.put("heartbeat", true); // 启用心跳保持长连接

    payload.put("parameters", parameters);
    payload.put("input", new JSONObject());

    command.put("header", header);
    command.put("payload", payload);

    webSocket.send(command.toJSONString());
    System.out.println("[百炼ASR] 已发送run-task命令，任务ID: " + taskId);
  }

  // 发送完成任务命令
  private static void sendFinishTaskCommand() {
    if (webSocket == null || !isConnected.get()) {
      System.err.println("[百炼ASR] WebSocket未连接，无法发送finish-task命令");
      return;
    }

    JSONObject command = new JSONObject();
    JSONObject header = new JSONObject();
    header.put("action", "finish-task");
    header.put("task_id", taskId);
    header.put("streaming", "duplex");

    JSONObject payload = new JSONObject();
    payload.put("input", new JSONObject());

    command.put("header", header);
    command.put("payload", payload);

    webSocket.send(command.toJSONString());
    System.out.println("[百炼ASR] 已发送finish-task命令，任务ID: " + taskId);
  }

  // 处理服务器事件
  private static void handleServerEvent(String text) {
    // 心跳事件不需要打印日志，减少输出噪音
    if (text.contains("\"event\":\"heartbeat\"")) {
      // 收到心跳，更新最后活动时间
      lastTranscriptUpdateTime = System.currentTimeMillis();
      return;
    }
    
    // System.out.println("[百炼转录] 收到服务器事件: " + text);
    
    JSONObject event = JSON.parseObject(text);
    JSONObject header = event.getJSONObject("header");
    String eventType = header.getString("event");
    
    System.out.println("[百炼转录] 事件类型: " + eventType);

    switch (eventType) {
      case "task-started":
        System.out.println("[百炼转录] 任务启动成功");
        break;
      case "result-generated":
        handleRecognitionResult(event);
        break;
      case "task-finished":
        System.out.println("[百炼转录] 任务完成");
        // 任务结束后可以重新开始新任务
        taskId = UUID.randomUUID().toString();
        sendRunTaskCommand();
        break;
      case "task-failed":
        String errorMessage = header.getString("error_message");
        System.err.println("[百炼转录] 任务失败: " + errorMessage);
        break;
      default:
        System.out.println("[百炼转录] 未知事件类型: " + eventType);
    }
  }

  // 处理识别结果 - 根据currentChatId判断赋值或拼接
  private static void handleRecognitionResult(JSONObject event) {
    JSONObject payload = event.getJSONObject("payload");
    JSONObject output = payload.getJSONObject("output");
    JSONObject sentence = output.getJSONObject("sentence");
    String text = sentence.getString("text");

    lastTranscriptUpdateTime = System.currentTimeMillis();
    System.out.println("[百炼转录] 实时转录结果: " + text);

    // 判断currentChatId是否为空
    if (currentChatId == null || currentChatId.isEmpty()) {
      // currentChatId为空：直接将生成的文本赋值给识别缓冲区
      currentTranscription.set(text);
      System.out.println("[百炼转录] 当前识别缓冲区: " + text);
    } else {
      // currentChatId不为空：将历史记录中最后一条与当前更新的进行拼接
      String existingText = currentTranscription.get();
      if (existingText != null && !existingText.isEmpty()) {
        // 有现有内容，进行拼接
        String combinedText = existingText + text;
        currentTranscription.set(combinedText);
        System.out.println("[百炼转录] 拼接后识别缓冲区: " + combinedText);
      } else {
        // 没有现有内容，直接赋值
        currentTranscription.set(text);
        System.out.println("[百炼转录] 当前识别缓冲区: " + text);
      }
    }
  }

  // Coze转录回调处理
  private static class TranscriptionCallbackHandler
      extends WebsocketsAudioTranscriptionsCallbackHandler {

    @Override
    public void onTranscriptionsCreated(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsCreatedEvent event) {
      System.out.println("[COZE ASR] ========== onTranscriptionsCreated 被调用 ==========");
      System.out.println("[COZE ASR] 转录会话已创建: " + event);
      isConnected.set(true);
      System.out.println("[COZE ASR] WebSocket连接已建立，isConnected=true");
    }

    @Override
    public void onTranscriptionsUpdated(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsUpdatedEvent event) {
      System.out.println("[COZE ASR] ========== onTranscriptionsUpdated 被调用 ==========");
      System.out.println("[COZE ASR] 转录配置已更新: " + event.getData());
    }

    @Override
    public void onTranscriptionsMessageUpdate(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageUpdateEvent event) {
      // System.out.println("[COZE ASR] ========== onTranscriptionsMessageUpdate 被调用 ==========");
      
      // 中断当前音频播放
      interruptAudioPlayback();

      String text = event.getData().getContent();
      currentTranscription.set(text);
      lastTranscriptUpdateTime = System.currentTimeMillis();

      System.out.println("[Coze转录] 实时转录结果: '" + text + "'");

      // 对应前端的interim处理
      lastSoundTime = System.currentTimeMillis(); // 更新最后有声音的时间

      // 检查是否是新的转录结果，避免重复添加
      if (transcriptionSegments.isEmpty()
          || !transcriptionSegments.get(transcriptionSegments.size() - 1).equals(text)) {
        transcriptionSegments.add(text); // 实时添加到转录片段
        System.out.println("[Coze转录] 添加转录片段到列表，当前共 " + transcriptionSegments.size() + " 个片段");
      }
    }

    @Override
    public void onTranscriptionsMessageCompleted(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageCompletedEvent event) {
      System.out.println("[COZE ASR] ========== onTranscriptionsMessageCompleted 被调用 ==========");
      System.out.println("[COZE ASR] 转录过程结束");
    }
  }

  // 初始化Coze转录客户端
  private static void initCozeTranscriptionClient() {
    System.out.println("[Coze转录] ========== 开始初始化 Coze 转录客户端 ==========");
    try {
      System.out.println("[Coze转录] 创建 WebsocketsAudioTranscriptionsClient...");
      transcriptionClient =
          coze.websockets()
              .audio()
              .transcriptions()
              .create(new WebsocketsAudioTranscriptionsCreateReq(new TranscriptionCallbackHandler()));
      System.out.println("[Coze转录] transcriptionClient 创建成功: " + transcriptionClient);
      
      // 配置转录参数
      System.out.println("[Coze转录] 配置转录参数...");
      System.out.println("[Coze转录] 采样率: " + SAMPLE_RATE + ", 声道: " + CHANNELS + ", 位深: 16, 编码: pcm");
      
      com.coze.openapi.client.websocket.event.model.InputAudio inputAudio =
          com.coze.openapi.client.websocket.event.model.InputAudio.builder()
              .sampleRate(SAMPLE_RATE)
              .codec("pcm")
              .format("pcm")
              .channel(CHANNELS)
              .bitDepth(16)
              .build();

      com.coze.openapi.client.websocket.event.model.TranscriptionsUpdateEventData updateData =
          com.coze.openapi.client.websocket.event.model.TranscriptionsUpdateEventData.builder()
              .inputAudio(inputAudio)
              .build();

      System.out.println("[Coze转录] 发送 transcriptionsUpdate 配置...");
      transcriptionClient.transcriptionsUpdate(updateData);
      System.out.println("[Coze转录] ========== Coze 转录客户端初始化完成，等待连接建立... ==========");
    } catch (Exception e) {
      System.err.println("[Coze转录] 初始化 Coze 转录客户端失败: " + e.getMessage());
      e.printStackTrace();
    }
  }



  // 初始化Qwen TTS连接
  private static void initQwenTtsConnection() {
    if (DASHSCOPE_API_KEY == null || DASHSCOPE_API_KEY.isEmpty()) {
      System.err.println("ERROR: DASHSCOPE_API_KEY environment variable not set");
      return;
    }

    try {
      QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
          .model("qwen3-tts-instruct-flash-realtime")
          .url("wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
          .apikey(DASHSCOPE_API_KEY)
          .build();

      audioPlayer = new RealtimePcmPlayer(24000);

      qwenTtsRealtime = new QwenTtsRealtime(param, new QwenTtsRealtimeCallback() {
        @Override
        public void onOpen() {
          System.out.println("Qwen TTS SDK connection established");
        }

        @Override
        public void onEvent(JsonObject message) {
          String type = message.get("type").getAsString();
          switch (type) {
            case "session.created":
              System.out.println(
                  "start session: "
                      + message.get("session").getAsJsonObject().get("id").getAsString());
              isSessionStarted.set(true);
              break;
            case "response.audio.delta":
              String recvAudioB64 = message.get("delta").getAsString();
              audioPlayer.write(recvAudioB64);
              break;
            case "response.done":
              System.out.println("response done");
              ttsCompleteLatch.get().countDown();
              break;
            case "session.finished":
              System.out.println("session finished");
              ttsCompleteLatch.get().countDown();
              break;
            default:
              break;
          }
        }

        @Override
        public void onClose(int code, String reason) {
          System.err.println("Qwen TTS SDK connection closed code: " + code + ", reason: " + reason);
        }
      });

      qwenTtsRealtime.connect();
      // 等待连接建立
      TimeUnit.MILLISECONDS.sleep(1000);
      System.out.println("Qwen TTS SDK connection established");
    } catch (InterruptedException e) {
      System.err.println("Qwen TTS SDK connection interrupted: " + e.getMessage());
      // 重新尝试连接
      System.out.println("Retrying Qwen TTS SDK connection...");
      initQwenTtsConnection();
    } catch (Exception e) {
      System.err.println("Qwen TTS SDK connection failed: " + e.getMessage());
      e.printStackTrace();
    }
  }

  // 实现Qwen TTS session.update指令
  private static void sendQwenTtsSessionUpdate(String voiceId, String instructions) {
    if (qwenTtsRealtime == null) {
      System.err.println("ERROR: Qwen TTS SDK not initialized");
      return;
    }

    // 如果会话已经开始，不允许更新
    if (isSessionStarted.get()) {
      System.err.println("ERROR: Cannot update session after it has started");
      return;
    }

    QwenTtsRealtimeConfig config =
        QwenTtsRealtimeConfig.builder()
            .voice((voiceId != null && !voiceId.isEmpty()) ? voiceId : QWEN_VOICE_ID)
            .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
            .mode("server_commit")
            .instructions(instructions)
            .optimizeInstructions(instructions != null && !instructions.isEmpty())
            .build();

    qwenTtsRealtime.updateSession(config);
    System.out.println(
        "Sent Qwen TTS session.update command with voice: "
            + voiceId
            + ", instructions: "
            + instructions);
  }

  // 实现Qwen TTS input_text_buffer.append指令
  private static void sendQwenTtsAppendText(String text) {
    if (qwenTtsRealtime == null) {
      System.err.println("ERROR: Qwen TTS SDK not initialized");
      return;
    }

    qwenTtsRealtime.appendText(text);
    System.out.println("Sent Qwen TTS input_text_buffer.append command with text: " + text);
  }

  // 实现Qwen TTS session.finish指令
  private static void sendQwenTtsFinish() {
    if (qwenTtsRealtime == null) {
      System.err.println("ERROR: Qwen TTS SDK not initialized");
      return;
    }

    // 仅在server_commit模式下需要commit
    qwenTtsRealtime.commit();
    qwenTtsRealtime.finish();
    System.out.println("Sent Qwen TTS session.finish command");
    // 重置会话状态
    isSessionStarted.set(false);
  }

  // 中断当前音频播放（对应前端的interrupt）
  private static void interruptAudioPlayback() {
    // 设置停止标志 - 这会让正在播放的音频及时停止
    shouldStopPlayback = true;
    
    // 注意：不要调用 thread.interrupt() 或 future.cancel(true)，
    // 因为这会中断正在进行的 Coze TTS 网络请求，导致 InterruptedIOException
    
    // 中断SDK音频播放器
    if (audioPlayer != null) {
      try {
        audioPlayer.cancel();
        System.out.println("[AUDIO] 中断SDK音频播放器");
      } catch (Exception e) {
        // 忽略
      }
    }

    // 中断传统音频线路 - 这会立即停止播放
    if (audioLine != null) {
      try {
        if (audioLine.isRunning()) {
          audioLine.stop();
        }
        if (audioLine.isOpen()) {
          audioLine.flush();
          audioLine.close();
        }
        audioLine = null;
        System.out.println("[AUDIO] 中断传统音频线路");
      } catch (Exception e) {
        System.err.println("[AUDIO] 中断音频播放时出错: " + e.getMessage());
        audioLine = null;
      }
    }

    // 更新状态
    isResponding.set(false);
  }

  // 播放音频方法（用于Coze TTS）
  private static void playAudio(
      byte[] audioData,
      int sampleRate,
      int sampleSizeInBits,
      int channels,
      boolean signed,
      boolean bigEndian)
      throws Exception {
    
    System.out.println("[AUDIO] 开始播放音频，数据大小: " + audioData.length + " 字节");
    System.out.println("[AUDIO] 音频格式: " + sampleRate + "Hz, " + sampleSizeInBits + "bit, " + channels + "声道");

    // 使用Java Sound API播放音频
    javax.sound.sampled.AudioFormat audioFormat =
        new javax.sound.sampled.AudioFormat(
            sampleRate, sampleSizeInBits, channels, signed, bigEndian);

    DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
    audioLine = (SourceDataLine) AudioSystem.getLine(info);
    audioLine.open(audioFormat);
    audioLine.start();
    System.out.println("[AUDIO] 音频线路已打开并开始播放");

    // 分块写入音频数据，检查停止标志
    int chunkSize = 1024 * 8; // 8KB chunks
    int offset = 0;
    while (offset < audioData.length && !shouldStopPlayback) {
      int length = Math.min(chunkSize, audioData.length - offset);
      audioLine.write(audioData, offset, length);
      offset += length;
    }
    
    if (shouldStopPlayback) {
      System.out.println("[AUDIO] 音频播放被中断");
    } else {
      if (audioLine != null) {
        audioLine.drain();
        System.out.println("[AUDIO] 音频播放完成");
      }
    }
    
    if (audioLine != null) {
      try {
        audioLine.close();
      } catch (Exception e) {
        // 忽略关闭时的异常
      }
      audioLine = null;
    }
  }

  // 创建会话（对应前端的createConversation函数）
  private static void createConversation(String botID, String userID) throws Exception {
    System.out.println("=== Creating conversation ====");

    if ("QWEN".equalsIgnoreCase(SPEECH_SERVICE)) {
      // 百炼语音识别不需要创建会话，直接使用WebSocket连接
      conversationId = UUID.randomUUID().toString();
    } else {
      // 使用Coze API创建真实会话
      CreateConversationReq createConversationReq = new CreateConversationReq();
      createConversationReq.setBotID(botID);
      
      CreateConversationResp conversation = coze.conversations().create(createConversationReq);
      conversationId = conversation.getConversation().getId();
    }

    System.out.println("[CONVERSATION] Created conversation ID: " + conversationId);
    System.out.println("[CONVERSATION] Bot ID: " + botID);
    System.out.println("[CONVERSATION] User ID: " + userID);
  }

  // 获取音频设备列表（对应前端的getUserMedia和enumerateDevices）
  private static List<AudioDevice> getAudioDevices() throws Exception {
    List<AudioDevice> devices = new ArrayList<>();

    // 获取所有音频输入设备
    javax.sound.sampled.Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
    for (javax.sound.sampled.Mixer.Info mixerInfo : mixerInfos) {
      try {
        javax.sound.sampled.Mixer mixer = AudioSystem.getMixer(mixerInfo);
        if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, JAVA_AUDIO_FORMAT))) {
          devices.add(new AudioDevice(mixerInfo));
        }
      } catch (Exception e) {
        // 跳过无法访问的设备
        continue;
      }
    }

    return devices;
  }

  // 音频设备信息类
  private static class AudioDevice {
    private javax.sound.sampled.Mixer.Info mixerInfo;

    public AudioDevice(javax.sound.sampled.Mixer.Info mixerInfo) {
      this.mixerInfo = mixerInfo;
    }

    public javax.sound.sampled.Mixer.Info getMixerInfo() {
      return mixerInfo;
    }

    public String getDeviceName() {
      return mixerInfo.getName();
    }
  }

  // 实时PCM音频播放器类
  public static class RealtimePcmPlayer {
    private int sampleRate;
    private SourceDataLine line;
    private javax.sound.sampled.AudioFormat audioFormat;
    private Thread decoderThread;
    private Thread playerThread;
    private java.util.concurrent.atomic.AtomicBoolean stopped =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private java.util.Queue<String> b64AudioBuffer =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private java.util.Queue<byte[]> RawAudioBuffer =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    // 构造函数初始化音频格式和音频线路
    public RealtimePcmPlayer(int sampleRate) throws javax.sound.sampled.LineUnavailableException {
      this.sampleRate = sampleRate;
      this.audioFormat = new javax.sound.sampled.AudioFormat(this.sampleRate, 16, 1, true, false);
      DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
      line = (SourceDataLine) AudioSystem.getLine(info);
      line.open(audioFormat);
      line.start();
      decoderThread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  while (!stopped.get()) {
                    String b64Audio = b64AudioBuffer.poll();
                    if (b64Audio != null) {
                      byte[] rawAudio = Base64.getDecoder().decode(b64Audio);
                      RawAudioBuffer.add(rawAudio);
                    } else {
                      try {
                        Thread.sleep(100);
                      } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                      }
                    }
                  }
                }
              });
      playerThread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  while (!stopped.get()) {
                    byte[] rawAudio = RawAudioBuffer.poll();
                    if (rawAudio != null) {
                      try {
                        playChunk(rawAudio);
                      } catch (IOException e) {
                        throw new RuntimeException(e);
                      } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                      }
                    } else {
                      try {
                        Thread.sleep(100);
                      } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                      }
                    }
                  }
                }
              });
      decoderThread.start();
      playerThread.start();
    }

    // 播放一个音频块并阻塞直到播放完成
    private void playChunk(byte[] chunk) throws IOException, InterruptedException {
      if (chunk == null || chunk.length == 0) return;

      int bytesWritten = 0;
      while (bytesWritten < chunk.length) {
        bytesWritten += line.write(chunk, bytesWritten, chunk.length - bytesWritten);
      }
      int audioLength = chunk.length / (this.sampleRate * 2 / 1000);
      // 等待缓冲区中的音频播放完成
      Thread.sleep(audioLength - 10);
    }

    public void write(String b64Audio) {
      b64AudioBuffer.add(b64Audio);
    }

    public void cancel() {
      b64AudioBuffer.clear();
      RawAudioBuffer.clear();
    }

    public void waitForComplete() throws InterruptedException {
      // 等待所有缓冲区中的音频数据播放完成
      while (!b64AudioBuffer.isEmpty() || !RawAudioBuffer.isEmpty()) {
        Thread.sleep(100);
      }
      // 等待音频线路播放完成
      line.drain();
    }

    public void shutdown() throws InterruptedException {
      stopped.set(true);
      decoderThread.join();
      playerThread.join();
      if (line != null && line.isRunning()) {
        line.drain();
        line.close();
      }
    }
  }
}