package example.websocket.demo.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AudioWebSocketMessage {

  private static final ObjectMapper mapper = new ObjectMapper();

  @JsonProperty("type")
  private String type;

  @JsonProperty("session_id")
  private String sessionId;

  @JsonProperty("audio_data")
  private String audioData;

  @JsonProperty("text")
  private String text;

  @JsonProperty("voice_id")
  private String voiceId;

  @JsonProperty("tone")
  private String tone;

  @JsonProperty("sample_rate")
  private Integer sampleRate;

  @JsonProperty("codec")
  private String codec;

  @JsonProperty("error")
  private String error;

  @JsonProperty("message")
  private String message;

  @JsonProperty("is_final")
  private Boolean isFinal;

  public AudioWebSocketMessage() {}

  public static AudioWebSocketMessage fromJson(String json) throws Exception {
    return mapper.readValue(json, AudioWebSocketMessage.class);
  }

  public String toJson() throws Exception {
    return mapper.writeValueAsString(this);
  }

  public static AudioWebSocketMessage createAudioInput(String sessionId, String base64Audio) {
    AudioWebSocketMessage msg = new AudioWebSocketMessage();
    msg.type = MessageType.AUDIO_INPUT.getValue();
    msg.sessionId = sessionId;
    msg.audioData = base64Audio;
    return msg;
  }

  public static AudioWebSocketMessage createAudioOutput(
      String sessionId, String base64Audio, boolean isFinal) {
    AudioWebSocketMessage msg = new AudioWebSocketMessage();
    msg.type = MessageType.AUDIO_OUTPUT.getValue();
    msg.sessionId = sessionId;
    msg.audioData = base64Audio;
    msg.isFinal = isFinal;
    return msg;
  }

  public static AudioWebSocketMessage createTranscription(
      String sessionId, String text, boolean isFinal) {
    AudioWebSocketMessage msg = new AudioWebSocketMessage();
    msg.type = MessageType.TRANSCRIPTION.getValue();
    msg.sessionId = sessionId;
    msg.text = text;
    msg.isFinal = isFinal;
    return msg;
  }

  public static AudioWebSocketMessage createChatResponse(String sessionId, String text) {
    AudioWebSocketMessage msg = new AudioWebSocketMessage();
    msg.type = MessageType.CHAT_RESPONSE.getValue();
    msg.sessionId = sessionId;
    msg.text = text;
    return msg;
  }

  public static AudioWebSocketMessage createConfig(
      String sessionId, Integer sampleRate, String codec) {
    AudioWebSocketMessage msg = new AudioWebSocketMessage();
    msg.type = MessageType.CONFIG.getValue();
    msg.sessionId = sessionId;
    msg.sampleRate = sampleRate;
    msg.codec = codec;
    return msg;
  }

  public static AudioWebSocketMessage createError(String sessionId, String error) {
    AudioWebSocketMessage msg = new AudioWebSocketMessage();
    msg.type = MessageType.ERROR.getValue();
    msg.sessionId = sessionId;
    msg.error = error;
    return msg;
  }

  public static AudioWebSocketMessage createSessionStart(String sessionId) {
    AudioWebSocketMessage msg = new AudioWebSocketMessage();
    msg.type = MessageType.SESSION_START.getValue();
    msg.sessionId = sessionId;
    msg.message = "Session started";
    return msg;
  }

  public static AudioWebSocketMessage createSessionEnd(String sessionId) {
    AudioWebSocketMessage msg = new AudioWebSocketMessage();
    msg.type = MessageType.SESSION_END.getValue();
    msg.sessionId = sessionId;
    msg.message = "Session ended";
    return msg;
  }

  public static AudioWebSocketMessage createInterrupt(String sessionId) {
    AudioWebSocketMessage msg = new AudioWebSocketMessage();
    msg.type = MessageType.INTERRUPT.getValue();
    msg.sessionId = sessionId;
    return msg;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getAudioData() {
    return audioData;
  }

  public void setAudioData(String audioData) {
    this.audioData = audioData;
  }

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }

  public String getVoiceId() {
    return voiceId;
  }

  public void setVoiceId(String voiceId) {
    this.voiceId = voiceId;
  }

  public String getTone() {
    return tone;
  }

  public void setTone(String tone) {
    this.tone = tone;
  }

  public Integer getSampleRate() {
    return sampleRate;
  }

  public void setSampleRate(Integer sampleRate) {
    this.sampleRate = sampleRate;
  }

  public String getCodec() {
    return codec;
  }

  public void setCodec(String codec) {
    this.codec = codec;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Boolean getIsFinal() {
    return isFinal;
  }

  public void setIsFinal(Boolean isFinal) {
    this.isFinal = isFinal;
  }

  public enum MessageType {
    AUDIO_INPUT("audio_input"),
    AUDIO_OUTPUT("audio_output"),
    TRANSCRIPTION("transcription"),
    CHAT_RESPONSE("chat_response"),
    CONFIG("config"),
    ERROR("error"),
    SESSION_START("session_start"),
    SESSION_END("session_end"),
    INTERRUPT("interrupt");

    private final String value;

    MessageType(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }
}
