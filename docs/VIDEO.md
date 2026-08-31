# Video

The primary v1.0.0 backend is `/system/bin/screenrecord` executed through root.

Before capture the app reads `screenrecord --help`. Flags such as `--fps`, `--frame-rate`, `--codec`, `--bit-rate` and `--size` are only used when actually present in that help output.

High-refresh eligibility requires the intersection of display modes, hardware encoder 720p rate support, and backend rate control. If stock `screenrecord` exposes no explicit rate control, the system backend withholds 90/120 FPS rather than pretending they can be forced. A future `NativeRootBackend` can remove that limitation.

Final FPS is calculated from encoded video sample timestamps with `MediaExtractor`. The app does not duplicate frames to meet a requested number.
