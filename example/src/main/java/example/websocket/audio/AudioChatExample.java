package example.websocket.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

import com.coze.openapi.client.audio.speech.CreateSpeechReq;
import com.coze.openapi.client.audio.speech.CreateSpeechResp;
import com.coze.openapi.client.chat.CreateChatReq;
import com.coze.openapi.client.chat.model.ChatEvent;
import com.coze.openapi.client.chat.model.ChatEventType;
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
  private static final int SILENCE_DURATION = 2000; // 静音持续时间（毫秒）

  // 解决Java Sound API的命名冲突
  private static final javax.sound.sampled.AudioFormat JAVA_AUDIO_FORMAT =
      new javax.sound.sampled.AudioFormat(
          SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, true, BIG_ENDIAN);

  private static AtomicBoolean isTranscribing = new AtomicBoolean(true);
  private static AtomicReference<String> currentTranscription = new AtomicReference<>();
  private static List<String> transcriptionSegments = new ArrayList<>();
  private static long lastSoundTime = System.currentTimeMillis();

  private static class TranscriptionCallbackHandler
      extends WebsocketsAudioTranscriptionsCallbackHandler {

    @Override
    public void onTranscriptionsMessageUpdate(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageUpdateEvent event) {
      String text = event.getData().getContent();
      currentTranscription.set(text);
      System.out.println("[TRANSCRIPTION] " + text);
      lastSoundTime = System.currentTimeMillis(); // 更新最后有声音的时间
      transcriptionSegments.add(text); // 实时添加到转录片段
    }

    @Override
    public void onTranscriptionsMessageCompleted(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageCompletedEvent event) {
      // TranscriptionsMessageCompletedEvent doesn't have data field
      // Use currentTranscription instead
      String text = currentTranscription.get();
      if (text != null) {
        transcriptionSegments.add(text);
      }
      currentTranscription.set(null);
      isTranscribing.set(false);
      System.out.println("[TRANSCRIPTION COMPLETED] " + text);
    }

    @Override
    public void onClientException(WebsocketsAudioTranscriptionsClient client, Throwable e) {
      e.printStackTrace();
      isTranscribing.set(false);
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

    // Step 1: Capture audio from microphone
    System.out.println("Capturing audio from microphone...");
    DataLine.Info info = new DataLine.Info(TargetDataLine.class, JAVA_AUDIO_FORMAT);
    TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
    microphone.open(JAVA_AUDIO_FORMAT);
    microphone.start();

    // 等待麦克风初始化
    TimeUnit.MILLISECONDS.sleep(1000); // 更长的初始化时间
    System.out.println("[DEBUG] 麦克风初始化完成，开始录制...");
    System.out.println("[DEBUG] 音频格式: " + JAVA_AUDIO_FORMAT);

    // Step 2: Stream audio to transcription API
    WebsocketsAudioTranscriptionsClient transcriptionClient =
        coze.websockets()
            .audio()
            .transcriptions()
            .create(new WebsocketsAudioTranscriptionsCreateReq(new TranscriptionCallbackHandler()));

    // Configure transcription settings - 参考前端实现
    com.coze.openapi.client.websocket.event.model.InputAudio inputAudio =
        com.coze.openapi.client.websocket.event.model.InputAudio.builder()
            .sampleRate(48000) // 前端使用48000采样率
            .codec("pcm")
            .format("pcm")
            .channel(CHANNELS)
            .build();

    com.coze.openapi.client.websocket.event.model.TranscriptionsUpdateEventData updateData =
        com.coze.openapi.client.websocket.event.model.TranscriptionsUpdateEventData.builder()
            .inputAudio(inputAudio)
            .build();

    transcriptionClient.transcriptionsUpdate(updateData);
    System.out.println("[DEBUG] 转录配置已发送: 48000采样率, PCM格式");
    System.out.println("[DEBUG] 转录配置已发送");

    // Start streaming audio
    byte[] audioBuffer = new byte[4096];
    int bytesRead;

    long lastUpdateTime = System.currentTimeMillis();
    lastSoundTime = System.currentTimeMillis(); // 初始化最后有声音的时间

    while (isTranscribing.get()) {
      bytesRead = microphone.read(audioBuffer, 0, audioBuffer.length);
      if (bytesRead > 0) {
        transcriptionClient.inputAudioBufferAppend(Arrays.copyOf(audioBuffer, bytesRead));

        // 检测是否有声音
        boolean hasSound = false;
        for (int i = 0; i < bytesRead; i += 2) { // 16位采样，每2字节一个样本
          short sample = (short) ((audioBuffer[i] & 0xFF) | (audioBuffer[i + 1] << 8));
          if (Math.abs(sample) > SILENCE_THRESHOLD) {
            hasSound = true;
            break;
          }
        }

        if (hasSound) {
          lastSoundTime = System.currentTimeMillis();
          System.out.println("[DEBUG] 检测到声音");
        } else {
          System.out.println("[DEBUG] 静音中...");
        }

        // 检测静音持续时间
        if (System.currentTimeMillis() - lastSoundTime > SILENCE_DURATION) {
          System.out.println("[DEBUG] 静音超过 " + SILENCE_DURATION + " 毫秒，结束录制");
          break;
        }
      }

      // Check if no update for 2 seconds
      if (currentTranscription.get() != null
          && System.currentTimeMillis() - lastUpdateTime > 2000) {
        transcriptionSegments.add(currentTranscription.get());
        currentTranscription.set(null);
        System.out.println("[DEBUG] 2秒无新识别结果，结束当前输入");
        break;
      }

      if (currentTranscription.get() != null) {
        lastUpdateTime = System.currentTimeMillis();
      }

      // 模拟人说话的间隔，避免发送过快
      TimeUnit.MILLISECONDS.sleep(100);
    }

    // Stop capturing audio
    microphone.stop();
    microphone.close();
    transcriptionClient.inputAudioBufferComplete();
    System.out.println("[DEBUG] 停止录制，等待转录完成...");

    // 等待转录结果
    System.out.println("[DEBUG] 等待转录结果...");
    TimeUnit.SECONDS.sleep(5); // 等待5秒让转录完成（更长时间确保完成）

    // Step 3: Send transcription to chat bot
    String fullTranscription = String.join(" ", transcriptionSegments);
    System.out.println("Full transcription: " + fullTranscription);

    if (fullTranscription.isEmpty()) {
      System.out.println("[WARNING] 转录结果为空，请检查麦克风或网络连接");
      System.out.println("[DEBUG] 尝试使用WebsocketTranscriptionsExample的方式重新连接...");
      // 可以在这里添加重试逻辑
      return;
    }

    CreateChatReq chatReq =
        CreateChatReq.builder()
            .botID(botID)
            .userID(userID)
            .messages(Collections.singletonList(Message.buildUserQuestionText(fullTranscription)))
            .build();

    System.out.println("Sending to chat bot...");
    Flowable<ChatEvent> chatResp = coze.chat().stream(chatReq);

    StringBuilder botResponse = new StringBuilder();

    chatResp
        .subscribeOn(Schedulers.io())
        .subscribe(
            event -> {
              if (ChatEventType.CONVERSATION_MESSAGE_DELTA.equals(event.getEvent())) {
                if (event.getMessage() != null && event.getMessage().getContent() != null) {
                  botResponse.append(event.getMessage().getContent());
                }
              }
            },
            throwable -> {
              System.err.println("Chat error: " + throwable.getMessage());
              throwable.printStackTrace();
            },
            () -> {
              System.out.println("\nBot response: " + botResponse.toString());

              // Step 4: Convert bot response to speech
              try {
                CreateSpeechReq speechReq =
                    CreateSpeechReq.builder()
                        .input(botResponse.toString())
                        .voiceID(voiceID)
                        .responseFormat(com.coze.openapi.client.audio.common.AudioFormat.WAV)
                        .sampleRate(24000)
                        .build();

                CreateSpeechResp speechResp = coze.audio().speech().create(speechReq);
                ExampleUtils.writePcmToWavFile(
                    speechResp.getResponse().bytes(), "bot_response.wav");

                System.out.println("Bot response saved to bot_response.wav");
              } catch (Exception e) {
                e.printStackTrace();
              }
            });

    // Wait for completion
    TimeUnit.SECONDS.sleep(10);
    coze.shutdownExecutor();
  }
}
