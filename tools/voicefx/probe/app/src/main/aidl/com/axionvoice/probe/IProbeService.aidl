package com.axionvoice.probe;

// Implemented by ProbeService, which Shizuku runs as the ADB shell user (uid 2000).
interface IProbeService {
    void destroy() = 16777114;        // Shizuku's reserved destroy transaction
    void exit() = 1;
    String hello() = 2;
    // 0 = VOICE_COMMUNICATION (call uplink path), 1 = MIC, 2 = VOICE_CALL, 3 = UNPROCESSED
    void setMonitorSource(int code) = 3;
    void playTone(int durationMs) = 4;
    void recordCall(int seconds, String fileName) = 5;
    String drainLog() = 6;
    void setMicMuteViaAudioManager(boolean mute) = 7;
}
