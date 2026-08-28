# R8 rules.
#
# Everything here is a thing R8 cannot see being used. That is the whole
# category: a shrinker removes what nothing references, and a JNI entry point,
# a reflective serializer lookup and a class loaded by name are all referenced
# from somewhere R8 does not read. The failure mode is the one this project has
# met three times in other forms — green build, green lint, green tests, broken
# on the artifact nobody ran.

# ── JNI ─────────────────────────────────────────────────────────────────────
# The native library resolves these by name and signature at call time. R8 sees
# no Kotlin caller for the C side's callbacks and no C caller for anything, so
# without this it renames both halves of the bridge and `nativeInit` fails to
# resolve its callbacks — which it reports by logging and returning, so the
# tunnel comes up and answers nothing.
# Duplicated from `proguard-android-optimize.txt` on purpose. Removing it
# changes nothing today — measured — because the platform's default file
# carries the same rule, and that is precisely why it is written out here: a
# rule this project depends on should be visible in this project, not inherited
# from a file that could change under it.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# **This one is load-bearing, and measured to be.** The callbacks C calls into
# are ordinary Kotlin methods, not native ones, so the rule above does not
# reach them: without this, `nativeTcpWrite`, `onTcpAccept`, `onTcpRecv` and
# `onUdpRecv` all leave the APK.
-keep class eu.nodepass.somewhere.vpn.NativeBridge { *; }
-keep class eu.nodepass.somewhere.quic.QuicConnection { *; }
-keep class eu.nodepass.somewhere.quic.QuicStack { *; }

# ── kotlinx.serialization ───────────────────────────────────────────────────
# Serializers are found by a generated companion R8 cannot trace to a caller.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Compile-only annotations ────────────────────────────────────────────────
# Test dependencies bring annotations whose own dependencies exist only at
# compile time. Nothing loads them at runtime, and R8 refuses to shrink an APK
# with references it cannot resolve unless told the absence is expected.
-dontwarn javax.lang.model.element.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
