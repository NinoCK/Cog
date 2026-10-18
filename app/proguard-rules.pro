# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The wake word's speech library (Vosk) reaches its native code through JNA, which binds by
# reflection: keep both, and the interface this app maps onto libvosk.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-keep interface com.bestassistant.wake.WakeModel$VoskWords { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
