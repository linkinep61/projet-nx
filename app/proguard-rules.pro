# ============================================================================
# Streamflix ProGuard / R8 rules
# ============================================================================

# ---- Keep source file / line info for crash reports ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Kotlin ----
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ---- Gson (used by Retrofit converter-gson) ----
# Keep any class that Gson may instantiate via reflection
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }
-keepattributes Signature
# Keep all model classes that Gson deserialises
-keep class com.streamflixreborn.streamflix.models.** { *; }
-keep class com.streamflixreborn.streamflix.utils.TMDb3$* { *; }
-keep class com.streamflixreborn.streamflix.utils.GitHub$* { *; }
-keep class com.streamflixreborn.streamflix.utils.SubDL$* { *; }
-keep class com.streamflixreborn.streamflix.utils.OpenSubtitles$* { *; }

# ---- kotlinx-serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}

# ---- Retrofit ----
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ---- Jsoup ----
-keep class org.jsoup.** { *; }

# ---- Room ----
-keep class androidx.room.** { *; }
-keep class com.streamflixreborn.streamflix.database.** { *; }
-keep class com.streamflixreborn.streamflix.download.DownloadEntity { *; }
-keep class com.streamflixreborn.streamflix.download.DownloadDao { *; }
-keep class com.streamflixreborn.streamflix.download.DownloadDao_Impl { *; }
-keep class com.streamflixreborn.streamflix.download.DownloadDatabase { *; }
-keep class com.streamflixreborn.streamflix.download.DownloadDatabase_Impl { *; }

# ---- Glide ----
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder { *** rewind(); }

# ---- ExoPlayer / Media3 ----
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---- Cronet ----
-keep class org.chromium.net.** { *; }
-dontwarn org.chromium.net.**

# ---- Conscrypt ----
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# ---- Mozilla Rhino (JavaScript engine) ----
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# ---- dnsjava ----
-keep class org.xbill.DNS.** { *; }
-dontwarn org.xbill.DNS.**

# ---- NanoHTTPD ----
-keep class fi.iki.elonen.** { *; }

# ---- ZXing ----
-keep class com.google.zxing.** { *; }

# ---- Java-WebSocket ----
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# ---- Navigation Safe Args ----
-keep class com.streamflixreborn.streamflix.fragments.**Directions { *; }
-keep class com.streamflixreborn.streamflix.fragments.**Args { *; }
-keep class com.streamflixreborn.streamflix.activities.**Directions { *; }

# ---- Providers & Extractors (may use class-name checks, reflection) ----
-keep class com.streamflixreborn.streamflix.providers.** { *; }
-keep class com.streamflixreborn.streamflix.extractors.** { *; }

# ---- Leanback ----
-keep class androidx.leanback.** { *; }
-dontwarn androidx.leanback.**

# ---- Keep ViewBinding generated classes ----
-keep class com.streamflixreborn.streamflix.databinding.** { *; }

# ---- Keep R class for resource references ----
-keep class com.streamflixreborn.streamflix.R$* { *; }

# ---- ViewPager2 internal field accessed via reflection ----
-keepclassmembers class androidx.viewpager2.widget.ViewPager2 {
    androidx.recyclerview.widget.RecyclerView mRecyclerView;
}

# ---- Suppress common harmless warnings ----
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn sun.misc.Unsafe
-dontwarn org.slf4j.impl.StaticLoggerBinder
