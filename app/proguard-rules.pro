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
