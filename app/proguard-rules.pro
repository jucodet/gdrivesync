# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Google Drive API
-keep class com.google.api.** { *; }
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**
-dontwarn com.google.api.**


