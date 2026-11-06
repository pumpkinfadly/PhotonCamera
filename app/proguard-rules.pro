# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

-repackageclasses

# JNI-referenced classes (DngRawData, RawCameraCalibration, fotufilm results)
# are annotated with androidx @Keep, which covers name and member keeping;
# native <methods> keeping ships in proguard-android-optimize.txt defaults.

# Gson reflective parsing of assets/films.json (FilmData uses
# TypeToken<List<Film>>); without these, R8 strips generic signatures and
# renames model fields, silently yielding an empty film list in release.
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.hinnka.mycamera.model.Film { *; }

