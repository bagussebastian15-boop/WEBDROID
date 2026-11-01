package com.ringan.browser

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object Status {
    const val PENDING = 1
    const val RUNNING = 2
    const val PAUSED = 4
    const val SUCCESSFUL = 8
    const val FAILED = 16
}

/** Satu potongan file [start..end] (inklusif). [pos] = byte berikutnya yang akan diunduh. */
class Chunk(val start: Long, @Volatile var end: Long, @Volatile var pos: Long = start) {
    val done: Boolean get() = pos > end
    val downloaded: Long get() = minOf(pos, end + 1) - start
}

class Task(
    val id: Long,
    val origUrl: String,
    val referer: String?,
    name: String,
    mime: String?
) {
    @Volatile var url: String = origUrl          // URL akhir setelah redirect
    @Volatile var name: String = name
    @Volatile var mime: String? = mime
    @Volatile var status: Int = Status.PENDING
    @Volatile var message: String = ""
    @Volatile var total: Long = -1L
    @Volatile var rangeOk: Boolean = false
    @Volatile var chunks: List<Chunk> = emptyList()
    @Volatile var sinkUri: String? = null        // content:// (API 29+) atau path file (API < 29)
    @Volatile var speed: Long = 0L
    @Volatile var flag: AtomicBoolean = AtomicBoolean(false)   // true = berhenti (jeda/hapus)
    @Volatile var future: Future<*>? = null
    @Volatile var disposition: String? = null
    val conns: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()

    val downloaded: Long get() = chunks.sumOf { it.downloaded }
}

/**
 * Mesin download ala IDM:
 *  - file dipecah jadi banyak potongan, diunduh paralel lewat beberapa koneksi (HTTP Range)
 *  - potongan yang selesai langsung diganti potongan baru, jadi semua koneksi selalu sibuk
 *  - bisa dijeda/dilanjutkan (juga setelah aplikasi ditutup), koneksi putus dicoba ulang otomatis
 *  - server tanpa dukungan Range otomatis memakai 1 koneksi
 */
object DownloadCenter {

    interface Listener {
        fun onFinished(id: Long)
    }

    private const val MAX_PARALLEL = 3        // jumlah file yang diunduh bersamaan
    private const val MAX_HISTORY = 50
    private const val UA = "Mozilla/5.0 (Android 14; Mobile; rv:115.0) Gecko/115.0 Firefox/115.0"

    private class Stopped : Exception()
    private class HttpStatus(val code: Int) : IOException("server membalas HTTP $code")
    private class Probe(
        val finalUrl: String,
        val total: Long,
        val rangeOk: Boolean,
        val mime: String?,
        val disposition: String?
    )

    private lateinit var ctx: Context
    private val tasks = CopyOnWriteArrayList<Task>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val lock = Any()
    private val saveLock = Any()
    private var nextId = 1L

    private val prefs get() = ctx.getSharedPreferences("download_settings", Context.MODE_PRIVATE)

    /** Jumlah koneksi paralel per file (4 / 8 / 16 / 32). */
    val connections: Int get() = prefs.getInt("connections", 8)

    fun setConnections(n: Int) {
        prefs.edit().putInt("connections", n.coerceIn(1, 32)).apply()
    }

    fun init(context: Context) {
        ctx = context.applicationContext
        load()
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun snapshot(): List<Task> = tasks.sortedByDescending { it.id }
    fun find(id: Long): Task? = tasks.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ kontrol

    fun enqueue(url: String, disposition: String?, mime: String?, referer: String?): Long {
        val cleanMime = mime?.substringBefore(';')?.trim()?.ifBlank { null }
        val name = URLUtil.guessFileName(url, disposition, cleanMime)
        val t = synchronized(lock) { Task(nextId++, url, referer, name, cleanMime) }
        t.disposition = disposition
        tasks.add(t)
        save()
        maybeStartNext()
        ensureService()
        return t.id
    }

    fun pause(id: Long) {
        val t = find(id) ?: return
        if (t.status != Status.RUNNING && t.status != Status.PENDING) return
        t.flag.set(true)
        pool.execute { disconnectAll(t) }
        t.status = Status.PAUSED
        t.message = "dijeda"
        t.speed = 0
        save()
        maybeStartNext()
    }

    fun resume(id: Long) {
        val t = find(id) ?: return
        if (t.status != Status.PAUSED && t.status != Status.FAILED) return
        t.flag = AtomicBoolean(false)
        t.status = Status.PENDING
        t.message = ""
        save()
        maybeStartNext()
        ensureService()
    }

    /** Membatalkan / menghapus. File hasil (atau sebagian) ikut dihapus. */
    fun remove(id: Long) {
        val t = find(id) ?: return
        t.flag.set(true)
        tasks.remove(t)
        val prev = t.future
        save()
        pool.execute {
            disconnectAll(t)
            if (prev != null) try { prev.get(10, TimeUnit.SECONDS) } catch (_: Exception) {}
            deleteSink(t)
        }
        maybeStartNext()
    }

    private fun disconnectAll(t: Task) {
        t.conns.forEach { try { it.disconnect() } catch (_: Exception) {} }
    }

    private fun maybeStartNext() {
        synchronized(lock) {
            var running = tasks.count { it.status == Status.RUNNING }
            for (t in tasks.filter { it.status == Status.PENDING }.sortedBy { it.id }) {
                if (running >= MAX_PARALLEL) break
                start(t)
                running++
            }
        }
    }

    private fun start(t: Task) {
        t.status = Status.RUNNING
        t.message = "menghubungi server…"
        val flag = t.flag
        val prev = t.future
        t.future = pool.submit(Runnable {
            if (prev != null) try { prev.get(10, TimeUnit.SECONDS) } catch (_: Exception) {}
            execute(t, flag)
        })
    }

    private fun ensureService() {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        } catch (_: Exception) {
            // Tetap jalan selama aplikasi terbuka, hanya tanpa notifikasi latar belakang.
        }
    }

    // ------------------------------------------------------------------ eksekusi satu file

    private fun execute(t: Task, flag: AtomicBoolean) {
        if (flag.get()) return
        var ch: FileChannel? = null
        try {
            val p = probe(t, flag)
            if (flag.get()) throw Stopped()

            var resumed = false
            if (t.chunks.isNotEmpty() && t.rangeOk && p.rangeOk && p.total == t.total && t.sinkUri != null) {
                ch = try { reopenSink(t) } catch (_: Exception) { null }
                resumed = ch != null
            }
            if (!resumed) {
                // mulai dari awal (unduhan baru, file berubah, atau file tujuan hilang)
                deleteSink(t)
                t.sinkUri = null
                t.total = p.total
                t.rangeOk = p.rangeOk
                t.mime = p.mime ?: t.mime
                t.name = URLUtil.guessFileName(p.finalUrl, p.disposition ?: t.disposition, t.mime)
                t.chunks = buildChunks(t)
                checkSpace(t)
                ch = createSink(t)
            }
            t.url = p.finalUrl
            t.message = ""
            save()

            val channel = ch ?: throw IOException("file tujuan tidak bisa dibuka")
            download(t, channel, flag)

            channel.close()
            ch = null
            if (!tasks.contains(t)) return
            finalizeSink(t)
            if (t.total <= 0) t.total = t.downloaded
            t.speed = 0
            t.message = ""
            t.status = Status.SUCCESSFUL
            save()
            notifyFinished(t)
            maybeStartNext()
        } catch (_: Stopped) {
            // dijeda atau dibatalkan; status sudah diatur oleh pause()/remove()
        } catch (e: Exception) {
            if (!flag.get()) fail(t, e)
        } finally {
            try { ch?.close() } catch (_: Exception) {}
        }
    }

    private fun fail(t: Task, e: Exception) {
        t.message = describe(e)
        t.status = Status.FAILED
        t.speed = 0
        save()
        notifyFinished(t)
        maybeStartNext()
    }

    private fun describe(e: Exception): String = when {
        e is HttpStatus -> "server membalas HTTP ${e.code}"
        e is UnknownHostException -> "server tidak ditemukan / tidak ada internet"
        e is SocketTimeoutException -> "koneksi timeout"
        e.message?.contains("ENOSPC") == true || e.message?.contains("No space", true) == true ->
            "ruang penyimpanan penuh"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun notifyFinished(t: Task) {
        listeners.forEach { try { it.onFinished(t.id) } catch (_: Exception) {} }
        try {
            DownloadNotifier.done(ctx, DownloadTracker(ctx), t)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ HTTP

    private fun openConnection(url: String, referer: String?, follow: Boolean): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = follow
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("Accept-Encoding", "identity")   // jaga Content-Length & Range tetap akurat
        if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
        return c
    }

    /** Cek ukuran file, dukungan Range, dan URL akhir (mengikuti redirect secara manual). */
    private fun probe(t: Task, flag: AtomicBoolean): Probe {
        var current = t.origUrl
        for (hop in 0..10) {
            if (flag.get()) throw Stopped()
            val c = openConnection(current, t.referer, false)
            t.conns.add(c)
            try {
                c.setRequestProperty("Range", "bytes=0-0")
                val code = c.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("pengalihan tanpa tujuan")
                    current = URL(URL(current), loc).toString()
                    continue
                }
                val mime = c.contentType?.substringBefore(';')?.trim()?.ifBlank { null }
                val cd = c.getHeaderField("Content-Disposition")
                return when {
                    code == 206 -> {
                        val total = c.getHeaderField("Content-Range")
                            ?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                        Probe(current, total, total > 0, mime, cd)
                    }
                    code in 200..299 -> Probe(current, c.contentLengthLong, false, mime, cd)
                    else -> throw HttpStatus(code)
                }
            } finally {
                t.conns.remove(c)
                c.disconnect()
            }
        }
        throw IOException("terlalu banyak pengalihan")
    }

    private fun buildChunks(t: Task): List<Chunk> {
        if (!t.rangeOk || t.total <= 0) {
            return listOf(Chunk(0L, if (t.total > 0) t.total - 1 else Long.MAX_VALUE - 1))
        }
        if (t.total <= 512L * 1024) return listOf(Chunk(0L, t.total - 1))
        val size = (t.total / (connections * 4L)).coerceIn(256L * 1024, 8L * 1024 * 1024)
        val out = ArrayList<Chunk>()
        var s = 0L
        while (s < t.total) {
            val e = minOf(s + size - 1, t.total - 1)
            out.add(Chunk(s, e))
            s = e + 1
        }
        return out
    }

    private fun checkSpace(t: Task) {
        if (t.total <= 0) return
        val free = try {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
        if (free < t.total + 16L * 1024 * 1024) throw IOException("ruang penyimpanan tidak cukup")
    }

    // ------------------------------------------------------------------ unduh paralel

    private fun download(t: Task, ch: FileChannel, flag: AtomicBoolean) {
        val limit = AtomicInteger(if (t.rangeOk) connections else 1)
        var round = 0
        while (true) {
            val todo = t.chunks.filter { !it.done }
            if (todo.isEmpty()) return
            if (flag.get()) throw Stopped()
            if (++round > 30) throw IOException("unduhan tidak lengkap")
            val error = runRound(t, ch, todo, limit, flag)
            if (flag.get()) throw Stopped()
            if (error != null) throw error
        }
    }

    private fun runRound(
        t: Task,
        ch: FileChannel,
        todo: List<Chunk>,
        limit: AtomicInteger,
        flag: AtomicBoolean
    ): Exception? {
        val queue = ConcurrentLinkedQueue<Chunk>(todo)
        val error = AtomicReference<Exception?>(null)
        val workers = minOf(limit.get(), todo.size).coerceAtLeast(1)
        val latch = CountDownLatch(workers)
        repeat(workers) {
            pool.execute {
                try {
                    worker(t, ch, queue, error, limit, flag)
                } finally {
                    latch.countDown()
                }
            }
        }

        var lastBytes = t.downloaded
        var lastTime = SystemClock.elapsedRealtime()
        while (!latch.await(1, TimeUnit.SECONDS)) {
            val now = SystemClock.elapsedRealtime()
            val bytes = t.downloaded
            val inst = ((bytes - lastBytes) * 1000 / maxOf(1L, now - lastTime)).coerceAtLeast(0)
            t.speed = if (t.speed > 0) (t.speed * 0.6 + inst * 0.4).toLong() else inst
            lastBytes = bytes
            lastTime = now
            save()
        }
        t.speed = 0
        return error.get()
    }

    private fun worker(
        t: Task,
        ch: FileChannel,
        queue: ConcurrentLinkedQueue<Chunk>,
        error: AtomicReference<Exception?>,
        limit: AtomicInteger,
        flag: AtomicBoolean
    ) {
        while (!flag.get() && error.get() == null) {
            val chunk = queue.poll() ?: return
            var attempt = 0
            while (!chunk.done && !flag.get() && error.get() == null) {
                val before = chunk.pos
                try {
                    fetch(t, ch, chunk, flag)
                } catch (e: Exception) {
                    if (flag.get()) return
                    if (e is HttpStatus && (e.code == 429 || e.code == 503) && limit.get() > 1) {
                        // server menolak terlalu banyak koneksi: kurangi, koneksi ini mundur
                        limit.decrementAndGet()
                        queue.add(chunk)
                        return
                    }
                    if (e is HttpStatus && e.code in 400..499 && e.code != 408) {
                        error.compareAndSet(null, e)
                        return
                    }
                    if (chunk.pos > before) attempt = 0
                    if (++attempt > 6) {
                        error.compareAndSet(null, e)
                        return
                    }
                    try {
                        Thread.sleep(minOf(500L * attempt, 4000L))
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        }
    }

    /** Mengunduh satu potongan mulai dari [Chunk.pos]; melanjutkan otomatis jika koneksi putus. */
    private fun fetch(t: Task, ch: FileChannel, chunk: Chunk, flag: AtomicBoolean) {
        if (!t.rangeOk) chunk.pos = chunk.start       // tanpa Range: harus mulai dari awal
        val c = openConnection(t.url, t.referer, true)
        t.conns.add(c)
        try {
            if (t.rangeOk) c.setRequestProperty("Range", "bytes=${chunk.pos}-${chunk.end}")
            val code = c.responseCode
            if (t.rangeOk) {
                if (code != 206) {
                    throw if (code in 200..299) IOException("server mengabaikan permintaan Range") else HttpStatus(code)
                }
            } else if (code !in 200..299) {
                throw HttpStatus(code)
            }

            val buf = ByteArray(64 * 1024)
            c.inputStream.use { input ->
                while (true) {
                    if (flag.get()) return
                    val n = input.read(buf)
                    if (n < 0) break
                    val allowed = if (t.rangeOk) minOf(n.toLong(), chunk.end - chunk.pos + 1).toInt() else n
                    if (allowed <= 0) break
                    writeFully(ch, ByteBuffer.wrap(buf, 0, allowed), chunk.pos)
                    chunk.pos += allowed
                    if (t.rangeOk && chunk.done) break
                }
            }

            if (t.rangeOk) {
                if (!chunk.done) throw IOException("koneksi terputus")
            } else if (chunk.end == Long.MAX_VALUE - 1) {
                // ukuran tidak diketahui: stream habis = selesai
                chunk.end = chunk.pos - 1
                t.total = chunk.pos
            } else if (!chunk.done) {
                throw IOException("koneksi terputus")
            }
        } finally {
            t.conns.remove(c)
            c.disconnect()
        }
    }

    private fun writeFully(ch: FileChannel, buf: ByteBuffer, position: Long) {
        var p = position
        while (buf.hasRemaining()) p += ch.write(buf, p)
    }

    // ------------------------------------------------------------------ file tujuan (folder Download)

    private fun createSink(t: Task): FileChannel {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, t.name)
                val m = t.mime
                if (m != null && m != "application/octet-stream") put(MediaStore.Downloads.MIME_TYPE, m)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("gagal membuat file di folder Download")
            t.sinkUri = uri.toString()
            // sistem bisa menambah akhiran "(1)" jika nama sudah dipakai
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) t.name = it.getString(0) ?: t.name
            }
            return openChannel(uri)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val f = uniqueFile(dir, t.name)
            t.name = f.name
            t.sinkUri = f.absolutePath
            return RandomAccessFile(f, "rw").channel
        }
    }

    private fun reopenSink(t: Task): FileChannel {
        val s = t.sinkUri ?: throw IOException("file tujuan hilang")
        return if (Build.VERSION.SDK_INT >= 29) openChannel(Uri.parse(s))
        else {
            val f = File(s)
            if (!f.exists()) throw IOException("file tujuan hilang")
            RandomAccessFile(f, "rw").channel
        }
    }

    private fun openChannel(uri: Uri): FileChannel {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("tidak bisa membuka file tujuan")
        return ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel
    }

    private fun finalizeSink(t: Task) {
        val s = t.sinkUri ?: return
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            ctx.contentResolver.update(Uri.parse(s), v, null, null)
        } else {
            MediaScannerConnection.scanFile(ctx, arrayOf(s), null, null)
        }
    }

    private fun deleteSink(t: Task) {
        val s = t.sinkUri ?: return
        try {
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.delete(Uri.parse(s), null, null)
            else File(s).delete()
        } catch (_: Exception) {}
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            if (!f.exists()) return f
            i++
        }
    }

    // ------------------------------------------------------------------ penyimpanan daftar

    private fun dataFile() = File(ctx.filesDir, "downloads.json")

    private fun load() {
        try {
            val f = dataFile()
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            nextId = root.optLong("next", 1L)
            val arr = root.optJSONArray("tasks") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = Task(
                    o.getLong("id"),
                    o.getString("orig"),
                    o.optString("ref").ifBlank { null },
                    o.optString("name"),
                    o.optString("mime").ifBlank { null }
                )
                t.url = o.optString("url", t.origUrl)
                t.total = o.optLong("total", -1L)
                t.rangeOk = o.optBoolean("range")
                t.sinkUri = o.optString("sink").ifBlank { null }
                t.message = o.optString("msg")
                var st = o.optInt("status", Status.FAILED)
                if (st == Status.RUNNING || st == Status.PENDING) {
                    st = Status.PAUSED
                    t.message = "terhenti saat aplikasi ditutup"
                }
                t.status = st
                val ca = o.optJSONArray("chunks")
                if (ca != null) {
                    t.chunks = (0 until ca.length()).map { k ->
                        val c = ca.getJSONArray(k)
                        Chunk(c.getLong(0), c.getLong(1), c.getLong(2))
                    }
                }
                tasks.add(t)
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        synchronized(saveLock) {
            try {
                tasks.filter { it.status == Status.SUCCESSFUL }
                    .sortedByDescending { it.id }
                    .drop(MAX_HISTORY)
                    .forEach { tasks.remove(it) }

                val arr = JSONArray()
                for (t in tasks) {
                    val o = JSONObject()
                        .put("id", t.id)
                        .put("orig", t.origUrl)
                        .put("ref", t.referer ?: "")
                        .put("name", t.name)
                        .put("mime", t.mime ?: "")
                        .put("url", t.url)
                        .put("total", t.total)
                        .put("range", t.rangeOk)
                        .put("sink", t.sinkUri ?: "")
                        .put("msg", t.message)
                        .put("status", t.status)
                    if (t.status != Status.SUCCESSFUL) {
                        val ca = JSONArray()
                        t.chunks.forEach { ca.put(JSONArray().put(it.start).put(it.end).put(it.pos)) }
                        o.put("chunks", ca)
                    }
                    arr.put(o)
                }
                val tmp = File(ctx.filesDir, "downloads.json.tmp")
                tmp.writeText(JSONObject().put("next", nextId).put("tasks", arr).toString())
                tmp.renameTo(dataFile())
            } catch (_: Exception) {}
        }
    }
}
