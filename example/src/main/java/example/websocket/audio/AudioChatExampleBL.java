package example.websocket.audio;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
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

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import com.coze.openapi.client.audio.speech.CreateSpeechReq;
import com.coze.openapi.client.audio.speech.CreateSpeechResp;
import com.coze.openapi.client.chat.CreateChatReq;
import com.coze.openapi.client.chat.model.ChatEvent;
import com.coze.openapi.client.chat.model.ChatEventType;
import com.coze.openapi.client.connversations.message.model.Message;
import com.coze.openapi.client.exception.CozeApiException;
import com.coze.openapi.service.auth.TokenAuth;
import com.coze.openapi.service.service.CozeAPI;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsClient;

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
public class AudioChatExampleBL {

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
  
  // 百炼API相关变量
  private static WebSocket webSocket = null;
  private static String taskId = UUID.randomUUID().toString();
  private static final String DASHSCOPE_API_KEY = System.getenv("DASHSCOPE_API_KEY");
  private static final String WEBSOCKET_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
  
  // Coze API相关变量
  private static CozeAPI coze = null;
  private static String cozeToken = System.getenv("COZE_API_TOKEN");
  private static String botID = System.getenv("COZE_BOT_ID");
  private static String userID = System.getenv("USER_ID");
  private static String voiceID = System.getenv("COZE_VOICE_ID");
  
  // 百炼CosyVoice相关变量
  private static WebSocket cosyVoiceWebSocket = null;
  private static String cosyVoiceTaskId = UUID.randomUUID().toString();
  private static final String COSY_VOICE_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
  private static List<byte[]> audioBuffer = new ArrayList<>();
  
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

  // 调用百炼语音识别服务
  private static void startBaichuanSpeechRecognition() {
    if (DASHSCOPE_API_KEY == null || DASHSCOPE_API_KEY.isEmpty()) {
      System.err.println("ERROR: DASHSCOPE_API_KEY environment variable not set");
      return;
    }

    OkHttpClient client = new OkHttpClient();

    Request request = new Request.Builder()
        .url(WEBSOCKET_URL)
        .addHeader("Authorization", "Bearer " + DASHSCOPE_API_KEY)
        .addHeader("user-agent", "Baichuan-Speech-Client/1.0")
        .build();

    webSocket = client.newWebSocket(request, new WebSocketListener() {
      @Override
      public void onOpen(WebSocket webSocket, Response response) {
        System.out.println("WebSocket connection established");
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
        System.out.println("WebSocket connection closing: " + reason);
      }

      @Override
      public void onClosed(WebSocket webSocket, int code, String reason) {
        isConnected.set(false);
        System.out.println("WebSocket connection closed: " + reason);
      }

      @Override
      public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        isConnected.set(false);
        System.err.println("WebSocket connection failed: " + t.getMessage());
        t.printStackTrace();
      }
    });
  }

  // 发送run-task指令
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
    parameters.put("sample_rate", 48000);
    parameters.put("language_hints", Arrays.asList("zh"));
    parameters.put("punctuation_prediction_enabled", true);
    parameters.put("inverse_text_normalization_enabled", true);

    payload.put("parameters", parameters);
    payload.put("input", new JSONObject());

    command.put("header", header);
    command.put("payload", payload);

    webSocket.send(command.toJSONString());
    System.out.println("Sent run-task command with task_id: " + taskId);
    System.out.println("Run-task command: " + command.toJSONString());
  }

  // 处理服务端事件
  private static void handleServerEvent(String eventJson) {
    JSONObject event = JSON.parseObject(eventJson);
    String eventType = event.getJSONObject("header").getString("event");

    switch (eventType) {
      case "task-started":
        System.out.println("Task started successfully");
        break;
      case "result-generated":
        handleRecognitionResult(event);
        break;
      case "task-finished":
        System.out.println("Task finished");
        // 任务结束后可以重新开始新任务
        taskId = UUID.randomUUID().toString();
        sendRunTaskCommand();
        break;
      case "task-failed":
        JSONObject header = event.getJSONObject("header");
        System.err.println("Task failed: " + header.getString("error_message"));
        break;
    }
  }

  // 处理识别结果
  private static void handleRecognitionResult(JSONObject event) {
    JSONObject payload = event.getJSONObject("payload");
    JSONObject output = payload.getJSONObject("output");
    JSONObject sentence = output.getJSONObject("sentence");
    String text = sentence.getString("text");

    currentTranscription.set(text);
    lastTranscriptUpdateTime = System.currentTimeMillis();
    lastSoundTime = System.currentTimeMillis();

    System.out.println("实时转录结果: " + text);

    // 处理中间结果和最终结果
    if (sentence.getBooleanValue("sentence_end")) {
      transcriptionSegments.add(text);
      // 调用聊天接口处理结果
      System.out.println("句子结束，准备调用聊天接口: " + text);
      try {
        callBotAndProcessResponse(coze, botID, userID, text, voiceID);
        // 清空转录片段
        transcriptionSegments.clear();
      } catch (Exception e) {
        System.err.println("调用聊天接口失败: " + e.getMessage());
        e.printStackTrace();
      }
    } else {
      // 中间结果，实时更新显示
      System.out.println("中间转录结果: " + text);
    }
  }

  // 发送音频数据
  private static void sendAudioData(byte[] audioData) {
    if (webSocket != null && isConnected.get()) {
      webSocket.send(ByteString.of(audioData));
    }
  }

  // 结束任务
  private static void finishTask() {
    if (webSocket != null && isConnected.get()) {
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
      System.out.println("Sent finish-task command");
    }
  }

  // 初始化CosyVoice WebSocket连接
  private static void initCosyVoiceConnection() {
    if (DASHSCOPE_API_KEY == null || DASHSCOPE_API_KEY.isEmpty()) {
      System.err.println("ERROR: DASHSCOPE_API_KEY environment variable not set");
      return;
    }

    OkHttpClient client = new OkHttpClient();

    Request request = new Request.Builder()
        .url(COSY_VOICE_URL)
        .addHeader("Authorization", "Bearer " + DASHSCOPE_API_KEY)
        .addHeader("user-agent", "Baichuan-Speech-Client/1.0")
        .build();

    cosyVoiceWebSocket = client.newWebSocket(request, new WebSocketListener() {
      @Override
      public void onOpen(WebSocket webSocket, Response response) {
        System.out.println("CosyVoice WebSocket connection established");
      }

      @Override
      public void onMessage(WebSocket webSocket, String text) {
        handleCosyVoiceEvent(text);
      }

      @Override
      public void onMessage(WebSocket webSocket, ByteString bytes) {
        handleCosyVoiceAudio(bytes.toByteArray());
      }

      @Override
      public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(1000, null);
        System.out.println("CosyVoice WebSocket connection closing: " + reason);
      }

      @Override
      public void onClosed(WebSocket webSocket, int code, String reason) {
        System.out.println("CosyVoice WebSocket connection closed: " + reason);
      }

      @Override
      public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        System.err.println("CosyVoice WebSocket connection failed: " + t.getMessage());
        t.printStackTrace();
      }
    });
  }

  // 实现CosyVoice run-task指令
  private static void sendCosyVoiceRunTask(String voiceId) {
    if (cosyVoiceWebSocket == null) {
      System.err.println("ERROR: CosyVoice WebSocket not connected");
      return;
    }

    JSONObject command = new JSONObject();
    JSONObject header = new JSONObject();
    header.put("action", "run-task");
    header.put("task_id", cosyVoiceTaskId);
    header.put("streaming", "duplex");

    JSONObject payload = new JSONObject();
    payload.put("task_group", "audio");
    payload.put("task", "tts");
    payload.put("function", "speech_synthesis");
    payload.put("model", "cosyvoice-v3-flash");

    JSONObject parameters = new JSONObject();
    parameters.put("voice_id", voiceId);
    parameters.put("format", "wav");
    parameters.put("sample_rate", 24000);
    parameters.put("enable_ssml", false);

    payload.put("parameters", parameters);
    payload.put("input", new JSONObject());

    command.put("header", header);
    command.put("payload", payload);

    cosyVoiceWebSocket.send(command.toJSONString());
    System.out.println("Sent CosyVoice run-task command with task_id: " + cosyVoiceTaskId);
  }

  // 实现CosyVoice continue-task指令
  private static void sendCosyVoiceContinueTask(String text) {
    if (cosyVoiceWebSocket == null) {
      System.err.println("ERROR: CosyVoice WebSocket not connected");
      return;
    }

    JSONObject command = new JSONObject();
    JSONObject header = new JSONObject();
    header.put("action", "continue-task");
    header.put("task_id", cosyVoiceTaskId);
    header.put("streaming", "duplex");

    JSONObject payload = new JSONObject();
    JSONObject input = new JSONObject();
    input.put("text", text);
    payload.put("input", input);

    command.put("header", header);
    command.put("payload", payload);

    cosyVoiceWebSocket.send(command.toJSONString());
    System.out.println("Sent CosyVoice continue-task command with text: " + text);
  }

  // 实现CosyVoice finish-task指令
  private static void sendCosyVoiceFinishTask() {
    if (cosyVoiceWebSocket == null) {
      System.err.println("ERROR: CosyVoice WebSocket not connected");
      return;
    }

    JSONObject command = new JSONObject();
    JSONObject header = new JSONObject();
    header.put("action", "finish-task");
    header.put("task_id", cosyVoiceTaskId);
    header.put("streaming", "duplex");

    JSONObject payload = new JSONObject();
    payload.put("input", new JSONObject());

    command.put("header", header);
    command.put("payload", payload);

    cosyVoiceWebSocket.send(command.toJSONString());
    System.out.println("Sent CosyVoice finish-task command");
  }

  // 处理CosyVoice事件
  private static void handleCosyVoiceEvent(String text) {
    try {
      System.out.println("Received CosyVoice event: " + text);
      JSONObject event = JSONObject.parseObject(text);
      JSONObject header = event.getJSONObject("header");
      String eventType = header.getString("event");

      if ("task-started".equals(eventType)) {
        System.out.println("CosyVoice task started");
      } else if ("result-generated".equals(eventType)) {
        System.out.println("CosyVoice result generated");
      } else if ("task-finished".equals(eventType)) {
        System.out.println("CosyVoice task finished");
        // 播放所有缓存的音频
        playBufferedAudio();
      } else if ("error".equals(eventType)) {
        JSONObject payload = event.getJSONObject("payload");
        String errorMsg = payload.getString("message");
        System.err.println("CosyVoice error: " + errorMsg);
      } else {
        System.out.println("Unknown CosyVoice event type: " + eventType);
      }
    } catch (Exception e) {
      System.err.println("Error parsing CosyVoice event: " + e.getMessage());
      e.printStackTrace();
    }
  }

  // 处理CosyVoice音频
  private static void handleCosyVoiceAudio(byte[] audioBytes) {
    audioBuffer.add(audioBytes);
    System.out.println("Received CosyVoice audio chunk: " + audioBytes.length + " bytes");
    System.out.println("Current audio buffer size: " + audioBuffer.size() + " chunks");
  }

  // 播放缓存的音频
  private static void playBufferedAudio() {
    if (audioBuffer.isEmpty()) {
      System.out.println("No audio to play");
      return;
    }

    System.out.println("Playing buffered audio with " + audioBuffer.size() + " chunks");

    // 合并所有音频片段
    int totalSize = audioBuffer.stream().mapToInt(b -> b.length).sum();
    byte[] combinedAudio = new byte[totalSize];
    int offset = 0;
    for (byte[] chunk : audioBuffer) {
      System.arraycopy(chunk, 0, combinedAudio, offset, chunk.length);
      offset += chunk.length;
    }

    System.out.println("Combined audio size: " + combinedAudio.length + " bytes");

    // 播放合并后的音频
    try {
      playAudio(combinedAudio, 24000, 16, 1, true, false);
      System.out.println("Played combined audio: " + combinedAudio.length + " bytes");
    } catch (Exception e) {
      System.err.println("Error playing audio: " + e.getMessage());
      e.printStackTrace();
    } finally {
      // 清空缓存
      audioBuffer.clear();
    }
  }

  // 调用bot并处理响应（对应前端的callBot函数）
  private static void callBotAndProcessResponse(CozeAPI coze, String botID, String userID, String transcription, String voiceID) throws Exception {
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

    System.out.println("Chat request: " + chatReq);
    
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
                    // Proceed with speech synthesis
                    System.out.println("\nBot response: " + botResponse.toString());
                    
                    // 添加AI回复到对话历史
                    Message aiMessage = Message.buildAssistantAnswer(botResponse.toString());
                    messageHistory.add(aiMessage);
                    
                    final String responseText = botResponse.toString();
                    
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
                          
                          // 使用百炼CosyVoice进行语音合成
                          System.out.println("[ASYNC] 开始使用百炼CosyVoice生成语音...");
                          
                          // 初始化CosyVoice连接
                          if (cosyVoiceWebSocket == null) {
                              initCosyVoiceConnection();
                              // 等待连接建立
                              Thread.sleep(1000);
                          }
                          
                          // 清空之前的音频缓存
                          audioBuffer.clear();
                          
                          // 发送run-task指令
                          sendCosyVoiceRunTask(voiceID);
                          
                          // 发送待合成文本
                          sendCosyVoiceContinueTask(content);
                          
                          // 发送finish-task指令
                          sendCosyVoiceFinishTask();
                          
                          System.out.println("[ASYNC] CosyVoice语音合成请求已发送");
                          System.out.println("[ASYNC] Waiting for audio chunks...");
                          
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
              if (throwable instanceof CozeApiException) {
                  CozeApiException apiException = (CozeApiException) throwable;
                  System.err.println("API Error Code: " + apiException.getCode());
                  System.err.println("API Error Message: " + apiException.getMsg());
                  System.err.println("Log ID: " + apiException.getLogID());
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
  private static void createConversation(String botID, String userID) throws Exception {
      System.out.println("=== Creating conversation ===");
      
      // 百炼语音识别不需要创建会话，直接使用WebSocket连接
      conversationId = UUID.randomUUID().toString();
      
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



  public static void main(String[] args) throws Exception {
    // Get environment variables
    cozeToken = System.getenv("COZE_API_TOKEN");
    botID = System.getenv("COZE_BOT_ID");
    userID = System.getenv("USER_ID");
    voiceID = System.getenv("COZE_VOICE_ID");

    // 初始化Coze client
    TokenAuth authCli = new TokenAuth(cozeToken);
    coze =
        new CozeAPI.Builder()
            .baseURL(System.getenv("COZE_API_BASE"))
            .auth(authCli)
            .readTimeout(30000)
            .build();

    // 初始化百炼语音识别服务
    startBaichuanSpeechRecognition();

    // 创建会话
    createConversation(botID, userID);

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

    // Add shutdown hook to clean up resources
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
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
    lastSoundTime = System.currentTimeMillis(); // 初始化最后有声音的时间
    
    System.out.println("开始实时录音和流式传输...");
    
    while (isTranscribing.get()) {
      bytesRead = microphone.read(audioBuffer, 0, audioBuffer.length);
      if (bytesRead > 0) {
          // 直接发送音频数据到百炼服务
          sendAudioData(Arrays.copyOf(audioBuffer, bytesRead));
          
          // 检测转录更新超时
          if (System.currentTimeMillis() - lastTranscriptUpdateTime > TRANSCRIPTION_TIMEOUT) {
              // 检查本地缓存是否为空
              if (!transcriptionSegments.isEmpty()) {
                  // 取list的最后一个值作为user message
                  String lastTranscription = transcriptionSegments.get(transcriptionSegments.size() - 1);
                  System.out.println("[TRANSCRIPTION TIMEOUT] 3秒无转录更新，清除缓冲区并处理结果");
                  
                  // 这里可以添加调用聊天接口的逻辑
                  System.out.println("[CHAT] 准备调用聊天接口: " + lastTranscription);
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
