-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers,allowobfuscation class com.hippo.ehviewer.coil.AnimatedWebPDrawable {
    java.nio.ByteBuffer source;
}

# Ktor logger
-dontwarn org.slf4j.impl.StaticLoggerBinder

# https://issuetracker.google.com/222232895
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.Sidecar*

# Google ML Kit Text Recognition
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
    *;
}
-keep class com.google.firebase.components.** { *; }
-dontwarn com.google.firebase.components.**

-keepattributes LineNumberTable

-allowaccessmodification
-repackageclasses
