package com.ringan.browser

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
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
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }

    private lateinit var geckoView: GeckoView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: Button
    private lateinit var btnForward: Button
    private lateinit var btnBookmark: Button
    private lateinit var bookmarks: BookmarkStore
    private lateinit var downloads: DownloadTracker
    private lateinit var downloadStrip: View
    private lateinit var downloadText: TextView
    private lateinit var downloadBar: ProgressBar
    private var pollingDownloads = false

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
        downloads = DownloadTracker(this)
        downloadStrip = findViewById(R.id.downloadStrip)
        downloadText = findViewById(R.id.downloadText)
        downloadBar = findViewById(R.id.downloadBar)
        downloadStrip.setOnClickListener { showDownloads() }
        DownloadCenter.addListener(downloadListener)

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
        if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) showDownloads()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.toString()?.let { openInNewTab(it) }
        if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) showDownloads()
    }

    override fun onResume() {
        super.onResume()
        startDownloadPolling()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(pollRunnable)
        pollingDownloads = false
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadCenter.removeListener(downloadListener)
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
                    downloadUrl(response.uri, header("content-disposition"), mime, tab.url)
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
                    options.add(Pair("Download") { downloadUrl(dl, referer = tab.url) })
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
            "Unduhan",
            "Download dari URL",
            "Koneksi download (${DownloadCenter.connections})",
            "Ekstensi",
            "Toko add-on Firefox",
            "Tab baru"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showBookmarks()
                    1 -> showDownloads()
                    2 -> askDownloadUrl()
                    3 -> showConnectionSetting()
                    4 -> showExtensions()
                    5 -> openInNewTab(ADDONS)
                    6 -> openInNewTab(HOME)
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
    // Mesin download sendiri (DownloadCenter): multi-koneksi bersegmen + resume, seperti IDM.
    // UI di sini: strip progres di atas bar bawah dan daftar "Unduhan".

    private val downloadListener = object : DownloadCenter.Listener {
        override fun onFinished(id: Long) {
            mainHandler.post {
                val info = downloads.find(id) ?: return@post
                when (info.status) {
                    Status.SUCCESSFUL -> toast("Selesai: ${info.name} (${downloads.sizeText(info.downloaded)})")
                    Status.FAILED -> toast("Gagal: ${info.name} \u2014 ${info.message}")
                }
                refreshDownloadStrip()
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (refreshDownloadStrip()) {
                mainHandler.postDelayed(this, 1000)
            } else {
                pollingDownloads = false
            }
        }
    }

    private fun askDownloadUrl() {
        val input = EditText(this).apply { hint = "https://.../file.mp3"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Download dari URL")
            .setView(input)
            .setPositiveButton("Download") { _, _ -> downloadUrl(input.text.toString().trim()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun downloadUrl(
        url: String,
        contentDisposition: String? = null,
        mime: String? = null,
        referer: String? = null
    ) {
        val u = url.trim()
        if (u.isEmpty()) return
        val full = when {
            u.startsWith("http://", true) || u.startsWith("https://", true) -> u
            u.contains("://") -> {
                toast("Hanya link http/https yang bisa diunduh")
                return
            }
            else -> "https://$u"
        }
        try {
            val id = DownloadCenter.enqueue(full, contentDisposition, mime, referer?.takeIf { it.startsWith("http") })
            downloads.find(id)?.let { toast("Download dimulai: ${it.name}") }
            startDownloadPolling()
        } catch (e: Exception) {
            toast("Gagal download: ${e.message}")
        }
    }

    private fun showConnectionSetting() {
        val options = intArrayOf(4, 8, 16, 32)
        val labels = options.map { "$it koneksi" + if (it == 8) " (default)" else "" }.toTypedArray()
        val checked = options.indexOf(DownloadCenter.connections).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Koneksi per download")
            .setSingleChoiceItems(labels, checked) { d, i ->
                DownloadCenter.setConnections(options[i])
                toast("Berlaku untuk download berikutnya: ${options[i]} koneksi")
                d.dismiss()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun startDownloadPolling() {
        if (pollingDownloads) return
        pollingDownloads = true
        mainHandler.post(pollRunnable)
    }

    /** Memperbarui strip; mengembalikan true jika masih ada download aktif. */
    private fun refreshDownloadStrip(): Boolean {
        val active = downloads.query().filter { it.active }
        if (active.isEmpty()) {
            downloadStrip.visibility = View.GONE
            return false
        }
        val first = active.first()
        val extra = if (active.size > 1) "  (+${active.size - 1} lainnya)" else ""
        downloadText.text = downloads.summary(first) + extra
        if (first.percent >= 0) {
            downloadBar.isIndeterminate = false
            downloadBar.progress = first.percent
        } else {
            downloadBar.isIndeterminate = true
        }
        downloadStrip.visibility = View.VISIBLE
        return true
    }

    private fun showDownloads() {
        var items = downloads.query()
        if (items.isEmpty()) {
            toast("Belum ada unduhan")
            return
        }
        val listAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(this).apply { adapter = listAdapter }

        fun fill() {
            items = downloads.query()
            listAdapter.setNotifyOnChange(false)
            listAdapter.clear()
            listAdapter.addAll(items.map { downloads.detail(it) })
            listAdapter.notifyDataSetChanged()
        }

        val refresher = object : Runnable {
            override fun run() {
                fill()
                mainHandler.postDelayed(this, 1000)
            }
        }

        list.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { onDownloadTapped(it) } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            items.getOrNull(pos)?.let { confirmRemoveDownload(it) }
            true
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Unduhan (ketuk: jeda/lanjut, tahan: hapus)")
            .setView(list)
            .setNegativeButton("Tutup", null)
            .create()
        dlg.setOnDismissListener { mainHandler.removeCallbacks(refresher) }
        dlg.show()
        refresher.run()
    }

    private fun onDownloadTapped(i: DownloadTracker.Info) {
        when (i.status) {
            Status.SUCCESSFUL -> openDownload(i)
            Status.RUNNING, Status.PENDING -> {
                downloads.pause(i.id)
                toast("Dijeda: ${i.name}")
            }
            else -> {
                downloads.resume(i.id)
                toast("Dilanjutkan: ${i.name}")
                startDownloadPolling()
            }
        }
    }

    private fun openDownload(i: DownloadTracker.Info) {
        val uri = downloads.uriFor(i.id)
        if (uri == null) {
            toast("File tidak ditemukan")
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, i.mime ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            toast("Tidak ada aplikasi untuk membuka file ini")
        } catch (e: Exception) {
            toast("File tidak bisa dibuka: ${e.message}")
        }
    }

    private fun confirmRemoveDownload(i: DownloadTracker.Info) {
        val (title, msg) = if (i.status == Status.SUCCESSFUL) {
            "Hapus dari daftar?" to "${i.name}\n\nFile hasil download ikut terhapus dari perangkat."
        } else {
            "Batalkan unduhan?" to "${i.name}\n\nData yang sudah terunduh akan dihapus."
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Ya") { _, _ ->
                downloads.remove(i.id)
                refreshDownloadStrip()
            }
            .setNegativeButton("Tidak", null)
            .show()
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
