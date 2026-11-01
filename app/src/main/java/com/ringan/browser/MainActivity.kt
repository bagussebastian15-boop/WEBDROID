package com.ringan.browser

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebResponse

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME = "https://duckduckgo.com"
        const val SEARCH = "https://duckduckgo.com/?q="
        const val ADDONS = "https://addons.mozilla.org/en-US/android/"
    }

    private lateinit var geckoView: GeckoView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button
    private lateinit var btnBookmark: Button
    private lateinit var bookmarks: BookmarkStore

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private val runtime get() = RinganApp.runtime
    private val mainHandler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.geckoview)
        urlBar = findViewById(R.id.urlBar)
        progressBar = findViewById(R.id.progress)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnBookmark = findViewById(R.id.btnBookmark)
        bookmarks = BookmarkStore(this)

        requestRuntimePermissions()
        setupExtensionPrompt()
        setupUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = current
                if (t != null && t.canGoBack) t.session.goBack() else finish()
            }
        })

        val first = createTab()
        showTab(first)
        first.session.loadUri(intent?.data?.toString() ?: HOME)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.toString()?.let { openInNewTab(it) }
    }

    // ------------------------------------------------------------------ UI

    private fun setupUi() {
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadFromBar(urlBar.text.toString())
                true
            } else false
        }
        btnBack.setOnClickListener { current?.session?.goBack() }
        btnForward.setOnClickListener { current?.session?.goForward() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        findViewById<Button>(R.id.btnTabs).setOnClickListener { showTabs() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu() }
    }

    private fun loadFromBar(input: String) {
        val t = current ?: return
        t.session.loadUri(normalize(input))
        urlBar.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun normalize(input: String): String {
        val s = input.trim()
        return when {
            s.isEmpty() -> HOME
            s.startsWith("http://") || s.startsWith("https://") ||
                s.startsWith("about:") || s.startsWith("file:") -> s
            !s.contains(' ') && s.contains('.') -> "https://$s"
            else -> SEARCH + Uri.encode(s)
        }
    }

    private fun updateNavButtons() {
        val t = current
        btnBack.isEnabled = t?.canGoBack == true
        btnForward.isEnabled = t?.canGoForward == true
        btnBookmark.text = if (t != null && bookmarks.contains(t.url)) "★" else "☆"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun requestRuntimePermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 28) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    // ------------------------------------------------------------------ tabs

    private fun createTab(): Tab {
        val session = GeckoSession()
        val tab = Tab(session)

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) {
                tab.title = title ?: ""
            }

            override fun onCloseRequest(s: GeckoSession) {
                closeTab(tab)
            }

            override fun onExternalResponse(s: GeckoSession, response: WebResponse) {
                val h = response.headers
                fun header(n: String) = h.entries.firstOrNull { it.key.equals(n, true) }?.value
                val mime = header("content-type")
                try { response.body?.close() } catch (_: Exception) {}
                if (response.uri.contains(".xpi") || mime?.contains("x-xpinstall") == true) {
                    installExtension(response.uri)
                } else {
                    downloadUrl(response.uri, header("content-disposition"), mime)
                }
            }

            override fun onContextMenu(
                s: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                val link = element.linkUri
                val src = element.srcUri
                val options = mutableListOf<Pair<String, () -> Unit>>()
                if (link != null) options.add(Pair("Buka di tab baru") { openInNewTab(link) })
                val dl = src ?: link
                if (dl != null) {
                    if (dl.contains(".xpi")) options.add(Pair("Pasang ekstensi") { installExtension(dl) })
                    options.add(Pair("Download") { downloadUrl(dl) })
                }
                val copy = link ?: src
                if (copy != null) options.add(Pair("Salin link") { copyText(copy) })
                if (options.isEmpty()) return
                AlertDialog.Builder(this@MainActivity)
                    .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
                    .show()
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                s: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>
            ) {
                tab.url = url ?: ""
                if (current === tab) {
                    if (!urlBar.hasFocus()) urlBar.setText(tab.url)
                    updateNavButtons()
                }
            }

            override fun onCanGoBack(s: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
                if (current === tab) updateNavButtons()
            }

            override fun onCanGoForward(s: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
                if (current === tab) updateNavButtons()
            }

            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val t = createTab()
                mainHandler.post { showTab(t) }
                return GeckoResult.fromValue(t.session)
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(s: GeckoSession, p: Int) {
                tab.progress = p
                if (current === tab) {
                    progressBar.progress = p
                    progressBar.visibility = if (p in 1..99) View.VISIBLE else View.INVISIBLE
                }
            }
        }

        tabs.add(tab)
        return tab
    }

    private fun showTab(tab: Tab) {
        if (!tab.session.isOpen) {
            try { tab.session.open(runtime) } catch (_: IllegalStateException) {}
        }
        geckoView.setSession(tab.session)
        current = tab
        if (!urlBar.hasFocus()) urlBar.setText(tab.url)
        progressBar.progress = tab.progress
        updateNavButtons()
    }

    private fun openInNewTab(url: String) {
        val t = createTab()
        showTab(t)
        t.session.loadUri(url)
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        if (tabs.isEmpty()) {
            val t = createTab()
            showTab(t)
            t.session.loadUri(HOME)
        } else if (current === tab) {
            showTab(tabs[minOf(idx, tabs.lastIndex)])
        }
        tab.session.close()
    }

    private fun showTabs() {
        val titles = tabs.map { t -> t.title.ifBlank { t.url.ifBlank { "Tab baru" } } }.toTypedArray()
        val dlg = AlertDialog.Builder(this)
            .setTitle("Tab (tahan untuk menutup)")
            .setItems(titles) { _, i -> showTab(tabs[i]) }
            .setPositiveButton("Tab baru") { _, _ -> openInNewTab(HOME) }
            .setNegativeButton("Tutup", null)
            .create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, pos, _ ->
            closeTab(tabs[pos])
            dlg.dismiss()
            true
        }
    }

    // ------------------------------------------------------------------ menu & bookmark

    private fun showMenu() {
        val items = arrayOf(
            "Bookmark",
            "Download dari URL",
            "Ekstensi",
            "Toko add-on Firefox",
            "Tab baru"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showBookmarks()
                    1 -> askDownloadUrl()
                    2 -> showExtensions()
                    3 -> openInNewTab(ADDONS)
                    4 -> openInNewTab(HOME)
                }
            }
            .show()
    }

    private fun toggleBookmark() {
        val t = current ?: return
        if (t.url.isBlank()) return
        if (bookmarks.contains(t.url)) {
            bookmarks.remove(t.url)
            toast("Bookmark dihapus")
        } else {
            bookmarks.add(t.title, t.url)
            toast("Bookmark disimpan")
        }
        updateNavButtons()
    }

    private fun showBookmarks() {
        val list = bookmarks.all()
        if (list.isEmpty()) {
            toast("Belum ada bookmark")
            return
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Bookmark (tahan untuk menghapus)")
            .setItems(list.map { it.title }.toTypedArray()) { _, i -> current?.session?.loadUri(list[i].url) }
            .setNegativeButton("Tutup", null)
            .create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, pos, _ ->
            bookmarks.remove(list[pos].url)
            dlg.dismiss()
            updateNavButtons()
            toast("Bookmark dihapus")
            true
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", text))
        toast("Link disalin")
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

    private fun downloadUrl(url: String, contentDisposition: String? = null, mime: String? = null) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Download dimulai: $name")
        } catch (e: Exception) {
            toast("Gagal download: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ extensions
    // Catatan: bagian ini paling bergantung pada versi GeckoView. Jika build gagal di sini,
    // sesuaikan nama method dengan Javadoc versi yang dipakai.

    private fun setupExtensionPrompt() {
        runtime.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPrompt(extension: WebExtension): GeckoResult<AllowOrDeny>? {
                val result = GeckoResult<AllowOrDeny>()
                val name = extension.metaData.name ?: extension.id
                val perms = (extension.metaData.permissions.toList() + extension.metaData.origins.toList())
                    .joinToString("\n").ifBlank { "(tidak ada izin khusus)" }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Pasang \"$name\"?")
                    .setMessage("Ekstensi ini meminta izin:\n\n$perms")
                    .setPositiveButton("Pasang") { _, _ -> result.complete(AllowOrDeny.ALLOW) }
                    .setNegativeButton("Batal") { _, _ -> result.complete(AllowOrDeny.DENY) }
                    .setOnCancelListener { result.complete(AllowOrDeny.DENY) }
                    .show()
                return result
            }
        })
    }

    private fun installExtension(uri: String) {
        runtime.webExtensionController.install(uri).accept(
            { ext -> toast("Ekstensi terpasang: ${ext?.metaData?.name ?: ""}") },
            { e -> toast("Gagal memasang ekstensi: ${e?.message}") }
        )
    }

    private fun showExtensions() {
        runtime.webExtensionController.list().accept(
            { list ->
                val exts = list ?: emptyList()
                val names = exts.map { it.metaData.name ?: it.id }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Ekstensi terpasang")
                    .setItems(names) { _, i -> confirmUninstall(exts[i]) }
                    .setPositiveButton("Pasang dari URL") { _, _ -> askInstallUrl() }
                    .setNegativeButton("Tutup", null)
                    .show()
            },
            { e -> toast("Gagal memuat ekstensi: ${e?.message}") }
        )
    }

    private fun confirmUninstall(ext: WebExtension) {
        AlertDialog.Builder(this)
            .setTitle("Hapus ekstensi?")
            .setMessage(ext.metaData.name ?: ext.id)
            .setPositiveButton("Hapus") { _, _ ->
                runtime.webExtensionController.uninstall(ext).accept(
                    { toast("Ekstensi dihapus") },
                    { e -> toast("Gagal menghapus: ${e?.message}") }
                )
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun askInstallUrl() {
        val input = EditText(this).apply { hint = "https://.../ekstensi.xpi"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Pasang ekstensi dari URL (.xpi)")
            .setView(input)
            .setPositiveButton("Pasang") { _, _ -> installExtension(input.text.toString().trim()) }
            .setNegativeButton("Batal", null)
            .show()
    }
}
