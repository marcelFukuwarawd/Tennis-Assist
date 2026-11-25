# MediaPipe uses reflection / JNI
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.courtvision.ai.** { *** Companion; }
-keepclasseswithmembers class com.courtvision.ai.** { kotlinx.serialization.KSerializer serializer(...); }
