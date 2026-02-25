package example.websocket.audio.config;

import java.io.InputStream;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

public class AppConfig {
  private static final String CONFIG_FILE = "websocket/config/application.yaml";
  private static AppConfig instance;

  // Speech Service
  private String speechService;

  // Coze
  private String cozeApiToken;
  private String cozeApiBase;
  private String cozeBotId;
  private String cozeVoiceId;

  // Qwen
  private String qwenApiKey;
  private String qwenVoiceId;

  // User
  private String userId;

  // LLM
  private String llmModel;

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

      // Speech Service
      Map<String, Object> speech = (Map<String, Object>) config.get("speech");
      if (speech != null) {
        speechService = resolveValue((String) speech.get("service"), "COZE");
      }

      // Coze
      Map<String, Object> coze = (Map<String, Object>) config.get("coze");
      if (coze != null) {
        Map<String, Object> cozeApi = (Map<String, Object>) coze.get("api");
        if (cozeApi != null) {
          cozeApiToken = resolveValue((String) cozeApi.get("token"), null);
          cozeApiBase = resolveValue((String) cozeApi.get("base_url"), null);
        }
        Map<String, Object> cozeBot = (Map<String, Object>) coze.get("bot");
        if (cozeBot != null) {
          cozeBotId = resolveValue((String) cozeBot.get("id"), null);
        }
        Map<String, Object> cozeVoice = (Map<String, Object>) coze.get("voice");
        if (cozeVoice != null) {
          cozeVoiceId = resolveValue((String) cozeVoice.get("id"), null);
        }
      }

      // Qwen
      Map<String, Object> qwen = (Map<String, Object>) config.get("qwen");
      if (qwen != null) {
        Map<String, Object> qwenApi = (Map<String, Object>) qwen.get("api");
        if (qwenApi != null) {
          qwenApiKey = resolveValue((String) qwenApi.get("key"), null);
        }
        Map<String, Object> qwenVoice = (Map<String, Object>) qwen.get("voice");
        if (qwenVoice != null) {
          qwenVoiceId = resolveValue((String) qwenVoice.get("id"), "Cherry");
        }
      }

      // User
      Map<String, Object> user = (Map<String, Object>) config.get("user");
      if (user != null) {
        userId = resolveValue((String) user.get("id"), null);
      }

      // LLM
      Map<String, Object> llm = (Map<String, Object>) config.get("llm");
      if (llm != null) {
        llmModel = resolveValue((String) llm.get("model"), null);
      }

      System.out.println("[Config] 配置文件加载成功");

    } catch (Exception e) {
      System.err.println("[Config] 加载配置文件失败: " + e.getMessage());
      loadFromEnv();
    }
  }

  private String resolveValue(String value, String defaultValue) {
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    // 支持 ${ENV_VAR} 或 ${ENV_VAR:default} 格式
    if (value.startsWith("${") && value.endsWith("}")) {
      String envExpr = value.substring(2, value.length() - 1);
      String[] parts = envExpr.split(":", 2);
      String envVar = parts[0];
      String envDefault = parts.length > 1 ? parts[1] : defaultValue;
      String envValue = System.getenv(envVar);
      return envValue != null ? envValue : envDefault;
    }
    return value;
  }

  private void loadFromEnv() {
    System.out.println("[Config] 从环境变量加载配置");
    speechService =
        System.getenv("SPEECH_SERVICE") != null ? System.getenv("SPEECH_SERVICE") : "COZE";
    qwenApiKey = System.getenv("DASHSCOPE_API_KEY");
    cozeApiToken = System.getenv("COZE_API_TOKEN");
    cozeApiBase = System.getenv("COZE_API_BASE");
    cozeBotId = System.getenv("COZE_BOT_ID");
    userId = System.getenv("USER_ID");
    cozeVoiceId = System.getenv("COZE_VOICE_ID");
    qwenVoiceId =
        System.getenv("QWEN_VOICE_ID") != null ? System.getenv("QWEN_VOICE_ID") : "Cherry";
    llmModel = System.getenv("LLM");
  }

  // Getters
  public String getSpeechService() {
    return speechService;
  }

  public String getCozeApiToken() {
    return cozeApiToken;
  }

  public String getCozeApiBase() {
    return cozeApiBase;
  }

  public String getCozeBotId() {
    return cozeBotId;
  }

  public String getCozeVoiceId() {
    return cozeVoiceId;
  }

  public String getQwenApiKey() {
    return qwenApiKey;
  }

  public String getQwenVoiceId() {
    return qwenVoiceId;
  }

  public String getUserId() {
    return userId;
  }

  public String getLlmModel() {
    return llmModel;
  }

  public void printConfig() {
    System.out.println("[Config] 当前配置:");
    System.out.println("  Speech Service: " + speechService);
    System.out.println("  Coze Bot ID: " + (cozeBotId != null ? "已设置" : "未设置"));
    System.out.println("  Qwen API Key: " + (qwenApiKey != null ? "已设置" : "未设置"));
    System.out.println("  User ID: " + (userId != null ? userId : "未设置"));
    System.out.println("  LLM Model: " + (llmModel != null ? llmModel : "未设置"));
  }
}
