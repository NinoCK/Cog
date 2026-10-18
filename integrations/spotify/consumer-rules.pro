# Applied to the app that includes this integration, when it shrinks its code.

# Spotify App Remote: its protocol types are (de)serialised with Gson by reflection.
-keep class com.spotify.protocol.** { *; }
-keep class com.spotify.android.appremote.** { *; }
-keep class com.spotify.sdk.android.auth.** { *; }
-dontwarn com.fasterxml.jackson.**
# Annotations the SDK was compiled against but doesn't ship.
-dontwarn com.spotify.base.annotations.**
-dontwarn javax.annotation.**
