package example.websocket.demo.service;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/** 性能日志记录器 - 用于记录ASR、Workflow、TTS各阶段的时延和性能指标 */
public class PerformanceLogger {
  private static final String LOG_FILE = "performance.log";
  private static final DateTimeFormatter TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
  private static final DateTimeFormatter FILE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

  private final String logFileName;
  private final PrintWriter writer;

  // ASR统计
  private final AtomicLong asrSessionStartTime = new AtomicLong(0);
  private final AtomicLong asrLastAudioTime = new AtomicLong(0); // 最后一次收到音频的时间
  private final AtomicLong asrAudioBytesAccumulated = new AtomicLong(0);
  private final AtomicLong asrAudioDurationMs = new AtomicLong(0);
  private final int sampleRate;
  private final int bytesPerSample;

  // Workflow统计
  private final AtomicLong workflowStartTime = new AtomicLong(0);

  // TTS统计
  private final AtomicLong ttsStartTime = new AtomicLong(0);

  // 端到端统计
  private final AtomicLong endToEndStartTime = new AtomicLong(0); // 端到端统计开始时间（ASR最后一次收到音频）

  public PerformanceLogger(int sampleRate, int bytesPerSample) {
    this.sampleRate = sampleRate;
    this.bytesPerSample = bytesPerSample;
    String timestamp = LocalDateTime.now().format(FILE_TIME_FORMATTER);
    this.logFileName = "performance_" + timestamp + ".log";
    try {
      this.writer = new PrintWriter(new FileWriter(logFileName, true));
      logHeader();
    } catch (IOException e) {
      throw new RuntimeException("无法创建性能日志文件: " + logFileName, e);
    }
  }

  private void logHeader() {
    writer.println(
        "================================================================================");
    writer.println("性能日志 - 启动时间: " + LocalDateTime.now().format(TIME_FORMATTER));
    writer.println("采样率: " + sampleRate + " Hz, 每样本字节数: " + bytesPerSample);
    writer.println(
        "================================================================================");
    writer.flush();
  }

  /** 记录ASR音频数据累积（每次发送音频时调用） */
  public void accumulateAsrAudio(byte[] audioData) {
    if (audioData != null && audioData.length > 0) {
      asrAudioBytesAccumulated.addAndGet(audioData.length);
      // 计算音频时长: 字节数 / (采样率 * 每样本字节数) * 1000 = 毫秒
      long durationMs = (audioData.length * 1000L) / (sampleRate * bytesPerSample);
      asrAudioDurationMs.addAndGet(durationMs);
      // 更新最后一次收到音频的时间
      asrLastAudioTime.set(System.currentTimeMillis());
    }
  }

  /** 开始ASR会话（首次发送音频或重新开始识别时调用） */
  public void startAsrSession() {
    long now = System.currentTimeMillis();
    asrSessionStartTime.set(now);
    asrLastAudioTime.set(now); // 初始化最后一次音频时间
    asrAudioBytesAccumulated.set(0);
    asrAudioDurationMs.set(0);
  }

  /** 标记端到端统计开始（ASR最后一次收到音频时调用） */
  public void markEndToEndStart() {
    long lastAudioTime = asrLastAudioTime.get();
    if (lastAudioTime > 0) {
      endToEndStartTime.set(lastAudioTime);
    } else {
      endToEndStartTime.set(System.currentTimeMillis());
    }
  }

  /**
   * 记录ASR最终识别结果 识别时延 = 最后一次收到音频的时间 → 得到最终识别结果的时间
   *
   * @param recognizedText 识别出的文本
   */
  public void logAsrResult(String recognizedText) {
    long endTime = System.currentTimeMillis();
    long lastAudioTime = asrLastAudioTime.get();

    // 识别时延：从最后一次收到音频到得到最终结果的时间
    // 这代表了ASR服务器的处理时间
    long recognitionLatencyMs = lastAudioTime > 0 ? endTime - lastAudioTime : 0;

    // 总会话时长：从首次识别到最终结果（包含用户说话时间）
    long sessionStartTime = asrSessionStartTime.get();
    long totalSessionMs = sessionStartTime > 0 ? endTime - sessionStartTime : 0;

    long audioDurationMs = asrAudioDurationMs.get();
    long audioBytes = asrAudioBytesAccumulated.get();
    int textLength = recognizedText != null ? recognizedText.length() : 0;

    String logEntry =
        String.format(
            "[ASR] 时间: %s | 音频时长: %d ms | 音频大小: %d bytes | 识别时延: %d ms | 总会话: %d ms | 文本长度: %d | 内容: %s",
            LocalDateTime.now().format(TIME_FORMATTER),
            audioDurationMs,
            audioBytes,
            recognitionLatencyMs,
            totalSessionMs,
            textLength,
            truncateText(recognizedText, 50));

    writer.println(logEntry);
    writer.flush();

    System.out.println(
        "[性能日志] ASR - 音频时长: "
            + audioDurationMs
            + "ms, 识别时延: "
            + recognitionLatencyMs
            + "ms, 总会话: "
            + totalSessionMs
            + "ms, 文本长度: "
            + textLength);

    // 记录完成后重置ASR累积数据，准备下一次识别
    resetAsrAccumulation();
  }

  /** 重置ASR累积数据（在最终识别完成后调用） */
  public void resetAsrAccumulation() {
    asrAudioBytesAccumulated.set(0);
    asrAudioDurationMs.set(0);
    asrSessionStartTime.set(0);
    asrLastAudioTime.set(0);
  }

  /** 开始Workflow计时 */
  public void startWorkflow() {
    workflowStartTime.set(System.currentTimeMillis());
  }

  /**
   * 记录Workflow结果
   *
   * @param inputText 输入文本
   * @param outputText 输出文本
   */
  public void logWorkflowResult(String inputText, String outputText) {
    long endTime = System.currentTimeMillis();
    long startTime = workflowStartTime.get();
    long latencyMs = startTime > 0 ? endTime - startTime : 0;
    int inputLength = inputText != null ? inputText.length() : 0;
    int outputLength = outputText != null ? outputText.length() : 0;

    String logEntry =
        String.format(
            "[WORKFLOW] 时间: %s | 输入长度: %d | 输出长度: %d | 处理时延: %d ms | 输入: %s | 输出: %s",
            LocalDateTime.now().format(TIME_FORMATTER),
            inputLength,
            outputLength,
            latencyMs,
            truncateText(inputText, 30),
            truncateText(outputText, 50));

    writer.println(logEntry);
    writer.flush();

    System.out.println(
        "[性能日志] Workflow - 输入长度: "
            + inputLength
            + ", 输出长度: "
            + outputLength
            + ", 时延: "
            + latencyMs
            + "ms");
  }

  /** 开始TTS计时 */
  public void startTts() {
    ttsStartTime.set(System.currentTimeMillis());
  }

  /**
   * 记录TTS结果
   *
   * @param text 输入文本
   * @param audioData 生成的音频数据
   * @param audioSampleRate 音频采样率
   */
  public void logTtsResult(String text, byte[] audioData, int audioSampleRate) {
    long endTime = System.currentTimeMillis();
    long startTime = ttsStartTime.get();
    long latencyMs = startTime > 0 ? endTime - startTime : 0;
    int textLength = text != null ? text.length() : 0;
    int audioBytes = audioData != null ? audioData.length : 0;

    // 计算音频时长: PCM 16bit 单声道
    // 时长(秒) = 字节数 / (采样率 * 每样本字节数)
    double audioDurationSec = audioSampleRate > 0 ? (double) audioBytes / (audioSampleRate * 2) : 0;
    long audioDurationMs = (long) (audioDurationSec * 1000);

    String logEntry =
        String.format(
            "[TTS] 时间: %s | 输入长度: %d | 输出大小: %d bytes | 音频时长: %.2f s (%d ms) | 合成时延: %d ms | 内容: %s",
            LocalDateTime.now().format(TIME_FORMATTER),
            textLength,
            audioBytes,
            audioDurationSec,
            audioDurationMs,
            latencyMs,
            truncateText(text, 50));

    writer.println(logEntry);
    writer.flush();

    System.out.println(
        "[性能日志] TTS - 输入长度: "
            + textLength
            + ", 输出大小: "
            + audioBytes
            + " bytes, 音频时长: "
            + String.format("%.2f", audioDurationSec)
            + "s, 合成时延: "
            + latencyMs
            + "ms");
  }

  /** 记录完整的端到端流程统计 端到端时延 = 从ASR最后一次收到音频到TTS开始播放的时间 */
  public void logEndToEndStats(String asrText, String workflowOutput, byte[] ttsAudio) {
    long endTime = System.currentTimeMillis();
    long startTime = endToEndStartTime.get();
    long totalLatencyMs = startTime > 0 ? endTime - startTime : 0;

    writer.println(
        "--------------------------------------------------------------------------------");
    writer.println(
        String.format(
            "[端到端统计] 时间: %s | 总时延: %d ms | ASR文本长度: %d | Workflow输出长度: %d | TTS音频大小: %d bytes",
            LocalDateTime.now().format(TIME_FORMATTER),
            totalLatencyMs,
            asrText != null ? asrText.length() : 0,
            workflowOutput != null ? workflowOutput.length() : 0,
            ttsAudio != null ? ttsAudio.length : 0));
    writer.println(
        "================================================================================");
    writer.flush();

    System.out.println("[性能日志] 端到端 - 总时延: " + totalLatencyMs + " ms (从结束语音输入到开始播放)");
  }

  private String truncateText(String text, int maxLength) {
    if (text == null) return "";
    if (text.length() <= maxLength) return text;
    return text.substring(0, maxLength) + "...";
  }

  public void close() {
    if (writer != null) {
      writer.println(
          "================================================================================");
      writer.println("性能日志 - 结束时间: " + LocalDateTime.now().format(TIME_FORMATTER));
      writer.println(
          "================================================================================");
      writer.flush();
      writer.close();
    }
  }
}
