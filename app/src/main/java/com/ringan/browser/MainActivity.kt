package com.ringan.browser

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream

/**
 * Browser ringan berbasis WebView sistem.
 * Hemat RAM: hanya SATU WebView hidup pada satu waktu (tab lain disimpan sebagai state).
 */
class MainActivity : Activity() {

    private class HomeTheme(val name: String, val bg: String, val fg: String, val tile: String)

    companion object {
        const val HOME_MARK = "ringan://home"
        const val MAX_TABS = 8

        private val THEMES = arrayOf(
            HomeTheme("Terang", "#ffffff", "#202124", "#f1f3f4"),
            HomeTheme("Gelap", "#121212", "#eeeeee", "#2a2a2a"),
            HomeTheme("Biru", "#0d47a1", "#ffffff", "#1565c0"),
            HomeTheme("Hijau", "#1b5e20", "#ffffff", "#2e7d32"),
            HomeTheme("Ungu", "#4a148c", "#ffffff", "#6a1b9a"),
            HomeTheme("Krem", "#fff8e1", "#3e2723", "#ffecb3")
        )
        val ENGINE_NAMES = arrayOf("DuckDuckGo Lite", "Google", "Bing", "Brave")
        val ENGINE_ACTIONS = arrayOf(
            "https://duckduckgo.com/lite/",
            "https://www.google.com/search",
            "https://www.bing.com/search",
            "https://search.brave.com/search"
        )

        // Video "layar penuh" untuk video apa pun di halaman (tanpa perlu tombol fullscreen situs)
        const val JS_ENTER = "(function(){if(window.__rvBox)return 'ok';" +
            "var vs=[].slice.call(document.querySelectorAll('video'));if(!vs.length)return 'none';" +
            "var v=null;for(var i=0;i<vs.length;i++){if(!vs[i].paused&&!vs[i].ended){v=vs[i];break;}}" +
            "if(!v){vs.sort(function(a,b){return b.clientWidth*b.clientHeight-a.clientWidth*a.clientHeight});v=vs[0];}" +
            "var ph=document.createComment('rv');v.parentNode.insertBefore(ph,v);" +
            "var box=document.createElement('div');" +
            "box.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;background:#000;z-index:2147483647;';" +
            "window.__rvBox=box;window.__rvPh=ph;window.__rvV=v;window.__rvStyle=v.getAttribute('style');window.__rvCtl=v.controls;" +
            "v.style.cssText='width:100%;height:100%;object-fit:contain;background:#000;position:static;max-width:none;max-height:none;';" +
            "v.controls=true;box.appendChild(v);document.body.appendChild(box);return 'ok';})()"

        const val JS_EXIT = "(function(){var b=window.__rvBox;if(!b)return;var v=window.__rvV,ph=window.__rvPh;" +
            "if(window.__rvStyle===null){v.removeAttribute('style');}else{v.setAttribute('style',window.__rvStyle);}" +
            "v.controls=window.__rvCtl;ph.parentNode.insertBefore(v,ph);ph.parentNode.removeChild(ph);" +
            "if(b.parentNode)b.parentNode.removeChild(b);window.__rvBox=null;})()"
    }

    private lateinit var container: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button
    private lateinit var btnBookmark: Button
    private lateinit var btnTabs: Button
    private lateinit var tabStrip: HorizontalScrollView
    private lateinit var tabStripInner: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var bookmarks: BookmarkStore
    private lateinit var prefs: SharedPreferences

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private var web: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var blockAds = true
    private var dataSaver = false

    // mode video layar penuh
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fsLayout: FrameLayout? = null
    private var pseudoFs = false
    private var landscapeNow = true

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.webContainer)
        urlBar = findViewById(R.id.urlBar)
        progressBar = findViewById(R.id.progress)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnBookmark = findViewById(R.id.btnBookmark)
        btnTabs = findViewById(R.id.btnTabs)
        tabStrip = findViewById(R.id.tabStrip)
        tabStripInner = findViewById(R.id.tabStripInner)
        bottomBar = findViewById(R.id.bottomBar)
        bookmarks = BookmarkStore(this)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        blockAds = prefs.getBoolean("blockAds", true)
        dataSaver = prefs.getBoolean("dataSaver", false)

        showLastCrashIfAny()
        requestStoragePermission()
        setupUi()

        val first = Tab()
        first.url = HOME_MARK
        tabs.add(first)
        showTab(first)
        intent?.data?.toString()?.let { loadUrl(it) }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.toString()?.let { openInNewTab(it) }
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
        web?.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        web?.resumeTimers()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        web?.clearCache(false)
    }

    override fun onDestroy() {
        destroyWeb()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitNativeFullscreen()
            pseudoFs -> exitPseudoVideo(true)
            urlBar.hasFocus() -> dismissUrlBar()
            web?.canGoBack() == true -> web?.goBack()
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ UI

    private fun setupUi() {
        urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                loadUrl(normalize(urlBar.text.toString()))
                dismissUrlBar()
                true
            } else false
        }
        btnBack.setOnClickListener { web?.goBack() }
        btnForward.setOnClickListener { web?.goForward() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnTabs.setOnClickListener { showTabs() }
        findViewById<Button>(R.id.btnVideo).setOnClickListener { toggleVideoFullscreen() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu() }
    }

    private fun dismissUrlBar() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
        container.isFocusableInTouchMode = true
        container.requestFocus()
    }

    private fun engineIndex() = prefs.getInt("engine", 0).coerceIn(0, ENGINE_ACTIONS.lastIndex)

    private fun normalize(input: String): String {
        val s = input.trim()
        return when {
            s.isEmpty() -> HOME_MARK
            s.startsWith("http://") || s.startsWith("https://") || s.startsWith("about:") -> s
            !s.contains(' ') && s.contains('.') -> "https://$s"
            else -> ENGINE_ACTIONS[engineIndex()] + "?q=" + Uri.encode(s)
        }
    }

    /** Samakan URL internal (halaman beranda) menjadi satu penanda. */
    private fun canon(u: String?): String {
        if (u == null) return ""
        return if (u.startsWith("ringan:") || u.startsWith("data:text/html")) HOME_MARK else u
    }

    private fun updateNavButtons() {
        btnBack.isEnabled = web?.canGoBack() == true
        btnForward.isEnabled = web?.canGoForward() == true
        val u = current?.url ?: ""
        btnBookmark.text = if (u.isNotBlank() && u != HOME_MARK && bookmarks.contains(u)) "★" else "☆"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    private fun showLastCrashIfAny() {
        val f = RinganApp.crashFile(application)
        if (!f.exists()) return
        val log = try { f.readText() } catch (_: Exception) { return }
        f.delete()
        AlertDialog.Builder(this)
            .setTitle("Aplikasi sebelumnya crash")
            .setMessage(log.take(3000))
            .setPositiveButton("Salin") { _, _ -> copyText(log) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    // ------------------------------------------------------------------ WebView

    private fun createWebView(): WebView {
        val w = WebView(this)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setGeolocationEnabled(false)
        s.blockNetworkImage = dataSaver
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                return when (u.scheme) {
                    null, "http", "https", "about", "data", "blob", "javascript", "ringan" -> false
                    else -> {
                        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                        true
                    }
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (blockAds && !request.isForMainFrame && AdBlock.isBlocked(request.url.host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (view !== web) return
                if (pseudoFs) exitPseudoVideo(false)
                val shown = canon(url)
                current?.url = shown
                if (!urlBar.hasFocus()) urlBar.setText(if (shown == HOME_MARK) "" else shown)
                updateNavButtons()
                updateTabsUi()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view !== web) return
                progressBar.visibility = View.INVISIBLE
                updateNavButtons()
            }

            @TargetApi(26)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                mainHandler.post {
                    toast("Halaman dihentikan sistem (memori), dimuat ulang")
                    recreateCurrent()
                }
                return true
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== web || pseudoFs) return
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (view !== web) return
                current?.title = title ?: ""
                updateTabsUi()
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                enterNativeFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitNativeFullscreen()
            }

            override fun getDefaultVideoPoster(): Bitmap? =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        w.setDownloadListener { url, ua, cd, mime, _ -> downloadUrl(url, cd, mime, ua) }

        w.setOnLongClickListener {
            val r = w.hitTestResult
            val extra = r.extra
            val t = r.type
            if (extra != null && (t == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE ||
                    t == WebView.HitTestResult.IMAGE_TYPE)
            ) {
                showLinkMenu(t == WebView.HitTestResult.IMAGE_TYPE, extra)
                true
            } else false
        }
        return w
    }

    private fun showLinkMenu(isImage: Boolean, url: String) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (!isImage) options.add(Pair("Buka di tab baru") { openInNewTab(url) })
        options.add(Pair(if (isImage) "Download gambar" else "Download") { downloadUrl(url) })
        options.add(Pair("Salin link") { copyText(url) })
        AlertDialog.Builder(this)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun destroyWeb() {
        leaveVideoModes()
        val w = web ?: return
        web = null
        container.removeView(w)
        w.stopLoading()
        w.destroy()
    }

    private fun saveCurrentState() {
        val t = current ?: return
        val w = web ?: return
        try {
            val b = Bundle()
            w.saveState(b)
            t.state = b
            t.url = canon(w.url).ifBlank { t.url }
            t.title = w.title ?: t.title
        } catch (_: Exception) {}
    }

    private fun loadUrl(u: String) {
        val custom = prefs.getString("homeUrl", "") ?: ""
        val target = if (u == HOME_MARK && custom.isNotBlank()) custom else u
        current?.url = target
        val w = web ?: return
        if (target == HOME_MARK) {
            w.loadDataWithBaseURL(HOME_MARK, buildHomeHtml(), "text/html", "utf-8", HOME_MARK)
            if (!urlBar.hasFocus()) urlBar.setText("")
        } else {
            w.loadUrl(target)
        }
        updateTabsUi()
    }

    // ------------------------------------------------------------------ beranda

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun buildHomeHtml(): String {
        val th = THEMES[prefs.getInt("homeTheme", 0).coerceIn(0, THEMES.lastIndex)]
        val title = esc(prefs.getString("homeTitle", "Ringan Browser") ?: "")
        val action = esc(ENGINE_ACTIONS[engineIndex()])
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>").append(title).append("</title><style>")
        sb.append("body{margin:0;padding:28px 12px;font-family:sans-serif;text-align:center;")
        sb.append("background:").append(th.bg).append(";color:").append(th.fg).append("}")
        sb.append("h1{font-size:26px;margin:8px 0 22px}")
        sb.append("input{width:92%;max-width:480px;padding:12px 16px;font-size:16px;border:none;")
        sb.append("border-radius:24px;background:").append(th.tile).append(";color:").append(th.fg).append("}")
        sb.append(".g{margin-top:24px}")
        sb.append("a.t{display:inline-block;width:44%;box-sizing:border-box;-webkit-box-sizing:border-box;")
        sb.append("margin:5px 1%;padding:14px 8px;border-radius:10px;font-size:14px;overflow:hidden;")
        sb.append("text-overflow:ellipsis;white-space:nowrap;text-decoration:none;")
        sb.append("background:").append(th.tile).append(";color:").append(th.fg).append("}")
        sb.append("</style></head><body><h1>").append(title).append("</h1>")
        sb.append("<form action=\"").append(action).append("\" method=\"get\">")
        sb.append("<input type=\"search\" name=\"q\" placeholder=\"Cari atau ketik alamat\"></form>")
        if (prefs.getBoolean("homeShowBm", true)) {
            val list = bookmarks.all().take(12)
            if (list.isNotEmpty()) {
                sb.append("<div class=\"g\">")
                for (b in list) {
                    sb.append("<a class=\"t\" href=\"").append(esc(b.url)).append("\">")
                        .append(esc(b.title)).append("</a>")
                }
                sb.append("</div>")
            }
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun showHomeSettings() {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            setPadding(0, (10 * d).toInt(), 0, 0)
        }

        val etTitle = EditText(this).apply {
            setSingleLine()
            setText(prefs.getString("homeTitle", "Ringan Browser"))
        }
        val etUrl = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "kosong = beranda bawaan"
            setText(prefs.getString("homeUrl", ""))
        }
        val spTheme = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                THEMES.map { it.name }
            )
            setSelection(prefs.getInt("homeTheme", 0).coerceIn(0, THEMES.lastIndex))
        }
        val spEngine = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                ENGINE_NAMES.toList()
            )
            setSelection(engineIndex())
        }
        val cbBm = CheckBox(this).apply {
            text = "Tampilkan bookmark di beranda"
            isChecked = prefs.getBoolean("homeShowBm", true)
        }

        col.addView(label("Judul beranda"))
        col.addView(etTitle)
        col.addView(label("Warna tampilan"))
        col.addView(spTheme)
        col.addView(label("Mesin pencari"))
        col.addView(spEngine)
        col.addView(cbBm)
        col.addView(label("Atau pakai halaman web sendiri sebagai beranda"))
        col.addView(etUrl)

        val scroll = ScrollView(this).apply { addView(col) }
        AlertDialog.Builder(this)
            .setTitle("Pengaturan beranda")
            .setView(scroll)
            .setPositiveButton("Simpan") { _, _ ->
                val u = etUrl.text.toString().trim()
                val url = if (u.isEmpty() || u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
                prefs.edit()
                    .putString("homeTitle", etTitle.text.toString().trim())
                    .putInt("homeTheme", spTheme.selectedItemPosition)
                    .putInt("engine", spEngine.selectedItemPosition)
                    .putBoolean("homeShowBm", cbBm.isChecked)
                    .putString("homeUrl", url)
                    .apply()
                if (current?.url == HOME_MARK) loadUrl(HOME_MARK)
                toast("Beranda disimpan")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ------------------------------------------------------------------ tabs

    private fun tabLabel(t: Tab): String = when {
        t.url == HOME_MARK -> "Beranda"
        t.title.isNotBlank() -> t.title
        t.url.isNotBlank() -> t.url.substringAfter("://").trimEnd('/')
        else -> "Tab baru"
    }

    private fun updateTabsUi() {
        btnTabs.text = "Tab ${tabs.size}"
        tabStrip.visibility = if (tabs.size > 1 && !pseudoFs) View.VISIBLE else View.GONE
        tabStripInner.removeAllViews()
        val d = resources.displayMetrics.density
        fun chip(text: String, bg: String, bold: Boolean): TextView = TextView(this).apply {
            this.text = text
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (150 * d).toInt()
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setBackgroundColor(Color.parseColor(bg))
        }
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = (4 * d).toInt() } }

        for (t in tabs) {
            val isCur = t === current
            val c = chip(tabLabel(t), if (isCur) "#d6e4ff" else "#eeeeee", isCur)
            c.setOnClickListener { if (t !== current) showTab(t) }
            c.setOnLongClickListener { closeTab(t); true }
            tabStripInner.addView(c, lp())
            if (isCur) {
                val x = chip("✕", "#d6e4ff", true)
                x.setOnClickListener { closeTab(t) }
                tabStripInner.addView(x, lp())
            }
        }
        val plus = chip("+", "#eeeeee", true)
        plus.setOnClickListener { openInNewTab(HOME_MARK) }
        tabStripInner.addView(plus, lp())
    }

    private fun showTab(tab: Tab) {
        saveCurrentState()
        destroyWeb()
        current = tab
        val w = createWebView()
        web = w
        container.addView(
            w,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val st = tab.state
        val restored = tab.url != HOME_MARK && st != null && w.restoreState(st) != null
        if (!restored) {
            if (tab.url.isBlank() || tab.url == HOME_MARK) loadUrl(HOME_MARK) else w.loadUrl(tab.url)
        }
        if (!urlBar.hasFocus()) urlBar.setText(if (tab.url == HOME_MARK) "" else tab.url)
        progressBar.visibility = View.INVISIBLE
        updateNavButtons()
        updateTabsUi()
    }

    /** Buat ulang WebView tab aktif (mis. setelah proses render dimatikan sistem). */
    private fun recreateCurrent() {
        val t = current ?: return
        destroyWeb()
        t.state = null
        showTab(t)
    }

    private fun openInNewTab(url: String) {
        if (tabs.size >= MAX_TABS) {
            toast("Maksimal $MAX_TABS tab (hemat RAM), dibuka di tab ini")
            loadUrl(url)
            return
        }
        val t = Tab()
        t.url = url
        tabs.add(t)
        showTab(t)
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        if (tabs.isEmpty()) {
            val t = Tab()
            t.url = HOME_MARK
            tabs.add(t)
            current = null
            destroyWeb()
            showTab(t)
        } else if (current === tab) {
            current = null
            destroyWeb()
            showTab(tabs[minOf(idx, tabs.lastIndex)])
        } else {
            updateTabsUi()
        }
    }

    private fun showTabs() {
        saveCurrentState()
        val titles = tabs.map { t -> (if (t === current) "● " else "") + tabLabel(t) }.toTypedArray()
        val dlg = AlertDialog.Builder(this)
            .setTitle("Tab (tahan untuk menutup)")
            .setItems(titles) { _, i -> if (tabs[i] !== current) showTab(tabs[i]) }
            .setPositiveButton("Tab baru") { _, _ -> openInNewTab(HOME_MARK) }
            .setNegativeButton("Tutup", null)
            .create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, pos, _ ->
            closeTab(tabs[pos])
            dlg.dismiss()
            true
        }
    }

    // ------------------------------------------------------------------ video layar penuh

    @Suppress("DEPRECATION")
    private fun setImmersive(on: Boolean) {
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun applyOrientation(landscape: Boolean) {
        landscapeNow = landscape
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private fun resetOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Dipanggil saat video dibuka lewat tombol fullscreen bawaan situs. */
    private fun enterNativeFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCb = callback
        val fl = FrameLayout(this)
        fl.setBackgroundColor(Color.BLACK)
        fl.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val d = resources.displayMetrics.density
        val rotate = Button(this).apply {
            text = "Putar"
            alpha = 0.75f
            setOnClickListener { applyOrientation(!landscapeNow) }
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (40 * d).toInt()).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (8 * d).toInt()
            marginEnd = (8 * d).toInt()
        }
        fl.addView(rotate, lp)

        (window.decorView as ViewGroup).addView(
            fl, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        fsLayout = fl
        setImmersive(true)
        if (prefs.getBoolean("fsLandscape", true)) applyOrientation(true) else landscapeNow = false
    }

    private fun exitNativeFullscreen() {
        val fl = fsLayout
        val cb = customCb
        fsLayout = null
        customView = null
        customCb = null
        if (fl != null) {
            (fl.parent as? ViewGroup)?.removeView(fl)
            fl.removeAllViews()
            setImmersive(false)
            resetOrientation()
        }
        cb?.onCustomViewHidden()
    }

    private fun setBarsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        urlBar.visibility = v
        bottomBar.visibility = v
        progressBar.visibility = if (visible) View.INVISIBLE else View.GONE
        if (visible) updateTabsUi() else tabStrip.visibility = View.GONE
    }

    /** Layar penuh untuk video apa pun di halaman (tombol "Video" / menu). */
    private fun toggleVideoFullscreen() {
        if (customView != null) {
            exitNativeFullscreen()
            return
        }
        if (pseudoFs) {
            exitPseudoVideo(true)
            return
        }
        val w = web ?: return
        w.evaluateJavascript(JS_ENTER) { r ->
            if (r != null && r.contains("ok")) {
                pseudoFs = true
                setBarsVisible(false)
                setImmersive(true)
                if (prefs.getBoolean("fsLandscape", true)) applyOrientation(true) else landscapeNow = false
                toast("Tekan Back untuk keluar dari layar penuh")
            } else {
                toast("Video tidak ditemukan di halaman ini")
            }
        }
    }

    private fun exitPseudoVideo(runJs: Boolean) {
        if (!pseudoFs) return
        pseudoFs = false
        if (runJs) web?.evaluateJavascript(JS_EXIT, null)
        setBarsVisible(true)
        setImmersive(false)
        resetOrientation()
    }

    /** Keluar dari semua mode layar penuh tanpa menyentuh WebView (dipakai saat WebView dihancurkan). */
    private fun leaveVideoModes() {
        if (customView != null) exitNativeFullscreen()
        if (pseudoFs) exitPseudoVideo(false)
    }

    // ------------------------------------------------------------------ menu & bookmark

    private fun showMenu() {
        val items = arrayOf(
            "Bookmark",
            "Beranda",
            "Pengaturan beranda",
            "Jadikan halaman ini beranda",
            "Video layar penuh",
            "Video fullscreen otomatis landscape: ${if (prefs.getBoolean("fsLandscape", true)) "AKTIF" else "mati"}",
            "Download dari URL",
            "Mode hemat data (blokir gambar): ${if (dataSaver) "AKTIF" else "mati"}",
            "Blokir iklan: ${if (blockAds) "AKTIF" else "mati"}",
            "Tab baru",
            "Hapus data penjelajahan"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showBookmarks()
                    1 -> loadUrl(HOME_MARK)
                    2 -> showHomeSettings()
                    3 -> {
                        val u = current?.url ?: ""
                        if (u.isBlank() || u == HOME_MARK) {
                            toast("Buka dulu halaman yang ingin dijadikan beranda")
                        } else {
                            prefs.edit().putString("homeUrl", u).apply()
                            toast("Beranda diubah ke halaman ini")
                        }
                    }
                    4 -> toggleVideoFullscreen()
                    5 -> {
                        val on = !prefs.getBoolean("fsLandscape", true)
                        prefs.edit().putBoolean("fsLandscape", on).apply()
                        toast(if (on) "Video fullscreen otomatis landscape" else "Video fullscreen mengikuti rotasi")
                    }
                    6 -> askDownloadUrl()
                    7 -> {
                        dataSaver = !dataSaver
                        prefs.edit().putBoolean("dataSaver", dataSaver).apply()
                        web?.settings?.blockNetworkImage = dataSaver
                        toast(if (dataSaver) "Gambar diblokir (muat ulang halaman)" else "Gambar aktif (muat ulang halaman)")
                    }
                    8 -> {
                        blockAds = !blockAds
                        prefs.edit().putBoolean("blockAds", blockAds).apply()
                        toast(if (blockAds) "Blokir iklan aktif" else "Blokir iklan mati")
                    }
                    9 -> openInNewTab(HOME_MARK)
                    10 -> clearBrowsingData()
                }
            }
            .show()
    }

    private fun clearBrowsingData() {
        web?.clearCache(true)
        web?.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        updateNavButtons()
        toast("Data penjelajahan dihapus")
    }

    private fun addCurrentBookmark() {
        val t = current ?: return
        if (t.url.isBlank() || t.url == HOME_MARK) {
            toast("Halaman ini tidak bisa dibookmark")
            return
        }
        bookmarks.add(t.title, t.url)
        toast("Bookmark disimpan")
        updateNavButtons()
    }

    private fun toggleBookmark() {
        val t = current ?: return
        if (t.url.isBlank() || t.url == HOME_MARK) return
        if (bookmarks.contains(t.url)) {
            bookmarks.remove(t.url)
            toast("Bookmark dihapus")
            updateNavButtons()
        } else {
            addCurrentBookmark()
        }
    }

    private fun showBookmarks() {
        val list = bookmarks.all()
        val b = AlertDialog.Builder(this).setTitle("Bookmark (tahan untuk opsi)")
        if (list.isEmpty()) {
            b.setMessage("Belum ada bookmark")
        } else {
            b.setItems(list.map { it.title }.toTypedArray()) { _, i -> loadUrl(list[i].url) }
        }
        b.setPositiveButton("Tambah halaman ini") { _, _ -> addCurrentBookmark() }
        b.setNegativeButton("Tutup", null)
        val dlg = b.create()
        dlg.show()
        if (list.isNotEmpty()) {
            dlg.listView.setOnItemLongClickListener { _, _, pos, _ ->
                dlg.dismiss()
                showBookmarkOptions(list[pos])
                true
            }
        }
    }

    private fun showBookmarkOptions(item: BookmarkStore.Item) {
        val options = arrayOf("Buka di tab baru", "Ubah judul", "Jadikan beranda", "Hapus")
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(options) { _, i ->
                when (i) {
                    0 -> openInNewTab(item.url)
                    1 -> {
                        val input = EditText(this).apply { setSingleLine(); setText(item.title) }
                        AlertDialog.Builder(this)
                            .setTitle("Ubah judul bookmark")
                            .setView(input)
                            .setPositiveButton("Simpan") { _, _ ->
                                bookmarks.rename(item.url, input.text.toString().trim())
                                if (current?.url == HOME_MARK) loadUrl(HOME_MARK)
                            }
                            .setNegativeButton("Batal", null)
                            .show()
                    }
                    2 -> {
                        prefs.edit().putString("homeUrl", item.url).apply()
                        toast("Beranda diubah")
                    }
                    3 -> {
                        bookmarks.remove(item.url)
                        updateNavButtons()
                        if (current?.url == HOME_MARK) loadUrl(HOME_MARK)
                        toast("Bookmark dihapus")
                    }
                }
            }
            .show()
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", text))
        toast("Disalin")
    }

    // ------------------------------------------------------------------ download

    private fun askDownloadUrl() {
        val input = EditText(this).apply { hint = "https://.../file.mp3"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Download dari URL")
            .setView(input)
            .setPositiveButton("Download") { _, _ -> downloadUrl(input.text.toString().trim()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun downloadUrl(url: String, contentDisposition: String? = null, mime: String? = null, ua: String? = null) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            if (!ua.isNullOrBlank()) req.addRequestHeader("User-Agent", ua)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Download dimulai: $name")
        } catch (e: Exception) {
            toast("Gagal download: ${e.message}")
        }
    }
}
