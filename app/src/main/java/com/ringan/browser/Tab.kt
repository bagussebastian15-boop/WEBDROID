package com.ringan.browser

import android.os.Bundle

/** Tab ringan: hanya menyimpan data. WebView hanya hidup untuk tab yang sedang tampil. */
class Tab {
    var title: String = ""
    var url: String = ""
    var state: Bundle? = null
}
