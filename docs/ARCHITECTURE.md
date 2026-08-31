# Architecture

## Layers

`MainActivity` is intentionally disposable while a recording runs. `RecordingService` owns the active session. UI effects are reduced and the task is moved to background when recording starts.

Core path:

`Capability detection -> ScreenRecordEngine -> VideoCaptureBackend -> AudioCaptureEngine -> RecordingSession -> MuxerEngine -> PerformanceMonitor`

### Video

`ScreenRecordEngine` intersects three independent capability sets:

1. Display refresh modes from `DisplayManager`.
2. Hardware encoder size/rate support from `MediaCodecList`.
3. Active backend guarantees from `VideoCaptureBackend.probe()`.

This prevents a 120 FPS UI option from appearing only because a display advertises 120 Hz.

### Audio

Audio is independent from video. Backends expose whether they can capture internal audio or microphone audio. The microphone backend encodes AAC LC to M4A. ROM-specific internal capture can be added without changing the video engine.

### Synchronization

Each capture handle stores a monotonic `elapsedRealtimeNanos()` start point. When video and audio are remuxed, each track is normalized to its first encoded PTS and shifted by its monotonic start offset. This avoids arbitrary zeroing that would move one stream ahead of the other.

### Native backend

`NativeRootBackend` is intentionally unavailable in v1.0.0. No placeholder binary is bundled. Future code in `native/` should only be enabled after the capture path is audited and tested across Android/ROM versions.
