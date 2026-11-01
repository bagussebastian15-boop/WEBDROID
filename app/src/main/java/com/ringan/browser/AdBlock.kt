package com.ringan.browser

/** Pemblokir iklan/pelacak sederhana berdasarkan nama host (tanpa memori besar). */
object AdBlock {
    private val hosts = arrayOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "google-analytics.com", "adnxs.com",
        "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
        "exoclick.com", "juicyads.com", "trafficjunky.net", "mgid.com",
        "revcontent.com", "pubmatic.com", "rubiconproject.com", "openx.net",
        "adform.net", "scorecardresearch.com", "quantserve.com",
        "hotjar.com", "mc.yandex.ru", "an.yandex.ru", "ads.yahoo.com",
        "advertising.com", "moatads.com", "casalemedia.com", "smartadserver.com"
    )

    fun isBlocked(host: String?): Boolean {
        if (host == null) return false
        for (h in hosts) {
            if (host == h || host.endsWith(".$h")) return true
        }
        return false
    }
}
