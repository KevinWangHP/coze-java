package example.websocket.audio;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

import com.coze.openapi.client.audio.speech.CreateSpeechReq;
import com.coze.openapi.client.audio.speech.CreateSpeechResp;
import com.coze.openapi.client.chat.CreateChatReq;
import com.coze.openapi.client.chat.model.ChatEvent;
import com.coze.openapi.client.chat.model.ChatEventType;
import com.coze.openapi.client.connversations.CreateConversationReq;
import com.coze.openapi.client.connversations.CreateConversationResp;
import com.coze.openapi.client.connversations.message.model.Message;
import com.coze.openapi.client.websocket.event.downstream.*;
import com.coze.openapi.service.auth.TokenAuth;
import com.coze.openapi.service.service.CozeAPI;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsCallbackHandler;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsClient;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsCreateReq;

import example.utils.ExampleUtils;
import io.reactivex.Flowable;
import io.reactivex.schedulers.Schedulers;

/*
This example demonstrates how to:
1. Capture audio from microphone
2. Stream audio to transcription API
3. Send transcribed text to chat bot
4. Convert bot response to speech
*/
public class AudioChatExample {

  private static final int SAMPLE_RATE = 48000; // 前端使用48000采样率
  private static final int CHANNELS = 1;
  private static final int SAMPLE_SIZE_IN_BITS = 16;
  private static final boolean BIG_ENDIAN = false;
  private static final int SILENCE_THRESHOLD = 1000; // 静音阈值
  private static final int SILENCE_DURATION = 5000; // 静音持续时间（毫秒）修改为5秒

  // 解决Java Sound API的命名冲突
  private static final javax.sound.sampled.AudioFormat JAVA_AUDIO_FORMAT =
      new javax.sound.sampled.AudioFormat(
          SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, true, BIG_ENDIAN);

  // 状态管理变量（对应前端）
  private static AtomicBoolean isTranscribing = new AtomicBoolean(true);
  private static AtomicBoolean isConnected = new AtomicBoolean(false);
  private static AtomicBoolean isListening = new AtomicBoolean(false);
  private static AtomicBoolean isResponding = new AtomicBoolean(false);
  
  // 转录相关变量
  private static AtomicReference<String> currentTranscription = new AtomicReference<>();
  private static List<String> transcriptionSegments = new ArrayList<>();
  private static long lastSoundTime = System.currentTimeMillis();
  private static long lastTranscriptUpdateTime = System.currentTimeMillis();
  private static final long TRANSCRIPTION_TIMEOUT = 2000; // 3秒无更新超时
  
  // 对话历史管理
  private static List<Message> messageHistory = new ArrayList<>();
  private static String conversationId = null;
  private static String currentChatId = null;
  
  // 音频播放队列管理
  private static ExecutorService audioPlaybackExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "AudioPlaybackThread");
    t.setDaemon(true);
    return t;
  });
  private static Future<?> currentAudioFuture = null;

  // 调用bot并处理响应（对应前端的callBot函数）
  private static void callBotAndProcessResponse(CozeAPI coze, String botID, String userID, String transcription, String voiceID, WebsocketsAudioTranscriptionsClient transcriptionClient) throws Exception {
    System.out.println("=== Calling Bot ====");
   
    // 中断当前音频播放
    interruptAudioPlayback();
    
    // 检查并处理最后一条消息如果是用户消息
    if (!messageHistory.isEmpty()) {
        Message lastMsg = messageHistory.get(messageHistory.size() - 1);
        if (lastMsg.getRole().getValue().equals("user")) {
            // 删除最后一条用户消息
            messageHistory.remove(messageHistory.size() - 1);
            System.out.println("[HISTORY] Removed duplicate user message");
        }
    }
    
    // 添加最新的用户消息到历史
    Message newUserMessage = Message.buildUserQuestionText(transcription);
    messageHistory.add(newUserMessage);
    System.out.println("[HISTORY] Added new user message: " + transcription);
    
    // 构造完整对话历史JSON字符串
    StringBuilder historyJson = new StringBuilder();
    historyJson.append("[");
    
    // 添加所有历史消息
    for (int i = 0; i < messageHistory.size(); i++) {
        Message msg = messageHistory.get(i);
        if (i > 0) {
            historyJson.append(",");
        }
        historyJson.append(String.format(
            "{\"role\":\"%s\",\"content\":\"%s\"}",
            msg.getRole().getValue(),
            msg.getContent().replace("\"", "\\\"").replace("\n", "\\n")
        ));
    }
    
    historyJson.append("]");
    
    String fullHistoryJson = historyJson.toString();
    System.out.println("Full conversation history JSON: " + fullHistoryJson);
    
    // 创建包含JSON历史的用户消息
    Message userMessage = Message.buildUserQuestionText(fullHistoryJson);

    
    System.out.println("User message with history: " + fullHistoryJson);
    
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
              System.out.println("[CHAT EVENT] Received event: " + eventValue);

              if (ChatEventType.CONVERSATION_CHAT_CREATED.getValue().equals(eventValue)) {
                if (event.getLogID() != null) {
                  String oldChatId = currentChatId;
                  currentChatId = event.getLogID();
                  System.out.println("[CHAT] Updated currentChatId: " + oldChatId + " -> " + currentChatId);
                }
              } else if (ChatEventType.CONVERSATION_MESSAGE_DELTA.getValue().equals(eventValue)) {
                if (event.getMessage() != null && event.getMessage().getContent() != null) {
                  botResponse.append(event.getMessage().getContent());
                }
              } else if (ChatEventType.CONVERSATION_CHAT_COMPLETED.getValue().equals(eventValue)) {
                if (event.getLogID() != null) {
                  String completedChatId = event.getLogID();
                  if (completedChatId.equals(currentChatId)) {
                                        System.out.println("[CHAT] Completed chat ID matches current: " + completedChatId);
                    // 发送清除音频缓冲区事件
                    System.out.println("[ASYNC] 发送清除音频缓冲区事件...");
                    transcriptionClient.inputAudioBufferClear();
                    // 等待清除完成
                    TimeUnit.MILLISECONDS.sleep(1000);
                    // Proceed with speech synthesis
                    System.out.println("\nBot response: " + botResponse.toString());
                    
                    // 添加AI回复到对话历史
                    Message aiMessage = Message.buildAssistantAnswer(botResponse.toString());
                    messageHistory.add(aiMessage);
                    
                    final String responseText = botResponse.toString();
                    // 中断所有正在进行的音频操作

                    
                    // 取消之前的音频播放任务
                    if (currentAudioFuture != null && !currentAudioFuture.isDone()) {
                        interruptAudioPlayback();
                        try {
                            currentAudioFuture.cancel(true);
                            System.out.println("[AUDIO] Cancelled previous audio playback task");
                        } catch (Exception e) {
                            System.err.println("[AUDIO] Error cancelling previous task: " + e.getMessage());
                        }
                    }
                    
                    // 异步处理TTS和播放，不阻塞录制线程
                    currentAudioFuture = audioPlaybackExecutor.submit(() -> {
                        try {
                          isResponding.set(true);
                          
                          // 确保停止所有正在播放的音频
                          interruptAudioPlayback();
                          
                          // 发送清除音频缓冲区事件
                          System.out.println("[ASYNC] 发送清除音频缓冲区事件...");
                          transcriptionClient.inputAudioBufferClear();
                          
                          // 等待清除完成
                          TimeUnit.MILLISECONDS.sleep(1000);
                          
                          // 解析语气和内容
                          String tone = "";
                          String content = responseText;
                          
                          // 匹配[语气] 内容格式
                          java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[(.*?)\\]");
                          java.util.regex.Matcher matcher = pattern.matcher(responseText);
                          
                          if (matcher.find()) {
                            tone = matcher.group(1);
                            content = matcher.replaceFirst("").trim();
                            System.out.println("[speech] tone: " + tone + " content: " + content);
                          }
                          
                          // 如果有语气参数，更新语音合成配置
                          if (!tone.isEmpty()) {
                            try {
                              // 这里需要实现speech.update发送逻辑
                              // 参考前端实现：发送speech.update事件更新合成配置
                              System.out.println("[speech] updated with tone: " + tone);
                            } catch (Exception e) {
                              System.err.println("[speech] update error: " + e.getMessage());
                            }
                          }
                          
                          // 创建语音合成请求
                          CreateSpeechReq speechReq =
                              CreateSpeechReq.builder()
                                  .input(content)
                                  .voiceID(voiceID)
                                  .responseFormat(com.coze.openapi.client.audio.common.AudioFormat.WAV)
                                  .sampleRate(24000)
                                  .build();

                          System.out.println("[ASYNC] 开始生成语音...");
                          CreateSpeechResp speechResp = coze.audio().speech().create(speechReq);
                          byte[] speechBytes = speechResp.getResponse().bytes();
                          
                          System.out.println("[ASYNC] 开始播放语音...");
                          visualizeAudio(speechBytes); // 使用未使用的可视化方法
                          playAudio(speechBytes, 24000, 16, 1, true, false);
                      
                          System.out.println("[ASYNC] 语音播放完成");
                          
                        } catch (InterruptedException e) {
                          System.out.println("[ASYNC] Audio playback task was interrupted");
                          Thread.currentThread().interrupt();
                        } catch (Exception e) {
                          System.err.println("[ASYNC] 音频播放错误: " + e.getMessage());
                          // 不打印完整栈跟踪以避免混乱
                        } finally {
                          isResponding.set(false);
                        }
                      });
                    
                    System.out.println("[MAIN] 继续录制，不等待语音播放完成");
                  } else {
                    System.out.println("[CHAT] Completed chat ID does not match current. Skipping speech synthesis.");
                    System.out.println("[CHAT] Current: " + currentChatId + " Completed: " + completedChatId);
                    // Skip speech synthesis for this completed chat
                  }
                  // Reset currentChatId after processing completed event
                }
              }
            },
            throwable -> {
              System.err.println("Chat error: " + throwable.getMessage());
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

  // 创建会话（对应前端的createConversation函数）
  private static void createConversation(CozeAPI coze, String botID, String userID) throws Exception {
      System.out.println("=== Creating conversation ===");
      
      // 使用Java客户端API创建会话
      CreateConversationReq req = new CreateConversationReq();
      req.setBotID(botID);
      
      CreateConversationResp resp = coze.conversations().create(req);
      conversationId = resp.getConversation().getId();
      
      System.out.println("[CONVERSATION] Created conversation ID: " + conversationId);
      System.out.println("[CONVERSATION] Bot ID: " + botID);
      System.out.println("[CONVERSATION] User ID: " + userID);
  }

  // 检查转录是否完成（通过检查是否有新的转录结果）
  private static boolean isTranscriptionCompleted(long lastUpdateTime) {
    // 如果超过2秒没有新的转录结果，认为转录完成
    return System.currentTimeMillis() - lastUpdateTime > 2000;
  }

  // 音频播放方法（支持队列管理）
  private static void playAudio(byte[] audioData, int sampleRate, int sampleSizeInBits, int channels, boolean signed, boolean bigEndian) throws Exception {
    // 实现音频播放逻辑
    System.out.println("[AUDIO] 开始播放音频");
    
    // 使用Java Sound API播放音频
    javax.sound.sampled.AudioFormat audioFormat = new javax.sound.sampled.AudioFormat(
        sampleRate, sampleSizeInBits, channels, signed, bigEndian);
    
    DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
    audioLine = (SourceDataLine) AudioSystem.getLine(info);
    audioLine.open(audioFormat);
    audioLine.start();
    
    audioLine.write(audioData, 0, audioData.length);
    audioLine.drain();
    audioLine.close();
    audioLine = null;
  }

  // 中断当前音频播放（对应前端的interrupt）
  private static SourceDataLine audioLine = null;
  
  private static void interruptAudioPlayback() {
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
        System.out.println("[AUDIO] 中断当前音频播放");
      } catch (Exception e) {
        System.err.println("[AUDIO] 中断音频播放时出错: " + e.getMessage());
        audioLine = null;
      }
    }
  }

  // 音频可视化功能（简化版，对应前端的AnalyserNode）
  private static void visualizeAudio(byte[] audioData) {
    if (audioData.length == 0) {
      return;
    }
    
    // 计算音频的平均振幅
    double amplitude = 0;
    for (int i = 0; i < audioData.length; i += 2) { // 16位采样
      short sample = (short) ((audioData[i] & 0xFF) | (audioData[i + 1] << 8));
      amplitude += Math.abs(sample);
    }
    amplitude /= (audioData.length / 2);
    
    // 生成可视化字符串
    int bars = (int) (amplitude / 1000);
    StringBuilder visual = new StringBuilder("[");
    for (int i = 0; i < bars; i++) {
      visual.append("#");
    }
    for (int i = bars; i < 20; i++) {
      visual.append(" ");
    }
    visual.append("] ");
    
    System.out.println("[AUDIO VISUAL] " + visual.toString() + " 振幅: " + (int) amplitude);
  }

  private static class TranscriptionCallbackHandler
      extends WebsocketsAudioTranscriptionsCallbackHandler {

    @Override
    public void onTranscriptionsCreated(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsCreatedEvent event) {
      System.out.println("=== Transcriptions Created ===");
      System.out.println("转录会话已创建: " + event);
      
      // 对应前端的on(WebsocketsEventType.TRANSCRIPTIONS_CREATED)
      isConnected.set(true);
      System.out.println("[CONNECTED] WebSocket连接已建立");
    }

    @Override
    public void onTranscriptionsUpdated(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsUpdatedEvent event) {
      System.out.println("=== Transcriptions Updated ===");
      System.out.println("转录配置已更新: " + event.getData());
    }

    @Override
    public void onTranscriptionsMessageUpdate(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageUpdateEvent event) {
      // 中断当前音频播放
      interruptAudioPlayback();
      
      String text = event.getData().getContent();
      currentTranscription.set(text);
      lastTranscriptUpdateTime = System.currentTimeMillis();
      
      System.out.println("=== Transcriptions Message Updated ===");
      System.out.println("实时转录结果: " + text);
      
      // 对应前端的interim处理
      lastSoundTime = System.currentTimeMillis(); // 更新最后有声音的时间
      
      // 检查是否是新的转录结果，避免重复添加
      if (transcriptionSegments.isEmpty() || !transcriptionSegments.get(transcriptionSegments.size() - 1).equals(text)) {
          transcriptionSegments.add(text); // 实时添加到转录片段
      }
      
      // 对应前端的debounce逻辑
      // 这里可以添加防抖处理，等待1200ms无更新后再处理
    }

    @Override
    public void onTranscriptionsMessageCompleted(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageCompletedEvent event) {
      System.out.println("[TRANSCRIPTION COMPLETED] 转录过程结束");
      
      // 对应前端的on(WebsocketsEventType.TRANSCRIPTIONS_MESSAGE_COMPLETED)
      // 这里可以添加转录完成后的处理逻辑
    }

    @Override
    public void onInputAudioBufferCleared(
        WebsocketsAudioTranscriptionsClient client, InputAudioBufferClearedEvent event) {
      System.out.println("=== Input Audio Buffer Cleared ===");
      System.out.println("音频缓冲区已清除");
    }

    @Override
    public void onInputAudioBufferCompleted(
        WebsocketsAudioTranscriptionsClient client, InputAudioBufferCompletedEvent event) {
      System.out.println("=== Input Audio Buffer Completed ===");
      System.out.println("音频缓冲区已完成");
    }

    @Override
    public void onClientException(WebsocketsAudioTranscriptionsClient client, Throwable e) {
      System.err.println("=== Client Exception ===");
      e.printStackTrace();
      isTranscribing.set(false);
      isConnected.set(false);
      
      // 对应前端的on(WebsocketsEventType.ERROR)
      System.err.println("[ERROR] WebSocket连接异常，需要重连");
      
      // 自动重连逻辑
      if (isListening.get()) {
        System.err.println("[RECONNECT] 尝试自动重连...");
        // 这里可以添加重连逻辑
      }
    }
  }

  public static void main(String[] args) throws Exception {
    // Get environment variables
    String token = System.getenv("COZE_API_TOKEN");
    String botID = System.getenv("COZE_BOT_ID");
    String userID = System.getenv("USER_ID");
    String voiceID = System.getenv("COZE_VOICE_ID");

    // Initialize Coze client
    TokenAuth authCli = new TokenAuth(token);
    CozeAPI coze =
        new CozeAPI.Builder()
            .baseURL(System.getenv("COZE_API_BASE"))
            .auth(authCli)
            .readTimeout(30000)
            .build();

    // 创建会话
    createConversation(coze, botID, userID);

    // Step 1: Get audio devices and select microphone
    System.out.println("Getting audio devices...");
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
    
    // 选择第一个可用设备
    AudioDevice selectedDevice = inputDevices.get(1);
    System.out.println("\n选择的音频设备: " + selectedDevice.getDeviceName());
    
    // Step 2: Capture audio from selected microphone
    System.out.println("Capturing audio from microphone...");
    DataLine.Info info = new DataLine.Info(TargetDataLine.class, JAVA_AUDIO_FORMAT);
    TargetDataLine microphone = null;
    
    // 尝试打开所选设备
    try {
        javax.sound.sampled.Mixer mixer = AudioSystem.getMixer(selectedDevice.getMixerInfo());
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
    microphone.open(JAVA_AUDIO_FORMAT);
    microphone.start();
    
    // 等待麦克风初始化
    TimeUnit.MILLISECONDS.sleep(1000); // 缩短初始化时间
    System.out.println("[DEBUG] 麦克风初始化完成，开始录制...");
    System.out.println("[DEBUG] 音频格式: " + JAVA_AUDIO_FORMAT);

    // Step 2: Stream audio to transcription API
    final WebsocketsAudioTranscriptionsClient transcriptionClient =
        coze.websockets()
            .audio()
            .transcriptions()
            .create(new WebsocketsAudioTranscriptionsCreateReq(new TranscriptionCallbackHandler()));
    
    // Add shutdown hook to clean up resources
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        System.out.println("[DEBUG] 程序退出，关闭WebSocket连接...");
        if (transcriptionClient != null) {
            transcriptionClient.close();
        }
        coze.shutdownExecutor();
        
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

    // Configure transcription settings - 参考前端实现
    com.coze.openapi.client.websocket.event.model.InputAudio inputAudio =
        com.coze.openapi.client.websocket.event.model.InputAudio.builder()
            .sampleRate(SAMPLE_RATE) // 使用全局定义的48000采样率
            .codec("pcm") // 修正为正确的16位小端PCM编码格式
            .format("pcm")
            .channel(CHANNELS)
            .bitDepth(16) // 添加位深度设置
            .build();

    com.coze.openapi.client.websocket.event.model.TranscriptionsUpdateEventData updateData =
        com.coze.openapi.client.websocket.event.model.TranscriptionsUpdateEventData.builder()
            .inputAudio(inputAudio)
            .build();

    transcriptionClient.transcriptionsUpdate(updateData);
    System.out.println("[DEBUG] 转录配置已发送: " + SAMPLE_RATE + "采样率, PCM格式");
    System.out.println("[DEBUG] 转录配置已发送: " + inputAudio);

    // 实时流式输入
    byte[] audioBuffer = new byte[1024];
    int bytesRead;
    lastSoundTime = System.currentTimeMillis(); // 初始化最后有声音的时间
    
    System.out.println("开始实时录音和流式传输...");
    
    while (isTranscribing.get()) {
      bytesRead = microphone.read(audioBuffer, 0, audioBuffer.length);
      if (bytesRead > 0) {
          // 直接发送音频数据
          transcriptionClient.inputAudioBufferAppend(Arrays.copyOf(audioBuffer, bytesRead));
          
          // 不再检测声音，改为在transcription update事件中处理音频停止
          
          // 检测转录更新超时
          if (System.currentTimeMillis() - lastTranscriptUpdateTime > TRANSCRIPTION_TIMEOUT) {
              // 检查本地缓存是否为空
              if (!transcriptionSegments.isEmpty()) {
                  // 取list的最后一个值作为user message
                  String lastTranscription = transcriptionSegments.get(transcriptionSegments.size() - 1);
                  System.out.println("[TRANSCRIPTION TIMEOUT] 3秒无转录更新，清除缓冲区并处理结果");
                  // transcriptionClient.inputAudioBufferClear();
                  
                  // 调用bot处理
                  callBotAndProcessResponse(coze, botID, "default_user_id", lastTranscription, voiceID, transcriptionClient);
                  // 清空转录片段
                  transcriptionSegments.clear();
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
}
