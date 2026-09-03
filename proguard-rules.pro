# org.json classes ship inside the Android platform (android.jar), not as a shaded library in
# this APK — R8 must not warn/strip on references to a class it can't see packaged here, since
# the platform copy is resolved at install time.
-dontwarn org.json.**

# Coroutines internals occasionally get flagged by R8's usage analysis without these; keep the
# volatile state fields coroutines' state-machine dispatch relies on.
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Compose compiler-generated classes carry synthetic $stable / $changed metadata that must
# survive shrinking, or recomposition correctness silently breaks at runtime with no build error.
-keep class androidx.compose.runtime.** { *; }

# AndroidX Security (EncryptedSharedPreferences) uses Tink under the hood, which does its own
# reflective provider lookups.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
