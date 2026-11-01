package com.ringan.browser

import org.mozilla.geckoview.GeckoSession

class Tab(val session: GeckoSession) {
    var title: String = ""
    var url: String = ""
    var progress: Int = 0
    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
}
