# Troubleshooting

## Root denied

Grant root to `com.zaid.screenrecorder` in Magisk/KernelSU/APatch, reopen the app and export diagnostics. The `id` check must return `uid=0`.

## 120 FPS is missing

This is intentional when any required layer is absent: 120 Hz display mode, hardware AVC/HEVC 1280×720@120 support, or a capture backend capable of controlling/verifying that rate. Stock `screenrecord` often lacks an FPS flag.

## Recording is half-screen or rotated

Export diagnostics before changing anything. Compare physical mode, logical size, `wm size`, density and rotation. The project records all of these so a ROM-specific transform can be fixed in the backend rather than adding a global rotation hack.

## Encoder fails

Try AVC and a lower FPS. The engine only selects hardware-reported size/rate pairs, but vendor codecs can still reject configurations at runtime because of concurrent gaming load or ROM bugs.

## No internal audio

This is currently expected unless a verified root/vendor audio backend exists for the ROM. Microphone capture is the implemented generic audio path. Do not enable random mixer controls permanently.

## Diagnostic archive

`zaid-screen-recorder-diagnostics.zip` contains Android/build identifiers, root implementation, display modes, MediaCodec encoder data, `screenrecord --help`, detected audio backend information and last recording logs/stats. It intentionally excludes account data, messages, photos, serial numbers and unrelated files.
