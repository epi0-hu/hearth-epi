# Hearth uses no reflection-based serialization, so the default optimized rules are enough.
-dontwarn org.jetbrains.annotations.**
# Bridge used by HTML message rendering
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
