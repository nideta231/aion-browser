-keep class org.libtorrent4j.** { *; }
-keep class com.nath.aion.downloads.** { *; }
-dontwarn **

# NewPipeExtractor uses reflection and runs YouTube's player JavaScript through Rhino.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.google.protobuf.** { *; }
