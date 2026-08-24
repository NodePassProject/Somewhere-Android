# Keep kotlinx.serialization metadata for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
