# Minification is off (see build.gradle.kts). If you turn it on, keep the
# classes Python and the native speech library call by reflection / JNI:
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.verba.app.data.ProgressSink { *; }
-keep class com.verba.app.service.InstallSink { *; }
