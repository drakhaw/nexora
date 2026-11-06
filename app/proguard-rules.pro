# libadb-android loads Conscrypt reflectively (SslUtils, PairingConnectionCtx).
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class io.github.muntashirakon.** { *; }
-dontwarn org.bouncycastle.**
