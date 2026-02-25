package example.websocket.audio.service;

import java.util.ArrayList;
import java.util.List;

public class EchoCanceller {
  private static final int ECHO_BUFFER_SIZE = 20;
  private static final double ECHO_SIMILARITY_THRESHOLD = 0.75;
  private static final double MIN_ENERGY_THRESHOLD = 100;

  private final List<byte[]> recentPlaybackAudio = new ArrayList<>();
  private final Object echoLock = new Object();

  public void addPlaybackAudio(byte[] audioData) {
    synchronized (echoLock) {
      recentPlaybackAudio.add(audioData.clone());
      while (recentPlaybackAudio.size() > ECHO_BUFFER_SIZE) {
        recentPlaybackAudio.remove(0);
      }
    }
  }

  public boolean isEcho(byte[] recordedAudio) {
    synchronized (echoLock) {
      if (recentPlaybackAudio.isEmpty()) {
        return false;
      }

      double recordedEnergy = calculateEnergy(recordedAudio);
      if (recordedEnergy < MIN_ENERGY_THRESHOLD) {
        return false;
      }

      for (byte[] playbackAudio : recentPlaybackAudio) {
        double similarity = calculateAudioSimilarity(recordedAudio, playbackAudio);
        if (similarity > ECHO_SIMILARITY_THRESHOLD) {
          return true;
        }
      }
      return false;
    }
  }

  public void clear() {
    synchronized (echoLock) {
      recentPlaybackAudio.clear();
    }
  }

  private double calculateEnergy(byte[] audioData) {
    if (audioData == null || audioData.length < 2) {
      return 0;
    }
    double sum = 0;
    int samples = audioData.length / 2;
    for (int i = 0; i < samples; i++) {
      short sample = (short) ((audioData[i * 2] & 0xFF) | (audioData[i * 2 + 1] << 8));
      sum += Math.abs(sample);
    }
    return samples > 0 ? sum / samples : 0;
  }

  private double calculateAudioSimilarity(byte[] recorded, byte[] playback) {
    if (recorded == null || playback == null || recorded.length < 4 || playback.length < 4) {
      return 0.0;
    }

    short[] recordedSamples = bytesToSamples(recorded);
    short[] playbackSamples = bytesToSamples(playback);

    if (recordedSamples.length == 0 || playbackSamples.length == 0) {
      return 0.0;
    }

    int length = Math.min(recordedSamples.length, playbackSamples.length);

    double correlation = 0;
    double recordedNorm = 0;
    double playbackNorm = 0;

    for (int i = 0; i < length; i++) {
      correlation += recordedSamples[i] * playbackSamples[i];
      recordedNorm += recordedSamples[i] * recordedSamples[i];
      playbackNorm += playbackSamples[i] * playbackSamples[i];
    }

    if (recordedNorm == 0 || playbackNorm == 0) {
      return 0.0;
    }

    return correlation / (Math.sqrt(recordedNorm) * Math.sqrt(playbackNorm));
  }

  private short[] bytesToSamples(byte[] audioData) {
    int samples = audioData.length / 2;
    short[] result = new short[samples];
    for (int i = 0; i < samples; i++) {
      result[i] = (short) ((audioData[i * 2] & 0xFF) | (audioData[i * 2 + 1] << 8));
    }
    return result;
  }
}
