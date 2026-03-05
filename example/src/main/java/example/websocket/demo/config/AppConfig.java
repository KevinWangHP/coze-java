package example.websocket.demo.config;

import java.io.InputStream;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

public class AppConfig {
  private static final String CONFIG_FILE = "websocket/config/application.yaml";
  private static AppConfig instance;

  // Global Configuration
  private String cozeToken;
  private String qwenToken;
  private String cozeBaseUrl;
  private String qwenBaseUrl;

  // ASR Configuration
  private String asrProvider;

  // Audio Sample Rates
  public static final int SAMPLE_RATE_QWEN = 16000;
  public static final int SAMPLE_RATE_COZE = 24000;

  // Workflow Configuration
  private String workflowProvider;
  private String workflowCozeBotId;
  private String workflowLlmModel;
  private String workflowUserId;

  // TTS Configuration
  private String ttsProvider;
  private boolean ttsStreaming;  // true = WebSocket streaming, false = HTTP non-streaming
  private String ttsCozeVoiceId;
  private String ttsQwenVoiceId;

  private AppConfig() {
    loadConfig();
  }

  public static synchronized AppConfig getInstance() {
    if (instance == null) {
      instance = new AppConfig();
    }
    return instance;
  }

  @SuppressWarnings("unchecked")
  private void loadConfig() {
    try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
      if (inputStream == null) {
        System.err.println("[Config] 配置文件未找到: " + CONFIG_FILE);
        loadFromEnv();
        return;
      }

      Yaml yaml = new Yaml();
      Map<String, Object> config = yaml.load(inputStream);

      // Global Configuration
      Map<String, Object> global = (Map<String, Object>) config.get("global");
      if (global != null) {
        // Tokens
        Map<String, Object> tokens = (Map<String, Object>) global.get("tokens");
        if (tokens != null) {
          cozeToken = resolveValue(tokens.get("coze"), null);
          qwenToken = resolveValue(tokens.get("qwen"), null);
        }
        // Base URLs
        Map<String, Object> baseUrls = (Map<String, Object>) global.get("base_urls");
        if (baseUrls != null) {
          cozeBaseUrl = resolveValue(baseUrls.get("coze"), null);
          qwenBaseUrl = resolveValue(baseUrls.get("qwen"), null);
        }
      }

      // ASR Configuration
      Map<String, Object> asr = (Map<String, Object>) config.get("asr");
      if (asr != null) {
        asrProvider = resolveValue(asr.get("provider"), "QWEN");
      }

      // Workflow Configuration
      Map<String, Object> workflow = (Map<String, Object>) config.get("workflow");
      if (workflow != null) {
        workflowProvider = resolveValue(workflow.get("provider"), "COZE");
        Map<String, Object> workflowCoze = (Map<String, Object>) workflow.get("coze");
        if (workflowCoze != null) {
          workflowCozeBotId = resolveValue(workflowCoze.get("bot_id"), null);
        }
        workflowLlmModel = resolveValue(workflow.get("llm_model"), null);
        workflowUserId = resolveValue(workflow.get("user_id"), null);
      }

      // TTS Configuration
      Map<String, Object> tts = (Map<String, Object>) config.get("tts");
      if (tts != null) {
        ttsProvider = resolveValue(tts.get("provider"), "QWEN");
        // Streaming mode: default false for backward compatibility
        Object streamingValue = tts.get("streaming");
        ttsStreaming = streamingValue != null && Boolean.parseBoolean(streamingValue.toString());
        Map<String, Object> ttsCoze = (Map<String, Object>) tts.get("coze");
        if (ttsCoze != null) {
          ttsCozeVoiceId = resolveValue(ttsCoze.get("voice_id"), null);
        }
        Map<String, Object> ttsQwen = (Map<String, Object>) tts.get("qwen");
        if (ttsQwen != null) {
          ttsQwenVoiceId = resolveValue(ttsQwen.get("voice_id"), "Cherry");
        }
      }

      System.out.println("[Config] 配置文件加载成功");

    } catch (Exception e) {
      System.err.println("[Config] 加载配置文件失败: " + e.getMessage());
      loadFromEnv();
    }
  }

  private String resolveValue(Object value, String defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    String strValue = value.toString();
    if (strValue.isEmpty()) {
      return defaultValue;
    }
    // 支持 ${ENV_VAR} 或 ${ENV_VAR:default} 格式
    if (strValue.startsWith("${") && strValue.endsWith("}")) {
      String envExpr = strValue.substring(2, strValue.length() - 1);
      String[] parts = envExpr.split(":", 2);
      String envVar = parts[0];
      String envDefault = parts.length > 1 ? parts[1] : defaultValue;
      String envValue = System.getenv(envVar);
      return envValue != null ? envValue : envDefault;
    }
    return strValue;
  }

  private void loadFromEnv() {
    System.out.println("[Config] 从环境变量加载配置");

    // Global Tokens
    cozeToken = System.getenv("COZE_API_TOKEN");
    qwenToken = System.getenv("DASHSCOPE_API_KEY");

    // Global Base URLs
    cozeBaseUrl = System.getenv("COZE_API_BASE");
    qwenBaseUrl = System.getenv("QWEN_API_BASE");

    // ASR
    asrProvider = System.getenv("ASR_PROVIDER") != null ? System.getenv("ASR_PROVIDER") : "QWEN";

    // Workflow
    workflowProvider =
        System.getenv("WORKFLOW_PROVIDER") != null ? System.getenv("WORKFLOW_PROVIDER") : "COZE";
    workflowCozeBotId = System.getenv("COZE_BOT_ID");
    workflowLlmModel = System.getenv("LLM");
    workflowUserId = System.getenv("USER_ID");

    // TTS
    ttsProvider = System.getenv("TTS_PROVIDER") != null ? System.getenv("TTS_PROVIDER") : "QWEN";
    String ttsStreamingEnv = System.getenv("TTS_STREAMING");
    ttsStreaming = ttsStreamingEnv != null && ttsStreamingEnv.equalsIgnoreCase("true");
    ttsCozeVoiceId = System.getenv("COZE_VOICE_ID");
    ttsQwenVoiceId =
        System.getenv("QWEN_VOICE_ID") != null ? System.getenv("QWEN_VOICE_ID") : "Cherry";
  }

  // ==================== Global Getters ====================
  public String getCozeToken() {
    return cozeToken;
  }

  public String getQwenToken() {
    return qwenToken;
  }

  public String getCozeBaseUrl() {
    return cozeBaseUrl;
  }

  public String getQwenBaseUrl() {
    return qwenBaseUrl;
  }

  // ==================== ASR Getters ====================
  public String getAsrProvider() {
    return asrProvider;
  }

  public int getAsrSampleRate() {
    return "QWEN".equalsIgnoreCase(asrProvider) ? SAMPLE_RATE_QWEN : SAMPLE_RATE_COZE;
  }

  // ==================== Workflow Getters ====================
  public String getWorkflowProvider() {
    return workflowProvider;
  }

  public String getWorkflowCozeBotId() {
    return workflowCozeBotId;
  }

  public String getWorkflowLlmModel() {
    return workflowLlmModel;
  }

  public String getWorkflowUserId() {
    return workflowUserId;
  }

  // ==================== TTS Getters ====================
  public String getTtsProvider() {
    return ttsProvider;
  }

  public boolean isTtsStreaming() {
    return ttsStreaming;
  }

  public String getTtsCozeVoiceId() {
    return ttsCozeVoiceId;
  }

  public String getTtsQwenVoiceId() {
    return ttsQwenVoiceId;
  }

  // ==================== Legacy Getters (for backward compatibility) ====================
  @Deprecated
  public String getSpeechService() {
    return asrProvider;
  }

  @Deprecated
  public String getCozeApiToken() {
    return cozeToken;
  }

  @Deprecated
  public String getCozeApiBase() {
    return cozeBaseUrl;
  }

  @Deprecated
  public String getCozeBotId() {
    return workflowCozeBotId;
  }

  @Deprecated
  public String getCozeVoiceId() {
    return ttsCozeVoiceId;
  }

  @Deprecated
  public String getQwenApiKey() {
    return qwenToken;
  }

  @Deprecated
  public String getQwenVoiceId() {
    return ttsQwenVoiceId;
  }

  @Deprecated
  public String getUserId() {
    return workflowUserId;
  }

  @Deprecated
  public String getLlmModel() {
    return workflowLlmModel;
  }

  public void printConfig() {
    System.out.println("[Config] 当前配置:");
    System.out.println("  ASR Provider: " + asrProvider);
    System.out.println("  Workflow Provider: " + workflowProvider);
    System.out.println("  TTS Provider: " + ttsProvider);
    System.out.println("  TTS Streaming: " + ttsStreaming);
    System.out.println("  User ID: " + (workflowUserId != null ? workflowUserId : "未设置"));
    System.out.println("  LLM Model: " + (workflowLlmModel != null ? workflowLlmModel : "未设置"));
  }
}
