# Audio

Target output is AAC LC, 48 kHz, up to stereo, approximately 256 kbps for high-quality profiles.

## Backends

- `MicrophoneBackend`: implemented with `AudioRecord` + hardware/software AAC `MediaCodec` and an M4A `MediaMuxer`.
- `RootAudioBackend`: probes common PCM utilities but is disabled until a mixer/loopback route is verified for the exact device.
- `AudioFlingerBackend`: diagnostics only. `dumpsys media.audio_flinger` is not treated as a PCM capture source.
- `VendorAudioBackend`: extension point for HyperOS/MIUI/AOSP custom-ROM profiles with reversible routing.

## Why internal audio is not pretended

The official playback-capture API normally uses a MediaProjection token. Root alone does not create a stable cross-ROM public PCM API. Enabling an arbitrary `tinymix` path can mute speakers, capture the wrong stream, or leave mixer state changed. The project therefore requires an audited backend profile before declaring internal audio available.

A future vendor profile must: probe exact controls, snapshot their original values, apply only temporary changes, restore them on stop/error, and keep timestamps on the same monotonic timeline as video.
