package example.websocket.audio.transcriptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

import com.coze.openapi.client.websocket.event.downstream.*;
import com.coze.openapi.client.websocket.event.model.InputAudio;
import com.coze.openapi.client.websocket.event.model.TranscriptionsUpdateEventData;
import com.coze.openapi.service.auth.TokenAuth;
import com.coze.openapi.service.service.CozeAPI;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsCallbackHandler;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsClient;
import com.coze.openapi.service.service.websocket.audio.transcriptions.WebsocketsAudioTranscriptionsCreateReq;

/*
This example demonstrates how to use the WebSocket transcription API to transcribe audio data,
process transcription events, and handle the results through callback methods.
 */
public class WebsocketTranscriptionsRealtimeExample {

  public static boolean isDone = false;

  private static class CallbackHandler extends WebsocketsAudioTranscriptionsCallbackHandler {
    private final ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024 * 10); // 分配 10MB 缓冲区

    public CallbackHandler() {
      super();
    }

    @Override
    public void onError(WebsocketsAudioTranscriptionsClient client, ErrorEvent event) {
      System.out.println(event);
    }

    @Override
    public void onClientException(WebsocketsAudioTranscriptionsClient client, Throwable e) {
      e.printStackTrace();
    }

    // 转录配置更新事件 (transcriptions.updated)
    @Override
    public void onTranscriptionsUpdated(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsUpdatedEvent event) {
      System.out.println("=== Transcriptions Updated ===");
      System.out.println(event);
    }

    // 转录创建事件 (transcriptions.created)
    @Override
    public void onTranscriptionsCreated(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsCreatedEvent event) {
      System.out.println("=== Transcriptions Created ===");
      System.out.println(event);
    }

    // 转录消息更新事件 (transcriptions.message.update)
    @Override
    public void onTranscriptionsMessageUpdate(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageUpdateEvent event) {
      System.out.println("[messageupdate] 实时转录结果: " + event.getData().getContent());
    }

    // 转录消息完成事件 (transcriptions.message.completed)
    @Override
    public void onTranscriptionsMessageCompleted(
        WebsocketsAudioTranscriptionsClient client, TranscriptionsMessageCompletedEvent event) {
      System.out.println("=== Transcriptions Message Completed ===");
      System.out.println(event);
      isDone = true;
    }

    // 语音缓冲区完成事件 (input_audio_buffer.completed)
    @Override
    public void onInputAudioBufferCompleted(
        WebsocketsAudioTranscriptionsClient client, InputAudioBufferCompletedEvent event) {
      System.out.println("=== Input Audio Buffer Completed ===");
      System.out.println(event);
    }
  }

  // For non-streaming chat API, it is necessary to create a chat first and then poll the chat
    // results.
    public static void main(String[] args) throws Exception {
      // Get an access_token through personal access token or oauth.
      String token = System.getenv("COZE_API_TOKEN");
      TokenAuth authCli = new TokenAuth(token);

      // Init the Coze client through the access_token.
      CozeAPI coze =
          new CozeAPI.Builder()
              .baseURL(System.getenv("COZE_API_BASE"))
              .auth(authCli)
              .readTimeout(10000)
              .build();

      WebsocketsAudioTranscriptionsClient client = null;
      TargetDataLine targetDataLine = null;
      try {
        client =
            coze.websockets()
                .audio()
                .transcriptions()
                .create(new WebsocketsAudioTranscriptionsCreateReq(new CallbackHandler()));

        // 设置音频格式
        AudioFormat audioFormat = new AudioFormat(24000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);

        // 列出所有可用的音频设备
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        System.out.println("可用音频设备:");
        for (int i = 0; i < mixerInfos.length; i++) {
          System.out.println(i + ": " + mixerInfos[i].getName());
        }

        // 选择设备2
        if (mixerInfos.length < 2) {
          System.out.println("没有足够的音频设备");
          return;
        }
        
        Mixer mixer = AudioSystem.getMixer(mixerInfos[1]); // 索引从0开始，设备2对应索引1
        targetDataLine = (TargetDataLine) mixer.getLine(info);
        targetDataLine.open(audioFormat);
        targetDataLine.start();

        // 录制音频到WAV文件
        File wavFile = new File("recording.wav");
        AudioInputStream audioInputStream = new AudioInputStream(targetDataLine);
        
        System.out.println("开始录音，按Ctrl+C停止...");
        // 录制5秒音频
        Thread recordThread = new Thread(() -> {
          try {
            AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, wavFile);
          } catch (IOException e) {
            e.printStackTrace();
          }
        });
        recordThread.start();
        
        // 等待5秒后停止录音
        TimeUnit.SECONDS.sleep(10);
        targetDataLine.stop();
        targetDataLine.close();
        recordThread.join();
        
        System.out.println("录音完成，开始转写...");
        
        // 配置音频格式
        InputAudio inputAudio =
            InputAudio.builder().sampleRate(24000).codec("pcm").format("wav").channel(1).bitDepth(16).build();
        client.transcriptionsUpdate(new TranscriptionsUpdateEventData(inputAudio));

        // 读取WAV文件并发送
        try (FileInputStream inputStream = new FileInputStream(wavFile)) {
          byte[] buffer = new byte[1024];
          int bytesRead;

          while ((bytesRead = inputStream.read(buffer)) != -1) {
            client.inputAudioBufferAppend(Arrays.copyOf(buffer, bytesRead));
            // 模拟人说话的间隔
            TimeUnit.MILLISECONDS.sleep(100);
          }
        } catch (IOException e) {
          e.printStackTrace();
        }

      } catch (Exception e) {
        e.printStackTrace();
      } finally {
        if (targetDataLine != null && targetDataLine.isOpen()) {
          targetDataLine.stop();
          targetDataLine.close();
        }
        if (client != null) {
          client.close();
        }
        coze.shutdownExecutor();
      }
    }
}
