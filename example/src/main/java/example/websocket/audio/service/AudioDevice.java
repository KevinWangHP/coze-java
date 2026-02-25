package example.websocket.audio.service;

import javax.sound.sampled.Mixer;

public class AudioDevice {
  private final Mixer.Info mixerInfo;

  public AudioDevice(Mixer.Info mixerInfo) {
    this.mixerInfo = mixerInfo;
  }

  public Mixer.Info getMixerInfo() {
    return mixerInfo;
  }

  public String getDeviceName() {
    return mixerInfo.getName();
  }
}
