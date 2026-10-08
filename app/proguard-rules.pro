-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class app.nova.chat.MainActivity$DownloadBridge { *; }
-dontwarn android.webkit.**
