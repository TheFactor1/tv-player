# Media3 ships its own consumer rules. Keep this file for app-specific rules.

# The FFmpeg decoder's native code looks up Java classes, fields and methods by name
# (e.g. FfmpegAudioDecoder.growOutputBuffer). R8 can't see those calls, so keep them intact;
# otherwise the release build plays silently while the debug build has sound.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.decoder.SimpleDecoderOutputBuffer { *; }
-keep class androidx.media3.decoder.DecoderInputBuffer { *; }
-keepclasseswithmembernames class * { native <methods>; }
