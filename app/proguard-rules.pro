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
#-renamesourcefileattribute SourceFile

# LiteRT-LM
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers @androidx.room.Entity class * {
    *;
}
-keep class com.pennywiseai.tracker.data.database.entity.** { *; }
-keep class com.pennywiseai.tracker.data.database.dao.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.android.EntryPoint class * { *; }

# Jetpack Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pennywiseai.tracker.**$$serializer { *; }
-keepclassmembers class com.pennywiseai.tracker.** {
    *** Companion;
}
-keepclasseswithmembers class com.pennywiseai.tracker.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JetBrains Markdown
-keep class org.intellij.markdown.** { *; }
-dontwarn org.intellij.markdown.**

# WorkManager
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# Keep data classes
-keep class com.pennywiseai.tracker.data.model.** { *; }
-keep class com.pennywiseai.tracker.domain.model.** { *; }

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# General Android
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Remove logging in release (inherited from upstream; spec 04 §4.6, AC-11-04).
#
# This is what keeps the inherited android.util.Log call sites out of the release build
# the owner installs — SmsTransactionProcessor alone logs the merchant and category, the
# transaction hash, the inserted row id and, via processBalanceUpdate, the account last-4.
# R8 honours -assumenosideeffects only with optimisation on, which the release build type
# gets from proguard-android-optimize.txt (the plain proguard-android.txt sets
# -dontoptimize and would silently make this a no-op).
#
# `e` and `w` are stripped along with the rest, deliberately: several of those call sites
# pass an exception whose message quotes an amount or an account, and AC-11-04 admits no
# "except for errors" clause. Diagnostics in release go through Timber instead, below.
#
# Do NOT add `println` to this list. RedactingReleaseTree emits through
# android.util.Log.println precisely because it is not here; listing it would delete the
# only logging the release build has left.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Timber (spec 04 §4.6). Belt and braces for the two levels the release tree already drops
# at runtime: RedactingReleaseTree.isLoggable refuses VERBOSE/DEBUG, and these rules let R8
# delete the call and its arguments outright, so a `Timber.d("saved %s", merchant)` does not
# even build its string in a release build. INFO and above are kept and redacted.
#
# Both classes are listed because Timber's static API is a companion named `Forest`:
# depending on the call site, Kotlin emits either the @JvmStatic bridge on `Timber` or a
# virtual call on `Timber$Forest`. No `static` modifier is given, so both forms match.
-assumenosideeffects class timber.log.Timber {
    public *** v(...);
    public *** d(...);
}
-assumenosideeffects class timber.log.Timber$Forest {
    public *** v(...);
    public *** d(...);
}

# Protobuf
-dontwarn com.google.protobuf.Internal$ProtoMethodMayReturnNull
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField

# SLF4J (used by OpenCSV)
-dontwarn org.slf4j.impl.StaticLoggerBinder

# JavaMail / android-mail (Fin email channel, spec 11).
# JavaMail resolves its IMAP provider reflectively, by reading META-INF/javamail.*, so R8
# sees no reference to it and strips it. The failure only shows at runtime, on the first
# sync, as "no provider for imaps" — keep the provider classes.
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
# android-activation ships the DataHandler support under this package name.
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn myjava.awt.datatransfer.**

# jsoup (email body extraction) — parses HTML reflectively in places.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Jetpack Security Crypto / Tink (the Gmail app password, spec 04 §4.1).
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# PDFBox Android (used for PDF statement parsing)
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter
-dontwarn com.tom_roush.pdfbox.pdmodel.graphics.image.SampledImageReader
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn org.bouncycastle.**
-dontwarn org.apache.harmony.**
-dontwarn javax.xml.stream.**