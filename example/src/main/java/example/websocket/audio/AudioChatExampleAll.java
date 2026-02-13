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

import com.alibaba.dashscope.audio.omni.*;
import com.alibaba.dashscope.audio.qwen_tts_realtime.*;
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
  private static final long TRANSCRIPTION_TIMEOUT = 3000; // 3秒无有效音频输入超时
  private static AtomicBoolean waitingForCompleted = new AtomicBoolean(false); // 标记是否已发送endSession，等待completed事件
  private static long lastStatusLogTime = System.currentTimeMillis(); // 上次状态日志时间

  // 语音服务选择变量
  private static final String SPEECH_SERVICE =
      System.getenv("SPEECH_SERVICE") != null ? System.getenv("SPEECH_SERVICE") : "COZE";

  // 百炼API相关变量
  private static final String DASHSCOPE_API_KEY = System.getenv("DASHSCOPE_API_KEY");
  private static final String QWEN_VOICE_ID =
      System.getenv("QWEN_VOICE_ID") != null ? System.getenv("QWEN_VOICE_ID") : "Cherry";

  // Qwen ASR相关变量
  private static OmniRealtimeConversation qwenAsrConversation = null;
  private static AtomicBoolean qwenAsrSessionStarted = new AtomicBoolean(false);
  private static final boolean QWEN_ASR_VAD_MODE = true; // true=VAD模式(默认), false=手动提交模式

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
      // 初始化Qwen语音识别服务
      initQwenAsrClient();
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
    java.io.BufferedReader reader =
        new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
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
    javax.sound.sampled.Line.Info lineInfo =
        new DataLine.Info(TargetDataLine.class, JAVA_AUDIO_FORMAT);

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
                  System.out.println("[DEBUG] 程序退出，关闭连接...");
                  if (qwenAsrConversation != null) {
                    try {
                      qwenAsrConversation.endSession();
                    } catch (Exception e) {
                      System.err.println("[QWEN ASR] 关闭会话失败: " + e.getMessage());
                    }
                    qwenAsrConversation.close();
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
          short sample = (short) ((audioBuffer[i] & 0xFF) | (audioBuffer[i + 1] << 8));
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
          // System.out.println("[AUDIO] 检测到语音输入，平均能量: " + String.format("%.0f", avgEnergy) + ",
          // 最大振幅: " + maxSample);
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
          // 直接发送音频数据到Qwen ASR服务
          if (qwenAsrConversation != null && qwenAsrSessionStarted.get()) {
            String audioBase64 =
                Base64.getEncoder().encodeToString(Arrays.copyOf(audioBuffer, bytesRead));
            qwenAsrConversation.appendAudio(audioBase64);
            // 只有在有声音时才更新最后发送音频的时间
            if (hasSound) {
              lastSoundTime = System.currentTimeMillis();
            }
          } else {
            System.out.println("[AUDIO] Qwen ASR连接未建立，跳过音频发送");
            System.out.println(
                "[AUDIO] qwenAsrConversation: " + (qwenAsrConversation != null ? "已初始化" : "未初始化"));
            System.out.println("[AUDIO] qwenAsrSessionStarted: " + qwenAsrSessionStarted.get());
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

        // Qwen ASR手动提交模式：检测有效音频输入超时（3秒无有效音频输入）
        long timeSinceLastSound = System.currentTimeMillis() - lastSoundTime;
        if ("QWEN".equalsIgnoreCase(SPEECH_SERVICE)
            && !QWEN_ASR_VAD_MODE  // 非VAD模式才需要手动commit
            && !waitingForCompleted.get()
            && timeSinceLastSound > TRANSCRIPTION_TIMEOUT) {

          System.out.println("[TRANSCRIPTION] 3秒无有效音频输入（距离上次有效音频" + timeSinceLastSound + "ms），发送commit");

          // 发送commit，等待completed事件返回结果
          if (qwenAsrConversation != null && qwenAsrSessionStarted.get()) {
            try {
              // 提交音频缓冲区（发送input_audio_buffer.commit）
              qwenAsrConversation.commit();
              waitingForCompleted.set(true);
              System.out.println("[QWEN ASR] 已发送commit，等待completed事件");
            } catch (Exception e) {
              System.err.println("[QWEN ASR] 发送commit失败: " + e.getMessage());
              waitingForCompleted.set(false);
              lastSoundTime = System.currentTimeMillis();
            }
          } else {
            // 会话未启动，重置计时器
            lastSoundTime = System.currentTimeMillis();
          }
        }
        
        // Coze模式的超时处理（保持不变）
        if (!"QWEN".equalsIgnoreCase(SPEECH_SERVICE) 
            && System.currentTimeMillis() - lastTranscriptUpdateTime > TRANSCRIPTION_TIMEOUT) {
          String lastTranscription = null;
          if (!transcriptionSegments.isEmpty()) {
            lastTranscription = transcriptionSegments.get(transcriptionSegments.size() - 1);
          }
          
          if (lastTranscription != null && !lastTranscription.trim().isEmpty()) {
            System.out.println("[TRANSCRIPTION] 2秒无转录更新，处理识别结果: " + lastTranscription);
            try {
              callBotAndProcessResponse(coze, botID, userID, lastTranscription, voiceID);
            } catch (Exception e) {
              System.err.println("[CHAT] 调用聊天接口失败: " + e.getMessage());
              e.printStackTrace();
            }
            lastTranscriptUpdateTime = System.currentTimeMillis();
            transcriptionSegments.clear();
          } else {
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

  // 处理转录结果（供Qwen ASR使用）
  private static void processTranscriptionResult(String transcription) {
    System.out.println("[TRANSCRIPTION] 处理转录结果: " + transcription);
    try {
      callBotAndProcessResponse(coze, botID, userID, transcription, voiceID);
    } catch (Exception e) {
      System.err.println("[CHAT] 调用聊天接口失败: " + e.getMessage());
      e.printStackTrace();
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
                  System.out.println("[聊天] 更新currentChatId: " + oldChatId + " -> " + currentChatId);
                }
              } else if (ChatEventType.CONVERSATION_MESSAGE_DELTA.getValue().equals(eventValue)) {
                if (event.getMessage() != null && event.getMessage().getContent() != null) {
                  botResponse.append(event.getMessage().getContent());
                }
              } else if (ChatEventType.CONVERSATION_CHAT_COMPLETED.getValue().equals(eventValue)) {
                if (event.getLogID() != null) {
                  String completedChatId = event.getLogID();
                  if (completedChatId.equals(currentChatId)) {
                    System.out.println("[聊天] 完成的聊天ID与当前匹配: " + completedChatId);

                    // 将currentChatId置空
                    currentChatId = null;
                    System.out.println("[CHAT] 重置currentChatId为null");

                    // ASR继续监听，不发送finish-task和run-task指令

                    // Proceed with speech synthesis
                    // System.out.println("\n[CHAT] Bot response: " + botResponse.toString());

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

                                // 添加AI回复到对话历史（在清空缓存后立即添加）
                                Message aiMessage = Message.buildAssistantAnswer(responseText);
                                messageHistory.add(aiMessage);
                                System.out.println("[历史记录] 添加AI回复到历史记录: " + responseText);

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
                                    // System.out.println("[语音] 语气: " + tone + " 内容: " + content);
                                  }

                                  // 保存实际使用的语音ID和语气
                                  final String actualVoiceId =
                                      (QWEN_VOICE_ID != null && !QWEN_VOICE_ID.isEmpty()) ? QWEN_VOICE_ID : "Cherry";

                                  // 发送session.update指令
                                  try {
                                    sendQwenTtsSessionUpdate(actualVoiceId, tone);
                                  } catch (Exception e) {
                                    System.err.println("[错误] Qwen TTS session update 失败: " + e.getMessage());
                                    // 如果失败，尝试重新初始化连接
                                    qwenTtsRealtime = null;
                                    initQwenTtsConnection();
                                    TimeUnit.MILLISECONDS.sleep(2000);
                                    if (qwenTtsRealtime != null) {
                                      sendQwenTtsSessionUpdate(actualVoiceId, tone);
                                    } else {
                                      System.err.println("[错误] Qwen TTS 重新初始化失败，跳过本次合成");
                                      return;
                                    }
                                  }

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
                                  System.out.println(
                                      "[COZE TTS] ========== 开始使用 Coze TTS 生成语音 ==========");
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
                                      System.out.println(
                                          "[COZE TTS] 解析到语气: '"
                                              + tone
                                              + "', 内容: '"
                                              + content
                                              + "'");
                                    }

                                    // 创建语音合成请求
                                    System.out.println("[COZE TTS] 创建语音合成请求...");
                                    String actualVoiceId =
                                        (voiceID != null && !voiceID.isEmpty()) ? voiceID : "alloy";
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
                                    System.out.println(
                                        "[COZE TTS] 语音数据大小: " + speechBytes.length + " 字节");

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
                    System.out.println("[聊天] 完成的聊天ID与当前不匹配，跳过语音合成");
                    System.out.println("[聊天] 当前: " + currentChatId + " 完成: " + completedChatId);
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

  // 初始化Qwen ASR客户端
  private static void initQwenAsrClient() {
    if (DASHSCOPE_API_KEY == null || DASHSCOPE_API_KEY.isEmpty()) {
      System.err.println("[错误] DASHSCOPE_API_KEY环境变量未设置");
      return;
    }

    try {
      OmniRealtimeParam param =
          OmniRealtimeParam.builder()
              .model("qwen3-asr-flash-realtime")
              .url("wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
              .apikey(DASHSCOPE_API_KEY)
              .build();

      qwenAsrConversation =
          new OmniRealtimeConversation(
              param,
              new OmniRealtimeCallback() {
                @Override
                public void onOpen() {
                  System.out.println("[QWEN ASR] WebSocket连接已建立");
                  isConnected.set(true);
                }

                @Override
                public void onEvent(JsonObject message) {
                  handleQwenAsrEvent(message);
                }

                @Override
                public void onClose(int code, String reason) {
                  isConnected.set(false);
                  qwenAsrSessionStarted.set(false);
                  System.out.println("[QWEN ASR] WebSocket连接已关闭: " + code + ", 原因: " + reason);
                }
              });

      qwenAsrConversation.connect();

      // 等待连接建立并配置会话
      TimeUnit.MILLISECONDS.sleep(500);

      // 配置会话参数
      OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
      transcriptionParam.setLanguage("zh");
      transcriptionParam.setInputSampleRate(16000);
      transcriptionParam.setInputAudioFormat("pcm");

      OmniRealtimeConfig.OmniRealtimeConfigBuilder configBuilder =
          OmniRealtimeConfig.builder()
              .modalities(Arrays.asList(OmniRealtimeModality.TEXT))
              .transcriptionConfig(transcriptionParam);
      
      // 根据模式配置VAD
      if (QWEN_ASR_VAD_MODE) {
        // VAD模式：启用自动断句
        configBuilder.enableTurnDetection(true);
        configBuilder.turnDetectionType("server_vad");
        configBuilder.turnDetectionThreshold(0.0f);
        configBuilder.turnDetectionSilenceDurationMs(3000);
        System.out.println("[QWEN ASR] 使用VAD模式（自动断句），静音检测时长: 2000ms");
      } else {
        // 手动提交模式：禁用VAD
        configBuilder.enableTurnDetection(false);
        System.out.println("[QWEN ASR] 使用手动提交模式（非VAD）");
      }
      
      OmniRealtimeConfig config = configBuilder.build();

      qwenAsrConversation.updateSession(config);
      System.out.println("[QWEN ASR] 会话配置已更新");

    } catch (Exception e) {
      System.err.println("[QWEN ASR] 初始化失败: " + e.getMessage());
      e.printStackTrace();
    }
  }

  // 处理Qwen ASR事件
  private static void handleQwenAsrEvent(JsonObject message) {
    String type = message.get("type").getAsString();
    System.out.println("[QWEN ASR] 事件类型: " + type);

    switch (type) {
      case "session.created":
        System.out.println(
            "[QWEN ASR] 会话已创建: "
                + message.get("session").getAsJsonObject().get("id").getAsString());
        break;
      case "session.updated":
        System.out.println("[QWEN ASR] 会话配置已更新");
        qwenAsrSessionStarted.set(true);
        break;
      case "input_audio_buffer.speech_started":
        // 此事件仅在VAD模式下发送，非VAD模式下不会收到
        System.out.println("[QWEN ASR] 检测到语音开始(VAD模式)，停止当前播放并清空缓存");
        interruptAudioPlayback();
        if (audioPlayer != null) {
          audioPlayer.cancel();
          System.out.println("[QWEN ASR] 已清空音频播放缓存");
        }
        break;
      case "input_audio_buffer.speech_stopped":
        // 此事件仅在VAD模式下发送，非VAD模式下不会收到
        System.out.println("[QWEN ASR] 检测到语音结束(VAD模式)");
        break;
      case "input_audio_buffer.committed":
        System.out.println("[QWEN ASR] 音频缓冲区已提交");
        break;
      case "conversation.item.created":
        System.out.println("[QWEN ASR] 对话项已创建");
        break;
      case "conversation.item.input_audio_transcription.text":
        // 实时识别结果（手动提交模式下，text事件只用于显示，不累积）
        String text = message.get("text").getAsString();
        String stash = message.has("stash") ? message.get("stash").getAsString() : "";
        String fullText = text + stash;

        lastTranscriptUpdateTime = System.currentTimeMillis();
        System.out.println("[QWEN ASR] 实时转录结果: " + fullText);

        // 收到实时转录结果时，打断所有播放，清空播放缓存
        interruptAudioPlayback();
        System.out.println("[QWEN ASR] 已打断播放并清空缓存");

        // 手动提交模式下，只保存最新结果，不累积
        currentTranscription.set(fullText);
        break;
      case "conversation.item.input_audio_transcription.completed":
        // 最终识别结果
        String finalTranscript = message.get("transcript").getAsString();
        System.out.println("[QWEN ASR] 收到completed事件，最终转录结果: " + finalTranscript);
        
        // 处理最终转录结果（无论waitingForCompleted状态如何，都处理completed事件）
        processTranscriptionResult(finalTranscript);
        waitingForCompleted.set(false);
        currentTranscription.set("");
        lastTranscriptUpdateTime = System.currentTimeMillis();
        lastSoundTime = System.currentTimeMillis(); // 重置音频输入时间，继续监听
        
        // 不重新建立连接，继续监听新的语音输入
        System.out.println("[QWEN ASR] 处理完成，继续监听...");
        break;
      case "conversation.item.input_audio_transcription.failed":
        System.err.println(
            "[QWEN ASR] 识别失败: "
                + message.get("error").getAsJsonObject().get("message").getAsString());
        break;
      case "session.finished":
        System.out.println("[QWEN ASR] 会话已结束");
        qwenAsrSessionStarted.set(false);
        waitingForCompleted.set(false); // 重置等待标记
        // 服务端关闭了连接，需要重新初始化以继续监听
        System.out.println("[QWEN ASR] 准备重新初始化连接...");
        reconnectQwenAsr();
        break;
      case "error":
        System.err.println(
            "[QWEN ASR] 错误: "
                + message.get("error").getAsJsonObject().get("message").getAsString());
        break;
      default:
        System.out.println("[QWEN ASR] 未处理的事件类型: " + type);
    }
  }

  // 重新连接Qwen ASR服务
  private static void reconnectQwenAsr() {
    System.out.println("[QWEN ASR] 正在重新连接...");
    // 关闭旧连接
    if (qwenAsrConversation != null) {
      try {
        qwenAsrConversation.close();
      } catch (Exception e) {
        // 忽略关闭时的错误
      }
      qwenAsrConversation = null;
    }
    isConnected.set(false);
    qwenAsrSessionStarted.set(false);

    // 延迟后重新初始化
    new Thread(
            () -> {
              try {
                TimeUnit.MILLISECONDS.sleep(500);
                initQwenAsrClient();
              } catch (Exception e) {
                System.err.println("[QWEN ASR] 重新连接失败: " + e.getMessage());
              }
            })
        .start();
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
              .create(
                  new WebsocketsAudioTranscriptionsCreateReq(new TranscriptionCallbackHandler()));
      System.out.println("[Coze转录] transcriptionClient 创建成功: " + transcriptionClient);

      // 配置转录参数
      System.out.println("[Coze转录] 配置转录参数...");
      System.out.println(
          "[Coze转录] 采样率: " + SAMPLE_RATE + ", 声道: " + CHANNELS + ", 位深: 16, 编码: pcm");

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
      QwenTtsRealtimeParam param =
          QwenTtsRealtimeParam.builder()
              .model("qwen3-tts-instruct-flash-realtime")
              .url("wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
              .apikey(DASHSCOPE_API_KEY)
              .build();

      audioPlayer = new RealtimePcmPlayer(24000);

      qwenTtsRealtime =
          new QwenTtsRealtime(
              param,
              new QwenTtsRealtimeCallback() {
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
                      isSessionStarted.set(false);
                      ttsCompleteLatch.get().countDown();
                      break;
                    default:
                      break;
                  }
                }

                @Override
                public void onClose(int code, String reason) {
                  System.err.println(
                      "Qwen TTS SDK connection closed code: " + code + ", reason: " + reason);
                  // 连接关闭后，重置连接对象，以便下次重新初始化
                  qwenTtsRealtime = null;
                  isSessionStarted.set(false);
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

    QwenTtsRealtimeConfig config =
        QwenTtsRealtimeConfig.builder()
            .voice((voiceId != null && !voiceId.isEmpty()) ? voiceId : QWEN_VOICE_ID)
            .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
            .mode("commit")
            .speechRate(1.1f)
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

    // commit模式下需要调用commit()提交缓冲区，然后调用finish()结束会话
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
    System.out.println(
        "[AUDIO] 音频格式: " + sampleRate + "Hz, " + sampleSizeInBits + "bit, " + channels + "声道");

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
      // Qwen ASR不需要创建Coze会话，直接使用DashScope连接
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
    System.out.println("[音频设备] 开始枚举音频设备...");

    // 获取所有音频输入设备 - 在单独的线程中执行以避免阻塞
    final javax.sound.sampled.Mixer.Info[][] mixerInfosHolder = new javax.sound.sampled.Mixer.Info[1][];
    Thread enumerateThread = new Thread(() -> {
      try {
        mixerInfosHolder[0] = AudioSystem.getMixerInfo();
      } catch (Exception e) {
        System.err.println("[音频设备] 枚举失败: " + e.getMessage());
      }
    });
    enumerateThread.setDaemon(true);
    enumerateThread.start();
    enumerateThread.join(5000); // 最多等待5秒
    
    if (enumerateThread.isAlive()) {
      System.err.println("[音频设备] 枚举超时，使用默认设备");
      enumerateThread.interrupt();
      // 返回空列表，让调用方使用默认设备
      return devices;
    }
    
    javax.sound.sampled.Mixer.Info[] mixerInfos = mixerInfosHolder[0];
    if (mixerInfos == null) {
      System.err.println("[音频设备] 无法获取设备列表");
      return devices;
    }
    
    System.out.println("[音频设备] 找到 " + mixerInfos.length + " 个音频设备");

    for (javax.sound.sampled.Mixer.Info mixerInfo : mixerInfos) {
      // 跳过可能导致卡住的设备名称
      String name = mixerInfo.getName().toLowerCase();
      if (name.contains("port") || name.contains("unknown") || name.contains("primary")) {
        System.out.println("[音频设备] 跳过可能不稳定的设备: " + mixerInfo.getName());
        continue;
      }
      
      try {
        System.out.println("[音频设备] 检查设备: " + mixerInfo.getName());
        javax.sound.sampled.Mixer mixer = AudioSystem.getMixer(mixerInfo);
        
        // 简单检查，不使用超时机制（避免线程问题）
        if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, JAVA_AUDIO_FORMAT))) {
          devices.add(new AudioDevice(mixerInfo));
          System.out.println("[音频设备] ✓ 支持输入: " + mixerInfo.getName());
        }
      } catch (Exception e) {
        // 跳过无法访问的设备
        System.out.println("[音频设备] ✗ 跳过设备: " + mixerInfo.getName() + " - " + e.getMessage());
        continue;
      }
    }

    System.out.println("[音频设备] 枚举完成，找到 " + devices.size() + " 个输入设备");
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
    // 预缓冲：积累至少200ms的数据才开始播放，避免网络抖动导致的卡顿
    private static final int PRE_BUFFER_MS = 200;
    private java.util.concurrent.atomic.AtomicBoolean preBufferReady =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile int bufferedDataSize = 0;

    // 构造函数初始化音频格式和音频线路
    public RealtimePcmPlayer(int sampleRate) throws javax.sound.sampled.LineUnavailableException {
      this.sampleRate = sampleRate;
      this.audioFormat = new javax.sound.sampled.AudioFormat(this.sampleRate, 16, 1, true, false);
      DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
      line = (SourceDataLine) AudioSystem.getLine(info);
      // 设置较大的缓冲区以减少卡顿
      int bufferSize = sampleRate * 2 * 2; // 2秒的缓冲区
      line.open(audioFormat, bufferSize);
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
                      bufferedDataSize += rawAudio.length;
                    } else {
                      try {
                        Thread.sleep(5);
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
                    // 预缓冲检查：积累足够数据后再开始播放
                    if (!preBufferReady.get()) {
                      int preBufferBytes = PRE_BUFFER_MS * sampleRate * 2 / 1000;
                      if (bufferedDataSize >= preBufferBytes) {
                        preBufferReady.set(true);
                        System.out.println("[AUDIO] 预缓冲完成，开始播放...");
                      } else {
                        try {
                          Thread.sleep(10);
                        } catch (InterruptedException e) {
                          throw new RuntimeException(e);
                        }
                        continue;
                      }
                    }

                    byte[] rawAudio = RawAudioBuffer.poll();
                    if (rawAudio != null) {
                      bufferedDataSize -= rawAudio.length;
                      try {
                        playChunk(rawAudio);
                      } catch (IOException e) {
                        throw new RuntimeException(e);
                      } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                      }
                    } else {
                      try {
                        Thread.sleep(5);
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

    // 播放一个音频块
    private void playChunk(byte[] chunk) throws IOException, InterruptedException {
      if (chunk == null || chunk.length == 0) return;

      int bytesWritten = 0;
      while (bytesWritten < chunk.length) {
        bytesWritten += line.write(chunk, bytesWritten, chunk.length - bytesWritten);
      }
      // 不阻塞等待，让播放器继续处理下一个块
    }

    public void write(String b64Audio) {
      b64AudioBuffer.add(b64Audio);
    }

    public void cancel() {
      b64AudioBuffer.clear();
      RawAudioBuffer.clear();
      bufferedDataSize = 0;
      preBufferReady.set(false);
    }

    public void waitForComplete() throws InterruptedException {
      // 等待所有缓冲区中的音频数据播放完成
      while (!b64AudioBuffer.isEmpty() || !RawAudioBuffer.isEmpty()) {
        Thread.sleep(50);
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
