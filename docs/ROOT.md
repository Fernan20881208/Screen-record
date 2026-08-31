# Root model

All privileged shell execution is centralized in `RootManager` and represented by `RootCommand` values. Shell arguments passed to `screenrecord` are quoted instead of concatenated as raw user text.

Detected implementations include Magisk, KernelSU, APatch and generic `su`. Detection first verifies `uid=0`; version strings are secondary identification only.

The project must never permanently modify `/system`, thermal services, SELinux state or CPU/GPU limits. Temporary ROM-specific audio routing introduced later must have an explicit rollback path in the same backend.
