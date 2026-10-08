package app.nova.chat

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPerm: PermissionRequest? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback ?: return@registerForActivityResult
            fileCallback = null
            cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val req = pendingPerm; pendingPerm = null
            if (req != null) {
                val granted = req.resources.filter { r ->
                    when (r) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> grants[android.Manifest.permission.RECORD_AUDIO] == true
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> grants[android.Manifest.permission.CAMERA] == true
                        else -> false
                    }
                }.toTypedArray()
                if (granted.isNotEmpty()) req.grant(granted) else req.deny()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        try { window.statusBarColor = Color.parseColor("#0b1020") } catch (_: Exception) {}

        web = findViewById(R.id.webview)
        val splash = findViewById<View>(R.id.splash)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            textZoom = 100
        }
        web.addJavascriptInterface(DownloadBridge(), "AndroidNova")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                return if (url.scheme == "http" || url.scheme == "https") { openExternal(url); true }
                else { try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}; true }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(DOWNLOAD_JS, null)
                if (splash.visibility == View.VISIBLE) {
                    splash.animate().alpha(0f).setDuration(350).withEndAction { splash.visibility = View.GONE }.start()
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try { filePicker.launch(params.createIntent()); true } catch (e: Exception) { fileCallback = null; false }
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                val need = mutableListOf<String>()
                request.resources.forEach { r ->
                    if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE) need.add(android.Manifest.permission.RECORD_AUDIO)
                    if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE) need.add(android.Manifest.permission.CAMERA)
                }
                val missing = need.filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) request.grant(request.resources)
                else { pendingPerm = request; permLauncher.launch(missing.toTypedArray()) }
            }
        }

        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("http")) {
                try {
                    val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val req = DownloadManager.Request(Uri.parse(url)).setMimeType(mimeType)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    toast("Скачивание: " + name)
                } catch (_: Exception) {}
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })

        if (savedInstanceState == null)
            web.loadUrl("https://appassets.androidplatform.net/assets/" + getString(R.string.start_path))
        else { web.restoreState(savedInstanceState); splash.visibility = View.GONE }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    private fun openExternal(uri: Uri) { try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) {} }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    inner class DownloadBridge {
        @JavascriptInterface
        fun save(filename: String, base64: String, mime: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val name = if (filename.isBlank()) "file_" + System.currentTimeMillis() else filename
                val type = if (mime.isBlank()) "application/octet-stream" else mime
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, type)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) } }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    java.io.File(dir, name).outputStream().use { it.write(bytes) }
                }
                runOnUiThread { toast("Сохранено в «Загрузки»: " + name) }
            } catch (e: Exception) { runOnUiThread { toast("Не удалось сохранить файл") } }
        }
    }

    companion object {
        private val DOWNLOAD_JS = """
(function(){
  if(window.__novaDL)return; window.__novaDL=1;
  function b64(blob,cb){var r=new FileReader();r.onload=function(){var s=String(r.result||"");var i=s.indexOf(",");cb(i>=0?s.slice(i+1):"");};r.readAsDataURL(blob);}
  function handle(url,name){
    if(!url)return false;
    if(url.indexOf("blob:")===0||url.indexOf("data:")===0){
      try{fetch(url).then(function(r){return r.blob();}).then(function(b){b64(b,function(d){try{AndroidNova.save(name||"file",d,b.type||"");}catch(e){}});});}catch(e){}
      return true;
    }
    return false;
  }
  document.addEventListener("click",function(e){
    var t=e.target; var a=(t&&t.closest)?t.closest("a[download],a[href^=blob:],a[href^=data:]"):null;
    if(a&&a.href){ if(handle(a.href,a.getAttribute("download")||"")){ e.preventDefault(); e.stopPropagation(); } }
  },true);
  try{
    var _c=HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click=function(){
      try{ var h=this.href||""; if((this.hasAttribute("download")||h.indexOf("blob:")===0||h.indexOf("data:")===0)&&handle(h,this.getAttribute("download")||"")){ return; } }catch(e){}
      return _c.apply(this,arguments);
    };
  }catch(e){}
})();
""".trimIndent()
    }
}
