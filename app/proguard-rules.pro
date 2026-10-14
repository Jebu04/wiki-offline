# libzim/libkiwix classes are looked up by name from JNI.
-keep class org.kiwix.libzim.** { *; }
-keep class org.kiwix.libkiwix.** { *; }

# LiteRT-LM calls back into its Kotlin classes from native code and serialises messages with Gson.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.gson.** { *; }
