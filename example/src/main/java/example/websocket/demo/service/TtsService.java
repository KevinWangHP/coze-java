package example.websocket.demo.service;

import java.util.function.Consumer;

public interface TtsService {
  void initialize();

  void synthesize(String text, String voiceId, String tone);

  void stop();

  void close();

  boolean isReady();

  boolean isPlaying();

  void setAudioCallback(Consumer<byte[]> callback);

  void setErrorCallback(Consumer<Exception> callback);
}
