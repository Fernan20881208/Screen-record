# Compatibility

| Platform | Video root backend | 720p60 | 720p90/120 | Internal audio | Notes |
|---|---|---:|---:|---:|---|
| AOSP-like Android | Expected when `/system/bin/screenrecord` exists | Capability-detected | Only if backend exposes verified rate control | Device/ROM backend required | Hardware codec still checked |
| HyperOS / MIUI | Expected, ROM changes possible | Capability-detected | Vendor CLI/native backend may be needed | Vendor profile required | Export diagnostics before adding routes |
| Custom ROM | Probe at runtime | Capability-detected | Probe at runtime | Probe at runtime | No hard-coded promise |
| No root | Advanced engine unavailable | No | No | No | App shows a clear root requirement instead of crashing |

Compatibility is deliberately runtime-derived. A 120 Hz panel does not imply a 120 FPS recording encoder or capture backend.
