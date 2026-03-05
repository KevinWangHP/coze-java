package example.websocket.demo.service;

import java.util.function.Consumer;

public interface AsrService {
  void initialize();

  void sendAudio(byte[] audioData);

  void commit();

  void close();

  boolean isReady();

  void setTranscriptionCallback(Consumer<String> callback);

  void setFinalTranscriptionCallback(Consumer<String> callback);

  void setErrorCallback(Consumer<Exception> callback);
}
