# Keep kotlinx.serialization generated serializers reachable when minifying.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# kotlinx.serialization keeps this via the plugin; the rest is belt and braces.
-keepclassmembers class com.pixelstandby.** {
    *** Companion;
}
-keepclasseswithmembers class com.pixelstandby.** {
    kotlinx.serialization.KSerializer serializer(...);
}