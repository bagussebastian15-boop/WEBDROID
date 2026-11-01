package com.ringan.browser

import android.content.Context
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.content.FileProvider
import java.io.File

/** Pembungkus DownloadCenter untuk UI: status, teks info (progres, ukuran, kecepatan, sisa waktu), dan aksi. */
class DownloadTracker(private val context: Context) {

    data class Info(
        val id: Long,
        val name: String,
        val status: Int,
        val message: String,
        val downloaded: Long,
        val total: Long,          // -1 jika ukuran belum diketahui
        val mime: String?,
        val speed: Long           // byte/detik
    ) {
        /** Sedang berjalan atau mengantre. */
        val active: Boolean
            get() = status == Status.RUNNING || status == Status.PENDING

        /** 0..100, atau -1 jika ukuran total belum diketahui. */
        val percent: Int
            get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else -1

        /** Perkiraan sisa waktu dalam detik, atau -1 jika belum bisa dihitung. */
        val etaSeconds: Long
            get() = if (status == Status.RUNNING && speed > 0 && total > downloaded)
                (total - downloaded) / speed else -1
    }

    private fun toInfo(t: Task) = Info(
        id = t.id,
        name = t.name,
        status = t.status,
        message = t.message,
        downloaded = if (t.status == Status.SUCCESSFUL && t.total > 0) t.total else t.downloaded,
        total = t.total,
        mime = t.mime,
        speed = t.speed
    )

    // ------------------------------------------------------------------ data & aksi

    fun query(limit: Int = 30): List<Info> = DownloadCenter.snapshot().take(limit).map { toInfo(it) }

    fun find(id: Long): Info? = DownloadCenter.find(id)?.let { toInfo(it) }

    fun pause(id: Long) = DownloadCenter.pause(id)
    fun resume(id: Long) = DownloadCenter.resume(id)

    /** Membatalkan unduhan atau menghapus catatan (file hasil download ikut terhapus). */
    fun remove(id: Long) = DownloadCenter.remove(id)

    /** Uri file hasil download untuk dibuka aplikasi lain. */
    fun uriFor(id: Long): Uri? {
        val s = DownloadCenter.find(id)?.sinkUri ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 29) Uri.parse(s)
            else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(s))
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ teks

    fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    private fun durationText(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun sizeProgress(i: Info): String =
        if (i.total > 0) "${sizeText(i.downloaded)} / ${sizeText(i.total)}" else sizeText(i.downloaded)

    fun reasonText(i: Info): String = i.message

    /** Satu baris ringkas (strip di atas bar bawah & notifikasi). */
    fun summary(i: Info, withName: Boolean = true): String {
        val parts = mutableListOf<String>()
        if (withName) parts += i.name
        when (i.status) {
            Status.PENDING -> parts += "Menunggu antrean…"
            Status.PAUSED -> {
                parts += "Dijeda"
                parts += sizeProgress(i)
            }
            Status.FAILED -> parts += "Gagal: ${i.message}"
            Status.SUCCESSFUL -> parts += "Selesai · ${sizeText(i.downloaded)}"
            else -> {
                if (i.percent >= 0) parts += "${i.percent}%"
                parts += if (i.downloaded == 0L && i.message.isNotBlank()) i.message else sizeProgress(i)
                if (i.speed > 0) parts += "${sizeText(i.speed)}/dtk"
                if (i.etaSeconds >= 0) parts += "sisa ${durationText(i.etaSeconds)}"
            }
        }
        return parts.joinToString(" · ")
    }

    /** Teks beberapa baris untuk satu item di daftar Unduhan. */
    fun detail(i: Info): String {
        val pct = if (i.percent >= 0) " · ${i.percent}%" else ""
        val l2 = when (i.status) {
            Status.SUCCESSFUL -> "Selesai · ${sizeText(i.downloaded)} · ketuk untuk membuka"
            Status.FAILED -> "Gagal · ${i.message} · ${sizeProgress(i)} · ketuk untuk coba lagi"
            Status.PENDING -> "Menunggu antrean…"
            Status.PAUSED -> "Dijeda$pct · ${sizeProgress(i)} · ketuk untuk lanjut"
            else -> if (i.downloaded == 0L && i.message.isNotBlank()) "Mengunduh · ${i.message}"
            else "Mengunduh$pct · ${sizeProgress(i)} · ketuk untuk jeda"
        }
        val l3 = if (i.status == Status.RUNNING && (i.speed > 0 || i.etaSeconds >= 0)) {
            buildList {
                if (i.speed > 0) add("${sizeText(i.speed)}/dtk")
                if (i.etaSeconds >= 0) add("sisa ${durationText(i.etaSeconds)}")
            }.joinToString(" · ")
        } else null
        return listOfNotNull(i.name, l2, l3).joinToString("\n")
    }
}
