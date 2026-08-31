# Native backend area

Reserved for a future audited `NativeRootBackend` that can capture compositor frames and feed Android hardware codecs with explicit frame-rate control when `/system/bin/screenrecord` is insufficient.

v1.0.0 intentionally ships no unknown native binary and therefore has no ABI-specific native dependency. Gradle still emits universal/ABI split APKs so packaging is ready for `arm64-v8a`, `armeabi-v7a` and `x86_64` when native code is introduced.
