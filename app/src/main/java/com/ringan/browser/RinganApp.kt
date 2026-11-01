package com.ringan.browser

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime

class RinganApp : Application() {
    companion object {
        lateinit var runtime: GeckoRuntime
            private set
    }

    override fun onCreate() {
        super.onCreate()
        runtime = GeckoRuntime.create(this)
    }
}
