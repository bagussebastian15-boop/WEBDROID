package com.ringan.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Penyimpanan bookmark sederhana (SharedPreferences + JSON), tanpa library tambahan. */
class BookmarkStore(context: Context) {

    data class Item(val title: String, val url: String)

    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun all(): List<Item> {
        val arr = JSONArray(prefs.getString("items", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Item(o.optString("title"), o.optString("url"))
        }
    }

    fun contains(url: String): Boolean = all().any { it.url == url }

    fun add(title: String, url: String) {
        if (url.isBlank() || contains(url)) return
        save(all() + Item(title.ifBlank { url }, url))
    }

    fun remove(url: String) = save(all().filterNot { it.url == url })

    private fun save(list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
