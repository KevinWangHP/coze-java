package example.websocket.demo.service;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public class EchoCanceller {
  // 回声缓冲区大小（约500ms的音频，根据采样率调整）
  private static final int MAX_BUFFER_SIZE_MS = 500;
  // 相似度阈值 - 降低阈值以提高检测率
  private static final double ECHO_SIMILARITY_THRESHOLD = 0.65;
  // 最小能量阈值 - 降低以捕获更多回声
  private static final double MIN_ENERGY_THRESHOLD = 50;
  // 回声与播放的最大时间差（毫秒）
  private static final long MAX_ECHO_DELAY_MS = 300;
  // 频谱相似度权重
  private static final double SPECTRAL_WEIGHT = 0.6;
  // 时域相似度权重
  private static final double TEMPORAL_WEIGHT = 0.4;

  private final ConcurrentLinkedQueue<TimestampedAudio> playbackBuffer = new ConcurrentLinkedQueue<>();
  private final AtomicLong lastPlaybackTime = new AtomicLong(0);
  private volatile int sampleRate = 16000;

  private static class TimestampedAudio {
    final byte[] data;
    final long timestamp;
    final double energy;

    TimestampedAudio(byte[] data, long timestamp, double energy) {
      this.data = data.clone();
      this.timestamp = timestamp;
      this.energy = energy;
    }
  }

  public void setSampleRate(int sampleRate) {
    this.sampleRate = sampleRate;
  }

  public void addPlaybackAudio(byte[] audioData) {
    if (audioData == null || audioData.length < 4) {
      return;
    }

    double energy = calculateEnergy(audioData);
    long now = System.currentTimeMillis();
    lastPlaybackTime.set(now);

    // 只存储有足够能量的音频
    if (energy > MIN_ENERGY_THRESHOLD) {
      playbackBuffer.offer(new TimestampedAudio(audioData, now, energy));
      cleanupOldEntries(now);
    }
  }

  public boolean isEcho(byte[] recordedAudio) {
    if (recordedAudio == null || recordedAudio.length < 4) {
      return false;
    }

    long now = System.currentTimeMillis();

    // 如果没有最近的播放，直接返回
    long lastPlay = lastPlaybackTime.get();
    if (now - lastPlay > MAX_ECHO_DELAY_MS) {
      return false;
    }

    double recordedEnergy = calculateEnergy(recordedAudio);
    if (recordedEnergy < MIN_ENERGY_THRESHOLD) {
      return false;
    }

    cleanupOldEntries(now);

    // 检查与最近播放的音频的相似度
    for (TimestampedAudio playback : playbackBuffer) {
      long delay = now - playback.timestamp;

      // 只检查时间差在合理范围内的
      if (delay > 0 && delay <= MAX_ECHO_DELAY_MS) {
        double similarity = calculateSimilarity(recordedAudio, playback.data);

        if (similarity > ECHO_SIMILARITY_THRESHOLD) {
          return true;
        }
      }
    }

    return false;
  }

  private void cleanupOldEntries(long now) {
    while (!playbackBuffer.isEmpty()) {
      TimestampedAudio oldest = playbackBuffer.peek();
      if (oldest == null || (now - oldest.timestamp) > MAX_BUFFER_SIZE_MS) {
        playbackBuffer.poll();
      } else {
        break;
      }
    }
  }

  private double calculateEnergy(byte[] audioData) {
    if (audioData == null || audioData.length < 2) {
      return 0;
    }

    double sum = 0;
    double sumSquares = 0;
    int samples = audioData.length / 2;

    for (int i = 0; i < samples; i++) {
      short sample = (short) ((audioData[i * 2] & 0xFF) | (audioData[i * 2 + 1] << 8));
      sum += Math.abs(sample);
      sumSquares += sample * sample;
    }

    if (samples == 0) return 0;

    // 使用 RMS 能量
    return Math.sqrt(sumSquares / samples);
  }

  private double calculateSimilarity(byte[] recorded, byte[] playback) {
    if (recorded == null || playback == null) {
      return 0.0;
    }

    // 确保长度一致，取较短的长度
    int minLength = Math.min(recorded.length, playback.length);
    if (minLength < 4) {
      return 0.0;
    }

    // 提取样本
    short[] recordedSamples = bytesToSamples(recorded, minLength);
    short[] playbackSamples = bytesToSamples(playback, minLength);

    if (recordedSamples.length == 0 || playbackSamples.length == 0) {
      return 0.0;
    }

    // 计算时域相似度（归一化互相关）
    double temporalSim = calculateTemporalSimilarity(recordedSamples, playbackSamples);

    // 计算频谱相似度（使用简单的频带能量分布）
    double spectralSim = calculateSpectralSimilarity(recordedSamples, playbackSamples);

    // 加权组合
    return TEMPORAL_WEIGHT * temporalSim + SPECTRAL_WEIGHT * spectralSim;
  }

  private double calculateTemporalSimilarity(short[] recorded, short[] playback) {
    int length = Math.min(recorded.length, playback.length);

    // 计算归一化互相关系数
    double meanRecorded = 0, meanPlayback = 0;
    for (int i = 0; i < length; i++) {
      meanRecorded += recorded[i];
      meanPlayback += playback[i];
    }
    meanRecorded /= length;
    meanPlayback /= length;

    double numerator = 0;
    double denomRecorded = 0;
    double denomPlayback = 0;

    for (int i = 0; i < length; i++) {
      double diffRecorded = recorded[i] - meanRecorded;
      double diffPlayback = playback[i] - meanPlayback;

      numerator += diffRecorded * diffPlayback;
      denomRecorded += diffRecorded * diffRecorded;
      denomPlayback += diffPlayback * diffPlayback;
    }

    if (denomRecorded == 0 || denomPlayback == 0) {
      return 0.0;
    }

    double correlation = numerator / Math.sqrt(denomRecorded * denomPlayback);

    // 映射到 [0, 1] 范围
    return (correlation + 1.0) / 2.0;
  }

  private double calculateSpectralSimilarity(short[] recorded, short[] playback) {
    int length = Math.min(recorded.length, playback.length);

    // 使用简单的频带能量比较
    // 将信号分为低频、中频、高频三个频带
    int bands = 3;
    double[] recordedBands = new double[bands];
    double[] playbackBands = new double[bands];

    // 简单的频带划分（基于样本索引的粗略估计）
    for (int i = 0; i < length - 1; i++) {
      // 计算差分（近似高频成分）
      double diffRecorded = Math.abs(recorded[i + 1] - recorded[i]);
      double diffPlayback = Math.abs(playback[i + 1] - playback[i]);

      int band = (i * bands) / length;
      recordedBands[band] += diffRecorded;
      playbackBands[band] += diffPlayback;
    }

    // 计算频带能量分布的相似度（余弦相似度）
    double dotProduct = 0;
    double normRecorded = 0;
    double normPlayback = 0;

    for (int i = 0; i < bands; i++) {
      dotProduct += recordedBands[i] * playbackBands[i];
      normRecorded += recordedBands[i] * recordedBands[i];
      normPlayback += playbackBands[i] * playbackBands[i];
    }

    if (normRecorded == 0 || normPlayback == 0) {
      return 0.0;
    }

    return dotProduct / (Math.sqrt(normRecorded) * Math.sqrt(normPlayback));
  }

  private short[] bytesToSamples(byte[] audioData, int maxBytes) {
    int samples = Math.min(maxBytes, audioData.length) / 2;
    short[] result = new short[samples];
    for (int i = 0; i < samples; i++) {
      result[i] = (short) ((audioData[i * 2] & 0xFF) | (audioData[i * 2 + 1] << 8));
    }
    return result;
  }

  public void clear() {
    playbackBuffer.clear();
    lastPlaybackTime.set(0);
  }
}
