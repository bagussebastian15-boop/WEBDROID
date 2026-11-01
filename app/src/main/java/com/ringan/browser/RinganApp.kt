package com.ringan.browser

import android.app.Application
import android.os.Build
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File

class RinganApp : Application() {
    companion object {
        lateinit var runtime: GeckoRuntime
            private set

        fun crashFile(app: Application) = File(app.filesDir, "last_crash.txt")
    }

    override fun onCreate() {
        super.onCreate()

        // Proses anak GeckoView (:tab, :gpu, dll.) juga menjalankan Application.onCreate().
        // GeckoRuntime hanya boleh dibuat di proses utama.
        if (!isMainProcess()) return

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

        val settings = GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(false)
            .build()
        runtime = GeckoRuntime.create(this, settings)
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            try {
                File("/proc/self/cmdline").readText().trim('\u0000')
            } catch (_: Exception) {
                packageName
            }
        }
        return name == packageName
    }
}
