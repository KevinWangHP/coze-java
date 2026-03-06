package example.websocket.demo.service;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 音频格式转换工具类
 * 用于不同采样率之间的转换
 */
public class AudioConverter {

  /**
   * 将音频数据从源采样率转换为目标采样率
   * 使用高质量的采样率转换算法
   *
   * @param sourceData   原始音频数据 (16-bit PCM)
   * @param sourceRate   源采样率
   * @param targetRate   目标采样率
   * @return 转换后的音频数据
   */
  public static byte[] convertSampleRate(byte[] sourceData, int sourceRate, int targetRate) {
    if (sourceRate == targetRate) {
      return sourceData;
    }

    try {
      // 创建源音频格式 (16-bit PCM, 单声道, 小端)
      AudioFormat sourceFormat = new AudioFormat(
          AudioFormat.Encoding.PCM_SIGNED,
          sourceRate,
          16,
          1,
          2,
          sourceRate,
          false
      );

      // 创建目标音频格式
      AudioFormat targetFormat = new AudioFormat(
          AudioFormat.Encoding.PCM_SIGNED,
          targetRate,
          16,
          1,
          2,
          targetRate,
          false
      );

      // 创建音频输入流
      ByteArrayInputStream bais = new ByteArrayInputStream(sourceData);
      AudioInputStream sourceStream = new AudioInputStream(bais, sourceFormat, sourceData.length / 2);

      // 使用 AudioSystem 进行采样率转换
      AudioInputStream convertedStream = AudioSystem.getAudioInputStream(targetFormat, sourceStream);

      // 读取转换后的数据
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int bytesRead;
      while ((bytesRead = convertedStream.read(buffer)) != -1) {
        baos.write(buffer, 0, bytesRead);
      }

      convertedStream.close();
      sourceStream.close();

      return baos.toByteArray();

    } catch (Exception e) {
      System.err.println("[AudioConverter] 采样率转换失败: " + e.getMessage());
      // 如果转换失败，使用线性插值作为备用方案
      return convertSampleRateLinear(sourceData, sourceRate, targetRate);
    }
  }

  /**
   * 将 24kHz 音频下采样到 16kHz
   * 下采样不会丢失信息，音质更好
   */
  public static byte[] convert24kTo16k(byte[] sourceData) {
    return convertSampleRate(sourceData, 24000, 16000);
  }

  /**
   * 将 16kHz 音频上采样到 24kHz
   * 上采样无法还原丢失的高频信息
   */
  public static byte[] convert16kTo24k(byte[] sourceData) {
    return convertSampleRate(sourceData, 16000, 24000);
  }

  /**
   * 简单的线性插值采样率转换（备用方案）
   * 当 AudioSystem 转换失败时使用
   */
  public static byte[] convertSampleRateLinear(byte[] sourceData, int sourceRate, int targetRate) {
    if (sourceRate == targetRate) {
      return sourceData;
    }

    // 计算转换比例
    double ratio = (double) targetRate / sourceRate;

    // 16-bit PCM，每个样本 2 字节
    int sourceSamples = sourceData.length / 2;
    int targetSamples = (int) (sourceSamples * ratio);
    byte[] targetData = new byte[targetSamples * 2];

    for (int i = 0; i < targetSamples; i++) {
      // 计算源位置
      double sourcePos = i / ratio;
      int sourceIndex = (int) sourcePos;
      double fraction = sourcePos - sourceIndex;

      // 获取相邻样本
      short sample1 = getSample(sourceData, sourceIndex);
      short sample2 = getSample(sourceData, Math.min(sourceIndex + 1, sourceSamples - 1));

      // 线性插值
      short interpolated = (short) (sample1 * (1 - fraction) + sample2 * fraction);

      // 写入目标数据
      setSample(targetData, i, interpolated);
    }

    return targetData;
  }

  private static short getSample(byte[] data, int sampleIndex) {
    int byteIndex = sampleIndex * 2;
    if (byteIndex + 1 >= data.length) {
      return 0;
    }
    return (short) ((data[byteIndex] & 0xFF) | (data[byteIndex + 1] << 8));
  }

  private static void setSample(byte[] data, int sampleIndex, short sample) {
    int byteIndex = sampleIndex * 2;
    if (byteIndex + 1 < data.length) {
      data[byteIndex] = (byte) (sample & 0xFF);
      data[byteIndex + 1] = (byte) ((sample >> 8) & 0xFF);
    }
  }
}