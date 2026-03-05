package example.websocket.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.sound.sampled.*;

public class AudioRecorder {
  private static final int CHANNELS = 1;
  private static final int SAMPLE_SIZE_IN_BITS = 16;
  private static final boolean BIG_ENDIAN = false;
  private static final int SILENCE_THRESHOLD = 50;
  private static final int BUFFER_SIZE = 1024;

  private final int sampleRate;
  private final javax.sound.sampled.AudioFormat audioFormat;
  private final int silenceThreshold;
  private TargetDataLine microphone;
  private volatile boolean isRecording = false;
  private Thread recordThread;
  private Consumer<AudioData> audioDataConsumer;
  private Consumer<Exception> errorConsumer;

  public AudioRecorder(int sampleRate) {
    this(sampleRate, SILENCE_THRESHOLD);
  }

  public AudioRecorder(int sampleRate, int silenceThreshold) {
    this.sampleRate = sampleRate;
    this.audioFormat =
        new javax.sound.sampled.AudioFormat(
            sampleRate, SAMPLE_SIZE_IN_BITS, CHANNELS, true, BIG_ENDIAN);
    this.silenceThreshold = silenceThreshold;
  }

  public List<AudioDevice> getAudioDevices() {
    List<AudioDevice> devices = new ArrayList<>();

    final Mixer.Info[][] mixerInfosHolder = new Mixer.Info[1][];
    Thread enumerateThread =
        new Thread(
            () -> {
              try {
                mixerInfosHolder[0] = AudioSystem.getMixerInfo();
              } catch (Exception e) {
                System.err.println("[音频设备] 枚举失败: " + e.getMessage());
              }
            });
    enumerateThread.setDaemon(true);
    enumerateThread.start();
    try {
      enumerateThread.join(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    if (enumerateThread.isAlive()) {
      System.err.println("[音频设备] 枚举超时");
      enumerateThread.interrupt();
      return devices;
    }

    Mixer.Info[] mixerInfos = mixerInfosHolder[0];
    if (mixerInfos == null) {
      return devices;
    }

    for (Mixer.Info mixerInfo : mixerInfos) {
      String name = mixerInfo.getName().toLowerCase();
      if (name.contains("port") || name.contains("unknown") || name.contains("primary")) {
        continue;
      }

      try {
        Mixer mixer = AudioSystem.getMixer(mixerInfo);
        if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, audioFormat))) {
          devices.add(new AudioDevice(mixerInfo));
        }
      } catch (Exception e) {
        // 跳过无法访问的设备
      }
    }

    return devices;
  }

  public void openDevice(AudioDevice device) throws LineUnavailableException {
    DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
    Mixer mixer = AudioSystem.getMixer(device.getMixerInfo());

    try {
      microphone = (TargetDataLine) mixer.getLine(info);
      microphone.open(audioFormat);
    } catch (Exception e) {
      System.out.println("[麦克风] 无法以指定格式打开，尝试默认格式");
      microphone = (TargetDataLine) AudioSystem.getLine(info);
      microphone.open();
    }

    microphone.start();

    try {
      TimeUnit.MILLISECONDS.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public void startRecording(Consumer<AudioData> consumer, Consumer<Exception> errorHandler) {
    if (microphone == null) {
      throw new IllegalStateException("麦克风未初始化");
    }

    this.audioDataConsumer = consumer;
    this.errorConsumer = errorHandler;
    this.isRecording = true;

    recordThread = new Thread(this::recordLoop, "AudioRecorderThread");
    recordThread.setDaemon(true);
    recordThread.start();
  }

  private void recordLoop() {
    byte[] buffer = new byte[BUFFER_SIZE];

    while (isRecording) {
      try {
        int bytesRead = microphone.read(buffer, 0, buffer.length);
        if (bytesRead > 0) {
          AudioData audioData = analyzeAudio(buffer, bytesRead);
          if (audioDataConsumer != null) {
            audioDataConsumer.accept(audioData);
          }
        }
      } catch (Exception e) {
        if (errorConsumer != null) {
          errorConsumer.accept(e);
        }
      }
    }
  }

  private AudioData analyzeAudio(byte[] buffer, int bytesRead) {
    boolean hasSound = false;
    double sum = 0;
    int count = 0;
    short maxSample = 0;

    for (int i = 0; i < bytesRead; i += 2) {
      short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
      sum += Math.abs(sample);
      count++;
      if (Math.abs(sample) > maxSample) {
        maxSample = (short) Math.abs(sample);
      }
      if (Math.abs(sample) > silenceThreshold) {
        hasSound = true;
      }
    }

    double avgEnergy = count > 0 ? sum / count : 0;
    byte[] data = new byte[bytesRead];
    
    // 如果声音太小（平均能量低于静音阈值的一半），将音频归零
    if (avgEnergy < silenceThreshold * 0.5) {
      // 全零数据
      for (int i = 0; i < bytesRead; i++) {
        data[i] = 0;
      }
      hasSound = false;
    } else {
      // 正常音频数据
      System.arraycopy(buffer, 0, data, 0, bytesRead);
    }

    return new AudioData(data, hasSound, avgEnergy, maxSample);
  }

  public void stopRecording() {
    isRecording = false;
    if (recordThread != null) {
      try {
        recordThread.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public void close() {
    stopRecording();
    if (microphone != null) {
      microphone.stop();
      microphone.close();
      microphone = null;
    }
  }

  public boolean isRecording() {
    return isRecording;
  }

  public javax.sound.sampled.AudioFormat getAudioFormat() {
    return audioFormat;
  }

  public static class AudioData {
    private final byte[] data;
    private final boolean hasSound;
    private final double avgEnergy;
    private final short maxSample;

    public AudioData(byte[] data, boolean hasSound, double avgEnergy, short maxSample) {
      this.data = data;
      this.hasSound = hasSound;
      this.avgEnergy = avgEnergy;
      this.maxSample = maxSample;
    }

    public byte[] getData() {
      return data;
    }

    public boolean hasSound() {
      return hasSound;
    }

    public double getAvgEnergy() {
      return avgEnergy;
    }

    public short getMaxSample() {
      return maxSample;
    }
  }
}
