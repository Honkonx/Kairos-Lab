# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# Servidor X11 embebido (Xlorie/termux-x11, módulo :x11-server). Se mantiene el paquete
# com.termux.x11 completo: las clases se invocan vía manifiesto (MainActivity), desde
# X11Service (CmdEntryPoint), y sus métodos nativos/JNI no deben ser renombrados ni
# podados (ver docs/x11/X11_EMBEBIDO.md). Con -dontobfuscate arriba el costo es
# mínimo (sin ofuscación, solo evita que R8 elimine clases no alcanzadas estáticamente).
-keep class com.termux.x11.** { *; }
