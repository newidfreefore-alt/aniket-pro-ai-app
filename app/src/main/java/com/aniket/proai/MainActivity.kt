package com.aniket.proai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    private val SITE = "https://dazzling-snickerdoodle-137764.netlify.app"

    private val DELETE_GALLERY = true

    private lateinit var web: WebView
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: Button? = null
    private var capturing = false
    private var shotUris = mutableListOf<Uri>()
    private var fileCb: ValueCallback<Array<Uri>>? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                wv: WebView?, cb: ValueCallback<Array<Uri>>?, fp: FileChooserParams?
            ): Boolean {
                fileCb = cb
                val i = fp?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT)
                startActivityForResult(i, 101)
                return true
            }
        }
        web.webViewClient = WebViewClient()
        setContentView(web)
        web.loadUrl(SITE)
        askPerms()
        handler.postDelayed({ showBubble() }, 2000)
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    private fun askPerms() {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.READ_MEDIA_IMAGES)
        else l.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (l.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
            requestPermissions(l.toTypedArray(), 1)
        if (!Settings.canDrawOverlays(this))
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")), 2)
    }

    private val observer = object : android.database.ContentObserver(handler) {
        override fun onChange(self: Boolean) {
            if (!capturing) return
            handler.postDelayed({ scanShots() }, 900)
        }
    }

    private fun scanShots() {
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED)
        val cur = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
            MediaStore.Images.Media.DATE_ADDED + " DESC")
        cur?.use {
            while (it.moveToNext()) {
                val name = it.getString(1)?.lowercase() ?: continue
                if (!name.contains("screenshot")) continue
                val added = it.getLong(2)
                if (System.currentTimeMillis() / 1000 - added > 180) continue
                val u = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    it.getLong(0).toString())
                if (!shotUris.contains(u)) shotUris.add(u)
            }
        }
        bubble?.text = if (capturing) "🔴 " + shotUris.size else "📸 " + shotUris.size
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this) || bubble != null) return
        val v = Button(this)
        v.text = "📸 0"
        v.setOnClickListener {
            capturing = !capturing
            if (capturing) shotUris.clear() else importAndPush()
            v.text = if (capturing) "🔴 0" else "📸 0"
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.END
        p.x = 8; p.y = 260
        wm.addView(v, p)
        bubble = v
    }

    private fun importAndPush() {
        val urls = mutableListOf<String>()
        val imported = mutableListOf<Uri>()
        for (u in shotUris) {
            try {
                val ins = contentResolver.openInputStream(u) ?: continue
                var bmp = BitmapFactory.decodeStream(ins)
                ins.close()
                val sc = Math.min(1.0, 1568.0 / Math.max(bmp.width, bmp.height).toDouble())
                if (sc < 1.0) bmp = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, baos)
                urls.add("data:image/jpeg;base64," +
                    Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                imported.add(u)
            } catch (e: Exception) { }
        }
        shotUris.clear()
        if (urls.isEmpty()) return
        val json = urls.joinToString(",", "[", "]") { "\"$it\"" }
        web.evaluateJavascript("if(window.__ssImport)window.__ssImport($json)", null)
        if (DELETE_GALLERY && Build.VERSION.SDK_INT >= 30 && imported.isNotEmpty()) {
            try {
                val pend = MediaStore.createDeleteRequest(this, imported)
                startIntentSenderForResult(pend.intentSender, 102, null, 0, 0, 0)
            } catch (e: Exception) { }
        }
    }

    override fun onActivityResult(rc: Int, rc2: Int, d: Intent?) {
        if (rc == 101) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(rc2, d))
            fileCb = null
        }
        super.onActivityResult(rc, rc2, d)
    }

    override fun onDestroy() {
        try { contentResolver.unregisterContentObserver(observer) } catch (e: Exception) { }
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        super.onDestroy()
    }
}
