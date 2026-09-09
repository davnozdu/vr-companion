package com.davnozdu.vrcompanion

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка обновлений по релизам GitHub.
 *
 * Своего сервера нет и не нужно: тег релиза и есть версия. Сравниваем
 * versionName, а не versionCode — в API его нет, зато тег всегда вида v1.2.
 */
object UpdateChecker {

    private const val API =
        "https://api.github.com/repos/davnozdu/vr-companion/releases/latest"

    data class Release(val version: String, val apkUrl: String?, val notes: String?)

    fun latest(): Release? = try {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        if (c.responseCode != 200) null else {
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = j.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url"); break
                    }
                }
            }
            Release(
                version = j.optString("tag_name").removePrefix("v"),
                apkUrl = apk,
                notes = j.optString("body").takeIf { it.isNotBlank() },
            )
        }
    } catch (_: Exception) {
        null   // сеть недоступна — не повод показывать ошибку
    }

    /** Сравнение вида 1.10 > 1.9: посегментно, а не лексикографически. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').mapNotNull { it.toIntOrNull() }
        val l = local.split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
