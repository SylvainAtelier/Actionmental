-keep class com.actionmental.platform.shizuku.** { *; }
-keep interface com.actionmental.platform.shizuku.** { *; }
-keepclassmembers class ** { @kotlinx.serialization.Serializable *; }

# --- 文件夹同步（smbj）---
# smbj 用 mbassador 事件总线（注解 + 反射分发）和 asn-one 解码，收缩会破坏它们；
# 体积代价约一兆，换来的是不会在 release 里才发现「NAS 连不上」。
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class com.hierynomus.asn1.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# 只用 NTLM：Kerberos / SPNEGO 依赖的 org.ietf.jgss 在 Android 上不存在，永远不会被加载
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn org.slf4j.**
-dontwarn javax.el.**
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**

# --- ADB 网络调试（libadb-android + spake2）---
# spake2 走 JNI，原生侧按名字找 Java 方法；libadb 反射调用 Conscrypt 的 exportKeyingMaterial
-keep class io.github.muntashirakon.** { *; }
# 自带的 Conscrypt：JNI 原生库按名字回调 Java 类，libadb 又按名字反射 OpenSSLProvider / Conscrypt
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
