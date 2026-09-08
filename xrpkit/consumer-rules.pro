# Consumer R8/ProGuard rules for xrp-android.
# Network responses are parsed from Gson JsonObject trees (no reflection on DTOs),
# but keep generic signatures and Room converters safe in minified consumers.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers class io.horizontalsystems.xrpkit.models.** {
    <fields>;
    <init>(...);
}
