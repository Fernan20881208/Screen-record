# Zaid Screen Recorder

**Version:** 1.0.0  
**Package:** `com.zaid.screenrecorder`

Zaid Screen Recorder is a root-first Android screen-recording project aimed at gaming workloads. It deliberately avoids using MediaProjection as the primary video path. The first backend executes the platform `/system/bin/screenrecord` through an audited root command layer and probes the device before exposing high-refresh options.

## Design goals

1. Game stability
2. Few lost frames
3. Low CPU overhead
4. Hardware encoding
5. A/V synchronization
6. Video quality
7. Low RAM usage
8. File size
9. UI effects

The app never claims 120 FPS merely because 120 was requested. Display modes, hardware MediaCodec capabilities and the active capture backend are intersected. The required fallback order is `120 -> 90 -> 60 -> 30`. Final FPS is measured from encoded MP4 timestamps.

## Current v1.0.0 foundation

- Root detection for Magisk, KernelSU, APatch and generic `su`.
- `screenrecord --help` inspection; unsupported flags are not injected.
- 1280×720 capture architecture with AVC by default and HEVC only when the CLI and hardware both expose it.
- Hardware encoder capability probing using `MediaCodecList` / `VideoCapabilities`.
- Physical/logical display, density, rotation, refresh and supported-mode detection.
- Modular `VideoCaptureBackend` with `SystemScreenRecordBackend` and a safe `NativeRootBackend` scaffold.
- Modular audio architecture with a working microphone AAC backend plus root/AudioFlinger/vendor detection points.
- MP4 remux using `MediaExtractor` + `MediaMuxer` and monotonic start offsets.
- Foreground recording service, notification stop action and optional lightweight overlay scaffold.
- Final encoded-frame/FPS/bitrate analysis from MP4 timestamps. Dropped frames are only reported when the backend provides a trustworthy counter.
- Liquid Glass-inspired Compose UI that removes the expensive blur while recording.
- Diagnostic ZIP exporter with non-sensitive device/build, display, encoder, backend and last-recording information.
- Universal and ABI split APK configuration for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

## Important limitation: internal audio

Android does not provide a generic public root API that magically returns the system PCM mix. `AudioFlinger` diagnostics are not a PCM capture API, and Android's official playback-capture path normally requires MediaProjection. Therefore v1.0.0 does **not** fake internal-audio support. It probes safe root/vendor routes and records video without internal audio when no audited ROM-specific backend is available. Microphone AAC capture is implemented. See `docs/AUDIO.md`.

## Requirements

- Android 8.0+ (`minSdk 26`).
- Root (`su`) granted to the app.
- `/system/bin/screenrecord` for the first video backend.
- A hardware AVC encoder for the selected size/rate.
- Android 13+ notification permission should be granted for the best foreground-service UX.

## Build

The project is pinned to stable tooling current for August 2026: Android Gradle Plugin 9.3.0, Gradle 9.5.0, JDK 17, Kotlin/Compose compiler 2.4.10, Compose BOM 2026.08.00 and `compileSdk 37`.

Local build with Gradle 9.5 installed:

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

GitHub Actions installs the required SDK and Gradle version, so no private local toolchain is required for CI.

## GitHub Actions

- `.github/workflows/build.yml`: tests, lint and debug APK artifacts on pushes/PRs.
- `.github/workflows/release.yml`: tag `v*` builds release APKs when signing secrets exist; otherwise produces clearly named debug fallback APKs.

Signing secrets:

- `ANDROID_KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

No key or password belongs in the repository.

## APK names

Signed tag builds rename the universal artifact to:

`Zaid-Screen-Recorder-vX.X.X-universal.apk`

ABI-specific APKs are also retained when Gradle emits them.

## Repository layout

```text
app/                    Android app and package-layer architecture
  src/main/java/com/zaid/screenrecorder/
    core/               configs, profiles, state, FPS fallback
    root/               audited root commands and CLI probing
    video/              display/encoder detectors and video backends
    audio/              audio backend abstraction and AAC microphone capture
    muxer/              native Android MP4 remux
    recorder/           session and performance analysis
    diagnostics/        privacy-conscious diagnostic ZIP
    ui/                 lightweight recording overlay
native/                 future native root backend design area
docs/                   architecture and compatibility documentation
.github/workflows/      CI and tagged releases
```

## Security rules

The project does not permanently modify `/system`, disable thermal protections, overclock hardware, permanently alter SELinux, or ship unknown native binaries. Root commands are centralized in `RootManager` / `RootCommand`.

## Troubleshooting

Run **Exportar diagnóstico** in the app and inspect `zaid-screen-recorder-diagnostics.zip`. See `docs/TROUBLESHOOTING.md` for root denial, missing FPS options, half-screen/rotation bugs, encoder failures and audio limitations.
