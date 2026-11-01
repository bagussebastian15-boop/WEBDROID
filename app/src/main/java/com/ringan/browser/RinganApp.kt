package com.ringan.browser

import android.app.Application
import java.io.File

class RinganApp : Application() {
    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "last_crash.txt")
    }

    override fun onCreate() {
        super.onCreate()
        // Catat crash Java ke file agar bisa ditampilkan saat aplikasi dibuka lagi
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                crashFile(this).writeText(
                    "Thread: ${t.name}\n" + android.util.Log.getStackTraceString(e)
                )
            } catch (_: Exception) {}
            old?.uncaughtException(t, e)
        }
    }
}
