# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\tools\adt-bundle-windows-x86_64-20131030\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# We use Reflection on the builder to avoid creating too many objects
 -keep class net.objecthunter.exp4j.ExpressionBuilder**
 -keepclassmembers class net.objecthunter.exp4j.ExpressionBuilder** {
    *;
 }
# Option screens
 -keep class net.kdt.pojavlaunch.prefs.screens** {*;}

# --- mcskill gRPC / protobuf-javalite ---
# protobuf-javalite's MessageSchema reads message fields reflectively, so R8 must not
# rename/remove them on the generated message classes.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
# gRPC discovers its transport and name resolver via ServiceLoader; the implementations
# have no compile-time references and would otherwise be stripped.
-keep class io.grpc.okhttp.** { *; }
-keep class * implements io.grpc.NameResolverProvider { *; }
-keep class * implements io.grpc.ManagedChannelProvider { *; }
# These libraries reference optional/absent classes (Guava annotations, Conscrypt, ...)
-dontwarn io.grpc.**
-dontwarn com.google.protobuf.**
-dontwarn org.conscrypt.**


