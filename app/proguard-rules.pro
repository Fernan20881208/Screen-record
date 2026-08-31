# Zaid Screen Recorder: keep Android entry points and model enum names used in diagnostics.
-keep class com.zaid.screenrecorder.RecordingService { *; }
-keepclassmembers enum com.zaid.screenrecorder.core.** { *; }
-dontwarn java.lang.invoke.**
