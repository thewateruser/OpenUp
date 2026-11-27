# The Shizuku user service runs in a separate process that Shizuku starts by class name.
-keep class org.openup.shell.ShellService { <init>(); *; }
-keep class org.openup.shell.IShellService { *; }
-keep class org.openup.shell.IShellService$* { *; }

# Hidden Wi-Fi classes are accessed reflectively in the shell process.
-dontwarn android.net.wifi.IWifiManager**

# zstd-jni uses JNI callbacks into these classes.
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
